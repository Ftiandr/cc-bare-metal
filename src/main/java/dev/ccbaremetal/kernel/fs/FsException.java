package dev.ccbaremetal.kernel.fs;

import dev.ccbaremetal.kernel.Errno;

/**
 * A file system error carrying a WASI errno. Stack traces are disabled: these are normal control flow.
 */
public final class FsException extends Exception {
    private final int errno;

    public FsException(int errno) {
        this(errno, Errno.describe(errno));
    }

    public FsException(int errno, String message) {
        super(message, null, false, false);
        this.errno = errno;
    }

    public int errno() {
        return errno;
    }
}
