package dev.ccbaremetal.kernel.lua;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.OpenFlags;
import dev.ccbaremetal.kernel.fs.VPath;
import dev.ccbaremetal.kernel.gfx.Framebuffer;
import dev.ccbaremetal.kernel.gfx.FramebufferProvider;
import dev.ccbaremetal.kernel.io.OpenFile;
import dev.ccbaremetal.kernel.io.Pipe;
import dev.ccbaremetal.kernel.periph.PeripheralException;
import dev.ccbaremetal.kernel.proc.Process;
import dev.ccbaremetal.kernel.tty.RawText;
import dev.ccbaremetal.kernel.tty.Terminal;
import org.squiddev.cobalt.Constants;
import org.squiddev.cobalt.LuaError;
import org.squiddev.cobalt.LuaState;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaValue;
import org.squiddev.cobalt.ValueFactory;
import org.squiddev.cobalt.Varargs;
import org.squiddev.cobalt.function.LibFunction;
import org.squiddev.cobalt.function.RegisteredFunction;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The native CC APIs (term, fs, os, peripheral, redstone) implemented on our kernel, plus our extensions
 * (gfx, wasm). With CC's own bios.lua and ROM on top, this is a complete CraftOS running inside the WASM machine.
 */
public final class CcApi {
    public static final String HOST = "ComputerCraft 1.120.0 (CC: Bare Metal " + Machine.VERSION + ")";

    private final Process process;
    private final Machine machine;
    private final boolean blockingEvents;
    private final Map<Process, FramebufferProvider> target = new HashMap<>();

    private CcApi(Process process, boolean blockingEvents) {
        this.process = process;
        this.machine = process.machine();
        this.blockingEvents = blockingEvents;
    }

    /**
     * Install all APIs into {@code globals}. With {@code blockingEvents}, os.pullEvent blocks the Java thread (used
     * for lua_eval); otherwise bios.lua implements it with coroutine.yield, like real CC.
     */
    public static void install(LuaState state, LuaTable globals, Process process, boolean blockingEvents) throws LuaError {
        var api = new CcApi(process, blockingEvents);
        globals.rawset("_HOST", ValueFactory.valueOf(HOST));
        globals.rawset("term", api.term());
        globals.rawset("fs", api.fs());
        var os = globals.rawget("os") instanceof LuaTable t ? t : new LuaTable();
        api.os(os);
        globals.rawset("os", os);
        globals.rawset("peripheral", api.peripheral());
        var rs = api.redstone();
        globals.rawset("redstone", rs);
        globals.rawset("rs", rs);
        globals.rawset("gfx", api.gfx());
        globals.rawset("wasm", api.wasm());
        // Global APIs registered by addons (ComputerCraftAPI.registerAPIFactory), as on a CC computer.
        for (var entry : api.machine.addonApis().all().entrySet()) {
            if (!globals.rawget(entry.getKey()).isNil()) continue;
            var value = LuaConvert.toLua(process, entry.getValue());
            if (!value.isNil()) globals.rawset(entry.getKey(), value);
        }
        // APIs of the computer itself (a turtle's "turtle"): globals, as CC's own turtle API is.
        for (var attached : api.machine.peripherals().list()) {
            if (!(attached.peripheral() instanceof dev.ccbaremetal.kernel.periph.HostApi)) continue;
            var name = attached.name();
            var table = new LuaTable();
            for (var method : attached.methods().keySet()) {
                fn(table, method, (s, a) -> {
                    try {
                        return LuaConvert.toLua(process, api.machine.peripherals().call(process, name, method, LuaConvert.fromLuaArgs(a).raw()));
                    } catch (PeripheralException e) {
                        throw new LuaError(e.getMessage());
                    }
                });
            }
            globals.rawset(name, table);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static void fn(LuaTable table, String name, LibFunction.ManyArgs body) {
        table.rawset(name, RegisteredFunction.ofV(name, body).create());
    }

    private static LuaValue str(String s) {
        return LuaText.encode(s);
    }

    private static String checkString(Varargs args, int i) throws LuaError {
        var v = args.arg(i);
        if (!v.isString()) throw new LuaError("bad argument #" + i + " (string expected, got " + v.typeName() + ")");
        return LuaText.decode(v);
    }

    private static int checkInt(Varargs args, int i) throws LuaError {
        var v = args.arg(i);
        if (!v.isNumber()) throw new LuaError("bad argument #" + i + " (number expected, got " + v.typeName() + ")");
        var d = v.toDouble();
        if (Double.isNaN(d)) throw new LuaError("bad argument #" + i + " (number expected, got nan)");
        return (int) Math.floor(d);
    }

    private static int optInt(Varargs args, int i, int def) throws LuaError {
        return args.arg(i).isNil() ? def : checkInt(args, i);
    }

    private static double checkDouble(Varargs args, int i) throws LuaError {
        var v = args.arg(i);
        if (!v.isNumber()) throw new LuaError("bad argument #" + i + " (number expected, got " + v.typeName() + ")");
        return v.toDouble();
    }

    /** CC colour bitmask (1, 2, 4, ... 32768) to palette index 0-15. */
    private static int colour(Varargs args, int i) throws LuaError {
        var value = checkInt(args, i);
        if (value <= 0) throw new LuaError("Colour out of range");
        var index = 31 - Integer.numberOfLeadingZeros(value);
        if (index > 15) throw new LuaError("Colour out of range");
        return index;
    }

    private static LuaValue bit(int index) {
        return ValueFactory.valueOf(1 << index);
    }

    private static Varargs fsError(FsException e) {
        return ValueFactory.varargsOf(Constants.NIL, str(e.getMessage()));
    }

    // ---- term -----------------------------------------------------------------------------------------------------

    /**
     * An event as CC Lua programs expect it. A typed character outside CC's charset becomes a "paste" of its UTF-8
     * bytes: read() and edit insert one byte per "char" event, but whole strings for "paste" (like the Unicode
     * support for CC's own computers does).
     */
    static Event forLua(Event event) {
        if (event.name().equals("char") && event.args().length > 0 && event.args()[0] instanceof String s
            && s.chars().anyMatch(c -> c > 0xFF)) {
            return new Event("paste", new Object[]{s});
        }
        return event;
    }

    private LuaTable term() {
        var t = new LuaTable();
        var term = machine.terminal();
        // One byte per cell, as on CC (the ROM counts positions in bytes); UTF-8 is compacted when shown (RawText).
        fn(t, "write", (s, a) -> {
            var cells = RawText.cells(LuaText.bytes(a.arg(1).isNil() ? ValueFactory.valueOf("nil") : a.arg(1).toLuaString()));
            term.write(cells, 0, cells.length);
            return Constants.NONE;
        });
        fn(t, "blit", (s, a) -> {
            var text = LuaText.bytes(a.arg(1));
            var fg = LuaText.bytes(a.arg(2));
            var bg = LuaText.bytes(a.arg(3));
            if (fg.length != text.length || bg.length != text.length) throw new LuaError("Arguments must be the same length");
            term.blit(RawText.cells(text), fg, bg);
            return Constants.NONE;
        });
        fn(t, "scroll", (s, a) -> {
            term.scroll(checkInt(a, 1));
            return Constants.NONE;
        });
        fn(t, "getCursorPos", (s, a) -> ValueFactory.varargsOf(ValueFactory.valueOf(term.cursorX() + 1), ValueFactory.valueOf(term.cursorY() + 1)));
        fn(t, "setCursorPos", (s, a) -> {
            term.setCursor(checkInt(a, 1) - 1, checkInt(a, 2) - 1);
            return Constants.NONE;
        });
        fn(t, "getCursorBlink", (s, a) -> ValueFactory.valueOf(term.cursorBlink()));
        fn(t, "setCursorBlink", (s, a) -> {
            term.setCursorBlink(a.arg(1).toBoolean());
            return Constants.NONE;
        });
        fn(t, "getSize", (s, a) -> ValueFactory.varargsOf(ValueFactory.valueOf(term.width()), ValueFactory.valueOf(term.height())));
        fn(t, "clear", (s, a) -> {
            term.clear();
            return Constants.NONE;
        });
        fn(t, "clearLine", (s, a) -> {
            term.clearLine();
            return Constants.NONE;
        });
        LibFunction.ManyArgs getText = (s, a) -> bit(term.textColour());
        LibFunction.ManyArgs setText = (s, a) -> {
            term.setTextColour(colour(a, 1));
            return Constants.NONE;
        };
        LibFunction.ManyArgs getBg = (s, a) -> bit(term.backgroundColour());
        LibFunction.ManyArgs setBg = (s, a) -> {
            term.setBackgroundColour(colour(a, 1));
            return Constants.NONE;
        };
        LibFunction.ManyArgs isColour = (s, a) -> Constants.TRUE;
        LibFunction.ManyArgs setPalette = (s, a) -> {
            var index = colour(a, 1);
            int rgb;
            if (a.arg(3).isNil()) {
                rgb = checkInt(a, 2);
            } else {
                rgb = (clampByte(checkDouble(a, 2)) << 16) | (clampByte(checkDouble(a, 3)) << 8) | clampByte(checkDouble(a, 4));
            }
            term.setPalette(index, rgb);
            return Constants.NONE;
        };
        LibFunction.ManyArgs getPalette = (s, a) -> rgb(term.palette(colour(a, 1)));
        LibFunction.ManyArgs nativePalette = (s, a) -> rgb(Terminal.DEFAULT_PALETTE[colour(a, 1)]);
        for (var suffix : List.of("Colour", "Color")) {
            fn(t, "getText" + suffix, getText);
            fn(t, "setText" + suffix, setText);
            fn(t, "getBackground" + suffix, getBg);
            fn(t, "setBackground" + suffix, setBg);
            fn(t, "is" + suffix, isColour);
            fn(t, "setPalette" + suffix, setPalette);
            fn(t, "getPalette" + suffix, getPalette);
            fn(t, "nativePalette" + suffix, nativePalette);
        }
        return t;
    }

    private static int clampByte(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v * 255)));
    }

    private static Varargs rgb(int rgb) {
        return ValueFactory.varargsOf(
            ValueFactory.valueOf(((rgb >> 16) & 255) / 255.0),
            ValueFactory.valueOf(((rgb >> 8) & 255) / 255.0),
            ValueFactory.valueOf((rgb & 255) / 255.0));
    }

    // ---- fs -------------------------------------------------------------------------------------------------------

    /** CC paths are relative to the root and never start with a slash. */
    private static String abs(String path) throws LuaError {
        try {
            return VPath.normalize("/" + path.replace('\\', '/'));
        } catch (FsException e) {
            throw new LuaError("/" + path + ": " + e.getMessage());
        }
    }

    private static String ccPath(String abs) {
        return abs.equals("/") ? "" : abs.substring(1);
    }

    private LuaTable fs() {
        var t = new LuaTable();
        var vfs = machine.vfs();
        fn(t, "list", (s, a) -> {
            var path = abs(checkString(a, 1));
            try {
                var names = vfs.list(path);
                var out = new LuaTable();
                for (var i = 0; i < names.size(); i++) out.rawset(i + 1, str(names.get(i)));
                return out;
            } catch (FsException e) {
                throw new LuaError("/" + ccPath(path) + ": " + (e.errno() == Errno.ENOTDIR ? "Not a directory" : "Not a directory"));
            }
        });
        fn(t, "combine", (s, a) -> {
            var sb = new StringBuilder();
            for (var i = 1; i <= a.count(); i++) sb.append('/').append(checkString(a, i));
            return str(ccPath(abs(sb.toString())));
        });
        fn(t, "getName", (s, a) -> {
            var path = abs(checkString(a, 1));
            return str(path.equals("/") ? "root" : VPath.name(path));
        });
        fn(t, "getDir", (s, a) -> {
            var path = abs(checkString(a, 1));
            return str(path.equals("/") ? ".." : ccPath(VPath.parent(path)));
        });
        fn(t, "getSize", (s, a) -> {
            var path = abs(checkString(a, 1));
            try {
                return ValueFactory.valueOf((double) vfs.stat(path).size());
            } catch (FsException e) {
                throw new LuaError("/" + ccPath(path) + ": No such file");
            }
        });
        fn(t, "exists", (s, a) -> ValueFactory.valueOf(vfs.exists(abs(checkString(a, 1)))));
        fn(t, "isDir", (s, a) -> ValueFactory.valueOf(vfs.isDirectory(abs(checkString(a, 1)))));
        fn(t, "isReadOnly", (s, a) -> {
            var path = abs(checkString(a, 1));
            try {
                var r = vfs.resolve(vfs.exists(path) ? path : VPath.parent(path));
                return ValueFactory.valueOf(r.backend().isReadOnly());
            } catch (FsException e) {
                return Constants.FALSE;
            }
        });
        fn(t, "makeDir", (s, a) -> {
            var path = abs(checkString(a, 1));
            try {
                vfs.mkdirs(path);
            } catch (FsException e) {
                throw new LuaError("/" + ccPath(path) + ": " + e.getMessage());
            }
            return Constants.NONE;
        });
        fn(t, "move", (s, a) -> {
            var from = abs(checkString(a, 1));
            var to = abs(checkString(a, 2));
            try {
                if (!vfs.exists(from)) throw new LuaError("/" + ccPath(from) + ": No such file");
                if (vfs.exists(to)) throw new LuaError("/" + ccPath(to) + ": File exists");
                try {
                    vfs.rename(from, to);
                } catch (FsException e) {
                    if (e.errno() != Errno.EXDEV) throw e;
                    copyRecursive(from, to);
                    vfs.deleteRecursive(from);
                }
            } catch (FsException e) {
                throw new LuaError(e.getMessage());
            }
            return Constants.NONE;
        });
        fn(t, "copy", (s, a) -> {
            var from = abs(checkString(a, 1));
            var to = abs(checkString(a, 2));
            try {
                if (!vfs.exists(from)) throw new LuaError("/" + ccPath(from) + ": No such file");
                if (vfs.exists(to)) throw new LuaError("/" + ccPath(to) + ": File exists");
                if (VPath.isWithin(to, from)) throw new LuaError("Can't copy a directory inside itself");
                copyRecursive(from, to);
            } catch (FsException e) {
                throw new LuaError(e.getMessage());
            }
            return Constants.NONE;
        });
        fn(t, "delete", (s, a) -> {
            var path = abs(checkString(a, 1));
            try {
                if (vfs.exists(path)) vfs.deleteRecursive(path);
            } catch (FsException e) {
                throw new LuaError("/" + ccPath(path) + ": " + e.getMessage());
            }
            return Constants.NONE;
        });
        fn(t, "open", (s, a) -> open(abs(checkString(a, 1)), checkString(a, 2)));
        fn(t, "getDrive", (s, a) -> {
            var path = abs(checkString(a, 1));
            if (!vfs.exists(path)) return Constants.NIL;
            try {
                var mount = vfs.resolve(path).mount();
                var desc = mount.backend().describe();
                if (mount.path().equals("/")) return str("hdd");
                return str(desc.equals("rom") ? "rom" : ccPath(mount.path()));
            } catch (FsException e) {
                return Constants.NIL;
            }
        });
        fn(t, "getFreeSpace", (s, a) -> {
            try {
                var backend = vfs.resolve(abs(checkString(a, 1))).backend();
                return backend.isReadOnly() ? ValueFactory.valueOf(0) : ValueFactory.valueOf((double) backend.freeSpace());
            } catch (FsException e) {
                return ValueFactory.valueOf(0);
            }
        });
        fn(t, "getCapacity", (s, a) -> {
            try {
                var backend = vfs.resolve(abs(checkString(a, 1))).backend();
                return backend.isReadOnly() ? Constants.NIL : ValueFactory.valueOf((double) backend.capacity());
            } catch (FsException e) {
                return Constants.NIL;
            }
        });
        fn(t, "attributes", (s, a) -> {
            var path = abs(checkString(a, 1));
            try {
                var stat = vfs.stat(path);
                var out = new LuaTable();
                out.rawset("size", ValueFactory.valueOf((double) stat.size()));
                out.rawset("isDir", ValueFactory.valueOf(stat.isDirectory()));
                out.rawset("isReadOnly", ValueFactory.valueOf(vfs.resolve(path).backend().isReadOnly()));
                out.rawset("created", ValueFactory.valueOf((double) (stat.ctime() / 1_000_000L)));
                out.rawset("modified", ValueFactory.valueOf((double) (stat.mtime() / 1_000_000L)));
                out.rawset("modification", ValueFactory.valueOf((double) (stat.mtime() / 1_000_000L)));
                return out;
            } catch (FsException e) {
                throw new LuaError("/" + ccPath(path) + ": No such file");
            }
        });
        return t;
    }

    private void copyRecursive(String from, String to) throws FsException {
        var vfs = machine.vfs();
        if (vfs.isDirectory(from)) {
            vfs.mkdirs(to);
            for (var child : vfs.list(from)) copyRecursive(VPath.join(from, child), VPath.join(to, child));
        } else {
            vfs.writeAll(to, vfs.readAll(from, 64 << 20));
        }
    }

    private Varargs open(String path, String mode) throws LuaError {
        var binary = mode.contains("b");
        var base = mode.replace("b", "");
        int flags = switch (base) {
            case "r" -> OpenFlags.READ;
            case "w" -> OpenFlags.WRITE | OpenFlags.CREATE | OpenFlags.TRUNCATE;
            case "a" -> OpenFlags.WRITE | OpenFlags.CREATE | OpenFlags.APPEND;
            case "r+" -> OpenFlags.READ | OpenFlags.WRITE;
            case "w+" -> OpenFlags.READ | OpenFlags.WRITE | OpenFlags.CREATE | OpenFlags.TRUNCATE;
            default -> throw new LuaError("Unsupported mode");
        };
        var vfs = machine.vfs();
        if (vfs.isDirectory(path)) return ValueFactory.varargsOf(Constants.NIL, str("/" + ccPath(path) + ": Not a file"));
        if ((flags & OpenFlags.CREATE) == 0 && !vfs.exists(path)) {
            return ValueFactory.varargsOf(Constants.NIL, str("/" + ccPath(path) + ": No such file"));
        }
        OpenFile file;
        try {
            file = vfs.open(path, flags);
        } catch (FsException e) {
            return ValueFactory.varargsOf(Constants.NIL, str("/" + ccPath(path) + ": " + e.getMessage()));
        }
        return handle(file, (flags & OpenFlags.READ) != 0, (flags & OpenFlags.WRITE) != 0, binary);
    }

    /** A CC file handle table over an open file. */
    private LuaTable handle(OpenFile file, boolean readable, boolean writable, boolean binary) {
        var t = new LuaTable();
        var closed = new boolean[1];
        var pushback = new ByteArrayOutputStream();
        Runnable check = () -> {
        };
        if (readable) {
            fn(t, "read", (s, a) -> {
                ensureOpen(closed);
                if (a.arg(1).isNil()) {
                    var b = readBytes(file, 1);
                    if (b.length == 0) return Constants.NIL;
                    return binary ? ValueFactory.valueOf(b[0] & 0xFF) : ValueFactory.valueOf(b);
                }
                var count = checkInt(a, 1);
                if (count < 0) throw new LuaError("Cannot read a negative number of bytes");
                var b = readBytes(file, count);
                return b.length == 0 && count > 0 ? Constants.NIL : ValueFactory.valueOf(b);
            });
            fn(t, "readAll", (s, a) -> {
                ensureOpen(closed);
                var out = new ByteArrayOutputStream();
                while (true) {
                    var b = readBytes(file, 8192);
                    if (b.length == 0) break;
                    out.writeBytes(b);
                }
                return ValueFactory.valueOf(out.toByteArray());
            });
            fn(t, "readLine", (s, a) -> {
                ensureOpen(closed);
                var keep = a.arg(1).toBoolean();
                var out = new ByteArrayOutputStream();
                var any = false;
                while (true) {
                    var b = readBytes(file, 1);
                    if (b.length == 0) break;
                    any = true;
                    if (b[0] == '\n') {
                        if (keep) out.write('\n');
                        break;
                    }
                    out.write(b[0]);
                }
                if (!any) return Constants.NIL;
                var bytes = out.toByteArray();
                var len = bytes.length;
                if (!keep && len > 0 && bytes[len - 1] == '\r') len--;
                return ValueFactory.valueOf(java.util.Arrays.copyOf(bytes, len));
            });
        }
        if (writable) {
            fn(t, "write", (s, a) -> {
                ensureOpen(closed);
                var v = a.arg(1);
                byte[] data = v.isNumber() && binary && !v.isString() ? new byte[]{(byte) v.toInteger()} : LuaText.bytes(v.isNil() ? str("nil") : v.toLuaString());
                writeBytes(file, data);
                return Constants.NONE;
            });
            fn(t, "writeLine", (s, a) -> {
                ensureOpen(closed);
                var data = LuaText.bytes(a.arg(1).isNil() ? str("nil") : a.arg(1).toLuaString());
                writeBytes(file, data);
                writeBytes(file, new byte[]{'\n'});
                return Constants.NONE;
            });
            fn(t, "flush", (s, a) -> {
                ensureOpen(closed);
                return Constants.NONE;
            });
        }
        fn(t, "seek", (s, a) -> {
            ensureOpen(closed);
            var whence = a.arg(1).isNil() ? "cur" : checkString(a, 1);
            var offset = a.arg(2).isNil() ? 0 : (long) checkDouble(a, 2);
            var w = switch (whence) {
                case "set" -> OpenFile.SEEK_SET;
                case "cur" -> OpenFile.SEEK_CUR;
                case "end" -> OpenFile.SEEK_END;
                default -> throw new LuaError("bad argument #1 to 'seek' (invalid option '" + whence + "')");
            };
            try {
                return ValueFactory.valueOf((double) file.seek(offset, w));
            } catch (FsException e) {
                return fsError(e);
            }
        });
        fn(t, "close", (s, a) -> {
            ensureOpen(closed);
            closed[0] = true;
            file.release();
            return Constants.NONE;
        });
        check.run();
        return t;
    }

    private static void ensureOpen(boolean[] closed) throws LuaError {
        if (closed[0]) throw new LuaError("attempt to use a closed file");
    }

    private byte[] readBytes(OpenFile file, int count) throws LuaError {
        var buffer = ByteBuffer.allocate(Math.min(count, 1 << 20));
        try {
            var n = file.read(buffer, false);
            return java.util.Arrays.copyOf(buffer.array(), Math.max(n, 0));
        } catch (FsException e) {
            throw new LuaError(e.getMessage());
        } catch (InterruptedException e) {
            process.checkKilled();
            throw new LuaError("Interrupted");
        }
    }

    private void writeBytes(OpenFile file, byte[] data) throws LuaError {
        try {
            file.write(ByteBuffer.wrap(data), false);
        } catch (FsException e) {
            throw new LuaError(e.getMessage());
        } catch (InterruptedException e) {
            process.checkKilled();
            throw new LuaError("Interrupted");
        }
    }

    // ---- os -------------------------------------------------------------------------------------------------------

    private void os(LuaTable t) {
        fn(t, "queueEvent", (s, a) -> {
            var name = checkString(a, 1);
            var rest = new Object[Math.max(0, a.count() - 1)];
            for (var i = 0; i < rest.length; i++) rest[i] = a.arg(i + 2);
            process.deliver(new Event(name, rest));
            return Constants.NONE;
        });
        fn(t, "startTimer", (s, a) -> ValueFactory.valueOf(machine.startTimer(process, checkDouble(a, 1))));
        fn(t, "cancelTimer", (s, a) -> {
            machine.cancelTimer(checkInt(a, 1));
            return Constants.NONE;
        });
        fn(t, "setAlarm", (s, a) -> {
            var time = checkDouble(a, 1);
            if (time < 0 || time >= 24) throw new LuaError("Number out of range");
            return ValueFactory.valueOf(machine.setAlarm(process, time));
        });
        fn(t, "cancelAlarm", (s, a) -> {
            machine.cancelAlarm(checkInt(a, 1));
            return Constants.NONE;
        });
        fn(t, "shutdown", (s, a) -> {
            throw new LuaExit(0);
        });
        fn(t, "reboot", (s, a) -> {
            throw new LuaExit(0);
        });
        LibFunction.ManyArgs id = (s, a) -> ValueFactory.valueOf(machine.id());
        fn(t, "getComputerID", id);
        fn(t, "computerID", id);
        LibFunction.ManyArgs label = (s, a) -> machine.label() == null ? Constants.NIL : str(machine.label());
        fn(t, "getComputerLabel", label);
        fn(t, "computerLabel", label);
        fn(t, "setComputerLabel", (s, a) -> {
            machine.setLabel(a.arg(1).isNil() ? null : checkString(a, 1));
            return Constants.NONE;
        });
        fn(t, "clock", (s, a) -> ValueFactory.valueOf((System.currentTimeMillis() - process.startTime) / 1000.0));
        fn(t, "time", (s, a) -> {
            var kind = a.arg(1).isNil() ? "ingame" : (a.arg(1) instanceof LuaTable) ? "table" : checkString(a, 1).toLowerCase(Locale.ROOT);
            return switch (kind) {
                case "utc" -> ValueFactory.valueOf(hours(ZonedDateTime.now(ZoneOffset.UTC)));
                case "local" -> ValueFactory.valueOf(hours(ZonedDateTime.now()));
                case "ingame" -> ValueFactory.valueOf(((machine.host().dayTime() + 6000) % 24000) / 1000.0);
                case "table" -> ValueFactory.valueOf((double) timeFromTable((LuaTable) a.arg(1)));
                default -> throw new LuaError("Unsupported operation");
            };
        });
        fn(t, "day", (s, a) -> {
            var kind = a.arg(1).isNil() ? "ingame" : checkString(a, 1).toLowerCase(Locale.ROOT);
            return switch (kind) {
                case "utc" -> ValueFactory.valueOf((double) (System.currentTimeMillis() / 86_400_000L));
                case "local" -> ValueFactory.valueOf((double) java.time.LocalDate.now().toEpochDay());
                case "ingame" -> ValueFactory.valueOf((double) ((machine.host().dayTime() + 6000) / 24000 + 1));
                default -> throw new LuaError("Unsupported operation");
            };
        });
        fn(t, "epoch", (s, a) -> {
            var kind = a.arg(1).isNil() ? "ingame" : checkString(a, 1).toLowerCase(Locale.ROOT);
            return switch (kind) {
                case "utc" -> ValueFactory.valueOf((double) System.currentTimeMillis());
                case "local" -> ValueFactory.valueOf((double) (System.currentTimeMillis()
                    + ZoneId.systemDefault().getRules().getOffset(Instant.now()).getTotalSeconds() * 1000L));
                case "ingame" -> {
                    var ticks = machine.host().dayTime() + 6000;
                    yield ValueFactory.valueOf((double) (ticks * 3600L));
                }
                default -> throw new LuaError("Unsupported operation");
            };
        });
        fn(t, "date", (s, a) -> {
            var format = a.arg(1).isNil() ? "%c" : checkString(a, 1);
            var time = a.arg(2).isNil() ? System.currentTimeMillis() / 1000 : (long) checkDouble(a, 2);
            var utc = format.startsWith("!");
            if (utc) format = format.substring(1);
            var date = ZonedDateTime.ofInstant(Instant.ofEpochSecond(time), utc ? ZoneOffset.UTC : ZoneId.systemDefault());
            if (format.startsWith("*t")) return dateTable(date);
            return str(strftime(format, date));
        });
        if (blockingEvents) {
            LibFunction.ManyArgs pull = (s, a) -> {
                var filter = a.arg(1).isNil() ? null : checkString(a, 1);
                try {
                    process.wantsEvents = true;
                    var event = process.events.take(filter, -1);
                    process.checkKilled();
                    return LuaConvert.toLua(process, forLua(event).toArray());
                } catch (InterruptedException e) {
                    process.checkKilled();
                    throw new LuaError("Interrupted");
                }
            };
            fn(t, "pullEventRaw", pull);
            fn(t, "pullEvent", (s, a) -> {
                var result = pull.invoke(s, a);
                if (result.first().isString() && result.first().toString().equals("terminate")) throw new LuaError("Terminated");
                return result;
            });
            fn(t, "sleep", (s, a) -> {
                try {
                    Thread.sleep((long) (Math.max(0, a.arg(1).optDouble(0)) * 1000));
                } catch (InterruptedException e) {
                    process.checkKilled();
                }
                return Constants.NONE;
            });
        }
    }

    private static double hours(ZonedDateTime t) {
        return t.getHour() + t.getMinute() / 60.0 + t.getSecond() / 3600.0;
    }

    private static long timeFromTable(LuaTable table) throws LuaError {
        var year = table.rawget("year").checkInteger();
        var month = table.rawget("month").checkInteger();
        var day = table.rawget("day").checkInteger();
        var hour = table.rawget("hour").optInteger(12);
        var min = table.rawget("min").optInteger(0);
        var sec = table.rawget("sec").optInteger(0);
        return ZonedDateTime.of(year, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault())
            .plusMonths(month - 1).plusDays(day - 1).plusHours(hour).plusMinutes(min).plusSeconds(sec).toEpochSecond();
    }

    private static LuaTable dateTable(ZonedDateTime d) {
        var t = new LuaTable();
        t.rawset("year", ValueFactory.valueOf(d.getYear()));
        t.rawset("month", ValueFactory.valueOf(d.getMonthValue()));
        t.rawset("day", ValueFactory.valueOf(d.getDayOfMonth()));
        t.rawset("hour", ValueFactory.valueOf(d.getHour()));
        t.rawset("min", ValueFactory.valueOf(d.getMinute()));
        t.rawset("sec", ValueFactory.valueOf(d.getSecond()));
        t.rawset("wday", ValueFactory.valueOf(d.getDayOfWeek().getValue() % 7 + 1));
        t.rawset("yday", ValueFactory.valueOf(d.getDayOfYear()));
        t.rawset("isdst", Constants.FALSE);
        return t;
    }

    static String strftime(String format, ZonedDateTime d) {
        var sb = new StringBuilder();
        for (var i = 0; i < format.length(); i++) {
            var c = format.charAt(i);
            if (c != '%' || i + 1 >= format.length()) {
                sb.append(c);
                continue;
            }
            var f = format.charAt(++i);
            switch (f) {
                case 'a' -> sb.append(d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
                case 'A' -> sb.append(d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
                case 'b', 'h' -> sb.append(d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
                case 'B' -> sb.append(d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
                case 'c' -> sb.append(strftime("%a %b %d %H:%M:%S %Y", d));
                case 'd' -> sb.append(String.format("%02d", d.getDayOfMonth()));
                case 'e' -> sb.append(String.format("%2d", d.getDayOfMonth()));
                case 'D', 'x' -> sb.append(strftime("%m/%d/%y", d));
                case 'F' -> sb.append(strftime("%Y-%m-%d", d));
                case 'H' -> sb.append(String.format("%02d", d.getHour()));
                case 'I' -> sb.append(String.format("%02d", (d.getHour() + 11) % 12 + 1));
                case 'j' -> sb.append(String.format("%03d", d.getDayOfYear()));
                case 'm' -> sb.append(String.format("%02d", d.getMonthValue()));
                case 'M' -> sb.append(String.format("%02d", d.getMinute()));
                case 'n' -> sb.append('\n');
                case 'p' -> sb.append(d.getHour() < 12 ? "AM" : "PM");
                case 'S' -> sb.append(String.format("%02d", d.getSecond()));
                case 't' -> sb.append('\t');
                case 'T', 'X' -> sb.append(strftime("%H:%M:%S", d));
                case 'u' -> sb.append(d.getDayOfWeek().getValue());
                case 'w' -> sb.append(d.getDayOfWeek().getValue() % 7);
                case 'y' -> sb.append(String.format("%02d", d.getYear() % 100));
                case 'Y' -> sb.append(d.getYear());
                case 'z' -> sb.append(d.getOffset().getId().replace(":", "").replace("Z", "+0000"));
                case 'Z' -> sb.append(d.getZone().getId());
                case '%' -> sb.append('%');
                default -> sb.append('%').append(f);
            }
        }
        return sb.toString();
    }

    // ---- peripheral -----------------------------------------------------------------------------------------------

    private LuaTable peripheral() {
        var t = new LuaTable();
        var peripherals = machine.peripherals();
        fn(t, "isPresent", (s, a) -> {
            var p = peripherals.get(checkString(a, 1));
            return ValueFactory.valueOf(p != null && !(p.peripheral() instanceof dev.ccbaremetal.kernel.periph.HostApi));
        });
        fn(t, "getType", (s, a) -> {
            var p = peripherals.get(checkString(a, 1));
            if (p == null) return Constants.NIL;
            var types = p.types();
            var out = new LuaValue[types.size()];
            for (var i = 0; i < out.length; i++) out[i] = str(types.get(i));
            return ValueFactory.varargsOf(out);
        });
        fn(t, "hasType", (s, a) -> {
            var p = peripherals.get(checkString(a, 1));
            if (p == null) return Constants.NIL;
            return ValueFactory.valueOf(p.types().contains(checkString(a, 2)));
        });
        fn(t, "getMethods", (s, a) -> {
            var p = peripherals.get(checkString(a, 1));
            if (p == null) return Constants.NIL;
            var names = new ArrayList<>(p.methods().keySet());
            names.sort(null);
            var out = new LuaTable();
            for (var i = 0; i < names.size(); i++) out.rawset(i + 1, str(names.get(i)));
            return out;
        });
        fn(t, "call", (s, a) -> {
            var name = checkString(a, 1);
            var method = checkString(a, 2);
            var p = peripherals.get(name);
            if (p == null) throw new LuaError("No peripheral attached");
            var args = LuaConvert.fromLuaArgs(a.subargs(3));
            try {
                return LuaConvert.toLua(process, peripherals.call(process, name, method, args.raw()));
            } catch (PeripheralException e) {
                throw new LuaError(e.getMessage());
            }
        });
        return t;
    }

    // ---- redstone -------------------------------------------------------------------------------------------------

    private static int side(String name) throws LuaError {
        var index = Machine.SIDES.indexOf(name.toLowerCase(Locale.ROOT));
        if (index < 0) throw new LuaError("bad argument #1 (unknown option " + name + ")");
        return index;
    }

    private LuaTable redstone() {
        var t = new LuaTable();
        fn(t, "getSides", (s, a) -> {
            var out = new LuaTable();
            var i = 1;
            for (var name : List.of("top", "bottom", "left", "right", "front", "back")) out.rawset(i++, str(name));
            return out;
        });
        fn(t, "getInput", (s, a) -> ValueFactory.valueOf(machine.redstoneInput(side(checkString(a, 1))) > 0));
        fn(t, "getOutput", (s, a) -> ValueFactory.valueOf(machine.redstoneOutput(side(checkString(a, 1))) > 0));
        fn(t, "setOutput", (s, a) -> {
            machine.setRedstoneOutput(side(checkString(a, 1)), a.arg(2).toBoolean() ? 15 : 0);
            return Constants.NONE;
        });
        for (var spelling : List.of("Analog", "Analogue")) {
            fn(t, "get" + spelling + "Input", (s, a) -> ValueFactory.valueOf(machine.redstoneInput(side(checkString(a, 1)))));
            fn(t, "get" + spelling + "Output", (s, a) -> ValueFactory.valueOf(machine.redstoneOutput(side(checkString(a, 1)))));
            fn(t, "set" + spelling + "Output", (s, a) -> {
                var level = checkInt(a, 2);
                if (level < 0 || level > 15) throw new LuaError("Expected number in range 0-15");
                machine.setRedstoneOutput(side(checkString(a, 1)), level);
                return Constants.NONE;
            });
        }
        fn(t, "getBundledInput", (s, a) -> ValueFactory.valueOf(0));
        fn(t, "getBundledOutput", (s, a) -> ValueFactory.valueOf(0));
        fn(t, "setBundledOutput", (s, a) -> Constants.NONE);
        fn(t, "testBundledInput", (s, a) -> Constants.FALSE);
        return t;
    }

    // ---- gfx (CC: Bare Metal extension) ---------------------------------------------------------------------------------

    private Framebuffer fb() {
        var provider = target.get(process);
        if (provider != null) {
            for (var a : machine.peripherals().list()) if (a.peripheral() == provider) return provider.framebuffer();
            target.remove(process);
        }
        return machine.framebuffer();
    }

    private void touched() {
        var provider = target.get(process);
        if (provider != null) provider.framebufferChanged();
    }

    private LuaTable gfx() {
        var t = new LuaTable();
        fn(t, "setTarget", (s, a) -> {
            if (a.arg(1).isNil()) {
                target.remove(process);
                return Constants.TRUE;
            }
            var attached = machine.peripherals().get(checkString(a, 1));
            if (attached == null || !(attached.peripheral() instanceof FramebufferProvider provider)) {
                return ValueFactory.varargsOf(Constants.FALSE, str("Not a pixel monitor"));
            }
            target.put(process, provider);
            return Constants.TRUE;
        });
        fn(t, "setMode", (s, a) -> {
            var provider = target.get(process);
            var w = checkInt(a, 1);
            if (w == 0 && provider == null) {
                machine.setDisplay(Machine.DisplayMode.TEXT, 0);
                return Constants.TRUE;
            }
            var ok = fb().setMode(w, checkInt(a, 2), optInt(a, 3, 4), a.arg(4).toBoolean() ? Framebuffer.PRESET_GRAY : 0);
            if (!ok) return Constants.FALSE;
            if (provider == null) {
                machine.setFramebufferCustom(true);
                machine.setDisplay(Machine.DisplayMode.GRAPHICS, process.pid);
            }
            touched();
            return Constants.TRUE;
        });
        fn(t, "textMode", (s, a) -> {
            machine.setDisplay(Machine.DisplayMode.TEXT, 0);
            return Constants.NONE;
        });
        fn(t, "getDefaultSize", (s, a) -> {
            var size = machine.defaultFramebufferSize();
            return ValueFactory.varargsOf(ValueFactory.valueOf(size[0]), ValueFactory.valueOf(size[1]));
        });
        fn(t, "getMode", (s, a) -> {
            var fb = fb();
            return ValueFactory.varargsOf(ValueFactory.valueOf(fb.width()), ValueFactory.valueOf(fb.height()), ValueFactory.valueOf(fb.bpp()));
        });
        fn(t, "getSize", (s, a) -> ValueFactory.varargsOf(ValueFactory.valueOf(fb().width()), ValueFactory.valueOf(fb().height())));
        fn(t, "clear", (s, a) -> {
            fb().clear(optInt(a, 1, 0));
            touched();
            return Constants.NONE;
        });
        fn(t, "setPixel", (s, a) -> {
            fb().setPixel(checkInt(a, 1), checkInt(a, 2), checkInt(a, 3));
            touched();
            return Constants.NONE;
        });
        fn(t, "getPixel", (s, a) -> ValueFactory.valueOf(fb().getPixel(checkInt(a, 1), checkInt(a, 2))));
        fn(t, "fill", (s, a) -> {
            fb().fill(checkInt(a, 1), checkInt(a, 2), checkInt(a, 3), checkInt(a, 4), checkInt(a, 5));
            touched();
            return Constants.NONE;
        });
        fn(t, "rect", (s, a) -> {
            fb().rect(checkInt(a, 1), checkInt(a, 2), checkInt(a, 3), checkInt(a, 4), checkInt(a, 5));
            touched();
            return Constants.NONE;
        });
        fn(t, "line", (s, a) -> {
            fb().line(checkInt(a, 1), checkInt(a, 2), checkInt(a, 3), checkInt(a, 4), checkInt(a, 5));
            touched();
            return Constants.NONE;
        });
        fn(t, "circle", (s, a) -> {
            fb().circle(checkInt(a, 1), checkInt(a, 2), checkInt(a, 3), checkInt(a, 4), a.arg(5).toBoolean());
            touched();
            return Constants.NONE;
        });
        fn(t, "text", (s, a) -> {
            var end = fb().text(checkInt(a, 1), checkInt(a, 2), LuaText.decode(a.arg(3).toLuaString()), optInt(a, 4, 15),
                optInt(a, 5, -1), optInt(a, 6, 1));
            touched();
            return ValueFactory.valueOf(end);
        });
        fn(t, "blit", (s, a) -> {
            var x = checkInt(a, 1);
            var y = checkInt(a, 2);
            var w = checkInt(a, 3);
            var h = checkInt(a, 4);
            var data = LuaText.bytes(a.arg(5));
            if (w <= 0 || h <= 0) return Constants.NONE;
            if (data.length < w * h) throw new LuaError("Pixel data too short (expected " + w * h + " bytes)");
            if (a.arg(6).isNil()) fb().blit(data, 0, w, x, y, w, h);
            else fb().blitKeyed(data, 0, w, x, y, w, h, checkInt(a, 6));
            touched();
            return Constants.NONE;
        });
        fn(t, "read", (s, a) -> ValueFactory.valueOf(fb().read(checkInt(a, 1), checkInt(a, 2), checkInt(a, 3), checkInt(a, 4))));
        fn(t, "scroll", (s, a) -> {
            fb().scroll(checkInt(a, 1), optInt(a, 2, 0));
            touched();
            return Constants.NONE;
        });
        fn(t, "setPalette", (s, a) -> {
            var index = checkInt(a, 1);
            var rgb = a.arg(3).isNil() ? checkInt(a, 2)
                : (clampByte(checkDouble(a, 2)) << 16) | (clampByte(checkDouble(a, 3)) << 8) | clampByte(checkDouble(a, 4));
            fb().setPalette(index, rgb);
            touched();
            return Constants.NONE;
        });
        fn(t, "getPalette", (s, a) -> ValueFactory.valueOf(fb().palette(checkInt(a, 1))));
        fn(t, "present", (s, a) -> {
            touched();
            try {
                fb().awaitPresented(100);
            } catch (InterruptedException e) {
                process.checkKilled();
            }
            return Constants.NONE;
        });
        return t;
    }

    // ---- wasm (CC: Bare Metal extension) --------------------------------------------------------------------------------

    private LuaTable wasm() {
        var t = new LuaTable();
        // wasm.run(path, ...): run a program on this terminal and wait for it. Returns the exit status.
        fn(t, "run", (s, a) -> {
            var path = checkString(a, 1);
            var argv = new ArrayList<String>();
            argv.add(path);
            for (var i = 2; i <= a.count(); i++) argv.add(LuaText.decode(a.arg(i).toLuaString()));
            try {
                var stdio = new OpenFile[3];
                for (var fd = 0; fd < 3; fd++) stdio[fd] = process.fds.file(fd).retain();
                var child = machine.spawn(process, path, argv, process.env(), stdio, 0);
                return ValueFactory.valueOf(waitChild(child.pid));
            } catch (FsException e) {
                return ValueFactory.varargsOf(Constants.NIL, str(e.getMessage()));
            }
        });
        // wasm.exec(path, {args...}, {stdin = "..."}): run with captured output. Returns status, stdout, stderr.
        fn(t, "exec", (s, a) -> {
            var path = checkString(a, 1);
            var argv = new ArrayList<String>();
            argv.add(path);
            if ((a.arg(2) instanceof LuaTable)) {
                var table = (LuaTable) a.arg(2);
                for (var i = 1; i <= table.length(); i++) argv.add(LuaText.decode(table.rawget(i).toLuaString()));
            }
            byte[] input = new byte[0];
            if ((a.arg(3) instanceof LuaTable)) {
                var opts = (LuaTable) a.arg(3);
                if (opts.rawget("stdin").isString()) input = LuaText.bytes(opts.rawget("stdin"));
            }
            try {
                return exec(path, argv, input);
            } catch (FsException e) {
                return ValueFactory.varargsOf(Constants.NIL, str(e.getMessage()));
            }
        });
        fn(t, "list", (s, a) -> {
            var out = new LuaTable();
            var i = 1;
            for (var p : machine.processes().all()) {
                var entry = new LuaTable();
                entry.rawset("pid", ValueFactory.valueOf(p.pid));
                entry.rawset("ppid", ValueFactory.valueOf(p.ppid));
                entry.rawset("name", str(p.name));
                entry.rawset("command", str(p.commandLine()));
                out.rawset(i++, entry);
            }
            return out;
        });
        fn(t, "kill", (s, a) -> {
            var p = machine.processes().get(checkInt(a, 1));
            if (p == null) return Constants.FALSE;
            p.signal(optInt(a, 2, Process.SIGTERM));
            return Constants.TRUE;
        });
        return t;
    }

    private int waitChild(int pid) throws LuaError {
        try {
            var status = new int[1];
            machine.processes().waitFor(process, pid, false, status);
            return status[0];
        } catch (FsException e) {
            throw new LuaError(e.getMessage());
        } catch (InterruptedException e) {
            process.checkKilled();
            throw new LuaError("Interrupted");
        }
    }

    private Varargs exec(String path, List<String> argv, byte[] input) throws FsException, LuaError {
        var in = new Pipe(machine.notifier());
        var out = new Pipe(machine.notifier());
        var err = new Pipe(machine.notifier());
        var child = machine.spawn(process, path, argv, process.env(), new OpenFile[]{in.readEnd, out.writeEnd, err.writeEnd}, 0);
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        var inPos = 0;
        var outOpen = true;
        var errOpen = true;
        var inOpen = true;
        var buffer = ByteBuffer.allocate(8192);
        try {
            while (outOpen || errOpen) {
                var progress = false;
                if (inOpen) {
                    if (inPos < input.length) {
                        try {
                            inPos += in.writeEnd.write(ByteBuffer.wrap(input, inPos, input.length - inPos), true);
                            progress = true;
                        } catch (FsException e) {
                            if (e.errno() != Errno.EAGAIN) inPos = input.length;
                        }
                    }
                    if (inPos >= input.length) {
                        in.writeEnd.release();
                        inOpen = false;
                    }
                }
                for (var k = 0; k < 2; k++) {
                    var pipe = k == 0 ? out : err;
                    if (k == 0 ? !outOpen : !errOpen) continue;
                    buffer.clear();
                    try {
                        var n = pipe.readEnd.read(buffer, true);
                        if (n == 0) {
                            if (k == 0) outOpen = false;
                            else errOpen = false;
                        } else {
                            (k == 0 ? stdout : stderr).write(buffer.array(), 0, n);
                            progress = true;
                        }
                    } catch (FsException e) {
                        if (e.errno() != Errno.EAGAIN) {
                            if (k == 0) outOpen = false;
                            else errOpen = false;
                        }
                    }
                }
                if (!progress) {
                    var gen = machine.notifier().generation();
                    machine.notifier().await(gen, 50_000_000L);
                    process.checkKilled();
                }
            }
        } catch (InterruptedException e) {
            process.checkKilled();
        } finally {
            if (inOpen) in.writeEnd.release();
            out.readEnd.release();
            err.readEnd.release();
        }
        var status = waitChild(child.pid);
        return ValueFactory.varargsOf(ValueFactory.valueOf(status), ValueFactory.valueOf(stdout.toByteArray()),
            ValueFactory.valueOf(stderr.toByteArray()));
    }
}
