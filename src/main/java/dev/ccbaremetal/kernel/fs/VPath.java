package dev.ccbaremetal.kernel.fs;

import dev.ccbaremetal.kernel.Errno;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Guest path helpers. Guest paths are always '/'-separated and resolved lexically (there are no symlinks in the
 * guest file system), so ".." can never escape the root.
 */
public final class VPath {
    public static final int MAX_PATH = 4096;
    public static final int MAX_NAME = 255;

    private static final Set<String> WINDOWS_RESERVED = Set.of(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    );

    private VPath() {
    }

    /** Resolve {@code path} against the absolute directory {@code base}, returning a canonical absolute path. */
    public static String resolve(String base, String path) throws FsException {
        if (path.isEmpty()) return normalize(base);
        return normalize(path.charAt(0) == '/' ? path : base + "/" + path);
    }

    /** Canonicalise an absolute path: "/a/./b/../c/" becomes "/a/c". */
    public static String normalize(String path) throws FsException {
        if (path.length() > MAX_PATH) throw new FsException(Errno.ENAMETOOLONG);
        if (path.indexOf('\0') >= 0) throw new FsException(Errno.EINVAL);
        List<String> parts = new ArrayList<>();
        for (var part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
                continue;
            }
            if (part.length() > MAX_NAME) throw new FsException(Errno.ENAMETOOLONG);
            parts.add(part);
        }
        if (parts.isEmpty()) return "/";
        var out = new StringBuilder(path.length());
        for (var part : parts) out.append('/').append(part);
        return out.toString();
    }

    public static String parent(String abs) {
        var idx = abs.lastIndexOf('/');
        return idx <= 0 ? "/" : abs.substring(0, idx);
    }

    public static String name(String abs) {
        return abs.substring(abs.lastIndexOf('/') + 1);
    }

    public static String join(String dir, String name) {
        return dir.equals("/") ? "/" + name : dir + "/" + name;
    }

    /** Whether {@code path} equals {@code prefix} or lies below it. Both must be canonical. */
    public static boolean isWithin(String path, String prefix) {
        if (prefix.equals("/")) return true;
        return path.equals(prefix) || (path.startsWith(prefix) && path.charAt(prefix.length()) == '/');
    }

    /** Path relative to a mount point, without leading slash; "" is the mount root. */
    public static String relativeTo(String path, String mount) {
        if (mount.equals("/")) return path.substring(1);
        return path.length() == mount.length() ? "" : path.substring(mount.length() + 1);
    }

    /**
     * Check that every component of a backend-relative path is safe to map onto a real host file system, on both
     * Windows and Linux. This is a second line of defence: paths are already canonical at this point.
     */
    public static void checkHostSafe(String rel) throws FsException {
        if (rel.isEmpty()) return;
        for (var part : rel.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new FsException(Errno.EINVAL);
            for (var i = 0; i < part.length(); i++) {
                var c = part.charAt(i);
                if (c < 32 || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                    throw new FsException(Errno.EINVAL, "Invalid character in file name");
                }
            }
            var last = part.charAt(part.length() - 1);
            if (last == '.' || last == ' ') throw new FsException(Errno.EINVAL, "File names may not end in '.' or ' '");
            var dot = part.indexOf('.');
            var stem = (dot < 0 ? part : part.substring(0, dot)).toLowerCase(Locale.ROOT).trim();
            if (WINDOWS_RESERVED.contains(stem)) throw new FsException(Errno.EINVAL, "Reserved file name");
        }
    }
}
