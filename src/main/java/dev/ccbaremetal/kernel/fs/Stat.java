package dev.ccbaremetal.kernel.fs;

/**
 * File metadata, times are nanoseconds since the epoch. {@code mode} holds the permission bits (07777: setuid,
 * setgid, sticky, rwx for owner, group, others), {@code uid}/{@code gid} the owner.
 */
public record Stat(FileType type, long size, long mtime, long ctime, long ino, int mode, int uid, int gid) {
    public static final int S_ISUID = 04000;
    public static final int S_ISGID = 02000;
    public static final int S_ISVTX = 01000;

    /** Owned by root with the usual mode for its type (directories 0755, files 0644, devices 0666). */
    public Stat(FileType type, long size, long mtime, long ctime, long ino) {
        this(type, size, mtime, ctime, ino, defaultMode(type), 0, 0);
    }

    public static int defaultMode(FileType type) {
        return switch (type) {
            case DIRECTORY -> 0755;
            case CHARACTER_DEVICE, BLOCK_DEVICE -> 0666;
            default -> 0644;
        };
    }

    public static Stat dir(long mtime) {
        return new Stat(FileType.DIRECTORY, 0, mtime, mtime, 0);
    }

    public static Stat file(long size, long mtime) {
        return new Stat(FileType.REGULAR_FILE, size, mtime, mtime, 0);
    }

    public static Stat device() {
        return new Stat(FileType.CHARACTER_DEVICE, 0, 0, 0, 0);
    }

    public boolean isDirectory() {
        return type == FileType.DIRECTORY;
    }

    public Stat withIno(long ino) {
        return new Stat(type, size, mtime, ctime, ino, mode, uid, gid);
    }

    public Stat withOwner(int mode, int uid, int gid) {
        return new Stat(type, size, mtime, ctime, ino, mode & 07777, uid, gid);
    }

    public Stat withSize(long size) {
        return new Stat(type, size, mtime, ctime, ino, mode, uid, gid);
    }
}
