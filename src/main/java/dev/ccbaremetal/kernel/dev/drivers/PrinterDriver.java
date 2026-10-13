package dev.ccbaremetal.kernel.dev.drivers;

import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.dev.Device;
import dev.ccbaremetal.kernel.dev.DeviceTree;
import dev.ccbaremetal.kernel.dev.Driver;
import dev.ccbaremetal.kernel.fs.FileType;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.Stat;
import dev.ccbaremetal.kernel.io.OpenFile;
import dev.ccbaremetal.kernel.periph.CcStrings;
import dev.ccbaremetal.kernel.periph.PeripheralException;
import dev.ccbaremetal.kernel.proc.Process;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Printers: /dev/lpN (class printer). Text written is printed when the file is closed, wrapped and split into pages. */
public final class PrinterDriver implements Driver {
    private final Machine machine;

    public PrinterDriver(Machine machine) {
        this.machine = machine;
    }

    @Override
    public String name() {
        return "printer";
    }

    @Override
    public boolean matches(Peripheral p) {
        return p.is("printer");
    }

    @Override
    public void probe(DeviceTree tree, Peripheral p) {
        tree.register(new Lp(machine, p.name()));
    }

    private static final class Lp extends Device {
        private final Machine machine;

        Lp(Machine machine, String peripheral) {
            super("printer", "lp", peripheral);
            this.machine = machine;
            attribute("ink", () -> value("getInkLevel"));
            attribute("paper", () -> value("getPaperLevel"));
        }

        /** A level, asked of the printer by the reading process. */
        private String value(String method) {
            var p = Process.CURRENT.get();
            if (p == null) return "\n";
            try {
                var r = machine.peripherals().call(p, peripheral(), method, new Object[0]);
                return (r.length > 0 && r[0] instanceof Number n ? Integer.toString(n.intValue()) : "") + "\n";
            } catch (PeripheralException e) {
                return "\n";
            }
        }

        @Override
        protected OpenFile open(String guestPath) {
            return new LpFile(machine, peripheral(), guestPath);
        }
    }

    private static final class LpFile extends OpenFile {
        private final Machine machine;
        private final String name;
        private final String guest;
        private final ByteArrayOutputStream text = new ByteArrayOutputStream();

        LpFile(Machine machine, String name, String guest) {
            this.machine = machine;
            this.name = name;
            this.guest = guest;
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
        public synchronized int write(ByteBuffer src, boolean nonBlocking) throws FsException {
            checkRevoked();
            var n = src.remaining();
            var bytes = new byte[n];
            src.get(bytes);
            text.writeBytes(bytes);
            return n;
        }

        private Object[] call(Process p, String method, Object... args) throws PeripheralException {
            return machine.peripherals().call(p, name, method, args);
        }

        @Override
        protected synchronized void onClose() {
            var p = Process.CURRENT.get();
            if (p == null || text.size() == 0) return;
            if (revoked()) {
                p.stderr(name + ": printer disconnected, nothing printed\n");
                return;
            }
            try {
                print(p, text.toString(StandardCharsets.UTF_8));
            } catch (PeripheralException e) {
                p.stderr(name + ": " + e.getMessage() + "\n");
            }
        }

        private void print(Process p, String content) throws PeripheralException {
            var started = call(p, "newPage");
            if (started.length == 0 || !Boolean.TRUE.equals(started[0])) throw new PeripheralException("cannot start a page (paper and ink?)");
            var size = call(p, "getPageSize");
            int w = ((Number) size[0]).intValue(), h = ((Number) size[1]).intValue();
            var y = 1;
            var line = new StringBuilder();
            for (var i = 0; i <= content.length(); i++) {
                var c = i < content.length() ? content.charAt(i) : '\n';
                if (c == '\t') {
                    line.append(" ".repeat(4 - line.length() % 4));
                    continue;
                }
                if (c != '\n' && line.length() < w) {
                    if (c >= ' ') line.append(c);
                    continue;
                }
                if (i == content.length() && line.isEmpty()) break;
                call(p, "setCursorPos", 1, y);
                call(p, "write", CcStrings.bytes(line.toString()));
                line.setLength(0);
                if (c != '\n') line.append(c);
                if (++y > h) {
                    call(p, "endPage");
                    var next = call(p, "newPage");
                    if (next.length == 0 || !Boolean.TRUE.equals(next[0])) throw new PeripheralException("out of paper or ink");
                    y = 1;
                }
            }
            call(p, "endPage");
        }

        @Override
        public String path() {
            return guest;
        }
    }
}
