package com.shellbox.terminal

import android.content.Context
import com.shellbox.shell.ProotBootstrap
import com.shellbox.shell.RishExecutor
import java.io.File

/**
 * Discovers available shell backends. Pure Kotlin — no Python dependency.
 */
object ShellDiscovery {

    data class Shell(
        val id: String,
        val name: String,
        val command: String,
        val args: Array<String>,
        val env: Array<String>,
        val cwd: String
    )

    fun getShells(ctx: Context): List<Shell> {
        val shells = mutableListOf<Shell>()
        val filesDir = ctx.filesDir.absolutePath
        val nativeDir = ctx.applicationInfo.nativeLibraryDir

        // 1. Android sh — always available
        shells.add(Shell(
            id = "sh", name = "Android Shell",
            command = "/system/bin/sh", args = arrayOf("-l"),
            env = arrayOf("TERM=xterm-256color", "HOME=$filesDir/home", "TMPDIR=$filesDir/tmp"),
            cwd = "$filesDir/home"
        ))

        // 2. Ubuntu proot
        if (ProotBootstrap.isInstalled(ctx)) {
            val prootBin = ProotBootstrap.findProotXed(ctx)
            if (prootBin != null) {
                val rootfs = ProotBootstrap.rootfsDir(ctx).absolutePath
                val args = buildProotArgs(rootfs, filesDir)
                val env = buildProotEnv(filesDir, nativeDir)
                shells.add(Shell(
                    id = "ubuntu", name = "Ubuntu (proot)",
                    command = prootBin.absolutePath, args = args, env = env,
                    cwd = "/root"
                ))
            }
        }

        // 3. Shizuku rish
        if (RishExecutor.isShizukuReady()) {
            shells.add(Shell(
                id = "rish", name = "Shizuku Shell",
                command = "/system/bin/sh", args = arrayOf(),
                env = arrayOf("TERM=xterm-256color"),
                cwd = "/data/local/tmp"
            ))
        }

        return shells
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
