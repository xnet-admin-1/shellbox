package com.shellbox.terminal

import android.content.Context
import com.shellbox.shell.ProotBootstrap
import com.shellbox.shell.RishExecutor
import java.io.File

object ShellDiscovery {

    data class Shell(
        val id: String,
        val name: String,
        val command: String,
        val args: Array<String>,
        val env: Array<String>,
        val cwd: String,
        val available: Boolean = true,
        val needsSetup: Boolean = false
    )

    fun getShells(ctx: Context): List<Shell> {
        val shells = mutableListOf<Shell>()
        val filesDir = ctx.filesDir.absolutePath
        val nativeDir = ctx.applicationInfo.nativeLibraryDir

        // Ensure dirs exist
        File(filesDir, "home").mkdirs()
        File(filesDir, "tmp").mkdirs()

        // 1. Android sh
        shells.add(Shell(
            id = "sh", name = "Android Shell",
            command = "/system/bin/sh", args = arrayOf(),
            env = arrayOf("TERM=xterm-256color", "HOME=/data/local/tmp", "TMPDIR=$filesDir/tmp",
                "PATH=/system/bin:/system/xbin"),
            cwd = "/data/local/tmp"
        ))

        // 2. Ubuntu proot — always show, mark if needs setup
        val ubuntuInstalled = ProotBootstrap.isInstalled(ctx)
        val prootBin = ProotBootstrap.findProotXed(ctx)
        if (prootBin != null && ubuntuInstalled) {
            val rootfs = ProotBootstrap.rootfsDir(ctx).absolutePath
            // Ensure /root exists in rootfs
            java.io.File(rootfs, "root").mkdirs()
            shells.add(Shell(
                id = "ubuntu", name = "Ubuntu (proot)",
                command = prootBin.absolutePath,
                args = buildProotArgs(rootfs, filesDir),
                env = buildProotEnv(filesDir, nativeDir),
                cwd = "/root"
            ))
        } else {
            shells.add(Shell(
                id = "ubuntu", name = "Ubuntu (setup needed)",
                command = "", args = arrayOf(), env = arrayOf(), cwd = "",
                available = false, needsSetup = true
            ))
        }

        // 3. Shizuku rish — use rish binary for elevated shell
        val shizukuAvailable = try { rikka.shizuku.Shizuku.pingBinder() } catch (_: Exception) { false }
        if (shizukuAvailable) {
            val rishReady = setupRish(ctx)
            val binDir = File(ctx.filesDir, "bin")
            shells.add(Shell(
                id = "rish",
                name = if (rishReady) "Shizuku Shell (ADB)" else "Shizuku (tap to grant)",
                command = if (rishReady) File(binDir, "rish").absolutePath else "",
                args = if (rishReady) arrayOf() else arrayOf(),
                env = if (rishReady) arrayOf(
                    "TERM=xterm-256color",
                    "RISH_APPLICATION_ID=com.shellbox",
                    "RISH_DEX=${File(binDir, "rish_shizuku.dex").absolutePath}"
                ) else arrayOf(),
                cwd = "/data/local/tmp",
                available = rishReady,
                needsSetup = !rishReady
            ))
        }

        return shells
    }

    /**
     * Copy rish + dex from Shizuku's storage to our private dir.
     * Dex must be chmod 400 on Android 14+.
     */
    private fun setupRish(ctx: Context): Boolean {
        val binDir = File(ctx.filesDir, "bin")
        binDir.mkdirs()
        val privateRish = File(binDir, "rish")
        val privateDex = File(binDir, "rish_shizuku.dex")

        val storagePaths = listOf(
            "/storage/emulated/0/shizuku/rish",
            "/sdcard/shizuku/rish"
        )
        for (src in storagePaths) {
            val srcFile = File(src)
            val srcDex = File(File(src).parent, "rish_shizuku.dex")
            if (srcFile.isFile && srcDex.isFile) {
                try {
                    // Remove old files
                    listOf(privateRish, privateDex).forEach { f ->
                        try { f.setWritable(true); f.delete() } catch (_: Exception) {}
                    }
                    srcFile.inputStream().use { i -> privateRish.outputStream().use { o -> i.copyTo(o) } }
                    srcDex.inputStream().use { i -> privateDex.outputStream().use { o -> i.copyTo(o) } }
                    privateRish.setExecutable(true)
                    privateDex.setReadable(true)
                    privateDex.setWritable(false) // chmod 400 for Android 14+
                    return true
                } catch (_: Exception) {}
            }
        }
        return privateRish.isFile && privateDex.isFile
    }

    private fun buildProotArgs(rootfs: String, filesDir: String): Array<String> {
        val args = mutableListOf("--kill-on-exit")

        fun bind(src: String, dst: String? = null) {
            args.addAll(listOf("-b", if (dst != null) "$src:$dst" else src))
        }

        for (mnt in listOf("/apex", "/odm", "/product", "/system", "/system_ext", "/vendor",
                           "/linkerconfig/ld.config.txt", "/linkerconfig/com.android.art/ld.config.txt")) {
            val f = File(mnt)
            if (f.exists()) bind(f.canonicalPath)
        }

        bind("/dev"); bind("/dev/urandom", "/dev/random")
        bind("/proc"); bind("/sys")
        bind("/proc/self/fd", "/dev/fd"); bind(filesDir)

        val tmpDir = File(rootfs, "tmp").also { it.mkdirs() }
        bind(tmpDir.absolutePath, "/dev/shm")
        bind("/proc/self/fd/0", "/dev/stdin")
        bind("/proc/self/fd/1", "/dev/stdout")
        bind("/proc/self/fd/2", "/dev/stderr")

        val fips = File(tmpDir, "fips_enabled").also { it.writeText("0\n") }
        bind(fips.absolutePath, "/proc/sys/crypto/fips_enabled")

        args.addAll(listOf(
            "-r", rootfs, "-0", "--link2symlink", "--sysvipc", "-L",
            "-w", "/root",
            "/usr/bin/env",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root", "USER=root", "TERM=xterm-256color",
            "TMPDIR=/tmp", "LANG=C.UTF-8", "LC_ALL=C.UTF-8",
            "/bin/bash", "--login"
        ))
        return args.toTypedArray()
    }

    private fun buildProotEnv(filesDir: String, nativeDir: String): Array<String> {
        val env = mutableListOf(
            "PROOT_TMP_DIR=$filesDir/tmp",
            "LD_LIBRARY_PATH=$filesDir"
        )
        val loader = File(nativeDir, "libproot.so")
        val loader32 = File(nativeDir, "libproot32.so")
        if (loader.exists()) env.add("PROOT_LOADER=${loader.absolutePath}")
        if (loader32.exists()) env.add("PROOT_LOADER32=${loader32.absolutePath}")
        return env.toTypedArray()
    }
}
