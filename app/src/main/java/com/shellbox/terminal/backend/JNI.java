package com.shellbox.terminal.backend;

/**
 * JNI bridge to libneoterm.so native methods.
 * Loads the native library and provides PTY operations.
 */
final class JNI {
    static {
        System.loadLibrary("neoterm");
    }

    static native int createSubprocess(String cmd, String cwd, String[] args, String[] envVars, int[] processId, int rows, int columns);
    static native void setPtyWindowSize(int fd, int rows, int cols);
    static native int waitFor(int processId);
    static native void close(int fileDescriptor);
}
