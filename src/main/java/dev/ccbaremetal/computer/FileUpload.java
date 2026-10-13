package dev.ccbaremetal.computer;

import dev.ccbaremetal.BareMetalConfig;
import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.VPath;
import dev.ccbaremetal.network.Payloads;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Files and folders dropped on a computer window from the player's desktop, written into the working directory of
 * the foreground program (the shell's current folder at the prompt). Two steps: the client announces names and
 * sizes and learns which files already exist (to ask before overwriting), then sends the contents in chunks. Limited
 * by {@code upload_max_kb}; only one upload per player at a time; server thread only.
 */
public final class FileUpload {
    private record Pending(int containerId, int id, String dir, dev.ccbaremetal.kernel.proc.Cred cred, List<String> paths, List<Long> sizes,
                           long total, ByteArrayOutputStream data) {
    }

    private final ComputerCore core;
    private final Map<UUID, Pending> pending = new HashMap<>();

    FileUpload(ComputerCore core) {
        this.core = core;
    }

    /** {@code player}: who uploads (one upload each); {@code reply}: sends the answer to that player. */
    public void start(UUID player, Payloads.UploadStart p, Consumer<Payloads.UploadReply> reply) {
        pending.remove(player);
        var m = core.machine();
        if (m == null || !m.isOn()) {
            fail(reply, p.uploadId(), "the computer is off");
            return;
        }
        var limit = (long) BareMetalConfig.get(BareMetalConfig.UPLOAD_MAX_KB) * 1024;
        if (limit <= 0) {
            fail(reply, p.uploadId(), "uploads are disabled on this server");
            return;
        }
        if (p.paths().size() != p.sizes().size() || p.paths().isEmpty()) {
            fail(reply, p.uploadId(), "nothing to upload");
            return;
        }
        long total = 0;
        for (var i = 0; i < p.paths().size(); i++) {
            var path = p.paths().get(i);
            var size = p.sizes().get(i);
            if (!validPath(path) || size < 0) {
                fail(reply, p.uploadId(), "bad file name: " + path);
                return;
            }
            total += size;
        }
        if (total > limit) {
            fail(reply, p.uploadId(), "too big: " + kib(total) + " (the limit is " + kib(limit) + ")");
            return;
        }
        var owner = foregroundProcess(m);
        if (owner != null && owner.cred.isRoot() && owner.name.equals("login")) {
            fail(reply, p.uploadId(), "log in first");
            return;
        }
        var cred = owner != null ? owner.cred : dev.ccbaremetal.kernel.proc.Cred.of(Machine.DEFAULT_UID, Machine.DEFAULT_UID, new int[]{Machine.DEFAULT_UID});
        var dir = owner != null && m.vfs().isDirectory(owner.cwd()) ? owner.cwd() : "/home/user";
        var conflicts = new ArrayList<String>();
        for (var path : p.paths()) {
            if (path.endsWith("/")) continue;
            var abs = VPath.join(dir, path);
            if (m.vfs().exists(abs)) conflicts.add(path);
        }
        pending.put(player, new Pending(p.containerId(), p.uploadId(), dir, cred, p.paths(), p.sizes(), total, new ByteArrayOutputStream()));
        reply.accept(new Payloads.UploadReply(p.uploadId(), Payloads.UPLOAD_READY, dir, conflicts, 0, ""));
    }

    public void data(UUID player, Payloads.UploadData p, Consumer<Payloads.UploadReply> reply) {
        var up = pending.get(player);
        if (up == null || up.id() != p.uploadId()) return;
        if (up.data().size() + p.data().length > up.total()) {
            pending.remove(player);
            fail(reply, up.id(), "more data than announced");
            return;
        }
        up.data().writeBytes(p.data());
        if (!p.last()) return;
        pending.remove(player);
        if (up.data().size() != up.total()) {
            fail(reply, up.id(), "upload incomplete");
            return;
        }
        var m = core.machine();
        if (m == null || !m.isOn()) {
            fail(reply, up.id(), "the computer is off");
            return;
        }
        try {
            // written with the permissions of whoever uses the computer, as if they had copied the files themselves
            var count = dev.ccbaremetal.kernel.proc.Cred.as(up.cred(), () -> write(m, up, p.overwrite()));
            reply.accept(new Payloads.UploadReply(up.id(), Payloads.UPLOAD_DONE, up.dir(), List.of(), count, ""));
        } catch (FsException e) {
            fail(reply, up.id(), Errno.describe(e.errno()));
        }
    }

    /** The player closed the window: forget a half-sent upload. */
    void forget(UUID player) {
        pending.remove(player);
    }

    private static int write(Machine m, Pending up, boolean overwrite) throws FsException {
        var bytes = up.data().toByteArray();
        var offset = 0;
        var count = 0;
        for (var i = 0; i < up.paths().size(); i++) {
            var path = up.paths().get(i);
            var size = (int) (long) up.sizes().get(i);
            var abs = VPath.join(up.dir(), path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
            if (path.endsWith("/")) {
                m.vfs().mkdirs(abs);
                continue;
            }
            var content = java.util.Arrays.copyOfRange(bytes, offset, offset + size);
            offset += size;
            m.vfs().mkdirs(VPath.parent(abs));
            if (m.vfs().exists(abs)) {
                if (!overwrite || m.vfs().isDirectory(abs)) continue;
            }
            m.vfs().writeAll(abs, content);
            // programs arrive runnable: desktops (Windows) often cannot say a file is executable
            if (isProgram(content)) m.vfs().chmod(abs, 0755);
            count++;
        }
        return count;
    }

    /** A WebAssembly program (not an object file: those have a "linking" section) or a #! script. */
    static boolean isProgram(byte[] content) {
        if (content.length >= 2 && content[0] == '#' && content[1] == '!') return true;
        if (content.length < 8 || content[0] != 0 || content[1] != 'a' || content[2] != 's' || content[3] != 'm') return false;
        var i = 8;
        while (i < content.length) {
            var id = content[i++] & 0xFF;
            long size = 0;
            for (var shift = 0; i < content.length; shift += 7) {
                var b = content[i++];
                size |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
            }
            if (id == 0 && i < content.length) {
                var nameLength = content[i] & 0x7F; // custom section names are short: one LEB byte
                if (nameLength == 7 && i + 8 <= content.length
                    && new String(content, i + 1, 7, java.nio.charset.StandardCharsets.US_ASCII).equals("linking")) {
                    return false;
                }
            }
            i += (int) size;
        }
        return true;
    }

    /** The foreground program (its group leader): files go to its folder, written with its permissions. */
    private static dev.ccbaremetal.kernel.proc.Process foregroundProcess(Machine m) {
        var fg = m.foreground();
        dev.ccbaremetal.kernel.proc.Process best = null;
        for (var p : m.processes().all()) {
            if (p.pgid != fg || p.state() != dev.ccbaremetal.kernel.proc.Process.State.RUNNING) continue;
            if (best == null || p.pid == fg) best = p;
        }
        return best;
    }

    /** Relative, no "..", no empty or control-character names: a dropped file cannot land outside the target. */
    private static boolean validPath(String path) {
        if (path.isEmpty() || path.startsWith("/") || path.length() > 1024) return false;
        var body = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        for (var part : body.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
            for (var i = 0; i < part.length(); i++) if (part.charAt(i) < 0x20 || part.charAt(i) == 0x7f) return false;
        }
        return true;
    }

    private static void fail(Consumer<Payloads.UploadReply> reply, int id, String message) {
        reply.accept(new Payloads.UploadReply(id, Payloads.UPLOAD_FAILED, "", List.of(), 0, message));
    }

    private static String kib(long bytes) {
        return bytes >= 1 << 20 ? String.format("%.1f MiB", bytes / 1048576.0) : (bytes + 1023) / 1024 + " KiB";
    }
}
