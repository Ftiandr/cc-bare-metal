package dev.ccbaremetal.kernel.wasi;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Memory;
import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.fs.FileType;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.OpenFlags;
import dev.ccbaremetal.kernel.fs.Stat;
import dev.ccbaremetal.kernel.fs.VPath;
import dev.ccbaremetal.kernel.io.OpenFile;
import dev.ccbaremetal.kernel.proc.FdTable;
import dev.ccbaremetal.kernel.proc.Process;
import dev.ccbaremetal.kernel.proc.ProcessExit;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * WASI preview1 ("wasi_snapshot_preview1"), implemented directly on our VFS and process model. Nothing here touches
 * the host machine: all paths resolve inside the guest VFS.
 */
public final class Wasi {
    public static final String MODULE = "wasi_snapshot_preview1";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_IO = 1 << 20;

    private Wasi() {
    }

    public static HostFunction[] functions(Process p) {
        var m = new Host.Module(MODULE, p);

        // ---- Arguments and environment ----
        m.fn("args_get", "ii", (i, mem, a) -> writeStrings(mem, p.argv, a[0], a[1]));
        m.fn("args_sizes_get", "ii", (i, mem, a) -> writeSizes(mem, p.argv, a[0], a[1]));
        m.fn("environ_get", "ii", (i, mem, a) -> writeStrings(mem, envList(p), a[0], a[1]));
        m.fn("environ_sizes_get", "ii", (i, mem, a) -> writeSizes(mem, envList(p), a[0], a[1]));

        // ---- Clocks, random, scheduling ----
        m.fn("clock_res_get", "ii", (i, mem, a) -> {
            mem.writeLong((int) a[1], a[0] == 0 ? 1_000_000L : 1_000L);
            return 0;
        });
        m.fn("clock_time_get", "iIi", (i, mem, a) -> {
            mem.writeLong((int) a[2], clock((int) a[0]));
            return 0;
        });
        m.fn("random_get", "ii", (i, mem, a) -> {
            var bytes = new byte[(int) Math.min(a[1], MAX_IO)];
            RANDOM.nextBytes(bytes);
            mem.write((int) a[0], bytes);
            return 0;
        });
        m.fn("sched_yield", ":i", (i, mem, a) -> {
            Thread.yield();
            return 0;
        });
        m.fn("proc_exit", "i:", (i, mem, a) -> {
            throw new ProcessExit((int) a[0] & 0xFF); // as on Linux: the exit status is its low 8 bits
        });
        m.fn("proc_raise", "i", (i, mem, a) -> {
            p.signal((int) a[0]);
            p.checkKilled();
            return 0;
        });

        // ---- File descriptors ----
        m.fn("fd_close", "i", (i, mem, a) -> {
            p.fds.close((int) a[0]);
            return 0;
        });
        m.fn("fd_read", "iiii", (i, mem, a) -> rw(p, mem, (int) a[0], a[1], a[2], a[3], false, -1));
        m.fn("fd_write", "iiii", (i, mem, a) -> rw(p, mem, (int) a[0], a[1], a[2], a[3], true, -1));
        m.fn("fd_pread", "iiiIi", (i, mem, a) -> rw(p, mem, (int) a[0], a[1], a[2], a[4], false, a[3]));
        m.fn("fd_pwrite", "iiiIi", (i, mem, a) -> rw(p, mem, (int) a[0], a[1], a[2], a[4], true, a[3]));
        m.fn("fd_seek", "iIii", (i, mem, a) -> {
            var pos = p.fds.file((int) a[0]).seek(a[1], (int) a[2]);
            mem.writeLong((int) a[3], pos);
            return 0;
        });
        m.fn("fd_tell", "ii", (i, mem, a) -> {
            var pos = p.fds.file((int) a[0]).seek(0, OpenFile.SEEK_CUR);
            mem.writeLong((int) a[1], pos);
            return 0;
        });
        m.fn("fd_sync", "i", (i, mem, a) -> {
            p.fds.file((int) a[0]).sync();
            return 0;
        });
        m.fn("fd_datasync", "i", (i, mem, a) -> {
            p.fds.file((int) a[0]).sync();
            return 0;
        });
        m.fn("fd_advise", "iIIi", (i, mem, a) -> {
            p.fds.get((int) a[0]);
            return 0;
        });
        m.fn("fd_allocate", "iII", (i, mem, a) -> {
            var file = p.fds.file((int) a[0]);
            var end = a[1] + a[2];
            if (file.stat().size() < end) file.truncate(end);
            return 0;
        });
        m.fn("fd_fdstat_get", "ii", (i, mem, a) -> {
            var entry = p.fds.get((int) a[0]);
            var ptr = (int) a[1];
            mem.writeByte(ptr, (byte) entry.file.type().wasi);
            mem.writeByte(ptr + 1, (byte) 0);
            mem.writeShort(ptr + 2, (short) entry.flags);
            mem.writeI32(ptr + 4, 0);
            mem.writeLong(ptr + 8, entry.rightsBase);
            mem.writeLong(ptr + 16, entry.rightsInheriting);
            return 0;
        });
        m.fn("fd_fdstat_set_flags", "ii", (i, mem, a) -> {
            p.fds.get((int) a[0]).flags = (int) a[1];
            return 0;
        });
        m.fn("fd_fdstat_set_rights", "iII", (i, mem, a) -> {
            var entry = p.fds.get((int) a[0]);
            entry.rightsBase &= a[1];
            entry.rightsInheriting &= a[2];
            return 0;
        });
        m.fn("fd_filestat_get", "ii", (i, mem, a) -> {
            var file = p.fds.file((int) a[0]);
            writeFilestat(mem, (int) a[1], file.stat(), file.path());
            return 0;
        });
        m.fn("fd_filestat_set_size", "iI", (i, mem, a) -> {
            p.fds.file((int) a[0]).truncate(a[1]);
            return 0;
        });
        m.fn("fd_filestat_set_times", "iIIi", (i, mem, a) -> {
            p.fds.get((int) a[0]);
            return 0;
        });
        m.fn("fd_prestat_get", "ii", (i, mem, a) -> {
            var entry = p.fds.get((int) a[0]);
            if (entry.preopen == null) return Errno.EBADF;
            mem.writeI32((int) a[1], 0);
            mem.writeI32((int) a[1] + 4, entry.preopen.getBytes(StandardCharsets.UTF_8).length);
            return 0;
        });
        m.fn("fd_prestat_dir_name", "iii", (i, mem, a) -> {
            var entry = p.fds.get((int) a[0]);
            if (entry.preopen == null) return Errno.EBADF;
            var name = entry.preopen.getBytes(StandardCharsets.UTF_8);
            mem.write((int) a[1], name, 0, (int) Math.min(name.length, a[2]));
            return 0;
        });
        m.fn("fd_renumber", "ii", (i, mem, a) -> {
            p.fds.renumber((int) a[0], (int) a[1]);
            return 0;
        });
        m.fn("fd_readdir", "iiiIi", (i, mem, a) -> readdir(p, mem, (int) a[0], (int) a[1], (int) a[2], a[3], (int) a[4]));

        // ---- Paths ----
        m.fn("path_open", "iiiiiIIii", (i, mem, a) -> pathOpen(p, mem, a));
        m.fn("path_filestat_get", "iiiii", (i, mem, a) -> {
            var path = path(p, mem, (int) a[0], a[2], a[3]);
            writeFilestat(mem, (int) a[4], p.machine().vfs().stat(path), path);
            return 0;
        });
        m.fn("path_filestat_set_times", "iiiiIIi", (i, mem, a) -> {
            p.machine().vfs().stat(path(p, mem, (int) a[0], a[2], a[3]));
            return 0;
        });
        m.fn("path_create_directory", "iii", (i, mem, a) -> {
            p.machine().vfs().mkdir(path(p, mem, (int) a[0], a[1], a[2]));
            return 0;
        });
        m.fn("path_remove_directory", "iii", (i, mem, a) -> {
            p.machine().vfs().rmdir(path(p, mem, (int) a[0], a[1], a[2]));
            return 0;
        });
        m.fn("path_unlink_file", "iii", (i, mem, a) -> {
            var path = path(p, mem, (int) a[0], a[1], a[2]);
            if (p.machine().vfs().isDirectory(path)) return Errno.EISDIR;
            p.machine().vfs().unlink(path);
            return 0;
        });
        m.fn("path_rename", "iiiiii", (i, mem, a) -> {
            var from = path(p, mem, (int) a[0], a[1], a[2]);
            var to = path(p, mem, (int) a[3], a[4], a[5]);
            p.machine().vfs().rename(from, to);
            return 0;
        });
        m.fn("path_readlink", "iiiiii", (i, mem, a) -> {
            p.machine().vfs().stat(path(p, mem, (int) a[0], a[1], a[2]));
            return Errno.EINVAL;
        });
        m.fn("path_symlink", "iiiii", (i, mem, a) -> Errno.ENOTSUP);
        m.fn("path_link", "iiiiiii", (i, mem, a) -> Errno.ENOTSUP);

        // ---- Polling ----
        m.fn("poll_oneoff", "iiii", (i, mem, a) -> pollOneoff(p, mem, (int) a[0], (int) a[1], (int) a[2], (int) a[3]));

        // ---- Sockets: not available (networking goes through CC modems/HTTP peripherals instead) ----
        m.fn("sock_accept", "iii", (i, mem, a) -> Errno.ENOTSUP);
        m.fn("sock_recv", "iiiiii", (i, mem, a) -> Errno.ENOTSUP);
        m.fn("sock_send", "iiiii", (i, mem, a) -> Errno.ENOTSUP);
        m.fn("sock_shutdown", "ii", (i, mem, a) -> Errno.ENOTSUP);

        return m.build();
    }

    private static List<String> envList(Process p) {
        var out = new ArrayList<String>();
        for (var e : p.env().entrySet()) out.add(e.getKey() + "=" + e.getValue());
        return out;
    }

    private static long writeStrings(Memory mem, List<String> strings, long ptrs, long buf) {
        var offset = (int) buf;
        for (var i = 0; i < strings.size(); i++) {
            var bytes = strings.get(i).getBytes(StandardCharsets.UTF_8);
            mem.writeI32((int) ptrs + i * 4, offset);
            mem.write(offset, bytes);
            mem.writeByte(offset + bytes.length, (byte) 0);
            offset += bytes.length + 1;
        }
        return 0;
    }

    private static long writeSizes(Memory mem, List<String> strings, long countPtr, long sizePtr) {
        var size = 0;
        for (var s : strings) size += s.getBytes(StandardCharsets.UTF_8).length + 1;
        mem.writeI32((int) countPtr, strings.size());
        mem.writeI32((int) sizePtr, size);
        return 0;
    }

    private static long clock(int id) {
        return switch (id) {
            case 0 -> System.currentTimeMillis() * 1_000_000L + System.nanoTime() % 1_000_000L;
            case 2, 3 -> {
                var bean = ManagementFactory.getThreadMXBean();
                yield bean.isCurrentThreadCpuTimeSupported() ? bean.getCurrentThreadCpuTime() : System.nanoTime();
            }
            default -> System.nanoTime();
        };
    }

    private static long rw(Process p, Memory mem, int fd, long iovs, long count, long resultPtr, boolean write, long offset)
        throws FsException, InterruptedException {
        var entry = p.fds.get(fd);
        var file = entry.file;
        var nonBlocking = (entry.flags & FdTable.FDFLAG_NONBLOCK) != 0;
        long total = 0;
        for (var i = 0; i < count; i++) {
            var ptr = mem.readInt((int) iovs + i * 8);
            var len = mem.readInt((int) iovs + i * 8 + 4);
            if (len <= 0) continue;
            len = Math.min(len, MAX_IO);
            int n;
            if (write) {
                var buffer = ByteBuffer.wrap(mem.readBytes(ptr, len));
                n = offset >= 0 ? file.pwrite(buffer, offset + total) : file.write(buffer, nonBlocking);
            } else {
                var buffer = ByteBuffer.allocate(len);
                try {
                    n = offset >= 0 ? file.pread(buffer, offset + total) : file.read(buffer, nonBlocking || total > 0);
                } catch (FsException e) {
                    // A later iovec would block: return what we already have.
                    if (e.errno() == Errno.EAGAIN && total > 0) break;
                    throw e;
                }
                mem.write(ptr, buffer.array(), 0, n);
            }
            total += n;
            if (n < len) break;
        }
        mem.writeI32((int) resultPtr, (int) total);
        return 0;
    }

    /**
     * Backends have no inode numbers, so a stable one is derived from the path. Programs rely on it to tell files
     * apart (clang drops include directories it thinks are duplicates).
     */
    static long inode(String path) {
        if (path == null || path.isEmpty()) return 1;
        var h = 0xcbf29ce484222325L;
        for (var i = 0; i < path.length(); i++) {
            h ^= path.charAt(i);
            h *= 0x100000001b3L;
        }
        return h == 0 ? 1 : h;
    }

    private static void writeFilestat(Memory mem, int ptr, Stat stat, String path) {
        mem.writeLong(ptr, 1);
        mem.writeLong(ptr + 8, stat.ino() != 0 ? stat.ino() : inode(path));
        mem.writeByte(ptr + 16, (byte) stat.type().wasi);
        for (var i = 17; i < 24; i++) mem.writeByte(ptr + i, (byte) 0);
        mem.writeLong(ptr + 24, 1);
        mem.writeLong(ptr + 32, stat.size());
        mem.writeLong(ptr + 40, stat.mtime());
        mem.writeLong(ptr + 48, stat.mtime());
        mem.writeLong(ptr + 56, stat.ctime());
    }

    /** Resolve a path relative to a directory descriptor. */
    static String path(Process p, Memory mem, int dirfd, long ptr, long len) throws FsException {
        var raw = Host.string(mem, ptr, len);
        var dir = p.fds.get(dirfd).file;
        if (dir.type() != FileType.DIRECTORY) throw new FsException(Errno.ENOTDIR);
        return VPath.resolve(dir.path(), raw);
    }

    private static long pathOpen(Process p, Memory mem, long[] a) throws FsException {
        var dirfd = (int) a[0];
        var path = path(p, mem, dirfd, a[2], a[3]);
        var oflags = (int) a[4];
        var rights = a[5];
        var fdflags = (int) a[7];

        var flags = 0;
        var wantsRead = (rights & (1L << 1)) != 0 || (rights & (1L << 14)) != 0;
        var wantsWrite = (rights & (1L << 6)) != 0 || (rights & (1L << 22)) != 0;
        if (wantsRead || !wantsWrite) flags |= OpenFlags.READ;
        if (wantsWrite) flags |= OpenFlags.WRITE;
        if ((oflags & 1) != 0) flags |= OpenFlags.CREATE;
        if ((oflags & 2) != 0) flags |= OpenFlags.DIRECTORY;
        if ((oflags & 4) != 0) flags |= OpenFlags.EXCLUSIVE;
        if ((oflags & 8) != 0) flags |= OpenFlags.TRUNCATE | OpenFlags.WRITE;
        if ((fdflags & 1) != 0) flags |= OpenFlags.APPEND;

        var vfs = p.machine().vfs();
        if (vfs.isDirectory(path)) {
            // Directories are opened read-only regardless of the requested rights (libc asks for everything).
            flags = OpenFlags.READ | OpenFlags.DIRECTORY;
        }
        var file = vfs.open(path, flags);
        int fd;
        try {
            fd = p.fds.add(file, fdflags & (FdTable.FDFLAG_APPEND | FdTable.FDFLAG_NONBLOCK));
        } catch (FsException e) {
            file.release();
            throw e;
        }
        mem.writeI32((int) a[8], fd);
        return 0;
    }

    private static long readdir(Process p, Memory mem, int fd, int buf, int bufLen, long cookie, int usedPtr) throws FsException {
        var entry = p.fds.get(fd);
        if (entry.file.type() != FileType.DIRECTORY) return Errno.ENOTDIR;
        var vfs = p.machine().vfs();
        var dir = entry.file.path();
        if (cookie == 0 || entry.listing == null) {
            var names = new ArrayList<String>();
            names.add(".");
            names.add("..");
            names.addAll(vfs.list(dir));
            entry.listing = names;
        }
        var names = entry.listing;
        var used = 0;
        for (var i = (int) cookie; i < names.size() && used < bufLen; i++) {
            var name = names.get(i);
            var nameBytes = name.getBytes(StandardCharsets.UTF_8);
            FileType type;
            if (i < 2) {
                type = FileType.DIRECTORY;
            } else {
                try {
                    type = vfs.stat(VPath.join(dir, name)).type();
                } catch (FsException e) {
                    type = FileType.UNKNOWN;
                }
            }
            var header = new byte[24];
            var hb = ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            hb.putLong(0, i + 1);
            hb.putLong(8, inode(i == 0 ? dir : i == 1 ? VPath.parent(dir) : VPath.join(dir, name)));
            hb.putInt(16, nameBytes.length);
            header[20] = (byte) type.wasi;
            var record = new byte[24 + nameBytes.length];
            System.arraycopy(header, 0, record, 0, 24);
            System.arraycopy(nameBytes, 0, record, 24, nameBytes.length);
            var n = Math.min(record.length, bufLen - used);
            mem.write(buf + used, record, 0, n);
            used += n;
        }
        mem.writeI32(usedPtr, used);
        return 0;
    }

    private static long pollOneoff(Process p, Memory mem, int in, int out, int count, int countPtr) throws FsException, InterruptedException {
        if (count <= 0) return Errno.EINVAL;
        var notifier = p.machine().notifier();
        var now = System.nanoTime();
        long deadline = Long.MAX_VALUE;
        for (var i = 0; i < count; i++) {
            var base = in + i * 48;
            if (mem.read(base + 8) == 0) {
                var clockId = mem.readInt(base + 16);
                var timeout = mem.readLong(base + 24);
                var absolute = (mem.readShort(base + 40) & 1) != 0;
                long relative = absolute ? timeout - clock(clockId) : timeout;
                deadline = Math.min(deadline, now + Math.max(0, relative));
            }
        }

        while (true) {
            p.checkKilled();
            var generation = notifier.generation();
            var events = 0;
            var current = System.nanoTime();
            for (var i = 0; i < count; i++) {
                var base = in + i * 48;
                var userdata = mem.readLong(base);
                var tag = mem.read(base + 8);
                var error = 0;
                var ready = false;
                if (tag == 0) {
                    ready = current >= deadline;
                } else {
                    var fd = mem.readInt(base + 16);
                    try {
                        var file = p.fds.file(fd);
                        ready = tag == 1 ? file.readReady() : file.writeReady();
                    } catch (FsException e) {
                        ready = true;
                        error = e.errno();
                    }
                }
                if (!ready) continue;
                var o = out + events * 32;
                mem.writeLong(o, userdata);
                mem.writeShort(o + 8, (short) error);
                mem.writeByte(o + 10, tag);
                for (var j = 11; j < 32; j++) mem.writeByte(o + j, (byte) 0);
                if (tag != 0) mem.writeLong(o + 16, 1);
                events++;
            }
            if (events > 0) {
                mem.writeI32(countPtr, events);
                return 0;
            }
            var wait = deadline == Long.MAX_VALUE ? 100_000_000L : Math.min(deadline - System.nanoTime(), 100_000_000L);
            if (wait > 0) notifier.await(generation, wait);
        }
    }
}
