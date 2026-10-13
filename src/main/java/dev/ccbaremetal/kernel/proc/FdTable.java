package dev.ccbaremetal.kernel.proc;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.io.OpenFile;

import java.util.List;

/** A process's file descriptor table. Descriptors share {@link OpenFile} descriptions via reference counting. */
public final class FdTable {
    public static final int MAX_FDS = 256;

    public static final int FDFLAG_APPEND = 1;
    public static final int FDFLAG_NONBLOCK = 4;

    public static final class Entry {
        public final OpenFile file;
        public int flags;
        public long rightsBase;
        public long rightsInheriting;
        /** For preopened directories: the name reported by fd_prestat_dir_name. */
        public final String preopen;
        /** Cached directory listing for fd_readdir. */
        public List<String> listing;

        Entry(OpenFile file, int flags, long rightsBase, long rightsInheriting, String preopen) {
            this.file = file;
            this.flags = flags;
            this.rightsBase = rightsBase;
            this.rightsInheriting = rightsInheriting;
            this.preopen = preopen;
        }
    }

    private final Entry[] entries = new Entry[MAX_FDS];

    public static final long RIGHTS_ALL = 0x1FFFFFFFL;
    /** All rights except FD_SEEK (1<<2) and FD_TELL (1<<5): what libc's isatty() checks for. */
    public static final long RIGHTS_STREAM = RIGHTS_ALL & ~((1L << 2) | (1L << 5));

    public synchronized int add(OpenFile file, int flags) throws FsException {
        return addAt(lowestFree(0), file, flags, null);
    }

    public synchronized int addPreopen(OpenFile file, String name) throws FsException {
        return addAt(lowestFree(0), file, 0, name);
    }

    private int lowestFree(int from) throws FsException {
        for (var i = from; i < MAX_FDS; i++) if (entries[i] == null) return i;
        throw new FsException(Errno.EMFILE);
    }

    /** Install {@code file} at {@code fd}, taking ownership of one reference. */
    public synchronized int addAt(int fd, OpenFile file, int flags, String preopen) {
        var rights = file.isSeekable() || file.type() == dev.ccbaremetal.kernel.fs.FileType.DIRECTORY ? RIGHTS_ALL : RIGHTS_STREAM;
        var old = entries[fd];
        entries[fd] = new Entry(file, flags, rights, RIGHTS_ALL, preopen);
        if (old != null) old.file.release();
        return fd;
    }

    public synchronized Entry get(int fd) throws FsException {
        if (fd < 0 || fd >= MAX_FDS || entries[fd] == null) throw new FsException(Errno.EBADF);
        return entries[fd];
    }

    public synchronized OpenFile file(int fd) throws FsException {
        return get(fd).file;
    }

    public synchronized void close(int fd) throws FsException {
        var entry = get(fd);
        entries[fd] = null;
        entry.file.release();
    }

    /** fd_renumber: move {@code from} onto {@code to}, closing whatever was at {@code to}. */
    public synchronized void renumber(int from, int to) throws FsException {
        var entry = get(from);
        if (to < 0 || to >= MAX_FDS) throw new FsException(Errno.EBADF);
        if (from == to) return;
        var old = entries[to];
        entries[to] = entry;
        entries[from] = null;
        if (old != null) old.file.release();
    }

    public synchronized int dup(int fd) throws FsException {
        var entry = get(fd);
        var target = lowestFree(0);
        entries[target] = new Entry(entry.file.retain(), entry.flags, entry.rightsBase, entry.rightsInheriting, null);
        return target;
    }

    public synchronized void dup2(int fd, int target) throws FsException {
        var entry = get(fd);
        if (target < 0 || target >= MAX_FDS) throw new FsException(Errno.EBADF);
        if (fd == target) return;
        var old = entries[target];
        entries[target] = new Entry(entry.file.retain(), entry.flags, entry.rightsBase, entry.rightsInheriting, null);
        if (old != null) old.file.release();
    }

    public synchronized void closeAll() {
        for (var i = 0; i < MAX_FDS; i++) {
            if (entries[i] != null) {
                entries[i].file.release();
                entries[i] = null;
            }
        }
    }

    public synchronized int count() {
        var n = 0;
        for (var e : entries) if (e != null) n++;
        return n;
    }
}
