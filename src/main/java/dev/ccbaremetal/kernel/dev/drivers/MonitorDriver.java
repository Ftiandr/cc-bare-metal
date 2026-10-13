package dev.ccbaremetal.kernel.dev.drivers;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.dev.Device;
import dev.ccbaremetal.kernel.dev.DeviceTree;
import dev.ccbaremetal.kernel.dev.Driver;
import dev.ccbaremetal.kernel.dev.TouchscreenDevice;
import dev.ccbaremetal.kernel.fs.FileType;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.Stat;
import dev.ccbaremetal.kernel.io.OpenFile;
import dev.ccbaremetal.kernel.lua.LuaArgs;
import dev.ccbaremetal.kernel.periph.PeripheralException;
import dev.ccbaremetal.kernel.proc.Process;
import dev.ccbaremetal.kernel.tty.AnsiWriter;
import dev.ccbaremetal.kernel.tty.Terminal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * CC monitors (of any mod with the "monitor" type): a text terminal /dev/monitorN (class tty) and the touchscreen
 * /dev/input/eventN (cells from 0).
 */
public final class MonitorDriver implements Driver {
    private final Machine machine;

    public MonitorDriver(Machine machine) {
        this.machine = machine;
    }

    @Override
    public String name() {
        return "monitor";
    }

    @Override
    public boolean matches(Peripheral p) {
        return p.is("monitor");
    }

    @Override
    public void probe(DeviceTree tree, Peripheral p) {
        tree.register(new MonitorTty(machine, p.name()));
        tree.register(new TouchscreenDevice(p.name(), "monitor", "monitor_touch", 1, () -> "", () -> size(machine, p.name())));
    }

    /** "W,H" in cells, asked of the monitor by the reading process. */
    private static String size(Machine machine, String peripheral) {
        var caller = Process.CURRENT.get();
        if (caller == null) return "";
        try {
            var r = machine.peripherals().call(caller, peripheral, "getSize", new Object[0]);
            return r.length >= 2 ? ((Number) r[0]).intValue() + "," + ((Number) r[1]).intValue() : "";
        } catch (PeripheralException e) {
            return "";
        }
    }

    /** /dev/monitorN: the monitor as a terminal (ANSI colours and cursor movement). */
    private static final class MonitorTty extends Device {
        private final Machine machine;

        MonitorTty(Machine machine, String peripheral) {
            super("tty", "monitor", peripheral);
            this.machine = machine;
            attribute("size", () -> size(machine, peripheral) + "\n");
        }

        @Override
        protected OpenFile open(String guestPath) {
            return new MonitorFile(machine, peripheral(), guestPath);
        }
    }

    /**
     * Output goes through the same ANSI parser as the computer's screen; after each write only the changed lines are
     * sent to the monitor (one blit per line). CC monitors hold one byte per cell (like Lua strings), so the terminal
     * works in bytes too: UTF-8 text takes one cell per byte and is shown as letters by the Unicode renderer.
     */
    private static final class MonitorFile extends OpenFile {
        private final Machine machine;
        private final String name;
        private final String guest;
        private Terminal term;
        private AnsiWriter writer;
        private char[][] sentText;
        private byte[][] sentColours;

        MonitorFile(Machine machine, String name, String guest) {
            this.machine = machine;
            this.name = name;
            this.guest = guest;
        }

        private Object[] call(Process p, String method, Object... args) throws PeripheralException {
            return machine.peripherals().call(p, name, method, args);
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
            var p = Process.CURRENT.get();
            if (p == null) throw new FsException(Errno.EIO);
            var n = src.remaining();
            var bytes = new byte[n];
            src.get(bytes);
            try {
                if (term == null) {
                    var size = call(p, "getSize");
                    int w = Math.max(1, ((Number) size[0]).intValue()), h = Math.max(1, ((Number) size[1]).intValue());
                    term = new Terminal(w, h);
                    writer = new AnsiWriter(term);
                    sentText = new char[h][];
                    sentColours = new byte[h][];
                }
                // Monitors show \n as a line break only: translate to CR LF like a terminal driver. Bytes >= 0x80 are
                // passed as private-use characters U+E080-U+E0FF (one cell per byte; U+0080-U+009F would be taken
                // for C1 control codes) and turned back into bytes when sent.
                var out = new ByteArrayOutputStream(n + 16);
                for (var b : bytes) {
                    var v = b & 0xFF;
                    if (v == '\n') out.write('\r');
                    if (v >= 0x80) {
                        var c = 0xE000 + v; // 3-byte UTF-8
                        out.write(0xE0 | c >> 12);
                        out.write(0x80 | c >> 6 & 0x3F);
                        out.write(0x80 | c & 0x3F);
                    } else {
                        out.write(v);
                    }
                }
                var data = out.toByteArray();
                writer.write(data, 0, data.length);
                sync(p);
            } catch (PeripheralException e) {
                p.stderr(name + ": " + e.getMessage() + "\n");
                throw new FsException(Errno.EIO);
            }
            return n;
        }

        private void sync(Process p) throws PeripheralException {
            var hex = "0123456789abcdef";
            for (var y = 0; y < term.height(); y++) {
                var text = term.line(y);
                var fg = term.fgLine(y);
                var bg = term.bgLine(y);
                var colours = new byte[fg.length * 2];
                System.arraycopy(fg, 0, colours, 0, fg.length);
                System.arraycopy(bg, 0, colours, fg.length, bg.length);
                if (Arrays.equals(text, sentText[y]) && Arrays.equals(colours, sentColours[y])) continue;
                var fgs = new StringBuilder();
                var bgs = new StringBuilder();
                for (var x = 0; x < text.length; x++) {
                    fgs.append(hex.charAt(fg[x] & 15));
                    bgs.append(hex.charAt(bg[x] & 15));
                }
                var raw = new byte[text.length];
                for (var x = 0; x < text.length; x++) raw[x] = (byte) (text[x] >= 0xE000 && text[x] <= 0xE0FF ? text[x] - 0xE000 : text[x]);
                call(p, "setCursorPos", 1, y + 1);
                call(p, "blit", new LuaArgs.Str(raw, new String(raw, StandardCharsets.ISO_8859_1)), fgs.toString(), bgs.toString());
                sentText[y] = text;
                sentColours[y] = colours;
            }
            call(p, "setCursorPos", term.cursorX() + 1, term.cursorY() + 1);
        }

        @Override
        public String path() {
            return guest;
        }
    }
}
