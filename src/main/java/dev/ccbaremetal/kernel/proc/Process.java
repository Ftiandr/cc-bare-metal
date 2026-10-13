package dev.ccbaremetal.kernel.proc;

import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.kernel.event.EventQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A guest process. Each process runs on its own platform thread (never the Minecraft server thread), so a busy
 * process can only slow itself down, and blocking calls (reading a pipe, waiting for events) are simple.
 */
public abstract class Process {
    private static final Logger LOG = LoggerFactory.getLogger(Process.class);
    private static final long STACK_SIZE = 64L * 1024 * 1024;

    public static final int SIGINT = 2;
    public static final int SIGKILL = 9;
    public static final int SIGTERM = 15;

    public static final ThreadLocal<Process> CURRENT = new ThreadLocal<>();

    public enum State { RUNNING, ZOMBIE, REAPED }

    protected final Machine machine;
    public final int pid;
    public volatile int ppid;
    public volatile int pgid;
    public final String name;
    public final List<String> argv;
    private final Map<String, String> env;
    public final FdTable fds = new FdTable();
    public final EventQueue events = new EventQueue();
    /** Java objects handed to the program as handles (see ObjectTable). */
    public final ObjectTable objects = new ObjectTable(this);
    public final long startTime = System.currentTimeMillis();

    /** Receives machine events (timers, peripherals...). */
    public volatile boolean wantsEvents;
    /** Receives keyboard/mouse events instead of the terminal when in the foreground. */
    public volatile boolean inputEvents;
    /** SIGINT/SIGTERM become a "terminate" event instead of killing the process. */
    public volatile boolean catchSignals;
    /** Whether this process changed the terminal mode (restored on exit). */
    public volatile boolean touchedTty;
    /** Who the process runs as; inherited by children, changed by setuid programs and setresuid. */
    public volatile Cred cred = Cred.ROOT;
    /** File mode bits removed from new files and directories. */
    public volatile int umask = 022;
    /** The controlling terminal: the console or a pseudo-terminal (ssh); /dev/tty, terminal modes, the job. */
    public volatile dev.ccbaremetal.kernel.tty.Tty ctty;

    volatile State state = State.RUNNING;
    volatile int exitCode;
    private volatile boolean killed;
    private volatile int killCode;
    private Thread thread;
    private byte[] pending = new byte[0];

    protected Process(Machine machine, int pid, int ppid, int pgid, String name, List<String> argv, Map<String, String> env) {
        this.machine = machine;
        this.pid = pid;
        this.ppid = ppid;
        this.pgid = pgid;
        this.name = name;
        this.argv = List.copyOf(argv);
        this.env = new LinkedHashMap<>(env);
    }

    public Machine machine() {
        return machine;
    }

    public State state() {
        return state;
    }

    public int exitCode() {
        return exitCode;
    }

    public synchronized Map<String, String> env() {
        return new LinkedHashMap<>(env);
    }

    public synchronized String getenv(String key) {
        return env.get(key);
    }

    public synchronized void setenv(String key, String value) {
        if (value == null) env.remove(key);
        else env.put(key, value);
    }

    /** The working directory: tracked through PWD, which our libc keeps in sync with chdir(). */
    public String cwd() {
        var pwd = getenv("PWD");
        return pwd == null || !pwd.startsWith("/") ? "/" : pwd;
    }

    public void start() {
        thread = new Thread(null, this::main, "ccbaremetal-" + machine.id() + "-" + pid + "-" + name, STACK_SIZE);
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    private void main() {
        CURRENT.set(this);
        var code = 0;
        try {
            code = run();
        } catch (ProcessExit e) {
            code = e.code();
        } catch (Throwable t) {
            var exit = ProcessExit.find(t);
            if (exit != null) {
                code = exit.code();
            } else if (killed) {
                code = killCode;
            } else {
                code = crashed(t);
            }
        } finally {
            if (killed) code = killCode;
            try {
                cleanup();
            } catch (Throwable t) {
                LOG.error("Error cleaning up process {}", pid, t);
            }
            fds.closeAll();
            CURRENT.remove();
            machine.processes().onExit(this, code);
        }
    }

    /** Report an unexpected failure to the process's stderr and pick an exit status. */
    protected int crashed(Throwable t) {
        if (t instanceof StackOverflowError) {
            stderr(name + ": stack overflow\n");
            return 134;
        }
        if (t instanceof OutOfMemoryError) {
            stderr(name + ": out of memory\n");
            return 137;
        }
        LOG.debug("Process {} ({}) crashed", pid, name, t);
        stderr(name + ": " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()) + "\n");
        return 134;
    }

    public void stderr(String message) {
        try {
            var file = fds.file(2);
            file.write(java.nio.ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)), true);
        } catch (Exception ignored) {
        }
    }

    protected abstract int run() throws Exception;

    protected void cleanup() {
    }

    /** Deliver a signal. Without a handler (catchSignals), anything but SIGKILL ends the process with 128+sig. */
    public void signal(int sig) {
        if (sig == 0) return;
        if (sig != SIGKILL && catchSignals) {
            events.offer(Event.of("terminate"));
            return;
        }
        kill(128 + sig);
    }

    /** Forcefully end the process with the given status. Interrupts blocking calls and running WASM code. */
    public void kill(int code) {
        if (state != State.RUNNING || killed) return;
        killCode = code;
        killed = true;
        onKill();
        var t = thread;
        if (t != null) t.interrupt();
    }

    protected void onKill() {
    }

    public boolean isKilled() {
        return killed;
    }

    /** Called at the start of every host call: unwinds the guest if it has been killed. */
    public void checkKilled() {
        if (killed) throw new ProcessExit(killCode);
    }

    public void deliver(Event event) {
        events.offer(event);
        // wakes event_wait_fds, which waits for events and descriptors at once
        machine.notifier().signal();
    }

    public synchronized void setPending(byte[] data) {
        pending = data;
    }

    public synchronized byte[] takePending() {
        var data = pending;
        pending = new byte[0];
        return data;
    }

    public Thread thread() {
        return thread;
    }

    public String commandLine() {
        return String.join(" ", argv);
    }
}
