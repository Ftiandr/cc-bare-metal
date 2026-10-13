package dev.ccbaremetal.kernel.io;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.fs.FileType;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.Stat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.function.Supplier;

/** A regular file backed by a {@link SeekableByteChannel}. */
public class ChannelFile extends OpenFile {
    private final SeekableByteChannel channel;
    private final String path;
    private final boolean readable;
    private final boolean writable;
    private final boolean append;
    private final StatSupplier stat;

    @FunctionalInterface
    public interface StatSupplier {
        Stat get() throws FsException;
    }

    public ChannelFile(SeekableByteChannel channel, String path, boolean readable, boolean writable, boolean append, StatSupplier stat) {
        this.channel = channel;
        this.path = path;
        this.readable = readable;
        this.writable = writable;
        this.append = append;
        this.stat = stat;
    }

    public static ChannelFile readOnly(SeekableByteChannel channel, String path, Supplier<Stat> stat) {
        return new ChannelFile(channel, path, true, false, false, stat::get);
    }

    @Override
    public FileType type() {
        return FileType.REGULAR_FILE;
    }

    @Override
    public Stat stat() throws FsException {
        try {
            var s = stat.get();
            return new Stat(s.type(), Math.max(s.size(), size()), s.mtime(), s.ctime(), s.ino());
        } catch (FsException e) {
            return Stat.file(size(), 0);
        }
    }

    private long size() {
        try {
            return channel.size();
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public synchronized int read(ByteBuffer dst, boolean nonBlocking) throws FsException {
        checkRevoked();
        if (!readable) throw new FsException(Errno.EBADF);
        try {
            var n = channel.read(dst);
            return Math.max(n, 0);
        } catch (IOException e) {
            throw io(e);
        }
    }

    @Override
    public synchronized int write(ByteBuffer src, boolean nonBlocking) throws FsException {
        checkRevoked();
        if (!writable) throw new FsException(Errno.EBADF);
        try {
            if (append) channel.position(channel.size());
            var total = 0;
            while (src.hasRemaining()) total += channel.write(src);
            return total;
        } catch (IOException e) {
            throw io(e);
        }
    }

    @Override
    public synchronized int pread(ByteBuffer dst, long position) throws FsException {
        checkRevoked();
        if (!readable) throw new FsException(Errno.EBADF);
        try {
            var old = channel.position();
            channel.position(position);
            try {
                return Math.max(channel.read(dst), 0);
            } finally {
                channel.position(old);
            }
        } catch (IOException e) {
            throw io(e);
        }
    }

    @Override
    public synchronized int pwrite(ByteBuffer src, long position) throws FsException {
        checkRevoked();
        if (!writable) throw new FsException(Errno.EBADF);
        try {
            var old = channel.position();
            channel.position(position);
            try {
                var total = 0;
                while (src.hasRemaining()) total += channel.write(src);
                return total;
            } finally {
                channel.position(old);
            }
        } catch (IOException e) {
            throw io(e);
        }
    }

    @Override
    public synchronized long seek(long offset, int whence) throws FsException {
        checkRevoked();
        try {
            var base = switch (whence) {
                case SEEK_SET -> 0;
                case SEEK_CUR -> channel.position();
                case SEEK_END -> channel.size();
                default -> throw new FsException(Errno.EINVAL);
            };
            var target = base + offset;
            if (target < 0) throw new FsException(Errno.EINVAL);
            channel.position(target);
            return target;
        } catch (IOException e) {
            throw io(e);
        }
    }

    @Override
    public synchronized void truncate(long size) throws FsException {
        checkRevoked();
        if (!writable) throw new FsException(Errno.EBADF);
        try {
            var current = channel.size();
            if (size < current) {
                channel.truncate(size);
            } else if (size > current) {
                var pos = channel.position();
                channel.position(size - 1);
                channel.write(ByteBuffer.wrap(new byte[1]));
                channel.position(pos);
            }
        } catch (IOException e) {
            throw io(e);
        }
    }

    @Override
    public boolean isSeekable() {
        return true;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    protected void onRevoke() {
        // the medium is gone (disk ejected): let go of it now, not when the program closes the file
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    protected void onClose() throws FsException {
        try {
            channel.close();
        } catch (IOException e) {
            throw io(e);
        }
    }

    static FsException io(IOException e) {
        if (e instanceof FsIOException f) return f.unwrap();
        var msg = e.getMessage();
        if (msg != null && msg.contains("space")) return new FsException(Errno.ENOSPC, msg);
        return new FsException(Errno.EIO, msg == null ? "I/O error" : msg);
    }

    /** An IOException wrapping an errno, used to tunnel errors through channel APIs. */
    public static final class FsIOException extends IOException {
        private final int errno;

        public FsIOException(int errno) {
            super(Errno.describe(errno));
            this.errno = errno;
        }

        public FsException unwrap() {
            return new FsException(errno);
        }
    }
}
