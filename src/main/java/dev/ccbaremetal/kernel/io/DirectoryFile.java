package dev.ccbaremetal.kernel.io;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.fs.FileType;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.Stat;

import java.nio.ByteBuffer;

/** An open directory. Listing happens through the VFS using {@link #path()}. */
public final class DirectoryFile extends OpenFile {
    private final String path;
    private final Stat stat;

    public DirectoryFile(String path, Stat stat) {
        this.path = path;
        this.stat = stat;
    }

    @Override
    public FileType type() {
        return FileType.DIRECTORY;
    }

    @Override
    public Stat stat() {
        return stat;
    }

    @Override
    public int read(ByteBuffer dst, boolean nonBlocking) throws FsException {
        throw new FsException(Errno.EISDIR);
    }

    @Override
    public int write(ByteBuffer src, boolean nonBlocking) throws FsException {
        throw new FsException(Errno.EISDIR);
    }

    @Override
    public String path() {
        return path;
    }
}
