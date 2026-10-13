package dev.ccbaremetal.kernel.fs;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.io.MemoryFile;
import dev.ccbaremetal.kernel.io.OpenFile;
import dev.ccbaremetal.kernel.proc.Process;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * /proc: read-only, generated on open. Process directories plus a few system files, and /proc/net/ccnet: the sockets
 * of the network stack, a header line then one line per socket: state, local name, local port, remote name, remote
 * port, uid, receive queue, send queue (bytes), separated by spaces (what ss and netstat show).
 */
public final class ProcBackend implements FsBackend {
    private static final List<String> SYSTEM = List.of("uptime", "version", "meminfo", "mounts", "loadavg", "computer");
    private static final List<String> PER_PROCESS = List.of("cmdline", "status", "cwd", "environ");

    private final Machine machine;

    public ProcBackend(Machine machine) {
        this.machine = machine;
    }

    private Process process(String id) {
        if (id.equals("self")) return Process.CURRENT.get();
        try {
            return machine.processes().get(Integer.parseInt(id));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final String NET = "net";
    private static final List<String> NET_FILES = List.of("ccnet");

    @Override
    public Stat stat(String path) throws FsException {
        if (path.isEmpty() || path.equals(NET)) return Stat.dir(0).withOwner(0555, 0, 0);
        if (path.startsWith(NET + "/") && NET_FILES.contains(path.substring(4))) {
            return Stat.file(content(path, null).length, 0).withOwner(0444, 0, 0);
        }
        var parts = path.split("/");
        if (parts.length == 1 && SYSTEM.contains(parts[0])) return Stat.file(content(parts[0], null).length, 0).withOwner(0444, 0, 0);
        var p = process(parts[0]);
        if (p == null) throw new FsException(Errno.ENOENT);
        if (parts.length == 1) return Stat.dir(p.startTime * 1_000_000L).withOwner(0555, p.cred.uid(), p.cred.gid());
        if (parts.length == 2 && PER_PROCESS.contains(parts[1])) {
            // the environment (passwords in variables...) and the folder are the owner's business, as on Linux
            var mode = parts[1].equals("environ") || parts[1].equals("cwd") ? 0400 : 0444;
            return Stat.file(content(parts[1], p).length, p.startTime * 1_000_000L).withOwner(mode, p.cred.uid(), p.cred.gid());
        }
        throw new FsException(Errno.ENOENT);
    }

    @Override
    public List<String> list(String path) throws FsException {
        if (path.isEmpty()) {
            var out = new ArrayList<>(SYSTEM);
            out.add(NET);
            out.add("self");
            for (var p : machine.processes().all()) out.add(Integer.toString(p.pid));
            return out;
        }
        if (path.equals(NET)) return NET_FILES;
        var parts = path.split("/");
        if (parts.length == 1 && process(parts[0]) != null) return PER_PROCESS;
        throw new FsException(stat(path).isDirectory() ? Errno.EIO : Errno.ENOTDIR);
    }

    @Override
    public OpenFile open(String path, String guestPath, int flags) throws FsException {
        if (OpenFlags.has(flags, OpenFlags.WRITE) || OpenFlags.has(flags, OpenFlags.CREATE)) throw new FsException(Errno.EACCES);
        if (path.startsWith(NET + "/") && NET_FILES.contains(path.substring(4))) return new MemoryFile(content(path, null), guestPath, 0);
        var parts = path.split("/");
        if (parts.length == 1 && SYSTEM.contains(parts[0])) return new MemoryFile(content(parts[0], null), guestPath, 0);
        var p = parts.length == 2 ? process(parts[0]) : null;
        if (p == null || !PER_PROCESS.contains(parts[1])) throw new FsException(Errno.ENOENT);
        return new MemoryFile(content(parts[1], p), guestPath, 0);
    }

    private byte[] content(String name, Process p) {
        var text = switch (name) {
            case "uptime" -> String.format("%.2f\n", machine.uptimeMillis() / 1000.0);
            case "version" -> "CC: Bare Metal kernel " + Machine.VERSION + " (Chicory WebAssembly, Java " + Runtime.version().feature() + ")\n";
            case "meminfo" -> {
                long used = 0;
                for (var proc : machine.processes().all()) used += machine.memoryOf(proc);
                var total = (long) machine.config().maxMemoryPages() * 65536L;
                yield "MemPerProcess: " + total / 1024 + " kB\nMemUsed: " + used / 1024 + " kB\nProcesses: "
                    + machine.processes().count() + "\n";
            }
            case "loadavg" -> machine.processes().count() + " processes\n";
            case "computer" -> "id " + machine.id() + "\nlabel " + (machine.label() == null ? "" : machine.label()) + "\n";
            case "mounts" -> {
                var sb = new StringBuilder();
                for (var m : machine.vfs().mounts()) {
                    // type, mount point, ro/rw, capacity and free bytes (0 for file systems without a size)
                    var backend = m.backend();
                    sb.append(backend.describe()).append(' ').append(m.path()).append(' ')
                        .append(backend.isReadOnly() ? "ro" : "rw").append(' ').append(backend.capacity()).append(' ')
                        .append(backend.capacity() > 0 ? backend.freeSpace() : 0).append('\n');
                }
                yield sb.toString();
            }
            case "net/ccnet" -> {
                var sb = new StringBuilder("state local lport remote rport uid recvq sendq\n");
                for (var m : machine.net().describe()) {
                    sb.append(m.get("state")).append(' ').append(m.get("local")).append(' ').append(m.get("lport")).append(' ')
                        .append(m.get("remote")).append(' ').append(m.get("rport")).append(' ').append(m.get("uid")).append(' ')
                        .append(m.getOrDefault("recvq", m.getOrDefault("queue", 0))).append(' ').append(m.getOrDefault("sendq", 0)).append('\n');
                }
                yield sb.toString();
            }
            case "cmdline" -> String.join("\0", p.argv) + "\0";
            case "status" -> "Name:\t" + p.name + "\nState:\t" + (p.state() == Process.State.RUNNING ? "R (running)" : "Z (zombie)")
                + "\nPid:\t" + p.pid + "\nPPid:\t" + p.ppid + "\nPgid:\t" + p.pgid
                + "\nUid:\t" + p.cred.uid() + "\t" + p.cred.euid() + "\nGid:\t" + p.cred.gid() + "\t" + p.cred.egid()
                + "\nVmSize:\t" + machine.memoryOf(p) / 1024
                + " kB\nStarted:\t" + p.startTime + "\n";
            case "cwd" -> p.cwd() + "\n";
            case "environ" -> {
                var sb = new StringBuilder();
                for (var e : p.env().entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\0');
                yield sb.toString();
            }
            default -> "";
        };
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String describe() {
        return "proc";
    }
}
