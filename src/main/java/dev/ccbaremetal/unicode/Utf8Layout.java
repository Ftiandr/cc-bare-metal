package dev.ccbaremetal.unicode;

import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.core.terminal.TextBuffer;
import dan200.computercraft.core.util.StringUtil;
import dev.ccbaremetal.kernel.gfx.TermFont;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Unicode for CC: Tweaked's own computers, monitors, pocket computers and printouts, without changing anything a
 * program can see.
 *
 * <p>CC keeps text as bytes: one byte per terminal cell, and every program, the ROM (window, read, edit) and other
 * addons compute positions in bytes. That stays exactly as it is. Only when drawing, a line's valid UTF-8 sequences
 * are shown as one character each, in our 6x9 font (CC's own glyphs where CC has the character). So "Привет" (12
 * bytes, 12 cells) is drawn as 6 letters.
 *
 * <p>To keep layouts that programs computed in bytes (columns, boxes, buttons), the compaction stays inside a
 * segment: a segment ends where the background colour changes and before a run of two or more spaces. The cells a
 * segment saves become blank space at its end, so whatever follows a segment is drawn exactly where the program put
 * it. The cursor and mouse positions are mapped with the same layout.
 */
public final class Utf8Layout {
    /** A laid out line: glyphs and the mapping between logical cells (bytes) and visual columns. */
    public record Line(int[] codepoints, int[] cells, int[] columns, int glyphs, int[] cellToColumn, int[] columnToCell) {
        /** Visual column of a logical cell (also for the position just after the line). */
        public int column(int cell) {
            if (cell < 0) return cell;
            return cell < cellToColumn.length ? cellToColumn[cell] : cell;
        }

        /** Logical cell shown at a visual column. */
        public int cell(int column) {
            if (column < 0) return column;
            return column < columnToCell.length ? columnToCell[column] : column;
        }
    }

    private Utf8Layout() {
    }

    private static boolean continuation(int b) {
        return (b & 0xC0) == 0x80;
    }

    /** Length of a valid UTF-8 multi-byte sequence starting at {@code i}, or 0. */
    private static int sequence(TextBuffer text, int i, int end) {
        int b0 = text.charAt(i);
        if (b0 > 0xFF) return 0;
        int length;
        if (b0 >= 0xC2 && b0 <= 0xDF) length = 2;
        else if (b0 >= 0xE0 && b0 <= 0xEF) length = 3;
        else if (b0 >= 0xF0 && b0 <= 0xF4) length = 4;
        else return 0;
        if (i + length > end) return 0;
        for (var k = 1; k < length; k++) {
            int b = text.charAt(i + k);
            if (b > 0xFF || !continuation(b)) return 0;
        }
        int b1 = text.charAt(i + 1);
        if (b0 == 0xE0 && b1 < 0xA0) return 0; // overlong
        if (b0 == 0xED && b1 > 0x9F) return 0; // surrogates
        if (b0 == 0xF0 && b1 < 0x90) return 0; // overlong
        if (b0 == 0xF4 && b1 > 0x8F) return 0; // above U+10FFFF
        return length;
    }

    private static int decode(TextBuffer text, int i, int length) {
        int b0 = text.charAt(i);
        int cp = length == 2 ? b0 & 0x1F : length == 3 ? b0 & 0x0F : b0 & 0x07;
        for (var k = 1; k < length; k++) cp = cp << 6 | (text.charAt(i + k) & 0x3F);
        return cp;
    }

    /**
     * Whether the line has any UTF-8 multi-byte character, or a character above U+00FF stored directly (printed
     * pages keep characters); the common case is no and CC draws the line unchanged.
     */
    public static boolean hasUnicode(TextBuffer text) {
        var n = text.length();
        for (var i = 0; i < n; i++) {
            int b = text.charAt(i);
            if (b > 0xFF) return true;
            if (b >= 0xC2 && b <= 0xF4 && i < n - 1 && sequence(text, i, n) > 0) return true;
        }
        return false;
    }

    /**
     * Lay out a line; null when it has no multi-byte characters. {@code background} (may be null, e.g. printouts)
     * splits segments where it changes.
     */
    public static Line layout(TextBuffer text, TextBuffer background) {
        if (!UnicodeConfig.enabled() || !hasUnicode(text)) return null;
        var n = text.length();
        var codepoints = new int[n];
        var cells = new int[n];
        var columns = new int[n];
        var cellToColumn = new int[n + 1];
        var columnToCell = new int[n];
        var glyphs = 0;

        var start = 0;
        while (start < n) {
            // Find the end of this segment.
            var end = start + 1;
            while (end < n) {
                if (background != null && background.charAt(end) != background.charAt(end - 1)) break;
                if (text.charAt(end) == ' ' && text.charAt(end - 1) == ' ') break;
                end++;
            }
            // Lay out its glyphs from its start; the saved cells stay blank at its end.
            var column = start;
            var i = start;
            while (i < end) {
                var length = sequence(text, i, end);
                var cp = length > 0 ? decode(text, i, length) : text.charAt(i);
                if (length == 0) length = 1;
                codepoints[glyphs] = cp;
                cells[glyphs] = i;
                columns[glyphs] = column;
                glyphs++;
                for (var k = 0; k < length; k++) cellToColumn[i + k] = column;
                columnToCell[column] = i;
                column++;
                i += length;
            }
            for (var c = column; c < end; c++) columnToCell[c] = end - 1;
            start = end;
        }
        cellToColumn[n] = n;
        return new Line(codepoints, cells, columns, glyphs, cellToColumn, columnToCell);
    }

    /** Laid out line {@code y} of a terminal (background aware), or null. */
    public static Line layout(Terminal terminal, int y) {
        if (y < 0 || y >= terminal.getHeight()) return null;
        return layout(terminal.getLine(y), terminal.getBackgroundColourLine(y));
    }

    /** The visual column of a terminal position (cursor). */
    public static int visualX(Terminal terminal, int x, int y) {
        var line = layout(terminal, y);
        return line == null ? x : line.column(x);
    }

    /** The logical cell (0-based) under a visual column (mouse). */
    public static int logicalX(Terminal terminal, int column, int y) {
        var line = layout(terminal, y);
        return line == null ? column : line.cell(column);
    }

    // ---- Glyphs -----------------------------------------------------------------------------------------------------

    /**
     * How to draw a character: CC's own glyph index (0-255) when CC has the character, otherwise -1 (our font,
     * drawn as pixel rectangles).
     */
    public static int ccGlyph(int cp) {
        if (cp < 0x80) return cp;
        if (cp < 0xA0) return '?';
        var mapped = StringUtil.unicodeToTerminal(cp);
        return mapped >= 0 && mapped <= 255 ? mapped : -1;
    }

    /** Horizontal runs of lit pixels of a glyph in our font: {x, y, width} triples. */
    private static final ConcurrentHashMap<Integer, int[]> RUNS = new ConcurrentHashMap<>();

    public static int[] runs(int cp) {
        return RUNS.computeIfAbsent(cp, c -> {
            var glyph = TermFont.get().glyph(c);
            var out = new java.util.ArrayList<int[]>();
            for (var y = 0; y < TermFont.HEIGHT; y++) {
                var x = 0;
                while (x < TermFont.WIDTH) {
                    if (!TermFont.pixel(glyph, x, y)) {
                        x++;
                        continue;
                    }
                    var start = x;
                    while (x < TermFont.WIDTH && TermFont.pixel(glyph, x, y)) x++;
                    out.add(new int[]{start, y, x - start});
                }
            }
            var flat = new int[out.size() * 3];
            for (var i = 0; i < out.size(); i++) System.arraycopy(out.get(i), 0, flat, i * 3, 3);
            return flat;
        });
    }

    /** Number of extra quads needed to draw a terminal (for pre-sized vertex buffers). */
    public static int extraQuads(Terminal terminal) {
        var total = 0;
        for (var y = 0; y < terminal.getHeight(); y++) {
            var line = layout(terminal, y);
            if (line == null) continue;
            for (var g = 0; g < line.glyphs(); g++) {
                if (ccGlyph(line.codepoints()[g]) < 0) total += runs(line.codepoints()[g]).length / 3;
            }
        }
        return total;
    }
}
