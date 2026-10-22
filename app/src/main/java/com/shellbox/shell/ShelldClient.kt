package com.shellbox.shell

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream

/**
 * Shizuku-free client for the bundled `xnix-shelld` privileged daemon.
 *
 * The daemon listens on an abstract AF_UNIX socket "@xnix-shelld" and runs
 * commands / allocates PTYs as the shell (uid 2000) user. The native JNI
 * library `shelld-client` implements the socket protocol.
 */
object ShelldClient {

    private const val TAG = "ShelldClient"

    init {
        System.loadLibrary("shelld-client")
    }

    /**
     * Connect to the daemon and request EXEC of [cmd] (`sh -c <cmd>`).
     * Returns a connected socket fd streaming the command's combined
     * stdout/stderr, or a negative errno on failure.
     */
    external fun connectExec(cmd: String): Int

    /**
     * Ask the daemon to allocate a real PTY and spawn `sh` (or `sh -c <cmd>`
     * when [cmd] is non-null). Returns the PTY master fd, or a negative errno.
     */
    external fun openPty(cmd: String?): Int

    /**
     * Convenience: run [command] and accumulate its output until EOF.
     * Mirrors the 8000-char truncation behaviour of the original executor.
     */
    fun exec(command: String, timeoutMs: Long = 15_000): String {
        val fd = connectExec(command)
        if (fd < 0) {
            Log.w(TAG, "connectExec failed: errno=${-fd}")
            return "Error: xnix-shelld connect failed (errno ${-fd})"
        }

        val sb = StringBuilder()
        val pfd = ParcelFileDescriptor.adoptFd(fd)
        val thread = Thread {
            try {
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sb.append(String(buf, 0, n))
                        if (sb.length > 8000) break
                    }
                }
            } catch (_: Exception) {
            }
        }
        thread.start()
        thread.join(timeoutMs)
        if (thread.isAlive) {
            thread.interrupt()
            sb.append("\n[timeout after ${timeoutMs / 1000}s]")
        }
        try { pfd.close() } catch (_: Exception) {}

        val out = sb.toString().trim()
        return if (out.length > 8000) out.take(8000) + "\n[truncated]"
        else out.ifEmpty { "(no output)" }
    }
}
