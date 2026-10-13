package dev.ccbaremetal.kernel.periph;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.kernel.fs.FileType;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.Stat;
import dev.ccbaremetal.kernel.io.MemoryFile;
import dev.ccbaremetal.kernel.io.OpenFile;
import dev.ccbaremetal.kernel.proc.Process;
import dev.ccbaremetal.kernel.sys.Json;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * /dev/periph: every peripheral of every mod as files, without per-mod code (the bus view; peripherals a driver
 * knows also get Linux-style devices, see {@code dev.DeviceTree}):
 * <pre>
 *   /dev/periph/NAME/METHOD    read: call it without arguments; write "ARGS" (words or a JSON array), then read
 *                              the result: one JSON array per call. Errors go to stderr and fail the write.
 *                              From the shell: echo 5 6 > getPixel; cat getPixel (the result of the last write is
 *                              kept for 10 seconds for the next reader).
 *   /dev/periph/NAME/type      its types, one per line
 *   /dev/periph/NAME/events    a stream of its events (first argument = NAME), one JSON array per line
 *   /dev/periph/events         every event of the computer
 * </pre>
 * Nothing runs unless a file is open: names are looked up when /dev is listed or a file opened.
 */
public final class PeriphFiles {
    private final Machine machine;
    private final Set<EventStream> streams = new CopyOnWriteArraySet<>();

    /** Open files of each peripheral, revoked when it goes away (weak: closed files just disappear). */
    private final Map<String, Set<OpenFile>> openFiles = new java.util.HashMap<>();
    private Map<String, List<String>> typeCache;
    /** Result of the last call made by writing arguments, for a following read from the shell (10 s). */
    private final Map<String, Object[]> lastResults = new java.util.concurrent.ConcurrentHashMap<>();
    private long typeCacheTime;

    public PeriphFiles(Machine machine) {
        this.machine = machine;
    }

    private static Process caller() {
        return Process.CURRENT.get();
    }

    // ---- Names ------------------------------------------------------------------------------------------------------

    /** Every reachable peripheral (on a side, or on a wired network) with its types; cached for a second. */
    public synchronized Map<String, List<String>> all() {
        var now = System.currentTimeMillis();
        if (typeCache != null && now - typeCacheTime < 1000) return typeCache;
        var out = new LinkedHashMap<String, List<String>>();
        var peripherals = machine.peripherals();
        for (var a : peripherals.list()) out.put(a.name(), a.types());
        var caller = caller();
        if (caller != null) {
            for (var a : peripherals.list()) {
                if (!a.methods().containsKey("getNamesRemote")) continue;
                try {
                    var names = peripherals.call(caller, a.name(), "getNamesRemote", new Object[0]);
                    if (names.length > 0 && names[0] instanceof Map<?, ?> map) {
                        for (var v : map.values()) {
                            var name = String.valueOf(v);
                            if (!out.containsKey(name)) out.put(name, peripherals.types(caller, name));
                        }
                    }
                } catch (PeripheralException ignored) {
                }
            }
        }
        var sorted = new LinkedHashMap<String, List<String>>();
        out.keySet().stream().sorted().forEach(k -> sorted.put(k, out.get(k)));
        typeCache = sorted;
        typeCacheTime = now;
        return sorted;
    }

    // ---- /dev/periph ------------------------------------------------------------------------------------------------

    /** Is {@code path} (relative to /dev) a directory of ours? */
    public boolean isDirectory(String path) {
        if (path.equals("periph")) return true;
        if (path.startsWith("periph/") && path.indexOf('/', 7) < 0) return all().containsKey(path.substring(7));
        return false;
    }

    public boolean isFile(String path) {
        if (path.equals("periph/events")) return true;
        if (path.startsWith("periph/")) {
            var rest = path.substring(7);
            var slash = rest.indexOf('/');
            if (slash < 0) return false;
            var name = rest.substring(0, slash);
            var file = rest.substring(slash + 1);
            if (!all().containsKey(name)) return false;
            return file.equals("type") || file.equals("events") || methods(name).contains(file);
        }
        return false;
    }

    public List<String> list(String path) {
        if (path.equals("periph")) {
            var out = new ArrayList<>(all().keySet());
            out.add("events");
            return out;
        }
        var name = path.substring(7);
        var out = new ArrayList<String>();
        out.add("type");
        out.add("events");
        out.addAll(methods(name));
        return out;
    }

    private List<String> methods(String name) {
        try {
            var caller = caller();
            if (caller == null) {
                var a = machine.peripherals().get(name);
                return a == null ? List.of() : new ArrayList<>(a.methods().keySet());
            }
            return machine.peripherals().methodNames(caller, name);
        } catch (PeripheralException e) {
            return List.of();
        }
    }

    public OpenFile open(String path) throws FsException {
        var file = openNode(path);
        var name = path.indexOf('/', 7) < 0 ? null : path.substring(7, path.indexOf('/', 7));
        if (name != null) {
            synchronized (openFiles) {
                openFiles.computeIfAbsent(name, k -> java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>())).add(file);
            }
        }
        return file;
    }

    /** The peripheral called {@code name} was detached: its open files fail with ENODEV from now on, as on Linux. */
    public void revoke(String name) {
        Set<OpenFile> files;
        synchronized (openFiles) {
            files = openFiles.remove(name);
        }
        if (files == null) return;
        for (var f : List.copyOf(files)) f.revoke(Errno.ENODEV);
        machine.notifier().signal(); // wake poll()
    }

    private OpenFile openNode(String path) throws FsException {
        var guest = "/dev/" + path;
        if (path.equals("periph/events")) return stream(null, guest);
        var rest = path.substring(7);
        var slash = rest.indexOf('/');
        if (slash < 0) throw new FsException(Errno.EISDIR);
        var name = rest.substring(0, slash);
        var file = rest.substring(slash + 1);
        if (!all().containsKey(name)) throw new FsException(Errno.ENOENT);
        if (file.equals("type")) {
            return new MemoryFile((String.join("\n", all().get(name)) + "\n").getBytes(StandardCharsets.UTF_8), guest, 0);
        }
        if (file.equals("events")) return stream(name, guest);
        if (!methods(name).contains(file)) throw new FsException(Errno.ENOENT);
        return new MethodFile(name, file, guest);
    }

    // ---- Events -----------------------------------------------------------------------------------------------------

    private OpenFile stream(String name, String guest) {
        var s = new EventStream(name, caller(), guest);
        streams.add(s);
        return s;
    }

    /** Every machine event passes here; only open event files cost anything. */
    public void onEvent(Event event) {
        // a side peripheral or one on a wired network went away
        if (event.name().equals("peripheral_detach") && event.args().length > 0) revoke(String.valueOf(event.args()[0]));
        if (streams.isEmpty()) return;
        for (var s : streams) {
            if (s.name == null || (event.args().length > 0 && s.name.equals(event.args()[0]))) s.push(event);
        }
        machine.notifier().signal();
    }

    private final class EventStream extends OpenFile {
        final String name;
        final Process owner;
        final String guest;
        final ByteArrayOutputStream queue = new ByteArrayOutputStream();

        EventStream(String name, Process owner, String guest) {
            this.name = name;
            this.owner = owner;
            this.guest = guest;
        }

        synchronized void push(Event event) {
            var line = (owner == null ? Json.write(event.toArray()) : Json.write(event.toArray(), owner.objects::encode)) + "\n";
            if (queue.size() > 256 * 1024) queue.reset(); // nobody reads: drop instead of growing
            queue.writeBytes(line.getBytes(StandardCharsets.UTF_8));
            notifyAll();
        }

        @Override
        public FileType type() {
            return FileType.CHARACTER_DEVICE;
        }

        @Override
        public Stat stat() {
            return Stat.device();
        }

        @Override
        public synchronized int read(ByteBuffer dst, boolean nonBlocking) throws FsException, InterruptedException {
            // events queued before the peripheral went away are still delivered, then the error
            while (queue.size() == 0) {
                checkRevoked();
                if (nonBlocking) throw new FsException(Errno.EAGAIN);
                wait(250);
            }
            var data = queue.toByteArray();
            var n = Math.min(dst.remaining(), data.length);
            dst.put(data, 0, n);
            queue.reset();
            queue.write(data, n, data.length - n);
            return n;
        }

        @Override
        public synchronized boolean readReady() {
            return queue.size() > 0 || revoked();
        }

        @Override
        protected void onRevoke() {
            streams.remove(this);
        }

        @Override
        protected void onClose() {
            streams.remove(this);
        }

        @Override
        public String path() {
            return guest;
        }
    }

    // ---- Method calls -----------------------------------------------------------------------------------------------

    /** One bare word: a number, true/false/nil, or else a string. */
    private static Object word(String w) {
        switch (w) {
            case "true":
                return true;
            case "false":
                return false;
            case "nil", "null":
                return null;
            default:
                try {
                    return Double.parseDouble(w);
                } catch (NumberFormatException e) {
                    return w;
                }
        }
    }

    /** Command-line style arguments: numbers, true/false/nil, JSON values, "quoted" or bare strings. */
    static Object[] parseArguments(String text) {
        var trimmed = text.strip();
        if (trimmed.startsWith("[")) {
            try {
                return Json.parseArgs(trimmed).toArray();
            } catch (IllegalArgumentException ignored) {
            }
            // Not JSON, but a flat list such as [1, привет] (the shell ate the inner quotes): items as words.
            if (trimmed.endsWith("]") && trimmed.indexOf('[', 1) < 0 && trimmed.indexOf('{') < 0) {
                var out = new ArrayList<>();
                for (var item : trimmed.substring(1, trimmed.length() - 1).split(",")) {
                    var w = item.strip();
                    if (w.isEmpty()) continue;
                    if (w.length() >= 2 && w.startsWith("\"") && w.endsWith("\"")) out.add(w.substring(1, w.length() - 1));
                    else out.add(word(w));
                }
                return out.toArray();
            }
        }
        var out = new ArrayList<>();
        var i = 0;
        while (i < trimmed.length()) {
            var c = trimmed.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            var start = i;
            if (c == '{' || c == '[') {
                var depth = 0;
                var inString = false;
                for (; i < trimmed.length(); i++) {
                    var ch = trimmed.charAt(i);
                    if (inString) {
                        if (ch == '\\') i++;
                        else if (ch == '"') inString = false;
                    } else if (ch == '"') {
                        inString = true;
                    } else if (ch == '{' || ch == '[') {
                        depth++;
                    } else if ((ch == '}' || ch == ']') && --depth == 0) {
                        i++;
                        break;
                    }
                }
                out.add(Json.toCc(Json.parse(trimmed.substring(start, i))));
                continue;
            }
            if (c == '"') {
                var sb = new StringBuilder();
                for (i++; i < trimmed.length() && trimmed.charAt(i) != '"'; i++) {
                    var ch = trimmed.charAt(i);
                    if (ch == '\\' && i + 1 < trimmed.length()) {
                        var e = trimmed.charAt(++i);
                        sb.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
                    } else {
                        sb.append(ch);
                    }
                }
                i++;
                out.add(sb.toString());
                continue;
            }
            while (i < trimmed.length() && !Character.isWhitespace(trimmed.charAt(i))) i++;
            out.add(word(trimmed.substring(start, i)));
        }
        return out.toArray();
    }

    private final class MethodFile extends OpenFile {
        private final String name;
        private final String method;
        private final String guest;
        private final StringBuilder pending = new StringBuilder();
        private byte[] result;
        private int resultPos;
        private boolean called;

        MethodFile(String name, String method, String guest) {
            this.name = name;
            this.method = method;
            this.guest = guest;
        }

        /** Run one call; the result becomes readable. Returns false (message on stderr) on failure. */
        private boolean call(String args) throws InterruptedException {
            called = true;
            var p = caller();
            if (p == null) return false;
            try {
                var values = machine.peripherals().call(p, name, method, CcStrings.fromText(parseArguments(args)));
                var text = Json.write(values, p.objects::encode) + "\n";
                append(text);
                if (!args.isBlank()) lastResults.put(name + "/" + method, new Object[]{System.currentTimeMillis(), text});
                return true;
            } catch (PeripheralException | IllegalArgumentException e) {
                var message = e instanceof IllegalArgumentException
                    ? "bad arguments (" + e.getMessage() + "): use words (1 2 \"text\") or one JSON array in single quotes: echo '[1, \"text\"]'"
                    : e.getMessage();
                p.stderr(name + "." + method + ": " + message + "\n");
                append("{\"error\":" + Json.write(e.getMessage()) + "}\n");
                return false;
            }
        }

        private void append(String text) {
            var bytes = text.getBytes(StandardCharsets.UTF_8);
            if (result == null || resultPos >= result.length) {
                result = bytes;
                resultPos = 0;
            } else {
                var merged = new byte[result.length - resultPos + bytes.length];
                System.arraycopy(result, resultPos, merged, 0, result.length - resultPos);
                System.arraycopy(bytes, 0, merged, result.length - resultPos, bytes.length);
                result = merged;
                resultPos = 0;
            }
        }

        @Override
        public FileType type() {
            return FileType.CHARACTER_DEVICE;
        }

        @Override
        public Stat stat() {
            return Stat.device();
        }

        @Override
        public synchronized int write(ByteBuffer src, boolean nonBlocking) throws FsException, InterruptedException {
            checkRevoked();
            var n = src.remaining();
            var bytes = new byte[n];
            src.get(bytes);
            pending.append(new String(bytes, StandardCharsets.UTF_8));
            var ok = true;
            int nl;
            while ((nl = pending.indexOf("\n")) >= 0) {
                var line = pending.substring(0, nl);
                pending.delete(0, nl + 1);
                ok &= call(line);
            }
            if (!ok) throw new FsException(Errno.EIO);
            return n;
        }

        @Override
        public synchronized int read(ByteBuffer dst, boolean nonBlocking) throws FsException, InterruptedException {
            checkRevoked();
            if (!pending.isEmpty()) {
                var line = pending.toString();
                pending.setLength(0);
                call(line);
            } else if (!called) {
                called = true;
                var last = lastResults.remove(name + "/" + method);
                if (last != null && System.currentTimeMillis() - (long) last[0] < 10_000) append((String) last[1]);
                else call("");
            }
            if (result == null || resultPos >= result.length) return 0;
            var n = Math.min(dst.remaining(), result.length - resultPos);
            dst.put(result, resultPos, n);
            resultPos += n;
            return n;
        }

        @Override
        protected synchronized void onClose() {
            if (revoked() || pending.isEmpty() || pending.toString().isBlank()) return;
            try {
                call(pending.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public String path() {
            return guest;
        }
    }
}
