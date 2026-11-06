package com.shellbox.shell

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Executes commands inside a proot Ubuntu/Debian environment.
 * Runs in UID 2000 via xnix-shelld.
 */
object ProotExecutor {

    private const val TAG = "ProotExecutor"
    @Volatile private var currentProcess: Process? = null

    fun exec(context: Context, command: String, timeoutMs: Long = 30_000): String {
        val rootfsPath = ProotBootstrap.rootfsPath(context)
        val nativeLibDir = context.applicationInfo.nativeLibraryDir

        val prootBin = when {
            File("/product/bin/proot").canExecute() -> "/product/bin/proot"
            File("/data/local/tmp/proot").canExecute() -> "/data/local/tmp/proot"
            File(nativeLibDir, "libproot-xed.so").exists() -> File(nativeLibDir, "libproot-xed.so").absolutePath
            else -> "/data/local/tmp/proot"
        }
        val loader = when {
            File("/product/lib64/libproot.so").exists() -> "/product/lib64/libproot.so"
            File(nativeLibDir, "libproot.so").exists() -> File(nativeLibDir, "libproot.so").absolutePath
            else -> ""
        }

        val loaderEnv = if (loader.isNotEmpty()) "PROOT_LOADER=$loader" else ""
        val escapedCmd = command.replace("\"", "\\\"")

        // Execute inside UID 2000 via xnix-shelld
        val fullCmd = "export LD_LIBRARY_PATH=/product/lib64:$nativeLibDir $loaderEnv PROOT_TMP_DIR=/data/local/tmp; " +
            "exec $prootBin --kill-on-exit -b /dev -b /dev/urandom:/dev/random -b /proc -b /sys -b /proc/self/fd:/dev/fd " +
            "-b /sdcard -r $rootfsPath -0 --link2symlink -L -w /root " +
            "/usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
            "HOME=/root USER=root TERM=xterm-256color TMPDIR=/tmp LANG=C.UTF-8 LC_ALL=C.UTF-8 /bin/bash -c \"$escapedCmd\""

        Log.d(TAG, "exec via shelld: $command")
        try {
            val output = ShelldClient.exec(fullCmd, timeoutMs)
            val filtered = output.lines()
                .filter { !it.startsWith("proot warning:") && !it.startsWith("proot info:") }
                .joinToString("\n").trim()
            if (filtered.isNotEmpty() && !filtered.startsWith("Error: xnix-shelld connect failed")) {
                return filtered
            }
        } catch (e: Exception) {
            Log.e(TAG, "shelld exec failed, falling back to local: ${e.message}")
        }

        // Fallback to local execution if shelld fails
        val filesDir = context.filesDir.absolutePath
        val localRootfs = File(filesDir, "env/ubuntu")
        val localProotBin = ProotBootstrap.findProotXed(context)
            ?: return "Error: proot binary not found in $nativeLibDir"
        if (!localRootfs.isDirectory) return "Error: Ubuntu rootfs not installed. Run setup first."

        val args = buildProotArgs(localRootfs, filesDir, command)
        val env = buildProotEnv(filesDir, nativeLibDir)

        return try {
            val pb = ProcessBuilder(listOf(localProotBin.absolutePath) + args)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            val process = pb.start()
            currentProcess = process

            val output = ShellExecutor.readAll(process.inputStream, timeoutMs)
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            currentProcess = null
            val out = output.lines()
                .filter { !it.startsWith("proot warning:") && !it.startsWith("proot info:") }
                .joinToString("\n").trim()
            if (out.length > 8000) out.take(8000) + "\n[truncated]"
            else out.ifEmpty { "(no output, exit ${process.exitValue()})" }
        } catch (e: Exception) {
            currentProcess = null
            Log.e(TAG, "exec failed", e)
            "Error: ${e.message}"
        }
    }

    /** Kill any running proot process. */
    fun cancel() {
        currentProcess?.let {
            Log.i(TAG, "Cancelling running proot process")
            it.destroyForcibly()
            currentProcess = null
        }
    }

    private fun buildProotArgs(rootfs: File, filesDir: String, command: String): List<String> {
        val args = mutableListOf("--kill-on-exit")

        fun bind(src: String, dst: String? = null) {
            args.addAll(listOf("-b", if (dst != null) "$src:$dst" else src))
        }

        for (mnt in listOf("/apex", "/odm", "/product", "/system", "/system_ext", "/vendor",
                           "/linkerconfig/ld.config.txt",
                           "/linkerconfig/com.android.art/ld.config.txt")) {
            val f = File(mnt)
            if (f.exists()) bind(f.canonicalPath)
        }

        bind("/dev")
        bind("/dev/urandom", "/dev/random")
        bind("/proc")
        bind("/sys")
        bind("/proc/self/fd", "/dev/fd")
        bind(filesDir)

        val tmpDir = File(rootfs, "tmp").also { it.mkdirs() }
        bind(tmpDir.absolutePath, "/dev/shm")

        bind("/proc/self/fd/0", "/dev/stdin")
        bind("/proc/self/fd/1", "/dev/stdout")
        bind("/proc/self/fd/2", "/dev/stderr")

        val fipsFile = File(tmpDir, "fips_enabled").also { it.writeText("0\n") }
        bind(fipsFile.absolutePath, "/proc/sys/crypto/fips_enabled")

        args.addAll(listOf(
            "-r", rootfs.absolutePath,
            "-0", "--link2symlink", "--sysvipc", "-L",
            "-w", "/root",
            "/usr/bin/env",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root", "USER=root", "TERM=xterm-256color",
            "TMPDIR=/tmp", "LANG=C.UTF-8", "LC_ALL=C.UTF-8",
            "/bin/bash", "-c", command
        ))
        return args
    }

    private fun buildProotEnv(filesDir: String, nativeLibDir: String): Map<String, String> {
        val env = mutableMapOf(
            "PROOT_TMP_DIR" to "$filesDir/tmp",
            "LD_LIBRARY_PATH" to filesDir
        )
        val loader = File(nativeLibDir, "libproot.so")
        val loader32 = File(nativeLibDir, "libproot32.so")
        if (loader.exists()) env["PROOT_LOADER"] = loader.absolutePath
        if (loader32.exists()) env["PROOT_LOADER32"] = loader32.absolutePath
        return env
    }
}
