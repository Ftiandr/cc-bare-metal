package dev.ccbaremetal.kernel.proc;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.fs.FsException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/** All processes of one machine: pid allocation, parent/child relations, exit status collection. */
public final class ProcessTable {
    private final Map<Integer, Process> processes = new ConcurrentHashMap<>();
    private final int limit;
    private final IntConsumer onExitHook;
    private int nextPid = 1;

    public ProcessTable(int limit, IntConsumer onExitHook) {
        this.limit = limit;
        this.onExitHook = onExitHook;
    }

    public synchronized int allocatePid() throws FsException {
        if (processes.size() >= limit) throw new FsException(Errno.EAGAIN, "Too many processes");
        for (var i = 0; i < 1 << 16; i++) {
            var pid = nextPid++;
            if (nextPid > 32767) nextPid = 2;
            if (!processes.containsKey(pid)) return pid;
        }
        throw new FsException(Errno.EAGAIN);
    }

    public synchronized void register(Process process) {
        processes.put(process.pid, process);
    }

    public Process get(int pid) {
        return processes.get(pid);
    }

    public List<Process> all() {
        var list = new ArrayList<>(processes.values());
        list.sort(Comparator.comparingInt(p -> p.pid));
        return list;
    }

    public int count() {
        return processes.size();
    }

    void onExit(Process process, int code) {
        synchronized (this) {
            process.exitCode = code;
            process.state = Process.State.ZOMBIE;
            // Orphans are adopted by init; zombies nobody will wait for are reaped immediately.
            for (var child : processes.values()) {
                if (child.ppid == process.pid) {
                    child.ppid = 1;
                    if (child.state == Process.State.ZOMBIE) reap(child);
                }
            }
            var parent = processes.get(process.ppid);
            if (parent == null || parent.state != Process.State.RUNNING || process.pid == 1) reap(process);
            notifyAll();
        }
        onExitHook.accept(process.pid);
    }

    private void reap(Process process) {
        process.state = Process.State.REAPED;
        processes.remove(process.pid);
    }

    /**
     * waitpid: wait for a child of {@code parent} to exit. {@code pid == -1} means any child.
     * Returns the pid and stores the status in {@code status[0]}, or 0 if {@code noHang} and nothing has exited.
     */
    public int waitFor(Process parent, int pid, boolean noHang, int[] status) throws FsException, InterruptedException {
        synchronized (this) {
            while (true) {
                var hasChild = false;
                for (var child : processes.values()) {
                    if (child.ppid != parent.pid || (pid > 0 && child.pid != pid)) continue;
                    hasChild = true;
                    if (child.state == Process.State.ZOMBIE) {
                        status[0] = child.exitCode;
                        reap(child);
                        return child.pid;
                    }
                }
                if (!hasChild) throw new FsException(Errno.ECHILD);
                if (noHang) return 0;
                parent.checkKilled();
                wait(250);
            }
        }
    }

    public void signalGroup(int pgid, int sig) {
        for (var p : processes.values()) {
            if (p.pgid == pgid && p.state == Process.State.RUNNING) p.signal(sig);
        }
    }

    public void killAll() {
        for (var p : processes.values()) p.kill(128 + Process.SIGKILL);
    }

    /** Wait (bounded) until every process has finished. */
    public boolean awaitEmpty(long timeoutMs) throws InterruptedException {
        var deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (this) {
            while (processes.values().stream().anyMatch(p -> p.state == Process.State.RUNNING)) {
                var remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return false;
                wait(remaining);
            }
            processes.clear();
            return true;
        }
    }
}
