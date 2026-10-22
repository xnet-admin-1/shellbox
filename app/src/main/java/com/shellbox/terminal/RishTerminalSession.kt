package com.shellbox.terminal

import android.os.ParcelFileDescriptor
import android.util.Log
import com.shellbox.shell.ShelldClient
import com.shellbox.terminal.backend.TerminalSession
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * TerminalSession backed by a REAL PTY allocated by the bundled `xnix-shelld`
 * privileged daemon (uid 2000). Shizuku-free.
 *
 * The daemon spawns `sh` as a session leader on a PTY slave and hands us the
 * master fd via SCM_RIGHTS. Because it is a real PTY, the shell performs its
 * own echo and line editing, so we simply pipe bytes in both directions.
 *
 * The constructor signature and public methods are preserved so MainActivity
 * and ShellDiscovery keep compiling.
 */
class RishTerminalSession(
    private val dexPath: String,
    private val rishEnv: Map<String, String>,
    changeCallback: SessionChangedCallback
) : TerminalSession(
    "/system/bin/sh", "/data/local/tmp",
    arrayOf("/system/bin/sh"),
    rishEnv.map { (k, v) -> "$k=$v" }.toTypedArray(),
    changeCallback
) {
    companion object {
        private const val TAG = "RishSession"
        private const val ESC = "\u001b"
        private val BANNER = "\r\n${ESC}[1;32m[X-NIX shell - uid=2000]${ESC}[0m\r\n"
        private val EXITED = "\r\n${ESC}[33m[X-NIX shell exited]${ESC}[0m\r\n"
    }

    @Volatile private var ptyFd: Int = -1
    @Volatile private var pfd: ParcelFileDescriptor? = null
    @Volatile private var ptyOut: FileOutputStream? = null
    @Volatile private var ptyActive = false

    override fun initializeEmulator(columns: Int, rows: Int) {
        initializeEmulatorOnly(columns, rows)
        Thread(null, { launchPtyProcess(columns, rows) }, "PtyLauncher").also {
            it.isDaemon = true; it.start()
        }
    }

    private fun launchPtyProcess(columns: Int, rows: Int) {
        try {
            val fd = ShelldClient.openPty(null)
            if (fd < 0) {
                val msg = "\r\nxnix-shelld error: openPty failed (errno ${-fd})\r\n"
                val b = msg.toByteArray()
                feedToEmulator(b, 0, b.size)
                finishIfRunning()
                return
            }

            ptyFd = fd
            val parcel = ParcelFileDescriptor.adoptFd(fd)
            pfd = parcel
            ptyOut = FileOutputStream(parcel.fileDescriptor)
            ptyActive = true

            val bannerBytes = BANNER.toByteArray()
            feedToEmulator(bannerBytes, 0, bannerBytes.size)

            // Reader: pump PTY master -> emulator. Real PTY, so no CRLF fixups.
            Thread(null, {
                val input = FileInputStream(parcel.fileDescriptor)
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        feedToEmulator(buf, 0, n)
                    }
                } catch (_: IOException) {}
                ptyActive = false
                val exitBytes = EXITED.toByteArray()
                feedToEmulator(exitBytes, 0, exitBytes.size)
                finishIfRunning()
            }, "PtyReader").also { it.isDaemon = true; it.start() }

            Log.d(TAG, "X-NIX PTY shell started (fd=$fd)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start X-NIX PTY process", e)
            val msg = "\r\nxnix-shelld error: ${e.message ?: "unknown"}\r\n"
            val b = msg.toByteArray()
            feedToEmulator(b, 0, b.size)
        }
    }

    /** Pipe user input straight to the PTY master — the PTY echoes. */
    override fun write(data: ByteArray, offset: Int, count: Int) {
        val out = ptyOut
        if (!ptyActive || out == null) return
        try {
            out.write(data, offset, count)
            out.flush()
        } catch (_: IOException) {}
    }

    override fun finishIfRunning() {
        ptyActive = false
        try { ptyOut?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        ptyOut = null
        pfd = null
        ptyFd = -1
        // Do NOT call super.finishIfRunning(): there is no local mShellPid to
        // SIGKILL; the daemon owns the remote shell process.
    }
}
