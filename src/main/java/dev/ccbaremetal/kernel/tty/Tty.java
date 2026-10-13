package dev.ccbaremetal.kernel.tty;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.fs.FileType;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.Stat;
import dev.ccbaremetal.kernel.io.Notifier;
import dev.ccbaremetal.kernel.io.OpenFile;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;

/**
 * A terminal device with a POSIX-like line discipline (cooked or raw mode). The machine's console: output goes
 * through an ANSI parser into the {@link Terminal}, input comes from keyboard events. A pseudo-terminal ({@link Pty}):
 * output goes to the master side, input is the bytes the master writes (ssh sessions).
 */
public final class Tty {
    public static final int MODE_RAW = 1;
    public static final int MODE_NOECHO = 2;
    public static final int MODE_NOSIG = 16;
    /** termios ICRNL in raw mode: Enter (and ^M) arrive as \n instead of \r. */
    public static final int MODE_ICRNL = 32;
    /** termios VMIN/VTIME are given: VMIN in bits 8-15, VTIME (tenths of a second) in bits 16-23. Raw mode only. */
    public static final int MODE_TIMED = 64;

    public static final int SIGINT = 2;
    public static final int SIGQUIT = 3;

    private final Terminal terminal;
    private final AnsiWriter writer;
    private final Notifier notifier;
    private final IntConsumer signal;
    /** Pseudo-terminals: where output goes (the console has its Terminal instead). */
    private final java.util.function.Consumer<byte[]> output;
    /** The foreground process group: gets Ctrl+C, may read. */
    private volatile int foreground;
    /** Window size of a pseudo-terminal (set by the master); the console's is its Terminal's. */
    private volatile int cols = 80;
    private volatile int rows = 24;
    /** Open descriptors of the device (a pseudo-terminal hangs up when the last one closes). */
    private int opened;
    private Runnable onLastClose;
    /** The other side went away (ssh connection closed): reads end, writes fail. */
    private boolean hungUp;

    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private byte[] queue = new byte[4096];
    private int queueStart;
    private int queueEnd;
    private int eofCount;
    private int mode;
    private int readers;
    /** Mouse buttons held down (bit per CC button), so plain motion (mode 1003) is not reported during drags. */
    private int buttonsDown;

    public Tty(Terminal terminal, Notifier notifier, IntConsumer signal) {
        this.terminal = terminal;
        this.writer = new AnsiWriter(terminal);
        this.notifier = notifier;
        this.signal = signal;
        this.output = null;
    }

    /** A pseudo-terminal's slave side: output goes to {@code output}, input comes from {@link #input}. */
    public Tty(Notifier notifier, IntConsumer signal, java.util.function.Consumer<byte[]> output) {
        this.terminal = null;
        this.writer = null;
        this.notifier = notifier;
        this.signal = signal;
        this.output = output;
    }

    public boolean isConsole() {
        return terminal != null;
    }

    public int foreground() {
        return foreground;
    }

    public void setForeground(int pgid) {
        foreground = pgid;
    }

    /** Columns and rows (TIOCGWINSZ). */
    public int[] size() {
        return terminal != null ? new int[]{terminal.width(), terminal.height()} : new int[]{cols, rows};
    }

    public void setSize(int cols, int rows) {
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
    }

    void onLastClose(Runnable action) {
        onLastClose = action;
    }

    /** The master side closed: readers see end of input, writers an error (as after a modem hang-up). */
    public synchronized void hangup() {
        hungUp = true;
        notifyAll();
        notifier.signal();
    }

    public synchronized boolean hungUp() {
        return hungUp;
    }

    /**
     * Bytes typed on a pseudo-terminal's master side, through the line discipline as a Linux tty does: raw mode
     * passes them on; cooked mode edits a line (Backspace/DEL, ^U), ^C/^\ signal the foreground job, ^D ends input.
     */
    public synchronized void input(byte[] data) {
        for (var b : data) {
            int c = b & 0xFF;
            if (c < 32 && c != '\t' && c != '\n' && c != '\r' && c != 27 || c == 127) {
                if (c == 127 || c == 8) {
                    if (raw()) push(new byte[]{b});
                    else eraseChar();
                } else {
                    controlChar(c);
                }
                continue;
            }
            if (raw()) {
                push(new byte[]{(byte) (c == '\r' && icrnl() ? '\n' : c)});
                continue;
            }
            if (c == '\r' || c == '\n') {
                line.write('\n');
                echo("\n");
                push(line.toByteArray());
                line.reset();
                continue;
            }
            line.write(c);
            if (echo()) write(new byte[]{b}, 0, 1);
        }
    }

    private void eraseChar() {
        var bytes = line.toByteArray();
        if (bytes.length == 0) return;
        var cut = bytes.length - 1;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) cut--;
        line.reset();
        line.write(bytes, 0, cut);
        echo("\b \b");
    }

    public Terminal terminal() {
        return terminal;
    }

    public synchronized int mode() {
        return mode;
    }

    public synchronized int setMode(int newMode) {
        var old = mode;
        mode = newMode;
        if ((old & MODE_RAW) == 0 && (newMode & MODE_RAW) != 0 && line.size() > 0) {
            // Switching to raw: flush any partially typed line so it is not lost.
            push(line.toByteArray());
            line.reset();
        }
        return old;
    }

    /** Reset to cooked mode and drop pending input (used when the foreground job changes). */
    public synchronized void resetInput() {
        mode = 0;
        line.reset();
        queueStart = queueEnd = 0;
        eofCount = 0;
    }

    public void write(byte[] data, int offset, int length) {
        if (output != null) {
            // a terminal translates \n to \r\n on output (termios OPOST/ONLCR); the console's Terminal does it itself
            var out = new ByteArrayOutputStream(length + 16);
            for (var i = offset; i < offset + length; i++) {
                if (data[i] == '\n') out.write('\r');
                out.write(data[i]);
            }
            output.accept(out.toByteArray());
            return;
        }
        writer.write(data, offset, length);
        var replies = writer.takeReplies();
        if (replies != null) {
            synchronized (this) {
                push(replies);
            }
        }
    }

    public void write(String s) {
        var bytes = s.getBytes(StandardCharsets.UTF_8);
        write(bytes, 0, bytes.length);
    }

    private boolean raw() {
        return (mode & MODE_RAW) != 0;
    }

    private boolean icrnl() {
        return (mode & MODE_ICRNL) != 0;
    }

    private boolean echo() {
        return (mode & MODE_NOECHO) == 0;
    }

    private void push(byte[] bytes) {
        if (queueEnd + bytes.length > queue.length) {
            var size = queueEnd - queueStart;
            if (size + bytes.length > 1 << 20) return;
            var newQueue = new byte[Math.max(queue.length, Integer.highestOneBit(size + bytes.length) * 2)];
            System.arraycopy(queue, queueStart, newQueue, 0, size);
            queue = newQueue;
            queueStart = 0;
            queueEnd = size;
        }
        System.arraycopy(bytes, 0, queue, queueEnd, bytes.length);
        queueEnd += bytes.length;
        notifyAll();
        notifier.signal();
    }

    private void echo(String s) {
        if (echo()) write(s);
    }

    /** A typed character (CC "char" event). */
    public synchronized void onChar(int codepoint) {
        var bytes = new String(Character.toChars(codepoint)).getBytes(StandardCharsets.UTF_8);
        if (raw()) {
            push(bytes);
            return;
        }
        line.writeBytes(bytes);
        if (echo()) write(bytes, 0, bytes.length);
    }

    /**
     * Pasted text. Programs that enabled bracketed paste (ESC[?2004h, e.g. edit) get it as one block between
     * ESC[200~ and ESC[201~, so they can insert it as is (no auto-indent per line); otherwise it is typed.
     */
    public synchronized void onPaste(String text) {
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (raw() && terminal.bracketedPaste()) {
            var clean = new StringBuilder(text.length());
            text.codePoints().filter(cp -> cp >= 32 || cp == '\n' || cp == '\t').forEach(clean::appendCodePoint);
            push(("\u001b[200~" + clean + "\u001b[201~").getBytes(StandardCharsets.UTF_8));
            return;
        }
        for (var i = 0; i < text.length(); ) {
            var cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n') onKey(Keys.ENTER, 0);
            else if (cp >= 32 || cp == '\t') onChar(cp);
        }
    }

    /** A pressed key (CC "key" event) with modifier bits from {@link Keys}. */
    public synchronized void onKey(int key, int mods) {
        if ((mods & Keys.MOD_CONTROL) != 0 && key >= Keys.A && key <= Keys.Z) {
            controlChar(key - Keys.A + 1);
            return;
        }
        if (raw()) {
            // xterm: modifiers as a parameter (1 + shift 1 + alt 2 + ctrl 4): Shift+Up = ESC[1;2A, Ctrl+Right = ESC[1;5C
            var mod = 1 + ((mods & Keys.MOD_SHIFT) != 0 ? 1 : 0) + ((mods & Keys.MOD_ALT) != 0 ? 2 : 0) + ((mods & Keys.MOD_CONTROL) != 0 ? 4 : 0);
            var seq = switch (key) {
                case Keys.ENTER, Keys.KP_ENTER -> icrnl() ? "\n" : "\r";
                case Keys.BACKSPACE -> (mods & Keys.MOD_CONTROL) != 0 ? "\u0017" : "\u007f"; // Ctrl+Backspace: ^W
                case Keys.TAB -> (mods & Keys.MOD_SHIFT) != 0 ? "\u001b[Z" : "\t";
                case Keys.ESCAPE -> "\u001b";
                case Keys.UP -> cursorKey('A', mod);
                case Keys.DOWN -> cursorKey('B', mod);
                case Keys.RIGHT -> cursorKey('C', mod);
                case Keys.LEFT -> cursorKey('D', mod);
                case Keys.HOME -> cursorKey('H', mod);
                case Keys.END -> cursorKey('F', mod);
                case Keys.INSERT -> csiTilde(2, mod);
                case Keys.DELETE -> csiTilde(3, mod);
                case Keys.PAGE_UP -> csiTilde(5, mod);
                case Keys.PAGE_DOWN -> csiTilde(6, mod);
                default -> key >= Keys.F1 && key < Keys.F1 + 4 ? "\u001bO" + (char) ('P' + key - Keys.F1) : null;
            };
            if (seq != null) push(seq.getBytes(StandardCharsets.UTF_8));
            return;
        }
        switch (key) {
            case Keys.ENTER, Keys.KP_ENTER -> {
                line.write('\n');
                echo("\n");
                push(line.toByteArray());
                line.reset();
            }
            case Keys.BACKSPACE -> eraseChar();
            case Keys.TAB -> {
                line.write('\t');
                echo(" ");
            }
            default -> {
            }
        }
    }

    /** Cursor keys: ESC O A in application mode (DECCKM) when unmodified, ESC [ A otherwise. */
    private String cursorKey(char letter, int mod) {
        if (mod == 1 && terminal.appCursor()) return "\u001bO" + letter;
        return csiLetter(letter, mod);
    }

    private static String csiLetter(char letter, int mod) {
        return mod == 1 ? "\u001b[" + letter : "\u001b[1;" + mod + letter;
    }

    private static String csiTilde(int code, int mod) {
        return mod == 1 ? "\u001b[" + code + "~" : "\u001b[" + code + ";" + mod + "~";
    }

    /**
     * Mouse input for programs that turned on xterm mouse tracking (ESC[?1000h; 1002 adds drags, 1003 any motion):
     * encoded as X10 (ESC[M b x y) or SGR (ESC[<b;x;yM/m, with ESC[?1006h). {@code kind}: click, up, drag, scroll,
     * move; {@code button} as in CC (1 left, 2 right, 3 middle; for scroll -1 up, 1 down); x, y 1-based cells.
     * Returns whether it was used.
     */
    public synchronized boolean onMouse(String kind, int button, int x, int y, int mods) {
        var mode = terminal.mouseMode();
        if (kind.equals("click") && button >= 1 && button <= 3) buttonsDown |= 1 << button;
        if (kind.equals("up") && button >= 1 && button <= 3) buttonsDown &= ~(1 << button);
        if (mode == 0 || !raw()) return false;
        if (kind.equals("drag") && mode < 1002) return true;
        // Plain motion: only in mode 1003, and not while a button is held (drags report that already).
        if (kind.equals("move") && (mode < 1003 || buttonsDown != 0)) return true;
        int b = switch (button) {
            case 2 -> 2;
            case 3 -> 1;
            default -> 0;
        };
        if (kind.equals("scroll")) b = button < 0 ? 64 : 65;
        if (kind.equals("drag")) b += 32;
        if (kind.equals("move")) b = 3 + 32; // motion with no button
        b += ((mods & Keys.MOD_SHIFT) != 0 ? 4 : 0) + ((mods & Keys.MOD_ALT) != 0 ? 8 : 0) + ((mods & Keys.MOD_CONTROL) != 0 ? 16 : 0);
        String seq;
        if (terminal.sgrMouse()) {
            seq = "\u001b[<" + b + ";" + x + ";" + y + (kind.equals("up") ? "m" : "M");
        } else {
            if (kind.equals("up")) b = 3 | (b & ~3 & ~64);
            if (x > 223 || y > 223) return true; // X10 cannot encode it
            seq = "\u001b[M" + (char) (32 + b) + (char) (32 + x) + (char) (32 + y);
        }
        var bytes = new byte[seq.length()];
        for (var i = 0; i < bytes.length; i++) bytes[i] = (byte) seq.charAt(i);
        push(bytes);
        return true;
    }

    private void controlChar(int c) {
        var sig = (mode & MODE_NOSIG) == 0;
        if (sig && c == 3) {
            line.reset();
            echo("^C\n");
            signal.accept(SIGINT);
            return;
        }
        if (sig && c == 28) {
            signal.accept(SIGQUIT);
            return;
        }
        if (raw()) {
            push(new byte[]{(byte) (c == '\r' && icrnl() ? '\n' : c)});
            return;
        }
        if (c == 4) {
            if (line.size() == 0) {
                eofCount++;
                notifyAll();
                notifier.signal();
            } else {
                push(line.toByteArray());
                line.reset();
            }
        } else if (c == 21) {
            var n = new String(line.toByteArray(), StandardCharsets.UTF_8).codePointCount(0, line.size());
            line.reset();
            echo("\b \b".repeat(n));
        } else if (c == 12) {
            write("\u001b[2J\u001b[H");
        }
    }

    public synchronized int read(ByteBuffer dst, boolean nonBlocking) throws FsException, InterruptedException {
        if (raw() && (mode & MODE_TIMED) != 0 && !nonBlocking) return readTimed(dst, (mode >> 8) & 0xFF, (mode >> 16) & 0xFF);
        while (queueStart == queueEnd) {
            if (hungUp) return 0;
            if (eofCount > 0) {
                eofCount--;
                return 0;
            }
            if (nonBlocking) throw new FsException(Errno.EAGAIN);
            readers++;
            try {
                wait();
            } finally {
                readers--;
            }
        }
        var n = Math.min(dst.remaining(), queueEnd - queueStart);
        if (!raw()) {
            // In cooked mode, deliver at most one line per read like a real terminal.
            for (var i = queueStart; i < queueStart + n; i++) {
                if (queue[i] == '\n') {
                    n = i - queueStart + 1;
                    break;
                }
            }
        }
        dst.put(queue, queueStart, n);
        queueStart += n;
        if (queueStart == queueEnd) queueStart = queueEnd = 0;
        return n;
    }

    /**
     * Non-canonical read with termios VMIN/VTIME (VTIME in tenths of a second), as on Linux: 0/0 polls, 0/T waits up
     * to T for any input, N/0 waits for N bytes, N/T waits for the first byte and then until N bytes or a gap of T.
     */
    private int readTimed(ByteBuffer dst, int vmin, int vtime) throws InterruptedException {
        var want = Math.max(1, Math.min(vmin, dst.remaining()));
        long timeout = vtime * 100L;
        if (vmin == 0) {
            if (queueStart == queueEnd && vtime > 0) waitFor(1, timeout);
        } else {
            waitFor(1, 0);
            while (available() < want) {
                var before = available();
                waitFor(before + 1, vtime > 0 ? timeout : 0);
                if (available() == before) break; // inter-byte timer expired
            }
        }
        var n = Math.min(dst.remaining(), available());
        dst.put(queue, queueStart, n);
        queueStart += n;
        if (queueStart == queueEnd) queueStart = queueEnd = 0;
        return n;
    }

    private int available() {
        return queueEnd - queueStart;
    }

    /** Wait until at least {@code count} bytes are queued, or {@code timeoutMs} passed (0 = no limit). */
    private void waitFor(int count, long timeoutMs) throws InterruptedException {
        var deadline = timeoutMs > 0 ? System.nanoTime() + timeoutMs * 1_000_000 : 0;
        readers++;
        try {
            while (available() < count) {
                if (timeoutMs <= 0) {
                    wait();
                    continue;
                }
                var left = (deadline - System.nanoTime()) / 1_000_000;
                if (left <= 0) return;
                wait(left);
            }
        } finally {
            readers--;
        }
    }

    public synchronized boolean readReady() {
        return queueStart != queueEnd || eofCount > 0 || hungUp;
    }

    /** Whether a process is blocked reading from the terminal (used to show the cursor). */
    public synchronized boolean hasReaders() {
        return readers > 0;
    }

    public OpenFile open() {
        synchronized (this) {
            opened++;
        }
        return new TtyFile();
    }

    /** An open descriptor of this terminal. */
    public final class TtyFile extends OpenFile {
        public Tty tty() {
            return Tty.this;
        }

        @Override
        protected void onClose() {
            Runnable last = null;
            synchronized (Tty.this) {
                if (--opened == 0) last = onLastClose;
            }
            if (last != null) last.run();
        }

        @Override
        public int read(ByteBuffer dst, boolean nonBlocking) throws FsException, InterruptedException {
            checkRevoked();
            return Tty.this.read(dst, nonBlocking);
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
        public int write(ByteBuffer src, boolean nonBlocking) throws FsException {
            checkRevoked();
            if (hungUp()) throw new FsException(Errno.EIO);
            var n = src.remaining();
            if (src.hasArray()) {
                Tty.this.write(src.array(), src.arrayOffset() + src.position(), n);
                src.position(src.limit());
            } else {
                var bytes = new byte[n];
                src.get(bytes);
                Tty.this.write(bytes, 0, n);
            }
            return n;
        }

        @Override
        public boolean readReady() {
            return revoked() || Tty.this.readReady();
        }

        @Override
        public boolean isTerminal() {
            return true;
        }

        @Override
        public String path() {
            return terminal != null ? "/dev/tty" : "";
        }
    }
}
