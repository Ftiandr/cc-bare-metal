package dev.ccbaremetal.kernel.proc;

import java.util.Arrays;

/**
 * Who a process is: real and effective user and group, supplementary groups (as in Linux; the effective ids decide
 * permissions, the real ids say who started a setuid program). Immutable; a process replaces its own.
 */
public record Cred(int uid, int euid, int gid, int egid, int[] groups) {
    public static final Cred ROOT = new Cred(0, 0, 0, 0, new int[]{0});

    public Cred {
        groups = groups.clone();
    }

    public static Cred of(int uid, int gid, int[] groups) {
        return new Cred(uid, uid, gid, gid, groups);
    }

    public boolean isRoot() {
        return euid == 0;
    }

    public boolean inGroup(int g) {
        if (egid == g) return true;
        for (var x : groups) if (x == g) return true;
        return false;
    }

    @Override
    public int[] groups() {
        return groups.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Cred c && c.uid == uid && c.euid == euid && c.gid == gid && c.egid == egid && Arrays.equals(c.groups, groups);
    }

    @Override
    public int hashCode() {
        return ((uid * 31 + euid) * 31 + gid) * 31 + egid;
    }

    @Override
    public String toString() {
        return "Cred[uid=" + uid + ", euid=" + euid + ", gid=" + gid + ", egid=" + egid + ", groups=" + Arrays.toString(groups) + "]";
    }

    // ---- Who is asking ------------------------------------------------------------------------------------------

    private static final ThreadLocal<Cred> ACTING = new ThreadLocal<>();

    /**
     * The credentials of the current request: the running guest process, or whoever the kernel acts for
     * ({@link #as}), or root for the kernel's own work (booting, the server thread).
     */
    public static Cred current() {
        var acting = ACTING.get();
        if (acting != null) return acting;
        var p = Process.CURRENT.get();
        return p != null ? p.cred : ROOT;
    }

    /** Run {@code action} with the permissions of {@code cred} (the kernel acting for a user: uploads...). */
    public static <T, E extends Exception> T as(Cred cred, ThrowingSupplier<T, E> action) throws E {
        var old = ACTING.get();
        ACTING.set(cred);
        try {
            return action.get();
        } finally {
            if (old == null) ACTING.remove();
            else ACTING.set(old);
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T, E extends Exception> {
        T get() throws E;
    }
}
