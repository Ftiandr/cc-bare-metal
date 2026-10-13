package dev.ccbaremetal.kernel.fs;

import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.api.filesystem.WritableMount;
import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.io.ChannelFile;
import dev.ccbaremetal.kernel.io.OpenFile;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapts a CC: Tweaked {@link Mount} (ROM, disk drives, mounts provided by other mods' peripherals) to our VFS.
 */
public final class CcMountBackend implements FsBackend {
    private final Mount mount;
    private final String label;
    /** Files open on this mount, revoked when it is unmounted (weak: closed files just disappear). */
    private final Set<OpenFile> open = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    public CcMountBackend(Mount mount, String label) {
        this.mount = mount;
        this.label = label;
    }

    private WritableMount writable() throws FsException {
        if (mount instanceof WritableMount w) return w;
        throw new FsException(Errno.EROFS);
    }

    @Override
    public Stat stat(String path) throws FsException {
        try {
            if (!mount.exists(path)) throw new FsException(Errno.ENOENT);
            var attrs = mount.getAttributes(path);
            var mtime = attrs.lastModifiedTime().toMillis() * 1_000_000L;
            var ctime = attrs.creationTime().toMillis() * 1_000_000L;
            var st = new Stat(attrs.isDirectory() ? FileType.DIRECTORY : FileType.REGULAR_FILE, attrs.isDirectory() ? 0 : attrs.size(), mtime, ctime, 0);
            // no owners on CC's mounts: the ROM is root's and read-only, disks are open to everyone (like vfat, umask 0)
            if (mount instanceof WritableMount) return st.withOwner(st.isDirectory() ? 0777 : 0666, 0, 0);
            return st.withOwner(st.isDirectory() ? 0555 : 0444, 0, 0);
        } catch (IOException e) {
            throw map(e);
        }
    }

    @Override
    public List<String> list(String path) throws FsException {
        try {
            if (!mount.exists(path)) throw new FsException(Errno.ENOENT);
            if (!mount.isDirectory(path)) throw new FsException(Errno.ENOTDIR);
            var out = new ArrayList<String>();
            mount.list(path, out);
            out.sort(null);
            return out;
        } catch (IOException e) {
            throw map(e);
        }
    }

    @Override
    public OpenFile open(String path, String guestPath, int flags) throws FsException {
        try {
            if (mount.exists(path) && mount.isDirectory(path)) throw new FsException(Errno.EISDIR);
            var write = OpenFlags.has(flags, OpenFlags.WRITE) || OpenFlags.has(flags, OpenFlags.APPEND);
            if (!write) {
                if (!mount.exists(path)) throw new FsException(Errno.ENOENT);
                return track(new ChannelFile(mount.openForRead(path), guestPath, true, false, false, () -> stat(path)));
            }
            var w = writable();
            var exists = w.exists(path);
            if (!exists && !OpenFlags.has(flags, OpenFlags.CREATE)) throw new FsException(Errno.ENOENT);
            if (exists && OpenFlags.has(flags, OpenFlags.CREATE) && OpenFlags.has(flags, OpenFlags.EXCLUSIVE)) throw new FsException(Errno.EEXIST);
            Set<OpenOption> options = new HashSet<>();
            options.add(StandardOpenOption.WRITE);
            options.add(StandardOpenOption.CREATE);
            if (OpenFlags.has(flags, OpenFlags.READ)) options.add(StandardOpenOption.READ);
            if (OpenFlags.has(flags, OpenFlags.TRUNCATE)) options.add(StandardOpenOption.TRUNCATE_EXISTING);
            if (OpenFlags.has(flags, OpenFlags.APPEND)) options.add(StandardOpenOption.APPEND);
            return track(new ChannelFile(w.openFile(path, options), guestPath, OpenFlags.has(flags, OpenFlags.READ), true,
                OpenFlags.has(flags, OpenFlags.APPEND), () -> stat(path)));
        } catch (IOException e) {
            throw map(e);
        }
    }

    private OpenFile track(OpenFile file) {
        open.add(file);
        return file;
    }

    /** The disk was ejected (or its drive removed): open files fail with EIO, as with a pulled USB stick. */
    @Override
    public void unmounted() {
        List<OpenFile> files;
        synchronized (open) {
            files = List.copyOf(open);
            open.clear();
        }
        for (var f : files) f.revoke(Errno.EIO);
    }

    @Override
    public void mkdir(String path) throws FsException {
        try {
            if (mount.exists(path)) throw new FsException(Errno.EEXIST);
            writable().makeDirectory(path);
        } catch (IOException e) {
            throw map(e);
        }
    }

    @Override
    public void unlink(String path) throws FsException {
        try {
            if (!mount.exists(path)) throw new FsException(Errno.ENOENT);
            if (mount.isDirectory(path)) throw new FsException(Errno.EISDIR);
            writable().delete(path);
        } catch (IOException e) {
            throw map(e);
        }
    }

    @Override
    public void rmdir(String path) throws FsException {
        try {
            if (path.isEmpty()) throw new FsException(Errno.EBUSY);
            if (!mount.exists(path)) throw new FsException(Errno.ENOENT);
            if (!mount.isDirectory(path)) throw new FsException(Errno.ENOTDIR);
            var children = new ArrayList<String>();
            mount.list(path, children);
            if (!children.isEmpty()) throw new FsException(Errno.ENOTEMPTY);
            writable().delete(path);
        } catch (IOException e) {
            throw map(e);
        }
    }

    @Override
    public void rename(String from, String to) throws FsException {
        try {
            var w = writable();
            if (w.exists(to)) {
                if (w.isDirectory(to)) throw new FsException(Errno.EEXIST);
                w.delete(to);
            }
            w.rename(from, to);
        } catch (IOException e) {
            throw map(e);
        }
    }

    @Override
    public boolean isReadOnly() {
        if (!(mount instanceof WritableMount w)) return true;
        try {
            return w.isReadOnly("");
        } catch (IOException e) {
            return true;
        }
    }

    @Override
    public long freeSpace() {
        try {
            return mount instanceof WritableMount w ? w.getRemainingSpace() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public long capacity() {
        return mount instanceof WritableMount w ? w.getCapacity() : 0;
    }

    @Override
    public String describe() {
        return label;
    }

    private static FsException map(IOException e) {
        if (e instanceof ChannelFile.FsIOException f) return f.unwrap();
        if (e instanceof FileNotFoundException) return new FsException(Errno.ENOENT, e.getMessage());
        var msg = e.getMessage() == null ? "I/O error" : e.getMessage();
        if (msg.contains("Out of space")) return new FsException(Errno.ENOSPC);
        if (msg.contains("Access denied")) return new FsException(Errno.EACCES);
        if (msg.contains("No such file")) return new FsException(Errno.ENOENT);
        return new FsException(Errno.EIO, msg);
    }
}
