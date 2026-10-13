package dev.ccbaremetal.kernel.tty;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * A CC-compatible text terminal: a grid of Unicode (BMP) characters, each with a foreground and background colour
 * index (0-15, where 0 is white and 15 black, matching CC's {@code colors} API and blit strings).
 *
 * <p>The terminal can be resized (the GUI picks a size that fits the player's window). Lines scrolled off the top go
 * into a scrollback history, unless the alternate screen (used by full-screen programs) is active.
 *
 * <p>All methods are synchronized; the terminal is written by guest threads and read by the server sync code.
 */
public final class Terminal {
    public static final int[] DEFAULT_PALETTE = {
        0xF0F0F0, 0xF2B233, 0xE57FD8, 0x99B2F2, 0xDEDE6C, 0x7FCC19, 0xF2B2CC, 0x4C4C4C,
        0x999999, 0x4C99B2, 0xB266E5, 0x3366CC, 0x7F664C, 0x57A64E, 0xCC4C4C, 0x111111,
    };

    public static final int MIN_WIDTH = 16;
    public static final int MIN_HEIGHT = 6;
    public static final int MAX_WIDTH = 320;
    public static final int MAX_HEIGHT = 200;
    public static final int MAX_HISTORY = 2000;

    /** A line of the scrollback history: characters and packed colours (fg << 4 | bg). */
    public record Line(char[] text, byte[] colours) {
    }

    private int width;
    private int height;
    private char[][] text;
    private byte[][] fg;
    private byte[][] bg;
    private final int[] palette = DEFAULT_PALETTE.clone();

    private final ArrayDeque<Line> history = new ArrayDeque<>();
    /** Absolute index of the line after the newest history line. */
    private long historyEnd;
    /** Bumped whenever the history is cleared, so clients drop their copy. */
    private int historyEpoch;

    // Alternate screen: the main screen and cursor saved while a full-screen program runs.
    private boolean alternate;
    private char[][] savedText;
    private byte[][] savedFg;
    private byte[][] savedBg;
    private int savedCursorX;
    private int savedCursorY;
    private boolean mouseReporting;
    /** xterm mouse tracking: 1000 clicks, 1002 + drags, 1003 + any motion; SGR (1006) encoding. */
    private int mouseMode;
    private boolean sgrMouse;
    /** Text a program put on the clipboard (OSC 52), waiting to be sent to the players viewing the screen. */
    private String clipboardRequest;
    /** xterm bracketed paste (mode 2004): pastes reach the program wrapped in ESC[200~ ... ESC[201~. */
    private boolean bracketedPaste;
    /** DECCKM (ESC[?1h): cursor keys send ESC O A instead of ESC [ A. */
    private boolean appCursor;
    /** DECSTBM scrolling region (inclusive rows); the whole screen unless a program set one. */
    private int scrollTop;
    private int scrollBottom;

    private int cursorX;
    private int cursorY;
    private boolean cursorBlink = true;
    private int textColour = 0;
    private int backgroundColour = 15;
    private long version;

    public Terminal(int width, int height) {
        this.width = width;
        this.height = height;
        text = new char[height][width];
        fg = new byte[height][width];
        bg = new byte[height][width];
        reset();
    }

    public synchronized void reset() {
        if (alternate) setAlternate(false);
        mouseReporting = false;
        mouseMode = 0;
        sgrMouse = false;
        bracketedPaste = false;
        appCursor = false;
        scrollTop = 0;
        scrollBottom = height - 1;
        cursorX = cursorY = 0;
        textColour = 0;
        backgroundColour = 15;
        cursorBlink = true;
        System.arraycopy(DEFAULT_PALETTE, 0, palette, 0, 16);
        clear();
    }

    public synchronized int width() {
        return width;
    }

    public synchronized int height() {
        return height;
    }

    /**
     * Change the size. Content is kept anchored at the top-left; when shrinking would cut off the cursor line, the
     * top lines are pushed into the history instead. Returns false if nothing changed.
     */
    public synchronized boolean resize(int newWidth, int newHeight) {
        newWidth = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, newWidth));
        newHeight = Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, newHeight));
        if (newWidth == width && newHeight == height) return false;
        var shift = Math.max(0, cursorY - newHeight + 1);
        if (!alternate) {
            for (var y = 0; y < shift && y < height; y++) pushHistory(y);
        }
        var nt = new char[newHeight][newWidth];
        var nf = new byte[newHeight][newWidth];
        var nb = new byte[newHeight][newWidth];
        copyResized(text, fg, bg, width, height, shift, nt, nf, nb, newWidth, newHeight);
        if (alternate && savedText != null) {
            var st = new char[newHeight][newWidth];
            var sf = new byte[newHeight][newWidth];
            var sb = new byte[newHeight][newWidth];
            var savedShift = Math.max(0, savedCursorY - newHeight + 1);
            copyResized(savedText, savedFg, savedBg, width, height, savedShift, st, sf, sb, newWidth, newHeight);
            savedText = st;
            savedFg = sf;
            savedBg = sb;
            savedCursorY -= savedShift;
        }
        text = nt;
        fg = nf;
        bg = nb;
        width = newWidth;
        height = newHeight;
        scrollTop = 0;
        scrollBottom = height - 1;
        cursorY -= shift;
        changed();
        return true;
    }

    private void copyResized(char[][] t, byte[][] f, byte[][] b, int w, int h, int shift,
                             char[][] nt, byte[][] nf, byte[][] nb, int nw, int nh) {
        for (var y = 0; y < nh; y++) {
            var src = y + shift;
            var n = 0;
            if (src < h) {
                n = Math.min(w, nw);
                System.arraycopy(t[src], 0, nt[y], 0, n);
                System.arraycopy(f[src], 0, nf[y], 0, n);
                System.arraycopy(b[src], 0, nb[y], 0, n);
            }
            Arrays.fill(nt[y], n, nw, ' ');
            Arrays.fill(nf[y], n, nw, (byte) textColour);
            Arrays.fill(nb[y], n, nw, (byte) backgroundColour);
        }
    }

    // ---- History, alternate screen, mouse reporting ------------------------------------------------------------

    private void pushHistory(int y) {
        // The history keeps the row as it was shown (bytes written by Lua programs compacted to characters).
        var shown = RawText.layout(text[y], fg[y], bg[y]);
        var rowFg = shown == null ? fg[y] : shown.fg();
        var rowBg = shown == null ? bg[y] : shown.bg();
        var colours = new byte[width];
        for (var x = 0; x < width; x++) colours[x] = (byte) ((rowFg[x] << 4) | (rowBg[x] & 15));
        var line = shown == null ? text[y] : shown.text();
        var end = width;
        while (end > 0 && line[end - 1] == ' ' && rowBg[end - 1] == 15) end--;
        history.addLast(new Line(Arrays.copyOf(line, end), Arrays.copyOf(colours, end)));
        if (history.size() > MAX_HISTORY) history.removeFirst();
        historyEnd++;
    }

    public synchronized void clearHistory() {
        history.clear();
        historyEpoch++;
        changed();
    }

    public synchronized int historySize() {
        return history.size();
    }

    /** Switch to/from the alternate screen (xterm mode 1049): the main screen is saved and restored untouched. */
    public synchronized void setAlternate(boolean on) {
        if (on == alternate) return;
        if (on) {
            savedText = text;
            savedFg = fg;
            savedBg = bg;
            savedCursorX = cursorX;
            savedCursorY = cursorY;
            text = new char[height][width];
            fg = new byte[height][width];
            bg = new byte[height][width];
            alternate = true;
            for (var y = 0; y < height; y++) clearRow(y);
        } else {
            text = savedText;
            fg = savedFg;
            bg = savedBg;
            cursorX = savedCursorX;
            cursorY = savedCursorY;
            savedText = null;
            savedFg = null;
            savedBg = null;
            alternate = false;
        }
        changed();
    }

    public synchronized boolean alternate() {
        return alternate;
    }

    /** Whether the running program asked for mouse events (xterm modes 1000/1002/1003). */
    public synchronized void setMouseReporting(boolean on) {
        if (mouseReporting != on) {
            mouseReporting = on;
            changed();
        }
    }

    /** Enable or disable one xterm mouse tracking mode (1000, 1002 or 1003). */
    public synchronized void setMouseMode(int mode, boolean on) {
        if (on) mouseMode = Math.max(mouseMode, mode);
        else if (mouseMode == mode || mode == 1000) mouseMode = 0;
        setMouseReporting(mouseMode != 0);
    }

    public synchronized int mouseMode() {
        return mouseMode;
    }

    public synchronized void setSgrMouse(boolean on) {
        sgrMouse = on;
    }

    public synchronized boolean sgrMouse() {
        return sgrMouse;
    }

    /** OSC 52: a program asked to put text on the clipboard. */
    public synchronized void requestClipboard(String text) {
        clipboardRequest = text;
    }

    public synchronized String takeClipboardRequest() {
        var t = clipboardRequest;
        clipboardRequest = null;
        return t;
    }

    public synchronized void setBracketedPaste(boolean on) {
        bracketedPaste = on;
    }

    public synchronized boolean bracketedPaste() {
        return bracketedPaste;
    }

    public synchronized void setAppCursor(boolean on) {
        appCursor = on;
    }

    public synchronized boolean appCursor() {
        return appCursor;
    }

    public synchronized boolean mouseReporting() {
        return mouseReporting;
    }

    private void changed() {
        version++;
    }

    public synchronized long version() {
        return version;
    }

    public synchronized void clear() {
        for (var y = 0; y < height; y++) clearRow(y);
        changed();
    }

    private void clearRow(int y) {
        Arrays.fill(text[y], ' ');
        Arrays.fill(fg[y], (byte) textColour);
        Arrays.fill(bg[y], (byte) backgroundColour);
    }

    public synchronized void clearLine() {
        if (cursorY >= 0 && cursorY < height) {
            clearRow(cursorY);
            changed();
        }
    }

    /** Clear part of the current line: mode 0 = cursor to end, 1 = start to cursor. */
    public synchronized void clearLinePart(int mode) {
        if (cursorY < 0 || cursorY >= height) return;
        int from = mode == 0 ? Math.max(cursorX, 0) : 0;
        int to = mode == 0 ? width : Math.min(cursorX + 1, width);
        for (var x = from; x < to; x++) {
            text[cursorY][x] = ' ';
            fg[cursorY][x] = (byte) textColour;
            bg[cursorY][x] = (byte) backgroundColour;
        }
        changed();
    }

    /** Clear part of the screen: mode 0 = cursor to end, 1 = start to cursor. */
    public synchronized void clearScreenPart(int mode) {
        clearLinePart(mode);
        if (mode == 0) {
            for (var y = Math.max(cursorY + 1, 0); y < height; y++) clearRow(y);
        } else {
            for (var y = 0; y < Math.min(cursorY, height); y++) clearRow(y);
        }
        changed();
    }

    /** Write text at the cursor without wrapping (CC term.write semantics). */
    public synchronized void write(char[] chars, int offset, int length) {
        var y = cursorY;
        if (y >= 0 && y < height) {
            for (var i = 0; i < length; i++) {
                var x = cursorX + i;
                if (x < 0) continue;
                if (x >= width) break;
                text[y][x] = chars[offset + i];
                fg[y][x] = (byte) textColour;
                bg[y][x] = (byte) backgroundColour;
            }
        }
        cursorX += length;
        changed();
    }

    public void write(String s) {
        var chars = s.toCharArray();
        write(chars, 0, chars.length);
    }

    /** CC term.blit: text plus per-character colour strings ('0'-'f'). */
    public synchronized void blit(char[] chars, byte[] fgs, byte[] bgs) {
        var y = cursorY;
        if (y >= 0 && y < height) {
            for (var i = 0; i < chars.length; i++) {
                var x = cursorX + i;
                if (x < 0) continue;
                if (x >= width) break;
                text[y][x] = chars[i];
                fg[y][x] = (byte) hexDigit(fgs[i], textColour);
                bg[y][x] = (byte) hexDigit(bgs[i], backgroundColour);
            }
        }
        cursorX += chars.length;
        changed();
    }

    public static int hexDigit(byte b, int fallback) {
        if (b >= '0' && b <= '9') return b - '0';
        if (b >= 'a' && b <= 'f') return b - 'a' + 10;
        if (b >= 'A' && b <= 'F') return b - 'A' + 10;
        return fallback;
    }

    /** Put a single character at the cursor and advance, wrapping and scrolling like a teletype. */
    public synchronized void putWrapping(char c) {
        if (cursorX >= width) newline();
        if (cursorY >= height) scrollToCursor();
        if (cursorX < 0) cursorX = 0;
        if (cursorY >= 0) {
            text[cursorY][cursorX] = c;
            fg[cursorY][cursorX] = (byte) textColour;
            bg[cursorY][cursorX] = (byte) backgroundColour;
        }
        cursorX++;
        changed();
    }

    public synchronized void newline() {
        cursorX = 0;
        index();
    }

    /** Line feed without carriage return (ESC D): at the bottom of the scrolling region the region scrolls up. */
    public synchronized void index() {
        if (cursorY == scrollBottom && !fullRegion()) {
            scrollRegion(scrollTop, scrollBottom, 1);
        } else {
            cursorY++;
            scrollToCursor();
        }
        changed();
    }

    /** Reverse line feed (ESC M): at the top of the scrolling region the region scrolls down. */
    public synchronized void reverseIndex() {
        if (cursorY == scrollTop) scrollRegion(scrollTop, scrollBottom, -1);
        else if (cursorY > 0) cursorY--;
        changed();
    }

    private boolean fullRegion() {
        return scrollTop == 0 && scrollBottom == height - 1;
    }

    /** DECSTBM: rows top..bottom (0-based, inclusive) scroll; invalid ranges reset to the whole screen. */
    public synchronized void setScrollRegion(int top, int bottom) {
        if (top < 0 || bottom >= height || top >= bottom) {
            top = 0;
            bottom = height - 1;
        }
        scrollTop = top;
        scrollBottom = bottom;
    }

    public synchronized int scrollTop() {
        return scrollTop;
    }

    public synchronized int scrollBottom() {
        return scrollBottom;
    }

    /** SU/SD (ESC[nS, ESC[nT): scroll the scrolling region; the whole screen also feeds the history. */
    public synchronized void scrollRegion(int lines) {
        if (fullRegion()) scroll(lines);
        else scrollRegion(scrollTop, scrollBottom, lines);
    }

    /** Scroll rows top..bottom by n lines (up if positive) without touching the history; new rows are blank. */
    private void scrollRegion(int top, int bottom, int lines) {
        var size = bottom - top + 1;
        if (lines == 0 || size <= 0) return;
        if (Math.abs(lines) >= size) {
            for (var y = top; y <= bottom; y++) clearRow(y);
        } else if (lines > 0) {
            for (var y = top; y <= bottom; y++) {
                if (y + lines <= bottom) copyRow(y + lines, y);
                else clearRow(y);
            }
        } else {
            for (var y = bottom; y >= top; y--) {
                if (y + lines >= top) copyRow(y + lines, y);
                else clearRow(y);
            }
        }
        changed();
    }

    /** IL / DL (ESC[nL, ESC[nM): insert or delete lines at the cursor inside the scrolling region. */
    public synchronized void insertLines(int n, boolean delete) {
        if (cursorY < scrollTop || cursorY > scrollBottom) return;
        scrollRegion(cursorY, scrollBottom, delete ? n : -n);
        cursorX = 0;
        changed();
    }

    /** ICH / DCH (ESC[n@, ESC[nP): insert blanks at the cursor or delete characters, shifting the rest of the line. */
    public synchronized void insertChars(int n, boolean delete) {
        if (cursorY < 0 || cursorY >= height || cursorX < 0 || cursorX >= width) return;
        n = Math.min(n, width - cursorX);
        var keep = width - cursorX - n;
        var row = cursorY;
        if (delete) {
            System.arraycopy(text[row], cursorX + n, text[row], cursorX, keep);
            System.arraycopy(fg[row], cursorX + n, fg[row], cursorX, keep);
            System.arraycopy(bg[row], cursorX + n, bg[row], cursorX, keep);
            blank(row, width - n, width);
        } else {
            System.arraycopy(text[row], cursorX, text[row], cursorX + n, keep);
            System.arraycopy(fg[row], cursorX, fg[row], cursorX + n, keep);
            System.arraycopy(bg[row], cursorX, bg[row], cursorX + n, keep);
            blank(row, cursorX, cursorX + n);
        }
        changed();
    }

    /** ECH (ESC[nX): blank n characters from the cursor without moving anything. */
    public synchronized void eraseChars(int n) {
        if (cursorY < 0 || cursorY >= height || cursorX < 0) return;
        blank(cursorY, cursorX, Math.min(width, cursorX + n));
        changed();
    }

    private void blank(int row, int from, int to) {
        if (from >= to) return;
        Arrays.fill(text[row], from, to, ' ');
        Arrays.fill(fg[row], from, to, (byte) textColour);
        Arrays.fill(bg[row], from, to, (byte) backgroundColour);
    }

    private void scrollToCursor() {
        if (cursorY >= height) {
            scroll(cursorY - height + 1);
            cursorY = height - 1;
        }
    }

    public synchronized void scroll(int lines) {
        if (lines == 0) return;
        if (lines > 0 && !alternate) {
            for (var y = 0; y < Math.min(lines, height); y++) pushHistory(y);
        }
        if (Math.abs(lines) >= height) {
            for (var y = 0; y < height; y++) clearRow(y);
        } else if (lines > 0) {
            for (var y = 0; y < height; y++) {
                if (y + lines < height) {
                    copyRow(y + lines, y);
                } else {
                    clearRow(y);
                }
            }
        } else {
            for (var y = height - 1; y >= 0; y--) {
                if (y + lines >= 0) {
                    copyRow(y + lines, y);
                } else {
                    clearRow(y);
                }
            }
        }
        changed();
    }

    private void copyRow(int from, int to) {
        System.arraycopy(text[from], 0, text[to], 0, width);
        System.arraycopy(fg[from], 0, fg[to], 0, width);
        System.arraycopy(bg[from], 0, bg[to], 0, width);
    }

    public synchronized void setCursor(int x, int y) {
        cursorX = x;
        cursorY = y;
        changed();
    }

    public synchronized int cursorX() {
        return cursorX;
    }

    public synchronized int cursorY() {
        return cursorY;
    }

    public synchronized void setCursorBlink(boolean blink) {
        cursorBlink = blink;
        changed();
    }

    public synchronized boolean cursorBlink() {
        return cursorBlink;
    }

    public synchronized void setTextColour(int c) {
        textColour = c & 15;
    }

    public synchronized int textColour() {
        return textColour;
    }

    public synchronized void setBackgroundColour(int c) {
        backgroundColour = c & 15;
    }

    public synchronized int backgroundColour() {
        return backgroundColour;
    }

    public synchronized void setPalette(int index, int rgb) {
        palette[index & 15] = rgb & 0xFFFFFF;
        changed();
    }

    public synchronized int palette(int index) {
        return palette[index & 15];
    }

    public synchronized char[] line(int y) {
        return text[y].clone();
    }

    /** Row {@code y} as it is shown (bytes written by Lua programs compacted to characters, see {@link RawText}). */
    public synchronized char[] shownLine(int y) {
        var shown = RawText.layout(text[y], fg[y], bg[y]);
        return shown == null ? text[y].clone() : shown.text();
    }

    /** The cell (0-based) of row {@code y} shown at visual column {@code column} (for mouse positions). */
    public synchronized int cellAt(int column, int y) {
        if (y < 0 || y >= height) return column;
        var shown = RawText.layout(text[y], fg[y], bg[y]);
        return shown == null ? column : shown.cell(column);
    }

    public synchronized byte[] fgLine(int y) {
        return fg[y].clone();
    }

    public synchronized byte[] bgLine(int y) {
        return bg[y].clone();
    }

    /** Result of {@link #snapshot}: the data plus the history position it covers, for incremental history sync. */
    public record Snapshot(byte[] data, long historyEnd, int historyEpoch) {
    }

    /**
     * Serialize the terminal: a 16 byte header (little endian: width, height, cursor x/y, blink, text colour, flags),
     * the palette, the UTF-16 text plane, then fg/bg packed into one byte per cell. Then the history section (big
     * endian): epoch, absolute index of the oldest retained line, absolute index of the first included line, count,
     * and the lines (length, chars, colours) newer than {@code historyFrom}. Pass -1 to include all of it.
     */
    public synchronized Snapshot snapshot(long historyFrom, int knownEpoch) {
        var historyStart = historyEnd - history.size();
        var from = knownEpoch == historyEpoch ? Math.max(historyFrom, historyStart) : historyStart;
        from = Math.min(from, historyEnd);
        var count = (int) (historyEnd - from);
        var historyBytes = 0;
        var skip = history.size() - count;
        var index = 0;
        for (var line : history) {
            if (index++ >= skip) historyBytes += 2 + line.text().length * 3;
        }
        var out = new byte[16 + 16 * 3 + width * height * 3 + 24 + historyBytes];
        // Rows with bytes written by Lua programs are sent as shown; the cursor moves with them.
        var rows = new RawText.Shown[height];
        for (var y = 0; y < height; y++) rows[y] = RawText.layout(text[y], fg[y], bg[y]);
        var shownCursorX = cursorY >= 0 && cursorY < height && rows[cursorY] != null ? rows[cursorY].column(cursorX) : cursorX;
        var i = 0;
        out[i++] = (byte) width;
        out[i++] = (byte) (width >> 8);
        out[i++] = (byte) height;
        out[i++] = (byte) (height >> 8);
        out[i++] = (byte) shownCursorX;
        out[i++] = (byte) (shownCursorX >> 8);
        out[i++] = (byte) cursorY;
        out[i++] = (byte) (cursorY >> 8);
        out[i++] = (byte) (cursorBlink ? 1 : 0);
        out[i++] = (byte) textColour;
        out[i++] = (byte) ((alternate ? 1 : 0) | (mouseReporting ? 2 : 0));
        i = 16;
        for (var c : palette) {
            out[i++] = (byte) (c >> 16);
            out[i++] = (byte) (c >> 8);
            out[i++] = (byte) c;
        }
        for (var y = 0; y < height; y++) {
            var row = rows[y] == null ? text[y] : rows[y].text();
            for (var x = 0; x < width; x++) {
                out[i++] = (byte) (row[x] >> 8);
                out[i++] = (byte) row[x];
            }
        }
        for (var y = 0; y < height; y++) {
            var rowFg = rows[y] == null ? fg[y] : rows[y].fg();
            var rowBg = rows[y] == null ? bg[y] : rows[y].bg();
            for (var x = 0; x < width; x++) out[i++] = (byte) ((rowFg[x] << 4) | (rowBg[x] & 15));
        }
        var buf = ByteBuffer.wrap(out, i, out.length - i);
        buf.putInt(historyEpoch);
        buf.putLong(historyStart);
        buf.putLong(from);
        buf.putInt(count);
        index = 0;
        for (var line : history) {
            if (index++ < skip) continue;
            buf.putShort((short) line.text().length);
            for (var c : line.text()) buf.putChar(c);
            buf.put(line.colours());
        }
        return new Snapshot(out, historyEnd, historyEpoch);
    }

    public Snapshot snapshot() {
        return snapshot(-1, -1);
    }

}
