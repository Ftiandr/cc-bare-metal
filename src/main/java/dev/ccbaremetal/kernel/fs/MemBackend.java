package dev.ccbaremetal.kernel.fs;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.io.ChannelFile;
import dev.ccbaremetal.kernel.io.OpenFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** A small in-memory read/write file system with a size limit, used for /tmp. Contents are lost on reboot. */
public final class MemBackend implements FsBackend {
    private final long capacity;
    private final Node root = Node.dir();
    private long used;

    public MemBackend(long capacity) {
        this(capacity, 0755);
    }

    /** {@code rootMode}: 01777 for /tmp (everyone creates files, only owners remove them). */
    public MemBackend(long capacity, int rootMode) {
        this.capacity = capacity;
        root.mode = rootMode;
    }

    private static final class Node {
        TreeMap<String, Node> children;
        byte[] data;
        int length;
        long mtime = System.currentTimeMillis() * 1_000_000L;
        int mode;
        int uid;
        int gid;

        static Node dir() {
            var n = new Node();
            n.children = new TreeMap<>();
            n.mode = 0755;
            return n;
        }

        static Node file() {
            var n = new Node();
            n.data = new byte[0];
            n.mode = 0644;
            return n;
        }

        boolean isDir() {
            return children != null;
        }
    }

    private Node find(String path) throws FsException {
        var node = root;
        if (path.isEmpty()) return node;
        for (var part : path.split("/")) {
            if (!node.isDir()) throw new FsException(Errno.ENOTDIR);
            node = node.children.get(part);
            if (node == null) throw new FsException(Errno.ENOENT);
        }
        return node;
    }

    private Node parentOf(String path) throws FsException {
        var idx = path.lastIndexOf('/');
        var parent = find(idx < 0 ? "" : path.substring(0, idx));
        if (!parent.isDir()) throw new FsException(Errno.ENOTDIR);
        return parent;
    }

    private static String nameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Override
    public synchronized Stat stat(String path) throws FsException {
        var node = find(path);
        return (node.isDir() ? Stat.dir(node.mtime) : Stat.file(node.length, node.mtime)).withOwner(node.mode, node.uid, node.gid);
    }

    @Override
    public synchronized void setAttributes(String path, int mode, int uid, int gid) throws FsException {
        var node = find(path);
        if (mode != -1) node.mode = mode & 07777;
        if (uid != -1) node.uid = uid;
        if (gid != -1) node.gid = gid;
    }

    @Override
    public synchronized List<String> list(String path) throws FsException {
        var node = find(path);
        if (!node.isDir()) throw new FsException(Errno.ENOTDIR);
        return new ArrayList<>(node.children.keySet());
    }

    @Override
    public synchronized OpenFile open(String path, String guestPath, int flags) throws FsException {
        if (path.isEmpty()) throw new FsException(Errno.EISDIR);
        var parent = parentOf(path);
        var name = nameOf(path);
        var node = parent.children.get(name);
        if (node == null) {
            if (!OpenFlags.has(flags, OpenFlags.CREATE)) throw new FsException(Errno.ENOENT);
            node = Node.file();
            parent.children.put(name, node);
        } else if (OpenFlags.has(flags, OpenFlags.CREATE) && OpenFlags.has(flags, OpenFlags.EXCLUSIVE)) {
            throw new FsException(Errno.EEXIST);
        }
        if (node.isDir()) throw new FsException(Errno.EISDIR);
        var write = OpenFlags.has(flags, OpenFlags.WRITE) || OpenFlags.has(flags, OpenFlags.APPEND);
        if (write && OpenFlags.has(flags, OpenFlags.TRUNCATE)) {
            used -= node.length;
            node.length = 0;
            node.data = new byte[0];
        }
        var file = node;
        return new ChannelFile(new NodeChannel(file), guestPath, OpenFlags.has(flags, OpenFlags.READ) || !write, write,
            OpenFlags.has(flags, OpenFlags.APPEND), () -> {
                synchronized (MemBackend.this) {
                    return Stat.file(file.length, file.mtime).withOwner(file.mode, file.uid, file.gid);
                }
            });
    }

    @Override
    public synchronized void mkdir(String path) throws FsException {
        if (path.isEmpty()) throw new FsException(Errno.EEXIST);
        var parent = parentOf(path);
        if (parent.children.containsKey(nameOf(path))) throw new FsException(Errno.EEXIST);
        parent.children.put(nameOf(path), Node.dir());
    }

    @Override
    public synchronized void unlink(String path) throws FsException {
        var node = find(path);
        if (node.isDir()) throw new FsException(Errno.EISDIR);
        parentOf(path).children.remove(nameOf(path));
        used -= node.length;
    }

    @Override
    public synchronized void rmdir(String path) throws FsException {
        if (path.isEmpty()) throw new FsException(Errno.EBUSY);
        var node = find(path);
        if (!node.isDir()) throw new FsException(Errno.ENOTDIR);
        if (!node.children.isEmpty()) throw new FsException(Errno.ENOTEMPTY);
        parentOf(path).children.remove(nameOf(path));
    }

    @Override
    public synchronized void rename(String from, String to) throws FsException {
        if (from.isEmpty() || to.isEmpty()) throw new FsException(Errno.EBUSY);
        if (VPath.isWithin("/" + to, "/" + from)) throw new FsException(Errno.EINVAL);
        var node = find(from);
        var destParent = parentOf(to);
        var existing = destParent.children.get(nameOf(to));
        if (existing != null) {
            if (existing.isDir() && !node.isDir()) throw new FsException(Errno.EISDIR);
            if (!existing.isDir() && node.isDir()) throw new FsException(Errno.ENOTDIR);
            if (existing.isDir() && !existing.children.isEmpty()) throw new FsException(Errno.ENOTEMPTY);
            if (!existing.isDir()) used -= existing.length;
        }
        parentOf(from).children.remove(nameOf(from));
        destParent.children.put(nameOf(to), node);
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public synchronized long freeSpace() {
        return Math.max(0, capacity - used);
    }

    @Override
    public long capacity() {
        return capacity;
    }

    @Override
    public String describe() {
        return "tmpfs";
    }

    private final class NodeChannel implements SeekableByteChannel {
        private final Node node;
        private long position;
        private boolean open = true;

        NodeChannel(Node node) {
            this.node = node;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            synchronized (MemBackend.this) {
                if (!open) throw new ClosedChannelException();
                if (position >= node.length) return -1;
                var n = (int) Math.min(dst.remaining(), node.length - position);
                dst.put(node.data, (int) position, n);
                position += n;
                return n;
            }
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            synchronized (MemBackend.this) {
                if (!open) throw new ClosedChannelException();
                var n = src.remaining();
                var end = position + n;
                if (end > Integer.MAX_VALUE - 8) throw new ChannelFile.FsIOException(Errno.EFBIG);
                if (end > node.length) {
                    var growth = end - node.length;
                    if (growth > capacity - used) throw new ChannelFile.FsIOException(Errno.ENOSPC);
                    if (end > node.data.length) {
                        node.data = java.util.Arrays.copyOf(node.data, (int) Math.max(end, Math.min(node.data.length * 2L, Integer.MAX_VALUE - 8)));
                    }
                    if (position > node.length) java.util.Arrays.fill(node.data, node.length, (int) position, (byte) 0);
                    used += growth;
                    node.length = (int) end;
                }
                src.get(node.data, (int) position, n);
                position = end;
                node.mtime = System.currentTimeMillis() * 1_000_000L;
                return n;
            }
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public SeekableByteChannel position(long newPosition) {
            position = newPosition;
            return this;
        }

        @Override
        public long size() {
            synchronized (MemBackend.this) {
                return node.length;
            }
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            synchronized (MemBackend.this) {
                if (size < node.length) {
                    used -= node.length - size;
                    node.length = (int) size;
                }
                return this;
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
