package dev.ccbaremetal.kernel.tty;

/**
 * Text written by CC Lua programs (term.write, term.blit), which count everything in bytes: the ROM's read(), print's
 * word wrap, window and edit all put one byte in one cell. To keep their positions right, such text is stored one
 * byte per cell; bytes 0x80-0xFF as the private-use characters U+F780-U+F7FF, so they are told apart from real
 * characters that other programs wrote. Only what is shown is compacted: a valid UTF-8 sequence of such bytes becomes
 * one character, any other such byte its CC character (Latin-1 / CC's drawing characters).
 *
 * <p>Like the Unicode support for CC's own computers ({@code Utf8Layout}), compaction stays inside a segment (it ends
 * where the background colour changes and before two spaces): the cells a segment saves become blank at its end, so
 * whatever follows is drawn where the program put it. Cursor and mouse positions map through the same layout.
 */
public final class RawText {
    /** Byte b (0x80-0xFF) is stored as {@code BASE + b}. */
    public static final char BASE = 0xF700;

    private RawText() {
    }

    /** A byte as stored in a cell. */
    public static char cell(int b) {
        b &= 0xFF;
        return b < 0x80 ? (char) b : (char) (BASE + b);
    }

    public static boolean isRaw(char c) {
        return c >= BASE + 0x80 && c <= BASE + 0xFF;
    }

    /** CC bytes (a Lua string) as cells. */
    public static char[] cells(byte[] bytes) {
        var out = new char[bytes.length];
        for (var i = 0; i < bytes.length; i++) out[i] = cell(bytes[i]);
        return out;
    }

    /** Whether a row has stored bytes (else it is shown as it is). */
    public static boolean hasRaw(char[] text) {
        for (var c : text) if (isRaw(c)) return true;
        return false;
    }

    /** A row as shown: characters and colours per column, and the column of every cell. */
    public record Shown(char[] text, byte[] fg, byte[] bg, int[] cellToColumn, int[] columnToCell) {
        public int column(int cell) {
            return cell >= 0 && cell < cellToColumn.length ? cellToColumn[cell] : cell;
        }

        public int cell(int column) {
            return column >= 0 && column < columnToCell.length ? columnToCell[column] : column;
        }
    }

    private static int byteAt(char[] text, int i) {
        return isRaw(text[i]) ? text[i] - BASE : -1;
    }

    /** Length of a valid UTF-8 sequence of stored bytes at {@code i} (before {@code end}), or 0. */
    private static int sequence(char[] text, int i, int end) {
        var b0 = byteAt(text, i);
        int length;
        if (b0 >= 0xC2 && b0 <= 0xDF) length = 2;
        else if (b0 >= 0xE0 && b0 <= 0xEF) length = 3;
        else if (b0 >= 0xF0 && b0 <= 0xF4) length = 4;
        else return 0;
        if (i + length > end) return 0;
        for (var k = 1; k < length; k++) {
            var b = byteAt(text, i + k);
            if (b < 0 || (b & 0xC0) != 0x80) return 0;
        }
        var b1 = byteAt(text, i + 1);
        if (b0 == 0xE0 && b1 < 0xA0) return 0; // overlong
        if (b0 == 0xED && b1 > 0x9F) return 0; // surrogates
        if (b0 == 0xF0 && b1 < 0x90) return 0; // overlong
        if (b0 == 0xF4 && b1 > 0x8F) return 0; // above U+10FFFF
        return length;
    }

    /** Lay out a row; null when it has no stored bytes. */
    public static Shown layout(char[] text, byte[] fg, byte[] bg) {
        if (!hasRaw(text)) return null;
        var n = text.length;
        var shown = new char[n];
        var shownFg = new byte[n];
        var shownBg = new byte[n];
        var cellToColumn = new int[n + 1];
        var columnToCell = new int[n];
        var start = 0;
        while (start < n) {
            var end = start + 1;
            while (end < n && bg[end] == bg[end - 1] && !(text[end] == ' ' && text[end - 1] == ' ')) end++;
            var column = start;
            var i = start;
            while (i < end) {
                var length = sequence(text, i, end);
                char c;
                if (length > 0) {
                    var cp = byteAt(text, i) & (length == 2 ? 0x1F : length == 3 ? 0x0F : 0x07);
                    for (var k = 1; k < length; k++) cp = cp << 6 | (byteAt(text, i + k) & 0x3F);
                    c = cp <= 0xFFFF ? (char) cp : '�';
                } else {
                    length = 1;
                    c = isRaw(text[i]) ? (char) (text[i] - BASE) : text[i];
                }
                shown[column] = c;
                shownFg[column] = fg[i];
                shownBg[column] = bg[i];
                for (var k = 0; k < length; k++) cellToColumn[i + k] = column;
                columnToCell[column] = i;
                column++;
                i += length;
            }
            for (var c = column; c < end; c++) {
                // Cells this segment saved: blank, in its background.
                shown[c] = ' ';
                shownFg[c] = fg[end - 1];
                shownBg[c] = bg[end - 1];
                columnToCell[c] = end - 1;
            }
            start = end;
        }
        cellToColumn[n] = n;
        return new Shown(shown, shownFg, shownBg, cellToColumn, columnToCell);
    }
}
