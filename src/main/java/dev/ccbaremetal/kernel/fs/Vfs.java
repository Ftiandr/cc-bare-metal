package dev.ccbaremetal.kernel.fs;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.io.DirectoryFile;
import dev.ccbaremetal.kernel.io.OpenFile;
import dev.ccbaremetal.kernel.proc.Cred;
import dev.ccbaremetal.kernel.proc.Process;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The virtual file system of one machine: a table of mounts, resolved by longest prefix. Every guest-visible path
 * goes through here, whether it comes from WASI, our own syscalls or the Lua fs API, and so do the permission
 * checks: the credentials are those of the calling process ({@link Cred#current()}), the kernel itself is root.
 */
public final class Vfs {
    public record Mount(String path, FsBackend backend) {
    }

    public record Resolved(Mount mount, String rel) {
        public FsBackend backend() {
            return mount.backend;
        }
    }

    private final List<Mount> mounts = new CopyOnWriteArrayList<>();

    public synchronized void mount(String path, FsBackend backend) throws FsException {
        var canonical = VPath.normalize(path);
        for (var m : mounts) if (m.path.equals(canonical)) throw new FsException(Errno.EBUSY);
        var list = new ArrayList<>(mounts);
        list.add(new Mount(canonical, backend));
        list.sort(Comparator.comparingInt((Mount m) -> m.path.length()).reversed());
        mounts.clear();
        mounts.addAll(list);
    }

    public synchronized boolean unmount(String path) {
        try {
            var canonical = VPath.normalize(path);
            for (var m : mounts) {
                if (!m.path.equals(canonical)) continue;
                mounts.remove(m);
                m.backend.unmounted();
                return true;
            }
            return false;
        } catch (FsException e) {
            return false;
        }
    }

    public List<Mount> mounts() {
        return List.copyOf(mounts);
    }

    public Resolved resolve(String abs) throws FsException {
        for (var m : mounts) {
            if (VPath.isWithin(abs, m.path)) return new Resolved(m, VPath.relativeTo(abs, m.path));
        }
        throw new FsException(Errno.ENOENT);
    }

    public boolean isMountPoint(String abs) {
        for (var m : mounts) if (m.path.equals(abs)) return true;
        return false;
    }

    /** Whether some mount lives strictly below {@code abs}, making it an implicit directory. */
    private boolean hasMountBelow(String abs) {
        for (var m : mounts) if (!m.path.equals(abs) && VPath.isWithin(m.path, abs)) return true;
        return false;
    }

    /** Stat without permission checks (the kernel's own bookkeeping). */
    public Stat rawStat(String abs) throws FsException {
        var r = resolve(abs);
        try {
            return r.backend().stat(r.rel());
        } catch (FsException e) {
            if (e.errno() == Errno.ENOENT && hasMountBelow(abs)) return Stat.dir(0);
            throw e;
        }
    }

    public Stat stat(String abs) throws FsException {
        searchParents(abs, Cred.current());
        return rawStat(abs);
    }

    /** Every directory on the way to {@code abs} must be searchable (x), as on Unix. */
    private void searchParents(String abs, Cred c) throws FsException {
        if (c.isRoot() || abs.equals("/")) return;
        var dir = "/";
        var st = rawStat(dir);
        Access.check(st, c, Access.X);
        var parts = abs.substring(1).split("/");
        for (var i = 0; i < parts.length - 1; i++) {
            dir = VPath.join(dir, parts[i]);
            st = rawStat(dir);
            if (!st.isDirectory()) throw new FsException(Errno.ENOTDIR);
            Access.check(st, c, Access.X);
        }
    }

    /** The parent directory of {@code abs}, checked for {@code want} (creating or removing entries: W and X). */
    private Stat parentFor(String abs, Cred c, int want) throws FsException {
        var parent = rawStat(VPath.parent(abs));
        if (!parent.isDirectory()) throw new FsException(Errno.ENOTDIR);
        Access.check(parent, c, want);
        return parent;
    }

    /** Owner and mode of a file or directory just created: the caller, its umask, setgid directories. */
    private void initOwner(String abs, boolean dir, Cred c) throws FsException {
        var parent = rawStat(VPath.parent(abs));
        var p = Process.CURRENT.get();
        var umask = p != null ? p.umask : 022;
        var mode = (dir ? 0777 : 0666) & ~umask;
        var gid = c.egid();
        if ((parent.mode() & Stat.S_ISGID) != 0) {
            gid = parent.gid();
            if (dir) mode |= Stat.S_ISGID;
        }
        var r = resolve(abs);
        try {
            r.backend().setAttributes(r.rel(), mode, c.euid(), gid);
        } catch (FsException e) {
            if (e.errno() != Errno.EPERM && e.errno() != Errno.EROFS) throw e; // a file system without owners
        }
    }

    /** chmod: the owner or root. Others than root lose setgid on files of groups they are not in. */
    public void chmod(String abs, int mode) throws FsException {
        var c = Cred.current();
        var st = stat(abs);
        if (!c.isRoot() && st.uid() != c.euid()) throw new FsException(Errno.EPERM);
        if (!c.isRoot() && !st.isDirectory() && !c.inGroup(st.gid())) mode &= ~Stat.S_ISGID;
        var r = resolve(abs);
        r.backend().setAttributes(r.rel(), mode & 07777, -1, -1);
    }

    /** chown: only root gives files away; the owner may change the group to one of their own groups. */
    public void chown(String abs, int uid, int gid) throws FsException {
        var c = Cred.current();
        var st = stat(abs);
        if (!c.isRoot()) {
            if (uid != -1 && uid != st.uid()) throw new FsException(Errno.EPERM);
            if (gid != -1 && (st.uid() != c.euid() || !c.inGroup(gid))) throw new FsException(Errno.EPERM);
        }
        var mode = st.mode();
        // as on Linux, changing the owner clears setuid/setgid of files
        if (!st.isDirectory() && (uid != -1 || gid != -1)) mode &= ~(Stat.S_ISUID | Stat.S_ISGID);
        var r = resolve(abs);
        r.backend().setAttributes(r.rel(), mode, uid, gid);
    }

    public boolean exists(String abs) {
        try {
            stat(abs);
            return true;
        } catch (FsException e) {
            return false;
        }
    }

    public boolean isDirectory(String abs) {
        try {
            return stat(abs).isDirectory();
        } catch (FsException e) {
            return false;
        }
    }

    public List<String> list(String abs) throws FsException {
        var c = Cred.current();
        if (!c.isRoot()) {
            var st = stat(abs);
            Access.check(st, c, Access.R);
        }
        var names = new TreeSet<String>();
        var r = resolve(abs);
        var listed = false;
        try {
            names.addAll(r.backend().list(r.rel()));
            listed = true;
        } catch (FsException e) {
            if (e.errno() != Errno.ENOENT || !hasMountBelow(abs)) throw e;
        }
        for (var m : mounts) {
            if (!m.path.equals(abs) && VPath.isWithin(m.path, abs)) {
                var rest = VPath.relativeTo(m.path, abs);
                var slash = rest.indexOf('/');
                names.add(slash < 0 ? rest : rest.substring(0, slash));
                listed = true;
            }
        }
        if (!listed) throw new FsException(Errno.ENOENT);
        return new ArrayList<>(names);
    }

    public OpenFile open(String abs, int flags) throws FsException {
        var c = Cred.current();
        var write = OpenFlags.has(flags, OpenFlags.WRITE) || OpenFlags.has(flags, OpenFlags.APPEND) || OpenFlags.has(flags, OpenFlags.TRUNCATE);
        searchParents(abs, c);
        Stat stat = null;
        try {
            stat = rawStat(abs);
        } catch (FsException e) {
            if (e.errno() != Errno.ENOENT) throw e;
        }
        if (stat != null && stat.isDirectory()) {
            if (write) throw new FsException(Errno.EISDIR);
            return new DirectoryFile(abs, stat);
        }
        if (OpenFlags.has(flags, OpenFlags.DIRECTORY)) throw new FsException(stat == null ? Errno.ENOENT : Errno.ENOTDIR);
        if (stat == null && !OpenFlags.has(flags, OpenFlags.CREATE)) throw new FsException(Errno.ENOENT);
        if (stat != null && OpenFlags.has(flags, OpenFlags.CREATE) && OpenFlags.has(flags, OpenFlags.EXCLUSIVE)) {
            throw new FsException(Errno.EEXIST);
        }
        if (stat == null && !isDirectory(VPath.parent(abs))) throw new FsException(Errno.ENOENT);
        if (stat != null) {
            var want = (OpenFlags.has(flags, OpenFlags.READ) || !write ? Access.R : 0) | (write ? Access.W : 0);
            Access.check(stat, c, want);
        } else {
            parentFor(abs, c, Access.W | Access.X);
        }
        var r = resolve(abs);
        var file = r.backend().open(r.rel(), abs, flags);
        if (stat == null) initOwner(abs, false, c);
        return file;
    }

    public void mkdir(String abs) throws FsException {
        var c = Cred.current();
        if (exists(abs)) throw new FsException(Errno.EEXIST);
        if (!isDirectory(VPath.parent(abs))) throw new FsException(Errno.ENOENT);
        parentFor(abs, c, Access.W | Access.X);
        var r = resolve(abs);
        r.backend().mkdir(r.rel());
        initOwner(abs, true, c);
    }

    /** mkdir -p */
    public void mkdirs(String abs) throws FsException {
        if (isDirectory(abs)) return;
        if (!abs.equals("/")) mkdirs(VPath.parent(abs));
        if (!isDirectory(abs)) mkdir(abs);
    }

    public void unlink(String abs) throws FsException {
        if (isMountPoint(abs)) throw new FsException(Errno.EBUSY);
        checkRemove(abs, Cred.current());
        var r = resolve(abs);
        r.backend().unlink(r.rel());
    }

    public void rmdir(String abs) throws FsException {
        if (isMountPoint(abs) || hasMountBelow(abs)) throw new FsException(Errno.EBUSY);
        checkRemove(abs, Cred.current());
        var r = resolve(abs);
        r.backend().rmdir(r.rel());
    }

    public void rename(String from, String to) throws FsException {
        if (isMountPoint(from) || isMountPoint(to) || hasMountBelow(from)) throw new FsException(Errno.EBUSY);
        var a = resolve(from);
        var b = resolve(to);
        if (a.mount() != b.mount()) throw new FsException(Errno.EXDEV);
        if (!isDirectory(VPath.parent(to))) throw new FsException(Errno.ENOENT);
        var c = Cred.current();
        var source = checkRemove(from, c);
        searchParents(to, c);
        var destParent = parentFor(to, c, Access.W | Access.X);
        try {
            Access.checkSticky(destParent, rawStat(to), c);
        } catch (FsException e) {
            if (e.errno() != Errno.ENOENT) throw e;
        }
        // a directory moved elsewhere gets a new "..": that needs write permission on it
        if (source.isDirectory() && !VPath.parent(from).equals(VPath.parent(to))) Access.check(source, c, Access.W);
        a.backend().rename(a.rel(), b.rel());
    }

    /** Removing {@code abs}: W and X on its directory, and the sticky bit rule; returns the entry's stat. */
    private Stat checkRemove(String abs, Cred c) throws FsException {
        searchParents(abs, c);
        var entry = rawStat(abs);
        var parent = parentFor(abs, c, Access.W | Access.X);
        Access.checkSticky(parent, entry, c);
        return entry;
    }

    /** Recursively delete a file or directory (used by the Lua fs.delete). */
    public void deleteRecursive(String abs) throws FsException {
        var stat = stat(abs);
        if (stat.isDirectory()) {
            for (var child : list(abs)) deleteRecursive(VPath.join(abs, child));
            rmdir(abs);
        } else {
            unlink(abs);
        }
    }

    /** Read a whole file, with a size limit. */
    public byte[] readAll(String abs, int limit) throws FsException {
        var file = open(abs, OpenFlags.READ);
        try {
            var out = new java.io.ByteArrayOutputStream();
            var buffer = java.nio.ByteBuffer.allocate(16384);
            while (true) {
                buffer.clear();
                int n;
                try {
                    n = file.read(buffer, false);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new FsException(Errno.EINTR);
                }
                if (n <= 0) break;
                out.write(buffer.array(), 0, n);
                if (out.size() > limit) throw new FsException(Errno.EFBIG);
            }
            return out.toByteArray();
        } finally {
            file.release();
        }
    }

    public void writeAll(String abs, byte[] data) throws FsException {
        var file = open(abs, OpenFlags.WRITE | OpenFlags.CREATE | OpenFlags.TRUNCATE);
        try {
            file.write(java.nio.ByteBuffer.wrap(data), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FsException(Errno.EINTR);
        } finally {
            file.release();
        }
    }
}
