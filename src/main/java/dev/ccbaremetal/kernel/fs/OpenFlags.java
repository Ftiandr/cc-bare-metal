package dev.ccbaremetal.kernel.fs;

/** Flags for {@link FsBackend#open}. */
public final class OpenFlags {
    public static final int READ = 1;
    public static final int WRITE = 2;
    public static final int CREATE = 4;
    public static final int TRUNCATE = 8;
    public static final int EXCLUSIVE = 16;
    public static final int APPEND = 32;
    public static final int DIRECTORY = 64;

    private OpenFlags() {
    }

    public static boolean has(int flags, int flag) {
        return (flags & flag) != 0;
    }
}
