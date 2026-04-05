package com.shellbox.terminal

import android.util.Log
import com.shellbox.terminal.backend.TerminalSession
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.IOException

/**
 * TerminalSession backed by ShizukuRemoteProcess (UID 2000, adb-level).
 * Runs sh -i over pipes with local echo since Android lacks PTY utilities.
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
        private val BANNER = "\r\n${ESC}[1;32m[Shizuku/ADB shell - uid=2000]${ESC}[0m\r\n"
        private val EXITED = "\r\n${ESC}[33m[Shizuku shell exited]${ESC}[0m\r\n"
    }

    @Volatile private var rishProcess: ShizukuRemoteProcess? = null
    @Volatile private var rishActive = false
    // Line buffer for local echo with editing support
    private val lineBuffer = StringBuilder()

    override fun initializeEmulator(columns: Int, rows: Int) {
        initializeEmulatorOnly(columns, rows)
        Thread(null, { launchRishProcess(columns, rows) }, "RishLauncher").also {
            it.isDaemon = true; it.start()
        }
    }

    private fun launchRishProcess(columns: Int, rows: Int) {
        try {
            val envArray = (rishEnv + mapOf(
                "RISH_APPLICATION_ID" to "com.shellbox",
                "TERM" to "xterm-256color",
                "COLUMNS" to "$columns",
                "LINES" to "$rows",
                "HOME" to "/data/local/tmp",
                "SHELL" to "/system/bin/sh"
            )).map { (k, v) -> "$k=$v" }.toTypedArray()

            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val proc = method.invoke(null,
                arrayOf("/system/bin/sh"),
                envArray,
                "/data/local/tmp"
            ) as ShizukuRemoteProcess

            rishProcess = proc
            rishActive = true

            val bannerBytes = BANNER.toByteArray()
            feedToEmulator(bannerBytes, 0, bannerBytes.size)

            // Read stdout — translate bare LF to CR+LF for terminal
            Thread(null, {
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val n = proc.inputStream.read(buf)
                        if (n < 0) break
                        feedWithCRLF(buf, n)
                    }
                } catch (_: IOException) {}
                rishActive = false
                val exitBytes = EXITED.toByteArray()
                feedToEmulator(exitBytes, 0, exitBytes.size)
                finishIfRunning()
            }, "RishStdout").also { it.isDaemon = true; it.start() }

            // Read stderr (prompt + errors) — also translate LF
            Thread(null, {
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val n = proc.errorStream.read(buf)
                        if (n < 0) break
                        feedWithCRLF(buf, n)
                    }
                } catch (_: IOException) {}
            }, "RishStderr").also { it.isDaemon = true; it.start() }

            // Set up prompt and interactive mode
            Thread.sleep(150)
            try {
                val init = "export PS1='$ '; set -o emacs 2>/dev/null\n"
                proc.outputStream.write(init.toByteArray())
                proc.outputStream.flush()
            } catch (_: IOException) {}

            Log.d(TAG, "Shizuku shell started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Shizuku process", e)
            val msg = "\r\nShizuku error: ${e.message ?: "unknown"}\r\n"
            val b = msg.toByteArray()
            feedToEmulator(b, 0, b.size)
        }
    }

    /**
     * Feed data to emulator, translating bare LF (0x0a) to CR+LF (0x0d 0x0a).
     * Without a PTY, the shell outputs bare LF which the terminal needs as CR+LF.
     */
    private fun feedWithCRLF(buf: ByteArray, len: Int) {
        var start = 0
        for (i in 0 until len) {
            if (buf[i] == 0x0a.toByte() && (i == 0 || buf[i - 1] != 0x0d.toByte())) {
                // Flush bytes before this LF
                if (i > start) feedToEmulator(buf, start, i - start)
                // Insert CR+LF
                feedToEmulator(byteArrayOf(0x0d, 0x0a), 0, 2)
                start = i + 1
            }
        }
        // Flush remaining bytes
        if (start < len) feedToEmulator(buf, start, len - start)
    }

    /**
     * Handle input with local echo and basic line editing.
     * Without a PTY, the shell won't echo characters or handle backspace visually.
     */
    override fun write(data: ByteArray, offset: Int, count: Int) {
        val rp = rishProcess
        if (!rishActive || rp == null) return

        try {
            for (i in offset until offset + count) {
                val b = data[i]
                when {
                    // Enter — send the line, echo CR+LF
                    b == '\r'.code.toByte() || b == '\n'.code.toByte() -> {
                        feedToEmulator(byteArrayOf(0x0d, 0x0a), 0, 2) // CR LF
                        rp.outputStream.write(lineBuffer.toString().toByteArray())
                        rp.outputStream.write(byteArrayOf(0x0a)) // LF to shell
                        rp.outputStream.flush()
                        lineBuffer.clear()
                    }
                    // Backspace / DEL
                    b == 0x7f.toByte() || b == 0x08.toByte() -> {
                        if (lineBuffer.isNotEmpty()) {
                            lineBuffer.deleteCharAt(lineBuffer.length - 1)
                            // Erase character visually: backspace + space + backspace
                            feedToEmulator(byteArrayOf(0x08, 0x20, 0x08), 0, 3)
                        }
                    }
                    // Ctrl+C — send interrupt, clear line
                    b == 0x03.toByte() -> {
                        lineBuffer.clear()
                        feedToEmulator("^C\r\n".toByteArray(), 0, 4)
                        rp.outputStream.write(byteArrayOf(0x03))
                        rp.outputStream.flush()
                    }
                    // Ctrl+D — send EOF
                    b == 0x04.toByte() -> {
                        rp.outputStream.write(byteArrayOf(0x04))
                        rp.outputStream.flush()
                    }
                    // Ctrl+L — clear screen
                    b == 0x0c.toByte() -> {
                        feedToEmulator("\u001b[2J\u001b[H".toByteArray(), 0, 7)
                        rp.outputStream.write("clear 2>/dev/null; echo\n".toByteArray())
                        rp.outputStream.flush()
                    }
                    // Regular printable character — echo and buffer
                    b >= 0x20.toByte() -> {
                        lineBuffer.append(b.toInt().toChar())
                        feedToEmulator(data, i, 1)
                    }
                    // Escape sequences (arrow keys etc) — pass through raw
                    else -> {
                        rp.outputStream.write(data, i, 1)
                        rp.outputStream.flush()
                    }
                }
            }
        } catch (_: IOException) {}
    }

    override fun finishIfRunning() {
        rishProcess?.let { try { it.destroy() } catch (_: Exception) {} }
        rishActive = false
        // Do NOT call super.finishIfRunning() — it sends SIGKILL to mShellPid
        // which is invalid for Shizuku remote sessions and can kill the app process.
    }
}
