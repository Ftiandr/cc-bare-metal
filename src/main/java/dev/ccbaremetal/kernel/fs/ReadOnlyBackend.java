package dev.ccbaremetal.kernel.fs;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.io.OpenFile;

import java.util.List;

/** A read-only view of another backend (the unpacked compiler toolchain). */
public final class ReadOnlyBackend implements FsBackend {
    private final FsBackend inner;
    private final String description;

    public ReadOnlyBackend(FsBackend inner, String description) {
        this.inner = inner;
        this.description = description;
    }

    @Override
    public Stat stat(String path) throws FsException {
        return inner.stat(path);
    }

    @Override
    public List<String> list(String path) throws FsException {
        return inner.list(path);
    }

    @Override
    public OpenFile open(String path, String guestPath, int flags) throws FsException {
        if (OpenFlags.has(flags, OpenFlags.WRITE) || OpenFlags.has(flags, OpenFlags.APPEND) || OpenFlags.has(flags, OpenFlags.CREATE)
            || OpenFlags.has(flags, OpenFlags.TRUNCATE)) {
            throw new FsException(Errno.EROFS);
        }
        return inner.open(path, guestPath, flags);
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public long freeSpace() {
        return 0;
    }

    @Override
    public long capacity() {
        return 0;
    }

    @Override
    public String describe() {
        return description;
    }
}
