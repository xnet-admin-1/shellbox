package com.shellbox.terminal

/**
 * Shizuku elevated terminal requires subclassing TerminalSession,
 * but Termux v0.118.0 marks it as `final`.
 *
 * Options for Phase 2:
 * 1. Fork termux-app terminal-emulator to make TerminalSession non-final
 * 2. Use AIOPE's approach (which uses a modified Termux library)
 * 3. Use reflection to access TerminalSession's internal PTY fd
 *
 * For now, Shizuku shell runs as app-level sh (not elevated).
 */
object ShizukuSession
