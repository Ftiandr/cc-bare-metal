package dev.ccbaremetal.kernel.io;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.fs.FileType;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.Stat;

import java.nio.ByteBuffer;

/** A read-only file over a byte array (system image files, /proc entries). */
public final class MemoryFile extends OpenFile {
    private final byte[] data;
    private final String path;
    private final long mtime;
    private long position;

    public MemoryFile(byte[] data, String path, long mtime) {
        this.data = data;
        this.path = path;
        this.mtime = mtime;
    }

    @Override
    public FileType type() {
        return FileType.REGULAR_FILE;
    }

    @Override
    public Stat stat() {
        return Stat.file(data.length, mtime);
    }

    @Override
    public synchronized int read(ByteBuffer dst, boolean nonBlocking) {
        var n = pread(dst, position);
        position += n;
        return n;
    }

    @Override
    public int pread(ByteBuffer dst, long pos) {
        if (pos >= data.length) return 0;
        var n = (int) Math.min(dst.remaining(), data.length - pos);
        dst.put(data, (int) pos, n);
        return n;
    }

    @Override
    public synchronized long seek(long offset, int whence) throws FsException {
        var base = switch (whence) {
            case SEEK_SET -> 0;
            case SEEK_CUR -> position;
            case SEEK_END -> data.length;
            default -> throw new FsException(Errno.EINVAL);
        };
        if (base + offset < 0) throw new FsException(Errno.EINVAL);
        return position = base + offset;
    }

    @Override
    public boolean isSeekable() {
        return true;
    }

    @Override
    public String path() {
        return path;
    }
}
