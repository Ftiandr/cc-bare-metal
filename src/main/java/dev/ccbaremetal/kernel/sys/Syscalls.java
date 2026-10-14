package dev.ccbaremetal.kernel.sys;

import com.dylibso.chicory.runtime.HostFunction;
import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.VPath;
import dev.ccbaremetal.kernel.gfx.Framebuffer;
import dev.ccbaremetal.kernel.gfx.FramebufferProvider;
import dev.ccbaremetal.kernel.io.OpenFile;
import dev.ccbaremetal.kernel.io.Pipe;
import dev.ccbaremetal.kernel.lua.LuaEval;
import dev.ccbaremetal.kernel.periph.PeripheralException;
import dev.ccbaremetal.kernel.proc.Process;
import dev.ccbaremetal.kernel.wasi.Host;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Our own syscalls, import module "ccbaremetal": processes, terminal, events, graphics, peripherals, Lua and redstone.
 * The C side is {@code baremetal.h} / libbaremetal. Variable-length results are left in the process's "pending" buffer
 * and fetched with {@code take}.
 */
public final class Syscalls {
    public static final String MODULE = "ccbaremetal";

    private Syscalls() {
    }

    /** Per-process graphics target: null means the machine's own screen. */
    private static final Map<Process, FramebufferProvider> TARGETS = new java.util.WeakHashMap<>();

    /** Signals and process groups: root, or the same user (real or effective ids), as on Linux. */
    private static boolean mayControl(Process sender, Process target) {
        var s = sender.cred;
        var t = target.cred;
        return s.isRoot() || s.uid() == t.uid() || s.uid() == t.euid() || s.euid() == t.uid() || s.euid() == t.euid();
    }

    private static boolean atConsoleOrRoot(Process p) {
        return p.cred.uid() == 0 || p.ctty == p.machine().tty();
    }

    private static void writeStatExt(com.dylibso.chicory.runtime.Memory mem, int ptr, dev.ccbaremetal.kernel.fs.Stat st) {
        var type = switch (st.type()) {
            case DIRECTORY -> 0040000;
            case CHARACTER_DEVICE -> 0020000;
            case BLOCK_DEVICE -> 0060000;
            case SYMBOLIC_LINK -> 0120000;
            case SOCKET_DGRAM, SOCKET_STREAM -> 0140000;
            default -> 0100000;
        };
        mem.writeI32(ptr, type | st.mode());
        mem.writeI32(ptr + 4, st.uid());
        mem.writeI32(ptr + 8, st.gid());
    }

    public static HostFunction[] functions(Process p) {
        var m = new Host.Module(MODULE, p);
        var machine = p.machine();

        // ---- Processes ----
        m.fn("spawn", "iiiiiiiiiii", (i, mem, a) -> {
            var path = Host.string(mem, a[0], a[1]);
            var argv = Host.strings(mem, a[2], a[3]);
            Map<String, String> env;
            if (a[4] == 0) {
                env = p.env();
            } else {
                env = new LinkedHashMap<>();
                for (var entry : Host.strings(mem, a[4], a[5])) {
                    var eq = entry.indexOf('=');
                    if (eq > 0) env.put(entry.substring(0, eq), entry.substring(eq + 1));
                }
            }
            env.putIfAbsent("PWD", p.cwd());
            var stdio = new OpenFile[3];
            for (var fd = 0; fd < 3; fd++) {
                var source = (int) a[6 + fd];
                if (source < 0) source = fd;
                try {
                    stdio[fd] = p.fds.file(source).retain();
                } catch (FsException e) {
                    for (var f : stdio) if (f != null) f.release();
                    throw e;
                }
            }
            var child = machine.spawn(p, path, argv, env, stdio, (int) a[9]);
            mem.writeI32((int) a[10], child.pid);
            return 0;
        });
        m.fn("wait", "iii", (i, mem, a) -> {
            var status = new int[1];
            int pid;
            try {
                pid = machine.processes().waitFor(p, (int) a[0], (a[1] & 1) != 0, status);
            } catch (FsException e) {
                // the documented contract is -errno (ECHILD: no such child); a positive number would look like a pid
                return -e.errno();
            }
            if (a[2] != 0) mem.writeI32((int) a[2], status[0]);
            return pid;
        });
        m.fn("kill", "ii", (i, mem, a) -> {
            var pid = (int) a[0];
            var sig = (int) a[1];
            if (pid < 0) {
                var any = false;
                for (var target : machine.processes().all()) {
                    if (target.pgid == -pid && target.state() == Process.State.RUNNING && mayControl(p, target)) {
                        target.signal(sig);
                        any = true;
                    }
                }
                return any ? 0 : Errno.EPERM;
            }
            var target = machine.processes().get(pid);
            if (target == null || target.state() != Process.State.RUNNING) return Errno.ESRCH;
            if (!mayControl(p, target)) return Errno.EPERM;
            target.signal(sig);
            return 0;
        });
        m.fn("getpid", ":i", (i, mem, a) -> p.pid);
        m.fn("getppid", ":i", (i, mem, a) -> p.ppid);
        m.fn("getpgid", ":i", (i, mem, a) -> p.pgid);
        m.fn("setpgid", "ii", (i, mem, a) -> {
            var target = a[0] == 0 ? p : machine.processes().get((int) a[0]);
            if (target == null) return Errno.ESRCH;
            if (!mayControl(p, target)) return Errno.EPERM;
            target.pgid = a[1] == 0 ? target.pid : (int) a[1];
            return 0;
        });
        m.fn("pipe", "i", (i, mem, a) -> {
            var pipe = new Pipe(machine.notifier());
            var r = p.fds.add(pipe.readEnd, 0);
            int w;
            try {
                w = p.fds.add(pipe.writeEnd, 0);
            } catch (FsException e) {
                p.fds.close(r);
                pipe.writeEnd.release();
                throw e;
            }
            mem.writeI32((int) a[0], r);
            mem.writeI32((int) a[0] + 4, w);
            return 0;
        });
        m.fn("dup", "i", (i, mem, a) -> {
            try {
                return p.fds.dup((int) a[0]);
            } catch (FsException e) {
                return -e.errno();
            }
        });
        // ioctl on a device file (OSS controls of /dev/dspN...): 0 or an errno
        m.fn("fd_ioctl", "iii", (i, mem, a) -> p.fds.file((int) a[0]).ioctl((int) a[1], mem, (int) a[2]));
        m.fn("dup2", "ii", (i, mem, a) -> {
            p.fds.dup2((int) a[0], (int) a[1]);
            return 0;
        });
        m.fn("sleep", "i", (i, mem, a) -> {
            Thread.sleep(Math.max(0, a[0]));
            return 0;
        });
        m.fn("setcwd", "ii", (i, mem, a) -> {
            var path = VPath.resolve(p.cwd(), Host.string(mem, a[0], a[1]));
            var st = machine.vfs().stat(path);
            if (!st.isDirectory()) return Errno.ENOTDIR;
            dev.ccbaremetal.kernel.fs.Access.check(st, p.cred, dev.ccbaremetal.kernel.fs.Access.X);
            p.setenv("PWD", path);
            return 0;
        });

        // ---- Users and permissions ----
        // [uid, euid, gid, egid, ngroups, groups...] into the buffer (at most `cap` ints); returns the count written.
        m.fn("cred_get", "ii", (i, mem, a) -> {
            var c = p.cred;
            var groups = c.groups();
            int[] all = new int[5 + groups.length];
            all[0] = c.uid();
            all[1] = c.euid();
            all[2] = c.gid();
            all[3] = c.egid();
            all[4] = groups.length;
            System.arraycopy(groups, 0, all, 5, groups.length);
            var n = Math.min((int) a[1], all.length);
            for (var k = 0; k < n; k++) mem.writeI32((int) a[0] + 4 * k, all[k]);
            return n;
        });
        // setresuid-like: -1 keeps a value. Root may set anything; others only swap between their real and effective
        // ids (a setuid program dropping privileges). Groups: root only (ngroups < 0 keeps them).
        m.fn("cred_set", "iiiiii", (i, mem, a) -> {
            var c = p.cred;
            int uid = (int) a[0], euid = (int) a[1], gid = (int) a[2], egid = (int) a[3];
            var root = c.isRoot();
            if (!root) {
                for (var v : new int[]{uid, euid}) if (v != -1 && v != c.uid() && v != c.euid()) return Errno.EPERM;
                for (var v : new int[]{gid, egid}) if (v != -1 && v != c.gid() && v != c.egid()) return Errno.EPERM;
                if (a[5] >= 0) return Errno.EPERM;
            }
            var groups = c.groups();
            if (a[5] >= 0) {
                if (a[5] > 64) return Errno.EINVAL;
                groups = new int[(int) a[5]];
                for (var k = 0; k < groups.length; k++) groups[k] = mem.readInt((int) a[4] + 4 * k);
            }
            p.cred = new dev.ccbaremetal.kernel.proc.Cred(uid == -1 ? c.uid() : uid, euid == -1 ? c.euid() : euid,
                gid == -1 ? c.gid() : gid, egid == -1 ? c.egid() : egid, groups);
            return 0;
        });
        m.fn("umask", "i", (i, mem, a) -> {
            var old = p.umask;
            if (a[0] >= 0) p.umask = (int) a[0] & 0777;
            return old;
        });
        // Mode (with the file type bits of Linux), owner, group of a path or an open file: [mode, uid, gid].
        m.fn("path_stat_ext", "iii", (i, mem, a) -> {
            var st = machine.vfs().stat(VPath.resolve(p.cwd(), Host.string(mem, a[0], a[1])));
            writeStatExt(mem, (int) a[2], st);
            return 0;
        });
        m.fn("fd_stat_ext", "ii", (i, mem, a) -> {
            var file = p.fds.file((int) a[0]);
            var st = file.stat();
            // files opened from a path report the attributes of that path (pipes and terminals have none)
            if (!file.path().isEmpty()) {
                try {
                    st = machine.vfs().rawStat(file.path()).withSize(st.size());
                } catch (FsException ignored) {
                }
            }
            writeStatExt(mem, (int) a[1], st);
            return 0;
        });
        m.fn("path_chmod", "iii", (i, mem, a) -> {
            machine.vfs().chmod(VPath.resolve(p.cwd(), Host.string(mem, a[0], a[1])), (int) a[2]);
            return 0;
        });
        m.fn("path_chown", "iiii", (i, mem, a) -> {
            machine.vfs().chown(VPath.resolve(p.cwd(), Host.string(mem, a[0], a[1])), (int) a[2], (int) a[3]);
            return 0;
        });
        m.fn("fd_chmod", "ii", (i, mem, a) -> {
            var path = p.fds.file((int) a[0]).path();
            if (path.isEmpty()) return Errno.EINVAL;
            machine.vfs().chmod(path, (int) a[1]);
            return 0;
        });
        m.fn("fd_chown", "iii", (i, mem, a) -> {
            var path = p.fds.file((int) a[0]).path();
            if (path.isEmpty()) return Errno.EINVAL;
            machine.vfs().chown(path, (int) a[1], (int) a[2]);
            return 0;
        });
        // access(): permission for the REAL user (what a setuid program's caller may do), mode R=4 W=2 X=1 (0: exists).
        m.fn("path_access", "iii", (i, mem, a) -> {
            var c = p.cred;
            var real = new dev.ccbaremetal.kernel.proc.Cred(c.uid(), c.uid(), c.gid(), c.gid(), c.groups());
            return dev.ccbaremetal.kernel.proc.Cred.as(real, () -> {
                var path = VPath.resolve(p.cwd(), Host.string(mem, a[0], a[1]));
                var st = machine.vfs().stat(path);
                if ((a[2] & 2) != 0 && machine.vfs().resolve(path).backend().isReadOnly()) return Errno.EROFS;
                dev.ccbaremetal.kernel.fs.Access.check(st, real, (int) a[2] & 7);
                return 0;
            });
        });
        m.fn("proc_flags", "i", (i, mem, a) -> {
            var old = (p.wantsEvents ? 1 : 0) | (p.inputEvents ? 2 : 0) | (p.catchSignals ? 4 : 0);
            var flags = (int) a[0];
            if (flags >= 0) {
                p.wantsEvents = (flags & 1) != 0;
                p.inputEvents = (flags & 2) != 0;
                p.catchSignals = (flags & 4) != 0;
                if (p.inputEvents) p.wantsEvents = true;
            }
            return old;
        });

        // ---- Terminal ----
        m.fn("tty_mode", "i", (i, mem, a) -> {
            var tty = p.ctty != null ? p.ctty : machine.tty();
            if (a[0] < 0) return tty.mode();
            p.touchedTty = true;
            return tty.setMode((int) a[0]);
        });
        m.fn("tty_size", "ii", (i, mem, a) -> {
            var size = (p.ctty != null ? p.ctty : machine.tty()).size();
            mem.writeI32((int) a[0], size[0]);
            mem.writeI32((int) a[1], size[1]);
            return 0;
        });
        m.fn("tty_setfg", "i", (i, mem, a) -> {
            (p.ctty != null ? p.ctty : machine.tty()).setForeground((int) a[0]);
            return 0;
        });
        m.fn("tty_getfg", ":i", (i, mem, a) -> (p.ctty != null ? p.ctty : machine.tty()).foreground());

        // ---- Cryptography (ssh): X25519, Ed25519, ChaCha20-Poly1305, SHA-256, HMAC-SHA-256 ----
        m.fn("crypto_x25519_keypair", "ii", (i, mem, a) -> {
            var kp = Crypto.x25519Keypair();
            mem.write((int) a[0], kp[0]);
            mem.write((int) a[1], kp[1]);
            return 0;
        });
        m.fn("crypto_x25519", "iii", (i, mem, a) -> {
            mem.write((int) a[2], Crypto.x25519(mem.readBytes((int) a[0], 32), mem.readBytes((int) a[1], 32)));
            return 0;
        });
        m.fn("crypto_ed25519_keypair", "ii", (i, mem, a) -> {
            var kp = Crypto.ed25519Keypair();
            mem.write((int) a[0], kp[0]);
            mem.write((int) a[1], kp[1]);
            return 0;
        });
        m.fn("crypto_ed25519_sign", "iiii", (i, mem, a) -> {
            mem.write((int) a[3], Crypto.ed25519Sign(mem.readBytes((int) a[0], 32), mem.readBytes((int) a[1], (int) a[2])));
            return 0;
        });
        // 0 when the signature is valid
        m.fn("crypto_ed25519_verify", "iiii", (i, mem, a) ->
            Crypto.ed25519Verify(mem.readBytes((int) a[0], 32), mem.readBytes((int) a[1], (int) a[2]), mem.readBytes((int) a[3], 64)) ? 0 : Errno.EACCES);
        // seal: out = ciphertext + 16-byte tag; open: out = plaintext, or EACCES if it was tampered with
        m.fn("crypto_seal", "iiiiiii", (i, mem, a) -> {
            var out = Crypto.seal(mem.readBytes((int) a[0], 32), mem.readBytes((int) a[1], 12), mem.readBytes((int) a[2], (int) a[3]),
                mem.readBytes((int) a[4], (int) a[5]));
            mem.write((int) a[6], out);
            return 0;
        });
        m.fn("crypto_open", "iiiiiii", (i, mem, a) -> {
            if (a[5] < 16) return Errno.EINVAL;
            var out = Crypto.open(mem.readBytes((int) a[0], 32), mem.readBytes((int) a[1], 12), mem.readBytes((int) a[2], (int) a[3]),
                mem.readBytes((int) a[4], (int) a[5]));
            if (out == null) return Errno.EACCES;
            mem.write((int) a[6], out);
            return 0;
        });
        m.fn("crypto_sha256", "iii", (i, mem, a) -> {
            mem.write((int) a[2], Crypto.sha256(mem.readBytes((int) a[0], (int) a[1])));
            return 0;
        });
        m.fn("crypto_hmac_sha256", "iiiii", (i, mem, a) -> {
            mem.write((int) a[4], Crypto.hmacSha256(mem.readBytes((int) a[0], (int) a[1]), mem.readBytes((int) a[2], (int) a[3])));
            return 0;
        });

        // ---- Pseudo-terminals (sshd) ----
        // [master fd, slave fd, id]; the owner gets ("pty", id) events when output is waiting or the session ended.
        m.fn("pty_open", "i", (i, mem, a) -> {
            p.wantsEvents = true;
            var pty = machine.openPty(p);
            var master = p.fds.add(pty.master(), 0);
            int slave;
            try {
                slave = p.fds.add(pty.slave.open(), 0);
            } catch (FsException e) {
                p.fds.close(master);
                throw e;
            }
            mem.writeI32((int) a[0], master);
            mem.writeI32((int) a[0] + 4, slave);
            mem.writeI32((int) a[0] + 8, pty.id());
            return 0;
        });
        m.fn("pty_resize", "iii", (i, mem, a) -> {
            var pty = dev.ccbaremetal.kernel.tty.Pty.of(p.fds.file((int) a[0]));
            if (pty == null) return Errno.ENOTTY;
            pty.slave.setSize((int) a[1], (int) a[2]);
            for (var q : machine.processes().all()) {
                if (q.ctty == pty.slave && q.state() == Process.State.RUNNING) q.deliver(Event.of("term_resize"));
            }
            return 0;
        });
        // ---- Screen lock, messages to every terminal ----
        // lock the screen for the caller's user (root: for `a[0]` if >= 0); only from the console
        m.fn("screen_lock", "i", (i, mem, a) -> {
            if (p.ctty != machine.tty()) return Errno.ENOTTY;
            machine.lockScreen(p.cred.uid() == 0 && a[0] >= 0 ? (int) a[0] : p.cred.uid());
            return 0;
        });
        // seconds < 0: the current setting (or -errno); else lock after that many seconds without input (0: never)
        m.fn("screen_autolock", "ii", (i, mem, a) -> {
            if (p.ctty != machine.tty()) return -Errno.ENOTTY;
            if (a[0] < 0) return machine.autoLockSeconds();
            machine.setAutoLock((int) a[0], p.cred.uid() == 0 && a[1] >= 0 ? (int) a[1] : p.cred.uid());
            return 0;
        });
        m.fn("screen_unlock", ":i", (i, mem, a) -> {
            if (!machine.screenLocked() || p.ctty != machine.lockTty() || p.cred.euid() != 0) return Errno.EPERM;
            machine.unlockScreen();
            return 0;
        });
        m.fn("tty_broadcast", "ii", (i, mem, a) -> {
            if (!atConsoleOrRoot(p)) return Errno.EPERM;
            machine.broadcast(Host.bytes(mem, a[0], a[1]));
            return 0;
        });

        // ---- Network (kernel/net/Net: stream sockets over modems) ----
        m.fn("net_listen", "ii", (i, mem, a) -> {
            var listener = machine.net().listen((int) a[0], p.cred.euid(), p.cred.isRoot());
            int fd;
            try {
                fd = p.fds.add(listener, 0);
            } catch (FsException e) {
                listener.release();
                throw e;
            }
            mem.writeI32((int) a[1], fd);
            return 0;
        });
        m.fn("net_accept", "iii", (i, mem, a) -> {
            if (!(p.fds.file((int) a[0]) instanceof dev.ccbaremetal.kernel.net.Net.Listener listener)) return Errno.ENOTSOCK;
            var conn = listener.accept((a[1] & 1) != 0);
            int fd;
            try {
                fd = p.fds.add(conn, 0);
            } catch (FsException e) {
                conn.release();
                throw e;
            }
            mem.writeI32((int) a[2], fd);
            return 0;
        });
        m.fn("net_connect", "iiiii", (i, mem, a) -> {
            var host = Host.string(mem, a[0], a[1]);
            var conn = machine.net().connect(host, (int) a[2], p.cred.euid(), a[3] <= 0 ? 10_000 : a[3]);
            int fd;
            try {
                fd = p.fds.add(conn, 0);
            } catch (FsException e) {
                conn.release();
                throw e;
            }
            mem.writeI32((int) a[4], fd);
            return 0;
        });
        m.fn("net_shutdown", "i", (i, mem, a) -> {
            if (!(p.fds.file((int) a[0]) instanceof dev.ccbaremetal.kernel.net.Net.Conn conn)) return Errno.ENOTSOCK;
            conn.shutdownWrite();
            return 0;
        });
        // JSON of one socket (state, local, lport, remote, rport, ...) in the pending buffer, or -errno
        m.fn("net_info", "i", (i, mem, a) -> {
            OpenFile file;
            try {
                file = p.fds.file((int) a[0]);
            } catch (FsException e) {
                return -e.errno();
            }
            var info = dev.ccbaremetal.kernel.net.Net.describe(file);
            if (info == null) return -Errno.ENOTSOCK;
            return pending(p, Json.write(info));
        });

        m.fn("term_palette", "ii", (i, mem, a) -> {
            if (a[0] < 0 || a[0] > 15) return Errno.EINVAL;
            machine.terminal().setPalette((int) a[0], (int) a[1]);
            return 0;
        });

        // ---- Events ----
        m.fn("event_wait", "iii", (i, mem, a) -> {
            p.wantsEvents = true;
            var filter = a[2] > 0 ? Host.string(mem, a[1], a[2]) : null;
            var event = p.events.take(filter, a[0] < 0 ? -1 : a[0]);
            if (event == null) return 0;
            return pending(p, Json.write(event.toArray(), p.objects::encode));
        });
        // Wait for one of the descriptors to become readable or for an event, whichever comes first (no polling):
        // 2 = descriptor number out[0] is ready (checked first), 1 = an event is waiting (take it with event_wait),
        // 0 = timeout.
        m.fn("event_wait_fds", "iiii", (i, mem, a) -> {
            p.wantsEvents = true;
            var notifier = machine.notifier();
            var deadline = a[2] < 0 ? Long.MAX_VALUE : System.nanoTime() + a[2] * 1_000_000L;
            while (true) {
                var seen = notifier.generation();
                // descriptors first: "is there output?" (timeout 0) must not be answered by a pending event
                for (var k = 0; k < a[1]; k++) {
                    var fd = mem.readInt((int) a[0] + 4 * k);
                    if (p.fds.file(fd).readReady()) {
                        mem.writeI32((int) a[3], fd);
                        return 2;
                    }
                }
                if (!p.events.isEmpty()) return 1;
                var left = deadline - System.nanoTime();
                if (left <= 0) return 0;
                p.checkKilled();
                notifier.await(seen, Math.min(left, 1_000_000_000L));
            }
        });
        m.fn("event_queue", "ii", (i, mem, a) -> {
            var parsed = Json.parseArgs(Host.string(mem, a[0], a[1]));
            if (parsed.isEmpty() || !(parsed.getFirst() instanceof String name)) return Errno.EINVAL;
            machine.queueEvent(new Event(name, parsed.subList(1, parsed.size()).toArray()));
            return 0;
        });
        m.fn("timer_start", "i", (i, mem, a) -> {
            p.wantsEvents = true;
            return machine.startTimer(p, a[0] / 1000.0);
        });
        m.fn("timer_cancel", "i", (i, mem, a) -> {
            machine.cancelTimer((int) a[0]);
            return 0;
        });
        m.fn("take", "ii", (i, mem, a) -> {
            var data = p.takePending();
            var n = (int) Math.min(data.length, a[1]);
            mem.write((int) a[0], data, 0, n);
            return n;
        });

        // ---- Graphics ----
        m.fn("gfx_target", "ii", (i, mem, a) -> {
            if (a[1] == 0) {
                synchronized (TARGETS) {
                    TARGETS.remove(p);
                }
                return 0;
            }
            var name = Host.string(mem, a[0], a[1]);
            var attached = machine.peripherals().get(name);
            if (attached == null) return Errno.ENODEV;
            if (!(attached.peripheral() instanceof FramebufferProvider provider)) return Errno.ENOTSUP;
            synchronized (TARGETS) {
                TARGETS.put(p, provider);
            }
            return 0;
        });
        m.fn("gfx_mode", "iiii", (i, mem, a) -> {
            var provider = target(p);
            if (provider == null && a[0] == 0) {
                machine.setDisplay(Machine.DisplayMode.TEXT, 0);
                return 0;
            }
            var fb = provider == null ? machine.framebuffer() : provider.framebuffer();
            if (!fb.setMode((int) a[0], (int) a[1], (int) a[2], (int) a[3] & 1)) return Errno.EINVAL;
            if (provider == null) {
                machine.setFramebufferCustom(true);
                machine.setDisplay(Machine.DisplayMode.GRAPHICS, p.pid);
            }
            else provider.framebufferChanged();
            return 0;
        });
        m.fn("gfx_info", "i", (i, mem, a) -> {
            var provider = target(p);
            var fb = fb(p);
            mem.writeI32((int) a[0], fb.width());
            mem.writeI32((int) a[0] + 4, fb.height());
            mem.writeI32((int) a[0] + 8, fb.bpp());
            mem.writeI32((int) a[0] + 12, provider == null && machine.display() == Machine.DisplayMode.GRAPHICS ? 1 : 0);
            return 0;
        });
        m.fn("gfx_palette", "iii", (i, mem, a) -> {
            var fb = fb(p);
            for (var k = 0; k < a[1]; k++) fb.setPalette((int) a[0] + k, mem.readInt((int) a[2] + k * 4));
            return changed(p);
        });
        m.fn("gfx_get_palette", "iii", (i, mem, a) -> {
            var fb = fb(p);
            for (var k = 0; k < a[1]; k++) mem.writeI32((int) a[2] + k * 4, fb.palette((int) a[0] + k));
            return 0;
        });
        m.fn("gfx_blit", "iiiiii", (i, mem, a) -> {
            var w = (int) a[2];
            var h = (int) a[3];
            var stride = (int) a[5];
            if (w <= 0 || h <= 0) return 0;
            if (stride < w) return Errno.EINVAL;
            var fb = fb(p);
            var data = Host.bytes(mem, a[4], ((long) stride * (h - 1) + w) * fb.bytesPerPixel());
            fb.blit(data, 0, stride, (int) a[0], (int) a[1], w, h);
            return changed(p);
        });
        m.fn("gfx_blit_key", "iiiiiii", (i, mem, a) -> {
            var w = (int) a[2];
            var h = (int) a[3];
            var stride = (int) a[5];
            if (w <= 0 || h <= 0) return 0;
            if (stride < w) return Errno.EINVAL;
            var fb = fb(p);
            var data = Host.bytes(mem, a[4], ((long) stride * (h - 1) + w) * fb.bytesPerPixel());
            fb.blitKeyed(data, 0, stride, (int) a[0], (int) a[1], w, h, (int) a[6]);
            return changed(p);
        });
        m.fn("gfx_read", "iiiiii", (i, mem, a) -> {
            var w = (int) a[2];
            var h = (int) a[3];
            var stride = (int) a[5];
            if (w <= 0 || h <= 0) return 0;
            var fb = fb(p);
            var bpb = fb.bytesPerPixel();
            var data = fb.read((int) a[0], (int) a[1], w, h);
            for (var y = 0; y < h; y++) mem.write((int) a[4] + y * stride * bpb, data, y * w * bpb, w * bpb);
            return 0;
        });
        m.fn("gfx_fill", "iiiii", (i, mem, a) -> {
            fb(p).fill((int) a[0], (int) a[1], (int) a[2], (int) a[3], (int) a[4]);
            return changed(p);
        });
        m.fn("gfx_clear", "i", (i, mem, a) -> {
            fb(p).clear((int) a[0]);
            return changed(p);
        });
        m.fn("gfx_pixel", "iii", (i, mem, a) -> {
            fb(p).setPixel((int) a[0], (int) a[1], (int) a[2]);
            return changed(p);
        });
        m.fn("gfx_get_pixel", "ii", (i, mem, a) -> fb(p).getPixel((int) a[0], (int) a[1]));
        m.fn("gfx_line", "iiiii", (i, mem, a) -> {
            fb(p).line((int) a[0], (int) a[1], (int) a[2], (int) a[3], (int) a[4]);
            return changed(p);
        });
        m.fn("gfx_rect", "iiiii", (i, mem, a) -> {
            fb(p).rect((int) a[0], (int) a[1], (int) a[2], (int) a[3], (int) a[4]);
            return changed(p);
        });
        m.fn("gfx_circle", "iiiii", (i, mem, a) -> {
            fb(p).circle((int) a[0], (int) a[1], (int) a[2], (int) a[3], a[4] != 0);
            return changed(p);
        });
        m.fn("gfx_text", "iiiiiii", (i, mem, a) -> {
            var text = Host.string(mem, a[2], a[3]);
            var end = fb(p).text((int) a[0], (int) a[1], text, (int) a[4], (int) a[5], (int) a[6]);
            changed(p);
            return end;
        });
        m.fn("gfx_scroll", "ii", (i, mem, a) -> {
            fb(p).scroll((int) a[0], (int) a[1]);
            return changed(p);
        });
        m.fn("gfx_present", ":i", (i, mem, a) -> {
            changed(p);
            fb(p).awaitPresented(100);
            return 0;
        });

        // ---- Peripherals ----
        m.fn("periph_list", ":i", (i, mem, a) -> pending(p, Json.write(machine.peripherals().describe(), p.objects::encode)));
        m.fn("periph_types", "ii", (i, mem, a) -> {
            try {
                return pending(p, Json.write(machine.peripherals().types(p, Host.string(mem, a[0], a[1])), p.objects::encode));
            } catch (PeripheralException e) {
                return -pending(p, e.getMessage());
            }
        });
        m.fn("periph_methods", "ii", (i, mem, a) -> {
            try {
                return pending(p, Json.write(machine.peripherals().methodNames(p, Host.string(mem, a[0], a[1])), p.objects::encode));
            } catch (PeripheralException e) {
                return -pending(p, e.getMessage());
            }
        });
        m.fn("periph_call", "iiiiii", (i, mem, a) -> {
            var name = Host.string(mem, a[0], a[1]);
            var method = Host.string(mem, a[2], a[3]);
            Object[] args;
            try {
                args = a[5] > 0 ? dev.ccbaremetal.kernel.periph.CcStrings.fromText(Json.parseArgs(Host.string(mem, a[4], a[5]), p.objects::get).toArray()) : new Object[0];
            } catch (IllegalArgumentException e) {
                return -pending(p, "bad JSON arguments: " + e.getMessage());
            }
            try {
                return pending(p, Json.write(machine.peripherals().call(p, name, method, args), p.objects::encode));
            } catch (PeripheralException e) {
                return -pending(p, e.getMessage());
            }
        });

        // ---- Lua ----
        m.fn("lua_eval", "iiii", (i, mem, a) -> {
            var code = Host.string(mem, a[0], a[1]);
            Object[] args;
            try {
                args = a[3] > 0 ? Json.parseArgs(Host.string(mem, a[2], a[3])).toArray() : new Object[0];
            } catch (IllegalArgumentException e) {
                return -pending(p, "bad JSON arguments: " + e.getMessage());
            }
            try {
                return pending(p, Json.write(LuaEval.forProcess(p).eval(code, args), p.objects::encode));
            } catch (LuaEval.EvalException e) {
                return -pending(p, e.getMessage());
            }
        });

        // ---- Objects returned by peripherals/APIs, and addon APIs ----
        m.fn("obj_call", "iiiii", (i, mem, a) -> {
            var target = p.objects.get((int) a[0]);
            if (target == null) return -pending(p, "object is no longer available");
            var method = Host.string(mem, a[1], a[2]);
            Object[] args;
            try {
                args = a[4] > 0 ? dev.ccbaremetal.kernel.periph.CcStrings.fromText(Json.parseArgs(Host.string(mem, a[3], a[4]), p.objects::get).toArray()) : new Object[0];
            } catch (IllegalArgumentException e) {
                return -pending(p, "bad JSON arguments: " + e.getMessage());
            }
            try {
                return pending(p, Json.write(dev.ccbaremetal.kernel.lua.LuaConvert.invoke(p, target, method, args), p.objects::encode));
            } catch (dan200.computercraft.api.lua.LuaException e) {
                return -pending(p, e.getMessage() == null ? "error" : e.getMessage());
            } catch (InterruptedException e) {
                p.checkKilled();
                return -pending(p, "Interrupted");
            } catch (RuntimeException e) {
                return -pending(p, "Java exception thrown: " + e);
            }
        });
        // Like obj_call, for methods that return bytes (an HTTP body, a file): the first result as raw bytes, without
        // JSON (which carries text). Returns length + 1 (then cc_take), 0 if the result is not a string (nil: EOF).
        m.fn("obj_call_raw", "iiiii", (i, mem, a) -> {
            var target = p.objects.get((int) a[0]);
            if (target == null) return -pending(p, "object is no longer available");
            var method = Host.string(mem, a[1], a[2]);
            Object[] args;
            try {
                args = a[4] > 0 ? dev.ccbaremetal.kernel.periph.CcStrings.fromText(Json.parseArgs(Host.string(mem, a[3], a[4]), p.objects::get).toArray()) : new Object[0];
            } catch (IllegalArgumentException e) {
                return -pending(p, "bad JSON arguments: " + e.getMessage());
            }
            try {
                var result = dev.ccbaremetal.kernel.lua.LuaConvert.invoke(p, target, method, args);
                var first = result.length > 0 ? result[0] : null;
                byte[] bytes;
                if (first instanceof byte[] b) bytes = b;
                else if (first instanceof java.nio.ByteBuffer b) {
                    bytes = new byte[b.remaining()];
                    b.duplicate().get(bytes);
                } else if (first instanceof String str) {
                    bytes = str.getBytes(str.chars().allMatch(c -> c <= 0xFF) ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
                } else {
                    return 0;
                }
                p.setPending(bytes);
                return bytes.length + 1;
            } catch (dan200.computercraft.api.lua.LuaException e) {
                return -pending(p, e.getMessage() == null ? "error" : e.getMessage());
            } catch (InterruptedException e) {
                p.checkKilled();
                return -pending(p, "Interrupted");
            } catch (RuntimeException e) {
                return -pending(p, "Java exception thrown: " + e);
            }
        });
        m.fn("obj_free", "i", (i, mem, a) -> {
            p.objects.free((int) a[0]);
            return 0;
        });
        m.fn("api_list", ":i", (i, mem, a) -> pending(p, Json.write(new java.util.ArrayList<>(machine.addonApis().all().keySet()))));
        m.fn("api_get", "ii", (i, mem, a) -> {
            var api = machine.addonApis().get(Host.string(mem, a[0], a[1]));
            var handle = api == null ? null : p.objects.handle(api);
            if (handle == null) return -pending(p, "no such API");
            return pending(p, Json.write(handle));
        });

        // ---- System ----
        // processors a program sees: the server's, at most cpus_per_process
        m.fn("cpu_count", ":i", (i, mem, a) -> Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), machine.config().cpus())));
        m.fn("sys_info", ":i", (i, mem, a) -> {
            var info = new LinkedHashMap<String, Object>();
            info.put("id", machine.id());
            info.put("label", machine.label());
            info.put("version", Machine.VERSION);
            info.put("uptime", machine.uptimeMillis() / 1000.0);
            info.put("day_time", machine.host().dayTime());
            info.put("epoch", System.currentTimeMillis());
            info.put("term_width", machine.terminal().width());
            info.put("term_height", machine.terminal().height());
            // The recommended graphics size (filling the text area with a common resolution), and the maximum.
            var size = machine.defaultFramebufferSize();
            info.put("screen_width", size[0]);
            info.put("screen_height", size[1]);
            info.put("max_screen_width", machine.framebuffer().maxWidth());
            info.put("max_screen_height", machine.framebuffer().maxHeight());
            info.put("cpus", Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), machine.config().cpus())));
            return pending(p, Json.write(info, p.objects::encode));
        });
        m.fn("sys_set_label", "ii", (i, mem, a) -> {
            machine.setLabel(a[1] == 0 ? null : Host.string(mem, a[0], a[1]));
            return 0;
        });
        m.fn("sys_shutdown", "i", (i, mem, a) -> {
            // root, or whoever sits at the computer (its console), as logind allows; not ssh users
            if (!atConsoleOrRoot(p)) return Errno.EPERM;
            if (a[0] != 0) machine.reboot();
            else machine.shutdownAsync();
            return 0;
        });
        m.fn("rs_get", "i", (i, mem, a) -> side(a[0]) ? machine.redstoneInput((int) a[0]) : -Errno.EINVAL);
        m.fn("rs_get_output", "i", (i, mem, a) -> side(a[0]) ? machine.redstoneOutput((int) a[0]) : -Errno.EINVAL);
        m.fn("rs_set", "ii", (i, mem, a) -> {
            if (!side(a[0])) return Errno.EINVAL;
            machine.setRedstoneOutput((int) a[0], (int) a[1]);
            return 0;
        });

        return m.build();
    }

    private static boolean side(long side) {
        return side >= 0 && side < 6;
    }

    private static FramebufferProvider target(Process p) {
        synchronized (TARGETS) {
            var provider = TARGETS.get(p);
            if (provider != null) {
                // Drop targets whose peripheral has been detached.
                var stillAttached = false;
                for (var a : p.machine().peripherals().list()) if (a.peripheral() == provider) stillAttached = true;
                if (!stillAttached) {
                    TARGETS.remove(p);
                    return null;
                }
            }
            return provider;
        }
    }

    private static Framebuffer fb(Process p) {
        var provider = target(p);
        return provider == null ? p.machine().framebuffer() : provider.framebuffer();
    }

    private static long changed(Process p) {
        var provider = target(p);
        if (provider != null) provider.framebufferChanged();
        return 0;
    }

    private static int pending(Process p, String text) {
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        p.setPending(bytes);
        return Math.max(bytes.length, 1);
    }
}
