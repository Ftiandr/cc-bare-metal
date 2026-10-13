package dev.ccbaremetal.kernel.io;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.fs.FileType;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.Stat;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An open file description, shared between file descriptors (and processes) that refer to it. Reference counted:
 * the underlying resource is released when the last descriptor is closed.
 */
public abstract class OpenFile {
    public static final int SEEK_SET = 0;
    public static final int SEEK_CUR = 1;
    public static final int SEEK_END = 2;

    private final AtomicInteger refs = new AtomicInteger(1);
    private volatile int revokedErrno;

    public abstract FileType type();

    public abstract Stat stat() throws FsException;

    /**
     * Read into {@code dst}. May block (pipes, terminals). Returns the number of bytes read, 0 on EOF.
     *
     * @param nonBlocking If set, throw EAGAIN instead of blocking.
     */
    public int read(ByteBuffer dst, boolean nonBlocking) throws FsException, InterruptedException {
        throw new FsException(Errno.EBADF);
    }

    public int write(ByteBuffer src, boolean nonBlocking) throws FsException, InterruptedException {
        throw new FsException(Errno.EBADF);
    }

    public int pread(ByteBuffer dst, long position) throws FsException {
        throw new FsException(Errno.ESPIPE);
    }

    public int pwrite(ByteBuffer src, long position) throws FsException {
        throw new FsException(Errno.ESPIPE);
    }

    public long seek(long offset, int whence) throws FsException {
        throw new FsException(Errno.ESPIPE);
    }

    public void truncate(long size) throws FsException {
        throw new FsException(Errno.EINVAL);
    }

    public void sync() throws FsException {
    }

    /** Whether a read would not block right now. */
    public boolean readReady() {
        return true;
    }

    /** Whether a write would not block right now. */
    public boolean writeReady() {
        return true;
    }

    public boolean isSeekable() {
        return false;
    }

    public boolean isTerminal() {
        return false;
    }

    /** The guest path this was opened from, if any. Used for directories (readdir) and /proc. */
    public String path() {
        return "";
    }

    /**
     * The device or file system behind this file went away (peripheral detached, disk ejected, monitor removed):
     * from now on I/O fails with {@code errno} (as on Linux after unplugging a device) and blocked readers wake up.
     */
    public final void revoke(int errno) {
        if (revokedErrno != 0) return;
        revokedErrno = errno;
        onRevoke();
        synchronized (this) {
            notifyAll();
        }
    }

    public final boolean revoked() {
        return revokedErrno != 0;
    }

    /** Throws the revocation error if the file was revoked; call before I/O and inside blocking waits. */
    protected final void checkRevoked() throws FsException {
        var errno = revokedErrno;
        if (errno != 0) throw new FsException(errno);
    }

    /** Release what the file holds when it is revoked (the descriptor itself stays until closed). */
    protected void onRevoke() {
    }

    public final OpenFile retain() {
        refs.incrementAndGet();
        return this;
    }

    public final void release() {
        if (refs.decrementAndGet() == 0) {
            try {
                onClose();
            } catch (FsException ignored) {
            }
        }
    }

    protected void onClose() throws FsException {
    }
}
