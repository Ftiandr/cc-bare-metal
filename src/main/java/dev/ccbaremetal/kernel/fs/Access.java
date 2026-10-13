package dev.ccbaremetal.kernel.fs;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.proc.Cred;

/** Unix permission checks: owner, group or other bits, root passes everything but execute without any x bit. */
public final class Access {
    public static final int R = 4;
    public static final int W = 2;
    public static final int X = 1;

    private Access() {
    }

    public static boolean allowed(Stat st, Cred c, int want) {
        if (c.isRoot()) {
            // root may run a file only if someone may (as on Linux); directories are always searchable
            return (want & X) == 0 || st.isDirectory() || (st.mode() & 0111) != 0;
        }
        int bits;
        if (st.uid() == c.euid()) bits = st.mode() >> 6;
        else if (c.inGroup(st.gid())) bits = st.mode() >> 3;
        else bits = st.mode();
        return (bits & want & 7) == want;
    }

    public static void check(Stat st, Cred c, int want) throws FsException {
        if (!allowed(st, c, want)) throw new FsException(Errno.EACCES);
    }

    /** Sticky directories (/tmp): only the owner of an entry, of the directory, or root may remove or rename it. */
    public static void checkSticky(Stat dir, Stat entry, Cred c) throws FsException {
        if ((dir.mode() & Stat.S_ISVTX) == 0 || c.isRoot()) return;
        if (entry.uid() != c.euid() && dir.uid() != c.euid()) throw new FsException(Errno.EPERM);
    }
}
