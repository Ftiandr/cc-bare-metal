package dev.ccbaremetal.kernel.fs;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.io.MemoryFile;
import dev.ccbaremetal.kernel.io.OpenFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipInputStream;

/**
 * A read-only, immutable file tree loaded once from a zip (the system image shipped in the mod jar). One instance
 * is shared by every computer; individual mounts expose a sub-directory of it.
 */
public final class ImageBackend implements FsBackend {
    private final Map<String, byte[]> files;
    private final Map<String, TreeSet<String>> dirs;
    private final String prefix;
    private final long mtime;

    private ImageBackend(Map<String, byte[]> files, Map<String, TreeSet<String>> dirs, String prefix, long mtime) {
        this.files = files;
        this.dirs = dirs;
        this.prefix = prefix;
        this.mtime = mtime;
    }

    public static ImageBackend load(InputStream stream, long mtime) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        Map<String, TreeSet<String>> dirs = new HashMap<>();
        dirs.put("", new TreeSet<>());
        try (var zip = new ZipInputStream(stream)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                var name = entry.getName();
                while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                if (name.isEmpty() || name.contains("..")) continue;
                addDirs(dirs, name, entry.isDirectory());
                if (!entry.isDirectory()) files.put(name, zip.readAllBytes());
            }
        }
        return new ImageBackend(files, dirs, "", mtime);
    }

    private static void addDirs(Map<String, TreeSet<String>> dirs, String path, boolean isDir) {
        if (isDir) dirs.computeIfAbsent(path, k -> new TreeSet<>());
        var idx = path.lastIndexOf('/');
        var parent = idx < 0 ? "" : path.substring(0, idx);
        dirs.computeIfAbsent(parent, k -> new TreeSet<>()).add(path.substring(idx + 1));
        if (idx >= 0) addDirs(dirs, parent, true);
    }

    /** A view of a sub-directory of this image. */
    public ImageBackend subtree(String path) {
        return new ImageBackend(files, dirs, full(path), mtime);
    }

    public boolean exists(String path) {
        var full = full(path);
        return files.containsKey(full) || dirs.containsKey(full);
    }

    public byte[] read(String path) {
        return files.get(full(path));
    }

    /** Programs that switch users run setuid root, like on Linux; nothing else in the system image is special. */
    private static final java.util.Set<String> SETUID = java.util.Set.of("bin/su", "bin/sudo", "bin/passwd", "bin/lock");

    /** The system is root's: programs (WebAssembly modules, scripts, multi-call aliases) 0755, data 0644. */
    private static int modeOf(String full, byte[] data) {
        if (SETUID.contains(full)) return 04755;
        var program = data.length >= 2 && ((data[0] == 0 && data[1] == 'a') || (data[0] == '#' && data[1] == '!'));
        return program ? 0755 : 0644;
    }

    private String full(String path) {
        if (prefix.isEmpty()) return path;
        return path.isEmpty() ? prefix : prefix + "/" + path;
    }

    @Override
    public Stat stat(String path) throws FsException {
        var full = full(path);
        var file = files.get(full);
        if (file != null) return Stat.file(file.length, mtime).withIno(full.hashCode() & 0xFFFFFFFFL).withOwner(modeOf(full, file), 0, 0);
        if (dirs.containsKey(full)) return Stat.dir(mtime).withIno(full.hashCode() & 0xFFFFFFFFL);
        throw new FsException(Errno.ENOENT);
    }

    @Override
    public List<String> list(String path) throws FsException {
        var full = full(path);
        var children = dirs.get(full);
        if (children == null) throw new FsException(files.containsKey(full) ? Errno.ENOTDIR : Errno.ENOENT);
        return new ArrayList<>(children);
    }

    @Override
    public OpenFile open(String path, String guestPath, int flags) throws FsException {
        var full = full(path);
        if (dirs.containsKey(full)) throw new FsException(Errno.EISDIR);
        var file = files.get(full);
        if (file == null) throw new FsException(OpenFlags.has(flags, OpenFlags.CREATE) ? Errno.EROFS : Errno.ENOENT);
        if (OpenFlags.has(flags, OpenFlags.WRITE) || OpenFlags.has(flags, OpenFlags.APPEND)) throw new FsException(Errno.EROFS);
        return new MemoryFile(file, guestPath, mtime);
    }

    @Override
    public String describe() {
        return "sysimage";
    }
}
