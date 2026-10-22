package com.shellbox.shell

/**
 * Elevated shell executor.
 *
 * Previously backed by Shizuku; now delegates to [ShelldClient], a client for
 * the bundled `xnix-shelld` privileged daemon (uid 2000). The public API is
 * preserved so existing callers keep compiling.
 */
object RishExecutor {

    fun exec(command: String, timeoutMs: Long = 15_000): String =
        ShelldClient.exec(command, timeoutMs)

    /**
     * The daemon is bundled and always available, so readiness is always true.
     * Kept for API compatibility with existing callers.
     */
    fun isShizukuReady(): Boolean = true
}
