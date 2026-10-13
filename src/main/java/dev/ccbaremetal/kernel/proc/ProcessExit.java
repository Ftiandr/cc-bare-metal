package dev.ccbaremetal.kernel.proc;

/** Thrown to unwind a guest when it calls proc_exit or is killed. */
public final class ProcessExit extends RuntimeException {
    private final int code;

    public ProcessExit(int code) {
        super("exit " + code, null, false, false);
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** Find a ProcessExit anywhere in a cause chain (runtimes may wrap host exceptions). */
    public static ProcessExit find(Throwable t) {
        for (var depth = 0; t != null && depth < 16; t = t.getCause(), depth++) {
            if (t instanceof ProcessExit e) return e;
        }
        return null;
    }
}
