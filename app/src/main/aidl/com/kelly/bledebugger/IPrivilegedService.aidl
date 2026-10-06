package com.kelly.bledebugger;

// Runs inside Shizuku's (root) process. Log bytes are passed back as plain binder data rather
// than as a file descriptor: SELinux would block the app's domain from reading a
// bluetooth_data_file fd even if root opened it.
interface IPrivilegedService {
    // Shizuku's reserved transaction code for tearing the service down.
    void destroy() = 16777114;

    // Runs `sh -c cmd` and returns combined stdout/stderr, prefixed with "exit=<code>\n".
    String exec(String cmd) = 1;

    // Absolute path of the current btsnoop log (may not exist yet).
    String findSnoopLogPath() = 2;

    // [size, inode] of the file, or [-1, -1] if it does not exist.
    long[] stat(String path) = 3;

    // Up to maxLen bytes starting at offset; empty array at EOF.
    byte[] read(String path, long offset, int maxLen) = 4;

    // Human-readable report of snoop settings and any snoop log files found on disk.
    String diagnose() = 5;
}
