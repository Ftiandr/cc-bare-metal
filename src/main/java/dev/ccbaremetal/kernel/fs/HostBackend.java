package dev.ccbaremetal.kernel.fs;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.io.ChannelFile;
import dev.ccbaremetal.kernel.io.OpenFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * A directory on the real disk (inside the world save), exposed read/write with a space quota.
 *
 * <p>Sandboxing: guest paths are canonical before they get here, every component is re-validated by
 * {@link VPath#checkHostSafe}, the resolved path must stay below the root, and symbolic links are never followed.
 *
 * <p>Owners and permission bits: the host file system cannot hold Unix owners portably (Windows), so they live in a
 * small text file next to the root (outside what the guest can reach). Only files that differ from the defaults
 * (owner {@code uid:gid}, mode 0644 / 0755) have an entry, so ordinary work writes nothing there.
 */
public final class HostBackend implements FsBackend {
    private static final long BLOCK = 512;

    private final Path root;
    private final long capacity;
    private final AtomicLong used = new AtomicLong(-1);
    /** Where owners and modes are kept, or null: every file has the default attributes, none can be changed. */
    private final Path metaFile;
    private final int defaultUid;
    private final int defaultGid;
    private final int defaultFileMode;
    private final int defaultDirMode;
    /** Path -> {mode, uid, gid}, for files that differ from the defaults; loaded on first use. */
    private java.util.TreeMap<String, int[]> meta;
    private boolean metaExisted;

    /** Everything owned by root, files 0644 (no attribute store). */
    public HostBackend(Path root, long capacity) {
        this(root, capacity, null, 0, 0, 0644, 0755);
    }

    public HostBackend(Path root, long capacity, Path metaFile, int defaultUid, int defaultGid, int defaultFileMode, int defaultDirMode) {
        this.root = root.toAbsolutePath().normalize();
        this.capacity = capacity;
        this.metaFile = metaFile;
        this.defaultUid = defaultUid;
        this.defaultGid = defaultGid;
        this.defaultFileMode = defaultFileMode;
        this.defaultDirMode = defaultDirMode;
    }

    // ---- Owners and modes -------------------------------------------------------------------------------------------

    private java.util.TreeMap<String, int[]> meta() {
        if (meta != null) return meta;
        meta = new java.util.TreeMap<>();
        if (metaFile == null) return meta;
        try {
            if (Files.isRegularFile(metaFile)) {
                metaExisted = true;
                for (var line : Files.readAllLines(metaFile, java.nio.charset.StandardCharsets.UTF_8)) {
                    var f = line.split(" ", 4);
                    if (f.length < 4 || line.startsWith("#")) continue;
                    try {
                        meta.put(unescape(f[3]), new int[]{Integer.parseInt(f[0], 8), Integer.parseInt(f[1]), Integer.parseInt(f[2])});
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger(HostBackend.class).warn("Cannot read {}", metaFile, e);
        }
        return meta;
    }

    /** Whether the attribute store did not exist yet (a new computer, or one from before owners existed). */
    public synchronized boolean freshAttributes() {
        meta();
        return metaFile != null && !metaExisted;
    }

    private void saveMeta() throws FsException {
        if (metaFile == null) return;
        var out = new StringBuilder("# CC: Bare Metal file owners: octal mode, uid, gid, path\n");
        for (var e : meta.entrySet()) {
            var v = e.getValue();
            out.append(Integer.toOctalString(v[0])).append(' ').append(v[1]).append(' ').append(v[2]).append(' ').append(escape(e.getKey())).append('\n');
        }
        try {
            var tmp = metaFile.resolveSibling(metaFile.getFileName() + ".tmp");
            Files.writeString(tmp, out, java.nio.charset.StandardCharsets.UTF_8);
            Files.move(tmp, metaFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            metaExisted = true;
        } catch (IOException e) {
            throw new FsException(Errno.EIO, e.getMessage());
        }
    }

    private static String escape(String path) {
        return path.replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D");
    }

    private static String unescape(String path) {
        return path.replace("%0A", "\n").replace("%0D", "\r").replace("%25", "%");
    }

    private synchronized Stat withAttributes(String path, Stat st) {
        var v = meta().get(path);
        if (v != null) return st.withOwner(v[0], v[1], v[2]);
        return st.withOwner(st.isDirectory() ? defaultDirMode : defaultFileMode, defaultUid, defaultGid);
    }

    @Override
    public synchronized void setAttributes(String path, int mode, int uid, int gid) throws FsException {
        if (metaFile == null) throw new FsException(Errno.EPERM);
        var current = stat(path);
        var v = new int[]{mode == -1 ? current.mode() : mode & 07777, uid == -1 ? current.uid() : uid, gid == -1 ? current.gid() : gid};
        var def = current.isDirectory() ? defaultDirMode : defaultFileMode;
        var old = meta().get(path);
        if (v[0] == def && v[1] == defaultUid && v[2] == defaultGid) {
            if (old == null) return;
            meta.remove(path);
        } else {
            if (old != null && java.util.Arrays.equals(old, v)) return;
            meta.put(path, v);
        }
        saveMeta();
    }

    /** Forget the attributes of {@code path} and everything below it (deleted). */
    private synchronized void dropAttributes(String path) throws FsException {
        var changed = meta().remove(path) != null;
        var below = meta.subMap(path + "/", path + "0"); // '0' follows '/': everything with the prefix "path/"
        if (!below.isEmpty()) {
            below.clear();
            changed = true;
        }
        if (changed) saveMeta();
    }

    /** Move the attributes of {@code from} (and below) to {@code to}. */
    private synchronized void moveAttributes(String from, String to) throws FsException {
        var moved = new java.util.TreeMap<String, int[]>();
        var v = meta().remove(from);
        if (v != null) moved.put(to, v);
        var below = meta.subMap(from + "/", from + "0");
        for (var e : below.entrySet()) moved.put(to + e.getKey().substring(from.length()), e.getValue());
        var changed = v != null || !below.isEmpty();
        below.clear();
        if (meta.subMap(to + "/", to + "0").size() > 0 || meta.containsKey(to)) {
            meta.remove(to);
            meta.subMap(to + "/", to + "0").clear();
            changed = true;
        }
        meta.putAll(moved);
        if (changed) saveMeta();
    }

    private Path resolve(String rel) throws FsException {
        VPath.checkHostSafe(rel);
        var path = rel.isEmpty() ? root : root.resolve(rel).normalize();
        if (!path.startsWith(root)) throw new FsException(Errno.EACCES);
        // Refuse to traverse symlinks anywhere below the root.
        for (var p = path; p != null && !p.equals(root); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new FsException(Errno.EACCES);
        }
        return path;
    }

    private void ensureRoot() throws FsException {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new FsException(Errno.EIO, e.getMessage());
        }
    }

    @Override
    public Stat stat(String path) throws FsException {
        if (path.isEmpty()) ensureRoot();
        try {
            var attrs = Files.readAttributes(resolve(path), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            var mtime = attrs.lastModifiedTime().toMillis() * 1_000_000L;
            var ctime = attrs.creationTime().toMillis() * 1_000_000L;
            var type = attrs.isDirectory() ? FileType.DIRECTORY : attrs.isRegularFile() ? FileType.REGULAR_FILE : FileType.UNKNOWN;
            return withAttributes(path, new Stat(type, attrs.isDirectory() ? 0 : attrs.size(), mtime, ctime, 0));
        } catch (IOException e) {
            throw map(e);
        }
    }

    @Override
    public List<String> list(String path) throws FsException {
        if (path.isEmpty()) ensureRoot();
        var dir = resolve(path);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new FsException(Files.exists(dir, LinkOption.NOFOLLOW_LINKS) ? Errno.ENOTDIR : Errno.ENOENT);
        }
        try (Stream<Path> stream = Files.list(dir)) {
            var out = new ArrayList<String>();
            stream.forEach(p -> out.add(p.getFileName().toString()));
            out.sort(null);
            return out;
        } catch (IOException e) {
            throw map(e);
        }
    }

    @Override
    public OpenFile open(String path, String guestPath, int flags) throws FsException {
        var file = resolve(path);
        if (path.isEmpty() || Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) throw new FsException(Errno.EISDIR);

        var write = OpenFlags.has(flags, OpenFlags.WRITE) || OpenFlags.has(flags, OpenFlags.APPEND);
        var options = new HashSet<OpenOption>();
        options.add(LinkOption.NOFOLLOW_LINKS);
        if (OpenFlags.has(flags, OpenFlags.READ)) options.add(StandardOpenOption.READ);
        if (write) options.add(StandardOpenOption.WRITE);
        if (OpenFlags.has(flags, OpenFlags.CREATE)) {
            options.add(OpenFlags.has(flags, OpenFlags.EXCLUSIVE) ? StandardOpenOption.CREATE_NEW : StandardOpenOption.CREATE);
            if (!Files.isDirectory(file.getParent(), LinkOption.NOFOLLOW_LINKS)) throw new FsException(Errno.ENOENT);
        }
        if (OpenFlags.has(flags, OpenFlags.TRUNCATE) && write) options.add(StandardOpenOption.TRUNCATE_EXISTING);
        if (!options.contains(StandardOpenOption.READ) && !write) options.add(StandardOpenOption.READ);

        long oldSize = 0;
        try {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) oldSize = Files.size(file);
            else if (write && freeSpace() < BLOCK) throw new FsException(Errno.ENOSPC);
        } catch (IOException e) {
            throw map(e);
        }

        try {
            var channel = Files.newByteChannel(file, options);
            if (options.contains(StandardOpenOption.TRUNCATE_EXISTING)) addUsed(-oldSize);
            var wrapped = write ? new QuotaChannel(channel) : channel;
            return new ChannelFile(wrapped, guestPath, OpenFlags.has(flags, OpenFlags.READ), write,
                OpenFlags.has(flags, OpenFlags.APPEND), () -> stat(path));
        } catch (IOException e) {
            throw map(e);
        }
    }

    @Override
    public void mkdir(String path) throws FsException {
        var dir = resolve(path);
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) throw new FsException(Errno.EEXIST);
        if (freeSpace() < BLOCK) throw new FsException(Errno.ENOSPC);
        try {
            Files.createDirectory(dir);
            addUsed(BLOCK);
        } catch (IOException e) {
            throw map(e);
        }
    }

    @Override
    public void unlink(String path) throws FsException {
        var file = resolve(path);
        if (path.isEmpty() || Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) throw new FsException(Errno.EISDIR);
        try {
            var size = Files.size(file);
            Files.delete(file);
            addUsed(-size);
            dropAttributes(path);
        } catch (IOException e) {
            throw map(e);
        }
    }

    @Override
    public void rmdir(String path) throws FsException {
        if (path.isEmpty()) throw new FsException(Errno.EBUSY);
        var dir = resolve(path);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new FsException(Files.exists(dir, LinkOption.NOFOLLOW_LINKS) ? Errno.ENOTDIR : Errno.ENOENT);
        }
        try {
            Files.delete(dir);
            addUsed(-BLOCK);
            dropAttributes(path);
        } catch (IOException e) {
            throw map(e);
        }
    }

    @Override
    public void rename(String from, String to) throws FsException {
        if (from.isEmpty() || to.isEmpty()) throw new FsException(Errno.EBUSY);
        if (VPath.isWithin("/" + to, "/" + from)) throw new FsException(Errno.EINVAL);
        var source = resolve(from);
        var dest = resolve(to);
        try {
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new FsException(Errno.ENOENT);
            if (Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new FsException(Errno.EISDIR);
                try (var s = Files.list(dest)) {
                    if (s.findAny().isPresent()) throw new FsException(Errno.ENOTEMPTY);
                }
                Files.delete(dest);
                addUsed(-BLOCK);
            } else if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new FsException(Errno.ENOTDIR);
                addUsed(-Files.size(dest));
            }
            Files.move(source, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            moveAttributes(from, to);
        } catch (IOException e) {
            throw map(e);
        }
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public long freeSpace() {
        return Math.max(0, capacity - used());
    }

    @Override
    public long capacity() {
        return capacity;
    }

    @Override
    public String describe() {
        return "hostfs";
    }

    private long used() {
        var value = used.get();
        if (value >= 0) return value;
        var total = new AtomicLong();
        if (Files.isDirectory(root)) {
            try (var walk = Files.walk(root)) {
                walk.forEach(p -> {
                    try {
                        total.addAndGet(Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ? BLOCK : Files.size(p));
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
        }
        used.compareAndSet(-1, total.get());
        return used.get();
    }

    private void addUsed(long delta) {
        used();
        used.addAndGet(delta);
    }

    private static FsException map(IOException e) {
        if (e instanceof ChannelFile.FsIOException f) return f.unwrap();
        if (e instanceof NoSuchFileException) return new FsException(Errno.ENOENT);
        if (e instanceof FileAlreadyExistsException) return new FsException(Errno.EEXIST);
        if (e instanceof DirectoryNotEmptyException) return new FsException(Errno.ENOTEMPTY);
        if (e instanceof NotDirectoryException) return new FsException(Errno.ENOTDIR);
        if (e instanceof AccessDeniedException) return new FsException(Errno.EACCES);
        return new FsException(Errno.EIO, e.getMessage() == null ? "I/O error" : e.getMessage());
    }

    /** Wraps a writable channel and enforces the quota as the file grows. */
    private final class QuotaChannel implements SeekableByteChannel {
        private final SeekableByteChannel delegate;

        QuotaChannel(SeekableByteChannel delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            var size = delegate.size();
            var growth = delegate.position() + src.remaining() - size;
            if (growth > 0) {
                if (growth > freeSpace()) throw new ChannelFile.FsIOException(Errno.ENOSPC);
                addUsed(growth);
            }
            return delegate.write(src);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            var old = delegate.size();
            delegate.truncate(size);
            if (size < old) addUsed(size - old);
            return this;
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
