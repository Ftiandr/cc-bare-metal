package dev.ccbaremetal.kernel;

import dan200.computercraft.api.ComputerCraftAPI;
import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.kernel.fs.CcMountBackend;
import dev.ccbaremetal.kernel.fs.DevBackend;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.HostBackend;
import dev.ccbaremetal.kernel.fs.ImageBackend;
import dev.ccbaremetal.kernel.fs.MemBackend;
import dev.ccbaremetal.kernel.fs.ReadOnlyBackend;
import dev.ccbaremetal.kernel.fs.ProcBackend;
import dev.ccbaremetal.kernel.fs.VPath;
import dev.ccbaremetal.kernel.fs.Vfs;
import dev.ccbaremetal.kernel.gfx.Framebuffer;
import dev.ccbaremetal.kernel.io.Notifier;
import dev.ccbaremetal.kernel.io.OpenFile;
import dev.ccbaremetal.kernel.periph.Peripherals;
import dev.ccbaremetal.kernel.proc.Process;
import dev.ccbaremetal.kernel.proc.ProcessTable;
import dev.ccbaremetal.kernel.proc.WasmProcess;
import dev.ccbaremetal.kernel.tty.Keys;
import dev.ccbaremetal.kernel.tty.Terminal;
import dev.ccbaremetal.kernel.tty.Tty;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One WASM computer: file system, terminal, framebuffer, processes, peripherals and redstone. Independent of
 * Minecraft except through {@link Host}, so the same kernel backs computers, turtles and pocket computers.
 *
 * <p>Threading: guest code runs on per-process threads; {@link #boot}, {@link #shutdown} and peripheral changes are
 * called from the server thread; the sync code reads the terminal/framebuffer from the server thread.
 */
public final class Machine {
    /** PATH of a new session, and the search path when a program runs without PATH (as after {@code env -i}). */
    static final String DEFAULT_PATH = "/usr/local/bin:/bin:/usr/bin:/sbin";

    public static final String VERSION = "0.1.0";
    private static final Logger LOG = LoggerFactory.getLogger(Machine.class);

    /** Sides in CC's order (ComputerSide): bottom, top, back, front, right, left. */
    public static final List<String> SIDES = List.of("bottom", "top", "back", "front", "right", "left");

    /**
     * @param maxMemoryPages      memory limit of one process (64 KiB pages)
     * @param toolchainMemoryPages memory limit of the compiler toolchain (programs under {@link #TOOLCHAIN_DIR}),
     *                             which needs far more than ordinary programs
     */
    public record Config(int maxMemoryPages, int toolchainMemoryPages, int maxProcesses, long diskCapacity, long tmpCapacity,
                         int termWidth, int termHeight, int screenWidth, int screenHeight) {
    }

    /** Where the clang toolchain is mounted. */
    public static final String TOOLCHAIN_DIR = "/usr/lib/llvm/";

    /** What the machine needs from the block/item hosting it. */
    public interface Host {
        MinecraftServer server();

        default void onLabelChanged(String label) {
        }

        default void onRedstoneOutputChanged() {
        }

        default void onStateChanged(boolean on) {
        }

        /** Current world day time, for os.setAlarm. */
        default long dayTime() {
            return 0;
        }

        /** Where the computer is, for addon APIs (null outside a world). */
        default net.minecraft.server.level.ServerLevel level() {
            return null;
        }

        default net.minecraft.core.BlockPos position() {
            return null;
        }
    }

    /** A program implemented in Java (Lua runtimes), referenced by files containing "#!native:NAME". */
    @FunctionalInterface
    public interface NativeProgram {
        Process create(Machine machine, int pid, int ppid, int pgid, String name, List<String> argv, Map<String, String> env);
    }

    private static final Map<String, NativeProgram> NATIVES = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService TIMERS = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "ccbaremetal-timers");
        t.setDaemon(true);
        return t;
    });
    private static final java.util.concurrent.ExecutorService CONTROL = Executors.newCachedThreadPool(r -> {
        var t = new Thread(r, "ccbaremetal-control");
        t.setDaemon(true);
        return t;
    });

    public static void registerNative(String name, NativeProgram program) {
        NATIVES.put(name, program);
    }

    public enum DisplayMode { TEXT, GRAPHICS }

    private final int id;
    private final Path storage;
    /** The computer's own disk (mounted at /), which keeps file owners. */
    private HostBackend rootFs;
    private final ImageBackend system;
    private final Config config;
    private final Host host;

    private final Notifier notifier = new Notifier();
    private final Terminal terminal;
    private final Tty tty;
    private final Framebuffer framebuffer;
    private final Vfs vfs = new Vfs();
    private final ProcessTable processes;
    private final Peripherals peripherals = new Peripherals(this);
    private final dev.ccbaremetal.kernel.periph.PeriphFiles periphFiles = new dev.ccbaremetal.kernel.periph.PeriphFiles(this);
    private final dev.ccbaremetal.kernel.io.InputHub inputHub = new dev.ccbaremetal.kernel.io.InputHub(notifier);
    private final dev.ccbaremetal.kernel.periph.AddonApis addonApis = new dev.ccbaremetal.kernel.periph.AddonApis(this);
    private final dev.ccbaremetal.kernel.net.Net net = new dev.ccbaremetal.kernel.net.Net(this);
    private final dev.ccbaremetal.kernel.dev.DeviceTree devices = new dev.ccbaremetal.kernel.dev.DeviceTree(this);
    private final AtomicInteger timerIds = new AtomicInteger();
    private final Map<Integer, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final List<long[]> alarms = new ArrayList<>();
    private final Map<Integer, Integer> alarmOwners = new HashMap<>();

    private final int[] redstoneInput = new int[6];
    private final int[] redstoneOutput = new int[6];

    private volatile boolean on;
    private volatile boolean busy;
    private volatile String label;
    private volatile long bootTime;
    private volatile DisplayMode display = DisplayMode.TEXT;
    // The screen lock (lock, lock -t): a separate terminal with the password prompt is shown and gets the keyboard;
    // the console and its programs go on behind it, untouched.
    private volatile Terminal lockTerminal;
    private volatile Tty lockTty;
    private volatile boolean screenLocked;
    private volatile int lockUid = -1;
    private volatile int lockPid;
    private volatile long autoLockMs;
    private volatile long lastInput = System.currentTimeMillis();
    /** Open pseudo-terminals (ssh sessions), for messages to every terminal (shutdown's warnings). */
    private final java.util.Set<dev.ccbaremetal.kernel.tty.Pty> ptys = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private volatile int displayOwner;
    /** The screen shows /dev/fb0 output: keep it after the writer exits, until a key press. */
    private volatile boolean fbLinger;
    /** The "char" event of the key that dismissed the picture is dropped (if it comes right after it). */
    private volatile long swallowCharUntil;
    private static final int LINGERING = -1;

    public Machine(int id, Path storage, ImageBackend system, Config config, Host host) {
        this.id = id;
        this.storage = storage;
        this.system = system;
        this.config = config;
        this.host = host;
        terminal = new Terminal(config.termWidth(), config.termHeight());
        tty = new Tty(terminal, notifier, this::signalForeground);
        framebuffer = new Framebuffer(config.screenWidth(), config.screenHeight(), Math.min(512, config.screenWidth()),
            Math.min(288, config.screenHeight()), 8);
        processes = new ProcessTable(config.maxProcesses(), this::onProcessExit);
        // the drivers, first match wins; then the computer's own devices (fb0, event0, event1 come first)
        devices.addDriver(new dev.ccbaremetal.kernel.dev.drivers.PixelMonitorDriver(this));
        devices.addDriver(new dev.ccbaremetal.kernel.dev.drivers.MonitorDriver(this));
        devices.addDriver(new dev.ccbaremetal.kernel.dev.drivers.SpeakerDriver(this));
        devices.addDriver(new dev.ccbaremetal.kernel.dev.drivers.PrinterDriver(this));
        devices.addDriver(new dev.ccbaremetal.kernel.dev.drivers.ModemDriver(this));
        dev.ccbaremetal.kernel.dev.ComputerDevices.register(this, devices, inputHub);
        devices.register(net.loopback());
    }

    // ---- Accessors ------------------------------------------------------------------------------------------------

    public int id() {
        return id;
    }

    public Config config() {
        return config;
    }

    public Vfs vfs() {
        return vfs;
    }

    public Notifier notifier() {
        return notifier;
    }

    public Terminal terminal() {
        return terminal;
    }

    public Tty tty() {
        return tty;
    }

    // ---- Screen lock ----------------------------------------------------------------------------------------------

    /** The terminal the computer's screen shows: the lock prompt while locked, else the console. */
    public Terminal shownTerminal() {
        var t = lockTerminal;
        return screenLocked && t != null ? t : terminal;
    }

    public DisplayMode shownDisplay() {
        return screenLocked ? DisplayMode.TEXT : display;
    }

    public boolean screenLocked() {
        return screenLocked;
    }

    /** The lock prompt's terminal (only its program may unlock). */
    public Tty lockTty() {
        return lockTty;
    }

    /** Lock the screen for {@code uid} (whose password, or root's, unlocks it): /bin/lock --screen runs on it. */
    public synchronized void lockScreen(int uid) throws FsException {
        if (!on || screenLocked) return;
        lockTerminal = new Terminal(terminal.width(), terminal.height());
        lockTty = new Tty(lockTerminal, notifier, sig -> {
        });
        lockUid = uid;
        screenLocked = true;
        try {
            spawnLocker();
        } catch (FsException e) {
            screenLocked = false;
            lockTerminal = null;
            lockTty = null;
            throw e;
        }
        notifier.signal();
    }

    private void spawnLocker() throws FsException {
        var f = lockTty.open();
        OpenFile[] stdio = {f, f.retain(), f.retain()};
        Map<String, String> env = new HashMap<>();
        env.put("PATH", DEFAULT_PATH);
        env.put("TERM", "ccbaremetal");
        env.put("HOME", "/root");
        var p = spawn(null, "/bin/lock", List.of("lock", "--screen", String.valueOf(lockUid)), env, stdio,
            SPAWN_NEW_GROUP | SPAWN_CTTY | SPAWN_FOREGROUND);
        lockPid = p.pid;
    }

    /** The right password was typed (called for the lock prompt's program). */
    public synchronized void unlockScreen() {
        if (!screenLocked) return;
        screenLocked = false;
        lockPid = 0;
        lockTerminal = null;
        lockTty = null;
        lastInput = System.currentTimeMillis();
        notifier.signal();
    }

    /** Lock after {@code seconds} without input (0: never), for {@code uid}. */
    public void setAutoLock(int seconds, int uid) {
        autoLockMs = Math.max(0, seconds) * 1000L;
        if (seconds > 0) lockUid = uid;
        lastInput = System.currentTimeMillis();
    }

    public int autoLockSeconds() {
        return (int) (autoLockMs / 1000);
    }

    public int lockUid() {
        return lockUid;
    }

    /** A message on every terminal: the console (and the lock prompt) and every ssh session (wall). */
    public void broadcast(byte[] text) {
        tty.write(text, 0, text.length);
        var lt = lockTty;
        if (screenLocked && lt != null) lt.write(text, 0, text.length);
        List<dev.ccbaremetal.kernel.tty.Pty> open;
        synchronized (ptys) {
            open = new ArrayList<>(ptys);
        }
        for (var pty : open) pty.slave.write(text, 0, text.length);
    }

    public Framebuffer framebuffer() {
        return framebuffer;
    }

    public ProcessTable processes() {
        return processes;
    }

    public dev.ccbaremetal.kernel.periph.AddonApis addonApis() {
        return addonApis;
    }

    public Peripherals peripherals() {
        return peripherals;
    }

    public dev.ccbaremetal.kernel.net.Net net() {
        return net;
    }

    /** Drivers and devices: what /dev and /sys show. */
    public dev.ccbaremetal.kernel.dev.DeviceTree devices() {
        return devices;
    }

    /** The computer's network name: the first line of /etc/hostname, else its label, else "computer-ID". */
    public String hostname() {
        try {
            var text = new String(vfs.readAll("/etc/hostname", 4096), StandardCharsets.UTF_8);
            var line = text.lines().findFirst().orElse("").strip();
            if (!line.isEmpty()) return line;
        } catch (FsException ignored) {
        }
        var l = label;
        return l != null && !l.isEmpty() ? l : "computer-" + id;
    }

    public MinecraftServer server() {
        return host.server();
    }

    public Host host() {
        return host;
    }

    public boolean isOn() {
        return on;
    }

    public String label() {
        return label;
    }

    public void setLabel(String label) {
        if (label != null) {
            label = label.strip();
            if (label.isEmpty()) label = null;
            else if (label.length() > 32) label = label.substring(0, 32);
        }
        this.label = label;
        host.onLabelChanged(label);
    }

    /** Restore the label without notifying (loading from NBT). */
    public void loadLabel(String label) {
        this.label = label;
    }

    public long uptimeMillis() {
        return on ? System.currentTimeMillis() - bootTime : 0;
    }

    public DisplayMode display() {
        return display;
    }

    /** The console's foreground process group. */
    public int foreground() {
        return tty.foreground();
    }

    public void setForeground(int pgid) {
        tty.setForeground(pgid);
    }

    public long memoryOf(Process p) {
        if (p instanceof WasmProcess w) {
            var instance = w.instance();
            if (instance != null) {
                try {
                    return (long) instance.memory().pages() * 65536L;
                } catch (RuntimeException ignored) {
                }
            }
        }
        return 0;
    }

    // ---- Power ----------------------------------------------------------------------------------------------------

    public synchronized void boot() {
        if (on || busy) return;
        on = true;
        bootTime = System.currentTimeMillis();
        screenLocked = false;
        lockTerminal = null;
        lockTty = null;
        lockPid = 0;
        autoLockMs = 0;
        lastInput = bootTime;
        terminal.reset();
        tty.resetInput();
        display = DisplayMode.TEXT;
        fbCustom = false;
        defaultFramebufferMode();
        try {
            mountAll();
            installDefaults();
            addonApis.start();
            net.start();
            var init = vfs.exists("/sbin/init") ? "/sbin/init" : "/bin/sh";
            // init runs as root; login (started by init) switches to the user
            Map<String, String> env = new HashMap<>();
            env.put("PATH", DEFAULT_PATH);
            env.put("HOME", "/root");
            env.put("PWD", "/");
            env.put("TERM", "ccbaremetal");
            env.put("USER", "root");
            env.put("SHELL", "/bin/sh");
            var ttyFile = tty.open();
            OpenFile[] stdio = {ttyFile, ttyFile.retain(), ttyFile.retain()};
            var p = spawn(null, init, List.of(init), env, stdio, 0);
            tty.setForeground(p.pgid);
        } catch (FsException e) {
            tty.write("\u001b[31mBoot failed: " + e.getMessage() + "\u001b[0m\n");
            LOG.warn("Machine {} failed to boot: {}", id, e.getMessage());
        }
        host.onStateChanged(true);
    }

    private void mountAll() throws FsException {
        for (var m : vfs.mounts()) vfs.unmount(m.path());
        // owners and modes of the computer's own files live next to its folder (see HostBackend)
        var attributes = storage.resolveSibling(storage.getFileName() + ".meta");
        rootFs = new HostBackend(storage, config.diskCapacity(), attributes, DEFAULT_UID, DEFAULT_UID, 0644, 0755);
        vfs.mount("/", rootFs);
        for (var dir : List.of("bin", "sbin", "lib", "usr/bin", "usr/lib", "usr/include", "usr/share")) {
            if (system.exists(dir)) vfs.mount("/" + dir, system.subtree(dir));
        }
        // /dev/tty is the calling process's controlling terminal (the console, or an ssh session's pseudo-terminal)
        vfs.mount("/dev", new DevBackend(() -> {
            var caller = Process.CURRENT.get();
            return (caller != null && caller.ctty != null ? caller.ctty : tty).open();
        }, devices, periphFiles));
        vfs.mount("/sys", new dev.ccbaremetal.kernel.fs.SysBackend(devices));
        vfs.mount("/proc", new ProcBackend(this));
        vfs.mount("/tmp", new MemBackend(config.tmpCapacity(), 01777));
        vfs.mount("/run", new MemBackend(1 << 20, 0755));
        var toolchain = Toolchain.get();
        if (toolchain != null) {
            vfs.mount("/usr/lib/llvm", new ReadOnlyBackend(new HostBackend(toolchain.resolve("llvm"), 0, null, 0, 0, 0755, 0755), "toolchain"));
            vfs.mount("/usr/lib/wasi", new ReadOnlyBackend(new HostBackend(toolchain.resolve("wasi"), 0, null, 0, 0, 0644, 0755), "toolchain"));
        }
        var server = host.server();
        if (server != null) {
            var rom = ComputerCraftAPI.createResourceMount(server, "computercraft", "lua/rom");
            if (rom != null) vfs.mount("/rom", new CcMountBackend(rom, "rom"));
        }
    }

    /** The first user ("user"), and the owner of files from before there were owners. */
    public static final int DEFAULT_UID = 1000;
    /** Group of administrators: may use sudo and install into /usr/local. */
    public static final int WHEEL_GID = 10;

    /** The default /etc/sudoers: root and the groups wheel and sudo may run anything (as before there was a file). */
    private static final String SUDOERS = """
        # /etc/sudoers - who may run what as whom (man sudoers). Edit it with visudo, which checks it before saving.
        #
        #   WHO     HOSTS = (AS USER[:GROUP]) [NOPASSWD:] COMMANDS
        #   bob     ALL = (root) /bin/pkg, /usr/bin/cc       bob may run these two as root
        #   %games  ALL = (root) NOPASSWD: /bin/redstone      a group, without asking the password
        #   alice   ALL = (ALL) ALL, !/bin/su                 anything but su
        # The last rule that matches decides. Paths are full: see which COMMAND.

        Defaults env_reset
        Defaults secure_path="/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin"
        Defaults timestamp_timeout=15

        root    ALL=(ALL:ALL) ALL
        %wheel  ALL=(ALL:ALL) ALL
        %sudo   ALL=(ALL:ALL) ALL

        #includedir /etc/sudoers.d
        """;

    private record DirSpec(String path, int mode, int uid, int gid) {
    }

    private static final List<DirSpec> SYSTEM_DIRS = List.of(
        new DirSpec("/", 0755, 0, 0), new DirSpec("/etc", 0755, 0, 0), new DirSpec("/home", 0755, 0, 0),
        new DirSpec("/root", 0700, 0, 0), new DirSpec("/usr", 0755, 0, 0),
        new DirSpec("/usr/local", 02775, 0, WHEEL_GID), new DirSpec("/usr/local/bin", 02775, 0, WHEEL_GID),
        new DirSpec("/usr/local/lib", 02775, 0, WHEEL_GID), new DirSpec("/usr/local/include", 02775, 0, WHEEL_GID),
        new DirSpec("/var", 0755, 0, 0), new DirSpec("/var/lib", 0755, 0, 0), new DirSpec("/var/lib/pkg", 02775, 0, WHEEL_GID),
        new DirSpec("/mnt", 0755, 0, 0));

    /**
     * Without the first-boot setup (tests: -Dccbaremetal.unattendedSetup=true): the account "user" without a
     * password, logged in automatically, as computers were before the setup existed.
     */
    private static boolean unattended() {
        return Boolean.getBoolean("ccbaremetal.unattendedSetup");
    }

    /**
     * First boot: directories with their owners, default configuration (/etc) and the accounts. A new computer only
     * has root (locked) and /etc/firstboot: init runs the setup (/sbin/setup), which asks for the hostname, the first
     * user and the passwords. Never overwrites. A computer from before owners existed gets them once: the system parts
     * become root's, its own programs stay runnable (and its "user" account is kept).
     */
    private boolean onDisk(String rel) {
        try {
            rootFs.stat(rel);
            return true;
        } catch (FsException e) {
            return false;
        }
    }

    private void installDefaults() {
        var fresh = rootFs.freshAttributes();
        var newComputer = !vfs.exists("/etc/passwd");
        for (var d : SYSTEM_DIRS) {
            try {
                // on the computer's own disk: /usr "exists" in the tree already (the image's /usr/bin and /usr/lib
                // are mounted inside it), but /usr/local can only be made once the disk has /usr itself
                var created = !d.path().equals("/") && !onDisk(d.path().substring(1));
                if (created) rootFs.mkdir(d.path().substring(1));
                if (created || fresh) {
                    vfs.chown(d.path(), d.uid(), d.gid());
                    vfs.chmod(d.path(), d.mode());
                }
            } catch (FsException e) {
                LOG.debug("Could not create {}: {}", d.path(), e.getMessage());
            }
        }
        if (fresh) {
            // files created before owners existed: everything defaults to the user; /etc is root's
            setOwnerTree("/etc", 0, 0);
            markPrograms("/home");
            markPrograms("/usr/local");
            markPrograms("/root");
        }
        if (system.exists("etc")) copyDefaults("etc", "/etc");
        try {
            // a computer from before the setup existed has a home but no accounts: it keeps its "user", as then
            var legacyUser = unattended() || (newComputer && vfs.exists("/home/user"));
            var days = System.currentTimeMillis() / 86_400_000L;
            if (newComputer) {
                vfs.writeAll("/etc/passwd", ("root:x:0:0:root:/root:/bin/sh\n"
                    + (legacyUser ? "user:x:" + DEFAULT_UID + ":" + DEFAULT_UID + ":user:/home/user:/bin/sh\n" : "")).getBytes(StandardCharsets.UTF_8));
            }
            if (!vfs.exists("/etc/group")) {
                var member = legacyUser ? "user" : "";
                vfs.writeAll("/etc/group", ("root:x:0:\nwheel:x:" + WHEEL_GID + ":" + member + "\nusers:x:100:" + member + "\n"
                    + (legacyUser ? "user:x:" + DEFAULT_UID + ":\n" : "")).getBytes(StandardCharsets.UTF_8));
            }
            if (!vfs.exists("/etc/shadow")) {
                // root locked ("!": sudo, or a password given in the setup); the old "user" without a password
                vfs.writeAll("/etc/shadow", ("root:!:" + days + ":0:99999:7:::\n"
                    + (legacyUser ? "user::" + days + ":0:99999:7:::\n" : "")).getBytes(StandardCharsets.UTF_8));
                vfs.chmod("/etc/shadow", 0600);
            }
            if (newComputer && legacyUser) {
                if (!vfs.exists("/home/user")) vfs.mkdir("/home/user");
                vfs.chown("/home/user", DEFAULT_UID, DEFAULT_UID);
                vfs.chmod("/home/user", 0750);
                vfs.writeAll("/etc/autologin", "user\n".getBytes(StandardCharsets.UTF_8));
            } else if (newComputer) {
                // init runs /sbin/setup while this exists
                vfs.writeAll("/etc/firstboot", "".getBytes(StandardCharsets.UTF_8));
            }
            if (!vfs.exists("/etc/sudoers")) {
                vfs.writeAll("/etc/sudoers", SUDOERS.getBytes(StandardCharsets.UTF_8));
                vfs.chmod("/etc/sudoers", 0440);
            }
            if (!vfs.exists("/etc/sudoers.d")) {
                vfs.mkdir("/etc/sudoers.d");
                vfs.chmod("/etc/sudoers.d", 0750);
            }
        } catch (FsException e) {
            LOG.warn("Machine {}: could not create the accounts: {}", id, e.getMessage());
        }
    }

    /** chown -R, keeping modes (the kernel acts as root here). */
    private void setOwnerTree(String path, int uid, int gid) {
        try {
            vfs.chown(path, uid, gid);
            if (vfs.isDirectory(path)) for (var child : vfs.list(path)) setOwnerTree(VPath.join(path, child), uid, gid);
        } catch (FsException ignored) {
        }
    }

    /** WebAssembly modules and scripts made before permissions existed get their x bits, so they still run. */
    private void markPrograms(String path) {
        try {
            var st = vfs.rawStat(path);
            if (st.isDirectory()) {
                for (var child : vfs.list(path)) markPrograms(VPath.join(path, child));
                return;
            }
            if (st.type() != dev.ccbaremetal.kernel.fs.FileType.REGULAR_FILE || st.size() < 2) return;
            var file = vfs.open(path, dev.ccbaremetal.kernel.fs.OpenFlags.READ);
            var head = java.nio.ByteBuffer.allocate(4);
            try {
                file.pread(head, 0);
            } finally {
                file.release();
            }
            var b = head.array();
            if ((b[0] == 0 && b[1] == 'a') || (b[0] == '#' && b[1] == '!')) vfs.chmod(path, st.mode() | 0111);
        } catch (FsException ignored) {
        }
    }

    private void copyDefaults(String imagePath, String target) {
        try {
            var image = system.subtree(imagePath);
            for (var name : image.list("")) {
                var child = VPath.join(target, name);
                var source = imagePath + "/" + name;
                if (system.subtree(source).exists("") && system.read(source) == null) {
                    if (!vfs.exists(child)) vfs.mkdir(child);
                    copyDefaults(source, child);
                } else if (!vfs.exists(child)) {
                    vfs.writeAll(child, system.read(source));
                }
            }
        } catch (FsException e) {
            LOG.debug("Could not install defaults into {}: {}", target, e.getMessage());
        }
    }

    /** Stop everything. Safe to call from the server thread: waits only briefly for guests to unwind. */
    public void shutdown() {
        shutdown(true);
    }

    /**
     * Stop because the world is unloading the computer (chunk unload, server stop): the host is not told, so the
     * computer stays "on" in its saved state and boots again when loaded.
     */
    public void halt() {
        shutdown(false);
    }

    public void haltAsync() {
        CONTROL.execute(this::halt);
    }

    private void shutdown(boolean notifyHost) {
        synchronized (this) {
            if (!on) return;
            on = false;
            busy = true;
        }
        try {
            processes.killAll();
            try {
                if (!processes.awaitEmpty(2000)) LOG.warn("Machine {}: some processes did not stop in time", id);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (var t : timers.values()) t.cancel(false);
            timers.clear();
            synchronized (alarms) {
                alarms.clear();
                alarmOwners.clear();
            }
            net.stop();
            devices.detachAll();
            peripherals.detachAll();
            addonApis.stop();
            for (var m : vfs.mounts()) vfs.unmount(m.path());
            for (var i = 0; i < 6; i++) redstoneOutput[i] = 0;
            host.onRedstoneOutputChanged();
            terminal.reset();
            display = DisplayMode.TEXT;
        } finally {
            busy = false;
        }
        if (notifyHost) host.onStateChanged(false);
    }

    /** Reboot on a background thread so the caller (possibly a guest process) is not blocked. */
    public void reboot() {
        CONTROL.execute(() -> {
            shutdown();
            boot();
        });
    }

    public void shutdownAsync() {
        CONTROL.execute(this::shutdown);
    }

    private void onProcessExit(int pid) {
        if (displayOwner == pid && display == DisplayMode.GRAPHICS && fbLinger) {
            displayOwner = LINGERING;
        } else if (displayOwner == pid && display == DisplayMode.GRAPHICS) {
            display = DisplayMode.TEXT;
            terminal.setCursorBlink(terminal.cursorBlink());
        }
        if (pid == 1 && on) shutdownAsync();
        if (pid == lockPid && screenLocked && on) {
            // the prompt may not go away while locked (killed, crashed): start it again
            CONTROL.execute(() -> {
                synchronized (this) {
                    try {
                        if (screenLocked && on && lockPid == pid) spawnLocker();
                    } catch (FsException e) {
                        LOG.warn("Machine {}: lock prompt failed: {}", id, e.getMessage());
                        unlockScreen();
                    }
                }
            });
        }
        var fgAlive = false;
        for (var p : processes.all()) {
            if (p.pgid == tty.foreground() && p.state() == Process.State.RUNNING) fgAlive = true;
        }
        if (!fgAlive) tty.setMode(0);
    }

    // ---- Processes ------------------------------------------------------------------------------------------------

    public static final int SPAWN_NEW_GROUP = 1;
    public static final int SPAWN_FOREGROUND = 2;
    /** The terminal on the child's standard input becomes its controlling terminal (a new session: sshd). */
    public static final int SPAWN_CTTY = 4;

    /**
     * Start a program. {@code path} is searched in PATH if it has no slash. Handles WASM binaries, "#!" scripts
     * and native programs. Takes ownership of one reference to each stdio file.
     */
    public Process spawn(Process parent, String path, List<String> argv, Map<String, String> env, OpenFile[] stdio, int flags)
        throws FsException {
        try {
            if (!on) throw new FsException(Errno.EPERM, "Machine is off");
            var cred = parent != null ? parent.cred : dev.ccbaremetal.kernel.proc.Cred.current();
            var cwd = env.getOrDefault("PWD", "/");
            var resolved = resolveProgram(path, env.getOrDefault("PATH", DEFAULT_PATH), cwd, cred);
            // running needs x (not r: the kernel reads the module itself, as Linux does)
            var program = vfs.stat(resolved);
            if (program.isDirectory()) throw new FsException(Errno.EACCES);
            dev.ccbaremetal.kernel.fs.Access.check(program, cred, dev.ccbaremetal.kernel.fs.Access.X);
            var bytes = readProgram(resolved);
            var script = false;
            argv = argv.isEmpty() ? List.of(path) : argv;

            // Interpreter scripts: "#!/path/to/interp [arg]".
            for (var depth = 0; bytes.length >= 2 && bytes[0] == '#' && bytes[1] == '!'; depth++) {
                if (depth > 4) throw new FsException(Errno.ENOEXEC, "Too many interpreter levels");
                var end = 2;
                while (end < bytes.length && end < 256 && bytes[end] != '\n') end++;
                var line = new String(bytes, 2, end - 2, StandardCharsets.UTF_8).strip();
                if (line.startsWith("@")) {
                    // Exec alias ("#!@/bin/coreutils"): run another binary with argv unchanged (multi-call programs).
                    resolved = resolveProgram(line.substring(1).strip(), env.getOrDefault("PATH", DEFAULT_PATH), cwd, cred);
                    bytes = readProgram(resolved);
                    continue;
                }
                if (line.startsWith("native:")) {
                    var nativeName = line.substring(7).strip();
                    var nativeProgram = NATIVES.get(nativeName);
                    if (nativeProgram == null) throw new FsException(Errno.ENOEXEC, "Unknown native program " + nativeName);
                    return start(parent, nativeProgram, resolved, argv, env, stdio, flags, null, execCred(cred, program, script));
                }
                // an interpreter script: setuid/setgid bits are ignored, as on Linux
                script = true;
                var space = line.indexOf(' ');
                var interp = space < 0 ? line : line.substring(0, space);
                var newArgv = new ArrayList<String>();
                newArgv.add(interp);
                if (space >= 0) newArgv.add(line.substring(space + 1).strip());
                newArgv.add(resolved);
                newArgv.addAll(argv.subList(1, argv.size()));
                argv = newArgv;
                resolved = resolveProgram(interp, env.getOrDefault("PATH", DEFAULT_PATH), cwd, cred);
                dev.ccbaremetal.kernel.fs.Access.check(vfs.stat(resolved), cred, dev.ccbaremetal.kernel.fs.Access.X);
                bytes = readProgram(resolved);
            }
            if (bytes.length < 8 || bytes[0] != 0 || bytes[1] != 'a' || bytes[2] != 's' || bytes[3] != 'm') {
                throw new FsException(Errno.ENOEXEC, "Exec format error");
            }
            return start(parent, null, resolved, argv, env, stdio, flags, bytes, execCred(cred, program, script));
        } catch (FsException e) {
            for (var f : stdio) if (f != null) f.release();
            throw e;
        }
    }

    /** A program file's contents, read by the kernel (only x was required of the caller). */
    private byte[] readProgram(String path) throws FsException {
        return dev.ccbaremetal.kernel.proc.Cred.as(dev.ccbaremetal.kernel.proc.Cred.ROOT, () -> vfs.readAll(path, 256 << 20));
    }

    /** The new process's credentials: the parent's, with the effective ids of a setuid/setgid program. */
    private static dev.ccbaremetal.kernel.proc.Cred execCred(dev.ccbaremetal.kernel.proc.Cred cred, dev.ccbaremetal.kernel.fs.Stat program, boolean script) {
        if (script) return cred;
        var euid = (program.mode() & dev.ccbaremetal.kernel.fs.Stat.S_ISUID) != 0 ? program.uid() : cred.euid();
        var egid = (program.mode() & dev.ccbaremetal.kernel.fs.Stat.S_ISGID) != 0 ? program.gid() : cred.egid();
        if (euid == cred.euid() && egid == cred.egid()) return cred;
        return new dev.ccbaremetal.kernel.proc.Cred(cred.uid(), euid, cred.gid(), egid, cred.groups());
    }

    private Process start(Process parent, NativeProgram program, String resolved, List<String> argv, Map<String, String> env,
                          OpenFile[] stdio, int flags, byte[] wasm, dev.ccbaremetal.kernel.proc.Cred cred) throws FsException {
        var pid = processes.allocatePid();
        var ppid = parent == null ? 0 : parent.pid;
        var pgid = parent == null || (flags & SPAWN_NEW_GROUP) != 0 ? pid : parent.pgid;
        var name = argv.isEmpty() ? VPath.name(resolved) : VPath.name(argv.getFirst().isEmpty() ? resolved : argv.getFirst());
        var p = program != null
            ? program.create(this, pid, ppid, pgid, name, argv, env)
            : new WasmProcess(this, pid, ppid, pgid, name, argv, env, wasm,
                resolved.startsWith(TOOLCHAIN_DIR) ? config.toolchainMemoryPages() : config.maxMemoryPages());
        p.cred = cred;
        p.umask = parent != null ? parent.umask : 022;
        for (var i = 0; i < stdio.length; i++) {
            if (stdio[i] != null) p.fds.addAt(i, stdio[i], 0, null);
        }
        p.fds.addPreopen(vfs.open("/", dev.ccbaremetal.kernel.fs.OpenFlags.READ | dev.ccbaremetal.kernel.fs.OpenFlags.DIRECTORY), "/");
        if (parent != null && parent.wantsEvents && program != null) p.wantsEvents = true;
        processes.register(p);
        // the controlling terminal: the parent's, or (SPAWN_CTTY) the terminal on the new process's standard input
        p.ctty = parent != null && parent.ctty != null ? parent.ctty : tty;
        if ((flags & SPAWN_CTTY) != 0 && stdio.length > 0 && stdio[0] instanceof Tty.TtyFile t) p.ctty = t.tty();
        if ((flags & SPAWN_FOREGROUND) != 0) p.ctty.setForeground(pgid);
        p.start();
        return p;
    }

    private String resolveProgram(String path, String searchPath, String cwd, dev.ccbaremetal.kernel.proc.Cred cred) throws FsException {
        if (path.isEmpty()) throw new FsException(Errno.ENOENT);
        if (path.contains("/")) {
            var abs = VPath.resolve(cwd, path);
            if (!vfs.exists(abs) || vfs.isDirectory(abs)) throw new FsException(Errno.ENOENT, path + ": not found");
            return abs;
        }
        // the first match the caller may run; a match it may not run only matters if there is no other (EACCES)
        var denied = false;
        for (var dir : searchPath.split(":")) {
            if (dir.isEmpty()) continue;
            for (var candidate : new String[]{VPath.resolve(cwd, dir + "/" + path), VPath.resolve(cwd, dir + "/" + path + ".wasm")}) {
                try {
                    var st = vfs.stat(candidate);
                    if (st.isDirectory()) continue;
                    if (dev.ccbaremetal.kernel.fs.Access.allowed(st, cred, dev.ccbaremetal.kernel.fs.Access.X)) return candidate;
                    denied = true;
                } catch (FsException ignored) {
                }
            }
        }
        if (denied) throw new FsException(Errno.EACCES, path + ": Permission denied");
        throw new FsException(Errno.ENOENT, path + ": command not found");
    }

    // ---- Events and input -----------------------------------------------------------------------------------------

    /** Queue an event for every process listening to machine events. */
    public dev.ccbaremetal.kernel.periph.PeriphFiles periphFiles() {
        return periphFiles;
    }

    public void queueEvent(Event event) {
        if (!on) return;
        periphFiles.onEvent(event);
        if (devices.onEvent(event)) {
            notifier.signal();
            return;
        }
        for (var p : processes.all()) {
            if (p.wantsEvents && p.state() == Process.State.RUNNING) p.deliver(event);
        }
        notifier.signal();
    }

    /** Resize the text terminal (the GUI fits it to the player's window); programs get a "term_resize" event. */
    public void resizeTerminal(int cols, int rows) {
        var lt = lockTerminal;
        if (lt != null) lt.resize(cols, rows);
        if (terminal.resize(cols, rows)) {
            if (!fbCustom && display == DisplayMode.TEXT) defaultFramebufferMode();
            queueEvent(Event.of("term_resize"));
        }
    }

    /** The framebuffer mode was chosen by a program or the user (gfx mode, fbset): keep it on terminal resizes. */
    private volatile boolean fbCustom;

    public void setFramebufferCustom(boolean custom) {
        fbCustom = custom;
        if (!custom && display == DisplayMode.TEXT) defaultFramebufferMode();
    }

    /** The default framebuffer size: a common resolution filling the text area (its size in font pixels). */
    public int[] defaultFramebufferSize() {
        return Framebuffer.niceSize(terminal.width() * dev.ccbaremetal.kernel.gfx.TermFont.WIDTH,
            terminal.height() * dev.ccbaremetal.kernel.gfx.TermFont.HEIGHT, framebuffer.maxWidth(), framebuffer.maxHeight());
    }

    private void defaultFramebufferMode() {
        var size = defaultFramebufferSize();
        if (framebuffer.width() != size[0] || framebuffer.height() != size[1] || framebuffer.bpp() != 8) {
            framebuffer.setMode(size[0], size[1], 8, Framebuffer.PRESET_DEFAULT);
        }
    }

    /**
     * Whether the GUI should also send plain mouse motion: a program reads /dev/input/event1 or mice, or asked the
     * terminal for any-motion tracking (ESC[?1003h). The GUI sends it only when the cell changes, 20 times a second
     * at most, so nothing is sent while no program needs it.
     */
    public boolean wantsMouseMotion() {
        return on && !screenLocked && (inputHub.mouseOpen() || (display == DisplayMode.TEXT && terminal.mouseMode() >= 1003));
    }

    /** Whether the foreground program wants the mouse (event-driven programs, or xterm mouse reporting). */
    public boolean wantsMouse() {
        return on && !screenLocked && (terminal.mouseReporting() || !inputReceivers().isEmpty() || inputHub.mouseOpen());
    }

    private List<Process> inputReceivers() {
        var out = new ArrayList<Process>();
        for (var p : processes.all()) {
            if (p.inputEvents && p.pgid == tty.foreground() && p.state() == Process.State.RUNNING) out.add(p);
        }
        return out;
    }

    /** User input from the GUI (server thread). Routed to the foreground job: as events, or through the TTY. */
    public void input(Event event, int mods) {
        if (!on) return;
        lastInput = System.currentTimeMillis();
        if (screenLocked) {
            // only the password prompt hears the keyboard (not /dev/input readers, not the programs behind it)
            var lt = lockTty;
            if (lt == null) return;
            var args = event.args();
            switch (event.name()) {
                case "key" -> lt.onKey(((Number) args[0]).intValue(), mods);
                case "char" -> lt.onChar(((String) args[0]).codePointAt(0));
                case "paste" -> lt.onPaste((String) args[0]);
                default -> {
                }
            }
            return;
        }
        // /dev/input readers get everything; mouse motion is only for them.
        inputHub.feed(event, display == DisplayMode.GRAPHICS);
        if (event.name().equals("mouse_move")) {
            // Plain motion only goes to the terminal (xterm mode 1003); CC programs have no such event.
            var args = event.args();
            if (display == DisplayMode.TEXT && args.length >= 3 && args[1] instanceof Number x && args[2] instanceof Number y) {
                tty.onMouse("move", 0, x.intValue(), y.intValue(), mods);
            }
            return;
        }
        if (display == DisplayMode.GRAPHICS && displayOwner == LINGERING) {
            // A picture left by /dev/fb0: any key returns to the text screen (and is not passed on).
            if (event.name().equals("key")) {
                setDisplay(DisplayMode.TEXT, 0);
                swallowCharUntil = System.currentTimeMillis() + 150;
            }
            return;
        }
        if (event.name().equals("char") && System.currentTimeMillis() < swallowCharUntil) {
            swallowCharUntil = 0;
            return;
        }
        if (event.name().equals("key")) swallowCharUntil = 0;
        var receivers = inputReceivers();
        if (event.name().equals("key") && (mods & Keys.MOD_CONTROL) != 0 && event.args().length > 0
            && event.args()[0] instanceof Number n && !receivers.isEmpty()) {
            // Event-driven foreground programs (CraftOS, games): Ctrl+C / Ctrl+T terminate them like CC's Ctrl+T.
            var key = n.intValue();
            if (key == Keys.A + ('c' - 'a') || key == Keys.A + ('t' - 'a')) {
                signalForeground(key == Keys.A + ('c' - 'a') ? Process.SIGINT : Process.SIGTERM);
                return;
            }
        }
        if (!receivers.isEmpty()) {
            for (var p : receivers) p.deliver(event);
            notifier.signal();
            return;
        }
        var args = event.args();
        switch (event.name()) {
            case "key" -> tty.onKey(((Number) args[0]).intValue(), mods);
            case "char" -> {
                var s = (String) args[0];
                tty.onChar(s.codePointAt(0));
            }
            case "paste" -> tty.onPaste((String) args[0]);
            case "mouse_click", "mouse_up", "mouse_drag", "mouse_scroll" -> {
                // Programs with xterm mouse tracking (edit, ...) get the mouse through the terminal.
                if (display == DisplayMode.TEXT && args.length >= 3) {
                    tty.onMouse(event.name().substring(6), ((Number) args[0]).intValue(), ((Number) args[1]).intValue(),
                        ((Number) args[2]).intValue(), mods);
                }
                for (var p : processes.all()) {
                    if (p.wantsEvents && p.pgid == tty.foreground()) p.deliver(event);
                }
            }
            default -> {
                // Mouse and key-up events only matter to event-driven programs.
                for (var p : processes.all()) {
                    if (p.wantsEvents && p.pgid == tty.foreground()) p.deliver(event);
                }
            }
        }
    }

    // ---- Keyboards of players (the keyboard item) ---------------------------------------------------------------

    /**
     * A player took the computer's input with a keyboard: a new input device (/dev/input/eventN) for the time they
     * hold it, announced to programs as ("keyboard_attach", "eventN", player).
     */
    public dev.ccbaremetal.kernel.dev.KeyboardDevice attachKeyboard(String player) {
        var keyboard = new dev.ccbaremetal.kernel.dev.KeyboardDevice(player, notifier::signal);
        devices.register(keyboard);
        queueEvent(Event.of("keyboard_attach", keyboard.name(), player));
        return keyboard;
    }

    public void detachKeyboard(dev.ccbaremetal.kernel.dev.KeyboardDevice keyboard) {
        devices.unregister(keyboard);
        queueEvent(Event.of("keyboard_detach", keyboard.name(), keyboard.player()));
    }

    /**
     * Input from a player's keyboard. Keys and characters go where the window's would (terminal, CC events,
     * /dev/input/event0) and also to the keyboard's own device; the captured mouse only moves relatively:
     * "mouse_relative" (dx, dy), "mouse_button" (button, down), "mouse_wheel" (steps, positive up) to the
     * foreground program, and REL_X/REL_Y, buttons, REL_WHEEL on the device. Nothing reaches programs while the
     * screen is locked.
     */
    public void keyboardInput(dev.ccbaremetal.kernel.dev.KeyboardDevice keyboard, Event event, int mods) {
        if (!on) return;
        if (screenLocked) {
            if (!event.name().startsWith("mouse")) input(event, mods);
            return;
        }
        var args = event.args();
        switch (event.name()) {
            case "key" -> {
                keyboard.key(((Number) args[0]).intValue(), args.length > 1 && Boolean.TRUE.equals(args[1]) ? 2 : 1);
                input(event, mods);
            }
            case "key_up" -> {
                keyboard.key(((Number) args[0]).intValue(), 0);
                input(event, mods);
            }
            case "char", "paste" -> input(event, mods);
            case "mouse_relative" -> {
                keyboard.motion(((Number) args[0]).intValue(), ((Number) args[1]).intValue());
                toForeground(event);
            }
            case "mouse_button" -> {
                keyboard.button(((Number) args[0]).intValue(), Boolean.TRUE.equals(args[1]));
                toForeground(event);
            }
            case "mouse_wheel" -> {
                keyboard.wheel(((Number) args[0]).intValue());
                toForeground(event);
            }
            default -> {
            }
        }
    }

    private void toForeground(Event event) {
        lastInput = System.currentTimeMillis();
        for (var p : processes.all()) {
            if (p.wantsEvents && p.pgid == tty.foreground() && p.state() == Process.State.RUNNING) p.deliver(event);
        }
        notifier.signal();
    }

    /** A new pseudo-terminal for {@code owner} (sshd): its master file and its slave terminal. */
    public dev.ccbaremetal.kernel.tty.Pty openPty(Process owner) {
        var pty = new dev.ccbaremetal.kernel.tty.Pty(notifier, owner, nextPty.incrementAndGet(), (t, sig) -> {
            if (t.foreground() > 1) processes.signalGroup(t.foreground(), sig);
        }, this::hangupTty);
        synchronized (ptys) {
            ptys.add(pty);
        }
        return pty;
    }

    /** The master side of a pseudo-terminal closed: its session is over (SIGHUP on Linux). */
    public void hangupTty(Tty t) {
        for (var p : processes.all()) {
            if (p.ctty == t && p.state() == Process.State.RUNNING) p.signal(Process.SIGTERM);
        }
    }

    private final java.util.concurrent.atomic.AtomicInteger nextPty = new java.util.concurrent.atomic.AtomicInteger();

    private void signalForeground(int sig) {
        var foreground = tty.foreground();
        if (foreground > 1) processes.signalGroup(foreground, sig);
    }

    // ---- Display --------------------------------------------------------------------------------------------------

    /**
     * /dev/fb0 was written (or /sys/.../display set to graphics): show the framebuffer, unless another program
     * already shows its own graphics.
     */
    public void showFramebuffer(int pid) {
        if (display == DisplayMode.GRAPHICS && displayOwner != LINGERING && displayOwner != 0 && displayOwner != pid
            && processes.get(displayOwner) != null) {
            return;
        }
        if (display != DisplayMode.GRAPHICS || displayOwner != pid) {
            display = DisplayMode.GRAPHICS;
            displayOwner = pid == 0 ? LINGERING : pid;
        }
        fbLinger = true;
    }

    public void setDisplay(DisplayMode mode, int owner) {
        fbLinger = false;
        display = mode;
        displayOwner = owner;
    }

    // ---- Timers and alarms ----------------------------------------------------------------------------------------

    public int startTimer(Process owner, double seconds) {
        var timerId = timerIds.incrementAndGet();
        var delay = Math.max(0, (long) (seconds * 1000));
        // CC timers are tick based; round up to the next 50ms like CC so programs behave the same.
        delay = Math.max(50, (delay + 49) / 50 * 50);
        if (seconds <= 0) delay = 0;
        timers.put(timerId, TIMERS.schedule(() -> {
            timers.remove(timerId);
            owner.deliver(Event.of("timer", timerId));
            notifier.signal();
        }, delay, TimeUnit.MILLISECONDS));
        return timerId;
    }

    public void cancelTimer(int timerId) {
        var t = timers.remove(timerId);
        if (t != null) t.cancel(false);
    }

    public int setAlarm(Process owner, double time) {
        var alarmId = timerIds.incrementAndGet();
        var now = host.dayTime();
        var target = (long) (time * 1000) - 6000;
        if (target < 0) target += 24000;
        var day = now / 24000;
        var at = day * 24000 + target;
        if (at <= now) at += 24000;
        synchronized (alarms) {
            alarms.add(new long[]{alarmId, at, owner.pid});
        }
        return alarmId;
    }

    public void cancelAlarm(int alarmId) {
        synchronized (alarms) {
            alarms.removeIf(a -> a[0] == alarmId);
        }
    }

    /** Called every server tick by the host. */
    public void tick() {
        if (!on) return;
        addonApis.update();
        net.tick();
        if (autoLockMs > 0 && !screenLocked && System.currentTimeMillis() - lastInput > autoLockMs) {
            try {
                lockScreen(lockUid);
            } catch (FsException e) {
                LOG.warn("Machine {}: could not lock the screen: {}", id, e.getMessage());
                autoLockMs = 0;
            }
        }
        var now = host.dayTime();
        synchronized (alarms) {
            for (var it = alarms.iterator(); it.hasNext(); ) {
                var a = it.next();
                if (now >= a[1]) {
                    it.remove();
                    var p = processes.get((int) a[2]);
                    if (p != null) p.deliver(Event.of("alarm", (int) a[0]));
                }
            }
        }
    }

    // ---- Redstone -------------------------------------------------------------------------------------------------

    public synchronized int redstoneInput(int side) {
        return redstoneInput[side];
    }

    public synchronized int redstoneOutput(int side) {
        return redstoneOutput[side];
    }

    public void setRedstoneOutput(int side, int level) {
        level = Math.max(0, Math.min(15, level));
        synchronized (this) {
            if (redstoneOutput[side] == level) return;
            redstoneOutput[side] = level;
        }
        MainThreadHook.run(host::onRedstoneOutputChanged);
    }

    /** Update inputs from the world (server thread); queues a "redstone" event when anything changed. */
    public void updateRedstoneInputs(int[] levels) {
        var changed = false;
        synchronized (this) {
            for (var i = 0; i < 6; i++) {
                if (redstoneInput[i] != levels[i]) {
                    redstoneInput[i] = levels[i];
                    changed = true;
                }
            }
        }
        if (changed) queueEvent(Event.of("redstone"));
    }

    /** Runs world-touching callbacks on the server thread. */
    private static final class MainThreadHook {
        static void run(Runnable r) {
            dev.ccbaremetal.kernel.periph.MainThread.submit(r);
        }
    }
}
