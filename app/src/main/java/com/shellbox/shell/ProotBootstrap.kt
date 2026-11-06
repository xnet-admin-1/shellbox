package com.shellbox.shell

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Downloads and configures the proot Ubuntu rootfs.
 * Runs on top of a UID 2000 (shell) environment via xnix-shelld.
 * Uses deps from XNet-NGO/deps repository.
 */
object ProotBootstrap {

    private const val TAG = "ProotBootstrap"
    const val UID2000_ROOTFS = "/data/local/tmp/ubuntu"
    private const val ROOTFS_VERSION = "rootfs_deps_v1"
    private val isSettingUp = java.util.concurrent.atomic.AtomicBoolean(false)

    fun envDir(ctx: Context) = File(ctx.filesDir, "env")
    fun rootfsDir(ctx: Context) = File(envDir(ctx), "ubuntu")
    fun rootfsPath(ctx: Context? = null): String = UID2000_ROOTFS
    private fun marker(ctx: Context, name: String) = File(envDir(ctx), ".$name")

    fun isInstalled(ctx: Context): Boolean {
        try {
            val check = ShelldClient.exec("test -f $UID2000_ROOTFS/bin/sh && echo 1 || echo 0")
            if (check == "1" || check.startsWith("1")) return true
        } catch (_: Exception) {}
        val rootfs = rootfsDir(ctx)
        return marker(ctx, ROOTFS_VERSION).exists()
            && rootfs.isDirectory
            && File(rootfs, "bin/sh").isFile
    }

    /**
     * Find proot-xed binary, handling Android renaming libproot-xed.so to libroot-xed.so.
     */
    fun findProotXed(ctx: Context): File? {
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        for (name in arrayOf("libproot-xed.so", "libroot-xed.so")) {
            val f = File(nativeDir, name)
            if (f.isFile) return f
        }
        val p = File("/product/bin/proot")
        if (p.canExecute()) return p
        val t = File("/data/local/tmp/proot")
        if (t.canExecute()) return t
        return null
    }

    /**
     * Re-apply patches to an existing rootfs.
     */
    fun ensurePatched(ctx: Context, log: (String) -> Unit) {
        val rootfs = rootfsDir(ctx)
        if (rootfs.isDirectory) {
            fixProotStubs(rootfs)
            patchRootfs(rootfs, log)
        }
        patchRootfsUid2000(log)
    }

    /**
     * Full bootstrap: create dirs, download rootfs from XNet-NGO/deps, extract in UID 2000, patch.
     * Call on a background thread.
     */
    fun setup(ctx: Context, log: (String) -> Unit): Boolean {
        if (!isSettingUp.compareAndSet(false, true)) {
            log("Ubuntu setup is already in progress...")
            return false
        }
        try {
            val nativeDir = ctx.applicationInfo.nativeLibraryDir

            // 1. Prepare UID 2000 rootfs directory
            log("Preparing UID 2000 environment in $UID2000_ROOTFS...")
            ShelldClient.exec("mkdir -p $UID2000_ROOTFS /data/local/tmp")

            val arch = System.getProperty("os.arch")?.lowercase() ?: "aarch64"
            val pdArch = when {
                "aarch64" in arch || "arm64" in arch -> "aarch64"
                "armv7" in arch || "arm" in arch -> "arm"
                "x86_64" in arch -> "x86_64"
                else -> "aarch64"
            }

            // Using repo deps (XNet-NGO/deps)
            val url = "https://github.com/XNet-NGO/deps/releases/download/rootfs-ubuntu-24.04.4/box-ubuntu-24.04-$pdArch.tar.xz"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val tarball = File(downloadsDir, ".shellbox_ubuntu_${System.currentTimeMillis()}.tar.xz")

            val hasSh = ShelldClient.exec("test -f $UID2000_ROOTFS/bin/sh && echo 1 || echo 0")
            if (hasSh != "1" && !hasSh.startsWith("1")) {
                log("Downloading Ubuntu 24.04 rootfs ($pdArch) from XNet-NGO/deps...")
                download(url, tarball, log)

                if (!tarball.exists() || tarball.length() == 0L) {
                    log("ERROR: Download failed or empty file")
                    return false
                }

                log("Extracting rootfs via bsdtar in UID 2000...")
                val bsdtarBin = if (File("/product/bin/bsdtar").canExecute()) "/product/bin/bsdtar"
                    else "$nativeDir/libbsdtar.so"
                val extractCmd = "LD_LIBRARY_PATH=$nativeDir:/product/lib64 " +
                    "$bsdtarBin -xf ${tarball.absolutePath} -C $UID2000_ROOTFS --strip-components 1 --no-same-owner"
                val out = ShelldClient.exec(extractCmd, timeoutMs = 180_000)
                if (out.isNotEmpty()) Log.d(TAG, "bsdtar output: $out")
                try { tarball.delete() } catch (_: Exception) {}

                val extractedSh = ShelldClient.exec("test -f $UID2000_ROOTFS/bin/sh && echo 1 || echo 0")
                if (extractedSh != "1" && !extractedSh.startsWith("1")) {
                    log("ERROR: Extraction failed - bin/sh not found in $UID2000_ROOTFS")
                    return false
                }
                log("Rootfs extracted successfully into UID 2000")
            } else {
                log("Rootfs already installed in UID 2000")
            }

            // 2. Patch rootfs in UID 2000
            patchRootfsUid2000(log)

            // 3. Run distro setup inside proot
            distroSetup(ctx, log)

            log("Ubuntu environment ready!")
            return true
        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            Log.e(TAG, "setup failed", e)
            return false
        } finally {
            isSettingUp.set(false)
        }
    }

    private fun patchRootfsUid2000(log: (String) -> Unit) {
        log("Patching UID 2000 rootfs...")
        ShelldClient.exec("mkdir -p $UID2000_ROOTFS/etc/apt/apt.conf.d $UID2000_ROOTFS/etc/dpkg/dpkg.cfg.d $UID2000_ROOTFS/root $UID2000_ROOTFS/tmp $UID2000_ROOTFS/var/log/apt")
        ShelldClient.exec("echo 'nameserver 8.8.8.8\\nnameserver 1.1.1.1' > $UID2000_ROOTFS/etc/resolv.conf")
        ShelldClient.exec("echo 'Acquire::Check-Date \"false\";\\nAPT::Get::Assume-Yes \"true\";\\nAPT::Get::Show-Upgraded \"false\";\\nAPT::Install-Recommends \"false\";\\nAPT::Sandbox::User \"root\";\\nDPkg::Options {\"--force-unsafe-io\";};' > $UID2000_ROOTFS/etc/apt/apt.conf.d/99-agent-optimizations")
        ShelldClient.exec("echo 'force-unsafe-io\\nforce-confdef\\nforce-confold\\nno-debsig' > $UID2000_ROOTFS/etc/dpkg/dpkg.cfg.d/99-agent-optimizations")
        ShelldClient.exec("echo 'export TERM=xterm-256color\\nexport LANG=C.UTF-8\\nexport LC_ALL=C.UTF-8\\nexport COLORTERM=truecolor\\nalias ls=\"ls --color=auto\"\\nalias ll=\"ls -lah\"' > $UID2000_ROOTFS/root/.bashrc")
        ShelldClient.exec("echo 'export PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"\\nexport LANG=C.UTF-8\\nexport LC_ALL=C.UTF-8' > $UID2000_ROOTFS/root/.profile")
        ShelldClient.exec("touch $UID2000_ROOTFS/.$ROOTFS_VERSION")
    }

    private fun prepareBsdtar(ctx: Context): File? {
        val p = File("/product/bin/bsdtar")
        if (p.canExecute()) return p
        val f = File(ctx.applicationInfo.nativeLibraryDir, "libbsdtar.so")
        return if (f.exists() && f.canExecute()) f else null
    }

    private fun runBsdtar(bsdtar: File, tarball: File, destDir: File, stripComponents: Int, log: (String) -> Unit): Boolean {
        destDir.mkdirs()
        val cmd = mutableListOf(
            bsdtar.absolutePath, "-xf", tarball.absolutePath,
            "-C", destDir.absolutePath, "--no-same-owner"
        )
        if (stripComponents > 0) {
            cmd.addAll(listOf("--strip-components", stripComponents.toString()))
        }
        Log.d(TAG, "bsdtar: ${cmd.joinToString(" ")}")
        return try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = bsdtar.parentFile?.absolutePath ?: ""
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val ok = proc.waitFor(300, TimeUnit.SECONDS)
            val exit = if (ok) proc.exitValue() else -1
            if (exit != 0) {
                if (!ok) { proc.destroyForcibly(); log("bsdtar timed out"); return false }
                val lines = output.lines().filter { it.isNotBlank() }
                val hardlinkErrors = lines.filter { "Can't create" in it || "Permission denied" in it }
                val otherErrors = lines.filter {
                    it.isNotBlank() && "Can't create" !in it && "Permission denied" !in it
                    && "Error exit delayed" !in it
                }
                if (otherErrors.isEmpty() && hardlinkErrors.isNotEmpty()) {
                    log("bsdtar: ${hardlinkErrors.size} hardlinks failed (Android limitation), fixing...")
                    fixFailedHardlinks(destDir, output, log)
                    true
                } else {
                    log("bsdtar error (exit $exit): ${output.take(500)}")
                    false
                }
            } else {
                if (output.isNotBlank()) Log.d(TAG, "bsdtar output: ${output.take(200)}")
                true
            }
        } catch (e: Exception) {
            log("bsdtar failed: ${e.message}")
            Log.e(TAG, "runBsdtar failed", e)
            false
        }
    }

    private fun fixFailedHardlinks(destDir: File, bsdtarOutput: String, log: (String) -> Unit) {
        val knownLinks = mapOf(
            "usr/bin/perl5.38.2" to "usr/bin/perl",
            "usr/bin/uncompress" to "usr/bin/gunzip",
            "usr/bin/pager" to "usr/bin/less",
            "usr/bin/zcmp" to "usr/bin/zdiff",
            "usr/bin/zegrep" to "usr/bin/zgrep",
            "usr/bin/zfgrep" to "usr/bin/zgrep",
            "usr/bin/zless" to "usr/bin/zmore",
            "usr/bin/bzcat" to "usr/bin/bzip2",
            "usr/bin/bunzip2" to "usr/bin/bzip2",
            "usr/bin/bzegrep" to "usr/bin/bzgrep",
            "usr/bin/bzfgrep" to "usr/bin/bzgrep",
            "usr/bin/bzless" to "usr/bin/bzmore",
            "usr/bin/lzcat" to "usr/bin/xz",
            "usr/bin/lzma" to "usr/bin/xz",
            "usr/bin/unlzma" to "usr/bin/xz",
            "usr/bin/unxz" to "usr/bin/xz",
            "usr/bin/xzcat" to "usr/bin/xz",
            "usr/bin/lzcmp" to "usr/bin/lzdiff",
            "usr/bin/lzegrep" to "usr/bin/lzgrep",
            "usr/bin/lzfgrep" to "usr/bin/lzgrep",
            "usr/bin/lzless" to "usr/bin/lzmore",
            "usr/sbin/vigr" to "usr/sbin/vipw",
            "usr/bin/sg" to "usr/bin/newgrp",
            "usr/bin/open" to "usr/bin/openvt",
            "usr/bin/i386" to "usr/bin/setarch",
            "usr/bin/x86_64" to "usr/bin/setarch",
            "usr/bin/linux32" to "usr/bin/setarch",
            "usr/bin/linux64" to "usr/bin/setarch",
            "usr/bin/uname26" to "usr/bin/setarch",
            "usr/bin/aarch64" to "usr/bin/setarch",
            "usr/bin/sha224sum" to "usr/bin/sha256sum",
            "usr/bin/sha384sum" to "usr/bin/sha512sum",
            "usr/bin/w.procps" to "usr/bin/w",
            "usr/bin/awk" to "usr/bin/mawk",
            "usr/bin/nawk" to "usr/bin/mawk",
            "usr/bin/rb" to "usr/bin/red",
            "usr/bin/pgrep" to "usr/bin/pidof",
            "usr/bin/slogin" to "usr/bin/ssh"
        )

        val regex = Regex("""^(.+?): Can't create '(.+?)': Permission denied""")
        val failedPaths = bsdtarOutput.lines().mapNotNull { regex.find(it)?.groupValues?.get(1) }

        var fixed = 0
        for (path in failedPaths) {
            val target = knownLinks[path] ?: continue
            val linkFile = File(destDir, path)
            val targetFile = File(destDir, target)
            if (targetFile.exists() && !linkFile.exists()) {
                try {
                    targetFile.inputStream().use { i -> linkFile.outputStream().use { o -> i.copyTo(o) } }
                    linkFile.setExecutable(targetFile.canExecute())
                    fixed++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fix hardlink $path → $target: ${e.message}")
                }
            }
        }
        log("  Fixed $fixed/${failedPaths.size} hardlinks (copied as regular files)")
    }

    private fun distroSetup(ctx: Context, log: (String) -> Unit) {
        log("Running distro setup...")
        try {
            // Fix any interrupted dpkg state first
            ProotExecutor.exec(ctx, "dpkg --configure -a --force-unsafe-io 2>/dev/null || true", timeoutMs = 60_000)
            // Then locale
            ProotExecutor.exec(ctx,
                "DEBIAN_FRONTEND=noninteractive dpkg-reconfigure locales 2>/dev/null || true",
                timeoutMs = 60_000)
            log("Distro setup complete")
        } catch (e: Exception) {
            Log.w(TAG, "distro_setup failed (non-fatal): ${e.message}")
            log("Distro setup skipped (${e.message})")
        }
    }

    private fun download(urlStr: String, dest: File, log: (String) -> Unit) {
        var url = URL(urlStr)
        var redirects = 0
        var conn: HttpURLConnection
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000; conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location") ?: break
                url = URL(url, loc)
                conn.disconnect()
                if (++redirects > 5) { log("ERROR: too many redirects"); return }
                continue
            }
            if (code != 200) {
                log("ERROR: HTTP $code from $url")
                conn.disconnect()
                return
            }
            break
        }
        val total = conn.contentLength.toLong()
        var downloaded = 0L
        var lastReportedMB = -1L
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(65536)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    downloaded += n
                    val mb = downloaded / (1024 * 1024)
                    if (total > 0 && mb > lastReportedMB) {
                        lastReportedMB = mb
                        log("  ${mb}MB / ${total / (1024 * 1024)}MB")
                    }
                }
            }
        }
        conn.disconnect()
        log("  Download complete (${dest.length() / 1024}KB)")
    }

    /**
     * Fix proot stubs — ensure they're simple exit-0 scripts.
     * Called every time shells are discovered, not just at setup.
     * Matches AIOPE's _fix_proot_stubs().
     */
    fun fixProotStubs(rootfs: File) {
        val stubScript = "#!/bin/sh\nexit 0\n"
        for (rel in listOf("sbin/ldconfig", "usr/sbin/ldconfig", "usr/bin/ldconfig",
                           "usr/sbin/invoke-rc.d", "sbin/start-stop-daemon", "usr/sbin/start-stop-daemon")) {
            val f = File(rootfs, rel)
            if (f.isFile) {
                try {
                    val content = f.readText()
                    if ("exit 0" in content && content.length < 30) continue
                    f.writeText(stubScript)
                    f.setExecutable(true)
                } catch (_: Exception) {}
            }
        }
    }

    private fun patchRootfs(rootfs: File, log: (String) -> Unit) {
        // DNS
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n")

        // Locale
        val localeGen = File(rootfs, "etc/locale.gen")
        if (localeGen.exists()) {
            localeGen.writeText(localeGen.readText().replace(Regex("""#\s?(en_US\.UTF-8\s+UTF-8)"""), "$1"))
        }
        File(rootfs, "etc/default").mkdirs()
        File(rootfs, "etc/default/locale").writeText("LANG=en_US.UTF-8\nLANGUAGE=en_US:en\nLC_ALL=en_US.UTF-8\n")

        // Fix permissions on bin dirs
        listOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin", "usr/local/sbin",
               "usr/lib/apt/methods", "usr/lib/dpkg").forEach { dir ->
            File(rootfs, dir).walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true) }
        }
        // dpkg info scripts need +x
        File(rootfs, "var/lib/dpkg/info").let { dir ->
            if (dir.isDirectory) dir.walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".postinst") || it.name.endsWith(".preinst")
                    || it.name.endsWith(".postrm") || it.name.endsWith(".prerm")) }
                .forEach { it.setExecutable(true) }
        }

        // .so files
        listOf("lib", "usr/lib").forEach { dir ->
            File(rootfs, dir).walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".so") || it.name.contains(".so.")) }
                .forEach { it.setExecutable(true) }
        }

        // Standard dirs
        for (d in listOf("tmp", "var/tmp", "var/cache/apt", "var/lib/apt", "home", "root",
                         "root/.config/pip", "root/workspace")) {
            File(rootfs, d).mkdirs()
        }
        File(rootfs, "tmp").setWritable(true, false)

        // apt config
        File(rootfs, "etc/apt/apt.conf.d").mkdirs()
        File(rootfs, "etc/apt/apt.conf.d/99-agent-optimizations").writeText("""
Acquire::Check-Date "false";
APT::Get::Assume-Yes "true";
APT::Get::Show-Upgraded "false";
APT::Install-Recommends "false";
APT::Install-Suggests "false";
APT::Quiet "2";
APT::Periodic::Update-Package-Lists "0";
APT::Periodic::Unattended-Upgrade "0";
Acquire::Retries "3";
Acquire::https::Timeout "30";
Acquire::http::Timeout "30";
APT::Sandbox::User "root";
DPkg::Options {"--force-unsafe-io";};
""".trimIndent() + "\n")

        // dpkg config
        File(rootfs, "etc/dpkg/dpkg.cfg.d").mkdirs()
        File(rootfs, "etc/dpkg/dpkg.cfg.d/PaxHeaders").let { if (it.exists()) it.deleteRecursively() }
        File(rootfs, "etc/dpkg/dpkg.cfg.d/99-agent-optimizations").writeText(
            "force-unsafe-io\nforce-confdef\nforce-confold\nno-debsig\n")

        // bashrc
        File(rootfs, "root/.bashrc").writeText("""
export TERM=xterm-256color
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export COLORTERM=truecolor
export DEBIAN_FRONTEND=noninteractive
export PS1='\u@mcpshell:\w$ '
export HISTSIZE=0
export HISTFILESIZE=0
alias ls='ls --color=auto'
alias ll='ls -lah'
alias apt='apt -qq'
""".trimIndent() + "\n")

        // profile
        File(rootfs, "root/.profile").writeText("""
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export DEBIAN_FRONTEND=noninteractive
""".trimIndent() + "\n")

        log("Rootfs patched (DNS, stubs, agent defaults)")
    }
}
