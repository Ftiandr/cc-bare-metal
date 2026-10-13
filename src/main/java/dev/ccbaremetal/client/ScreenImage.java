package dev.ccbaremetal.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.ccbaremetal.kernel.gfx.Framebuffer;
import dev.ccbaremetal.kernel.gfx.TermFont;
import dev.ccbaremetal.network.Compression;
import dev.ccbaremetal.network.FbData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Client copy of a screen: terminal (with its scrollback history) and/or framebuffer, rasterised into a dynamic
 * texture at native resolution (one texel per font or framebuffer pixel); the GUI scales it up. Used by the computer
 * GUI and by pixel monitors (framebuffer only).
 *
 * <p>Lines are addressed by absolute index: history lines from {@link #historyFirst()}, then the terminal rows from
 * {@link #historyEnd()}. This keeps a selection on the same text while the output scrolls.
 */
public final class ScreenImage implements AutoCloseable {
    private static final int MAX_HISTORY = 2000;
    private static int counter;

    private final ResourceLocation id;
    private DynamicTexture texture;
    private NativeImage image;

    // Terminal
    private int termWidth;
    private int termHeight;
    private char[] text = new char[0];
    private byte[] colours = new byte[0];
    private final int[] termPalette = new int[16];
    public int cursorX;
    public int cursorY;
    public boolean cursorBlink;
    public int cursorColour;
    private boolean alternate;
    private boolean mouseReporting;

    // History
    private record Line(char[] text, byte[] colours) {
    }

    private final List<Line> history = new ArrayList<>();
    private long historyFirst;
    private int historyEpoch = -1;

    // View: lines scrolled back from the live terminal, and the selection (absolute line, column; inclusive).
    private int scrollOffset;
    private boolean hasSelection;
    private long selStartLine;
    private int selStartCol;
    private long selEndLine;
    private int selEndCol;

    // Framebuffer
    private int fbWidth = 1;
    private int fbHeight = 1;
    private byte[] pixels = new byte[1];
    private final int[] fbPalette = new int[256];
    private int fbBpp = 8;
    // Framebuffer bytes received in the last two seconds, for the GUI's traffic display.
    private final java.util.ArrayDeque<long[]> received = new java.util.ArrayDeque<>();

    private boolean graphics;
    private boolean dirty = true;

    public ScreenImage(String kind) {
        id = ResourceLocation.fromNamespaceAndPath("ccbaremetal", "dynamic/" + kind + "_" + counter++);
    }

    public ResourceLocation texture() {
        return id;
    }

    public int textureWidth() {
        return image == null ? 1 : image.getWidth();
    }

    public int textureHeight() {
        return image == null ? 1 : image.getHeight();
    }

    public int fbWidth() {
        return fbWidth;
    }

    public int fbHeight() {
        return fbHeight;
    }

    public int fbBpp() {
        return fbBpp;
    }

    /** Compressed framebuffer bytes per second received recently. */
    public double bytesPerSecond() {
        var now = System.currentTimeMillis();
        while (!received.isEmpty() && received.peekFirst()[0] < now - 2000) received.removeFirst();
        var total = 0L;
        for (var r : received) total += r[1];
        return total / 2.0;
    }

    public int termWidth() {
        return termWidth;
    }

    public int termHeight() {
        return termHeight;
    }

    public boolean alternate() {
        return alternate;
    }

    public boolean mouseReporting() {
        return mouseReporting;
    }

    public void setGraphics(boolean graphics) {
        if (this.graphics != graphics) {
            this.graphics = graphics;
            dirty = true;
        }
    }

    public boolean graphics() {
        return graphics;
    }

    public void markDirty() {
        dirty = true;
    }

    /** Apply a terminal snapshot; returns how many lines were added to the history. */
    public int applyTerminal(byte[] deflated) {
        var data = ByteBuffer.wrap(Compression.inflate(deflated, 1 << 23)).order(ByteOrder.LITTLE_ENDIAN);
        var w = Short.toUnsignedInt(data.getShort());
        var h = Short.toUnsignedInt(data.getShort());
        var cx = data.getShort();
        var cy = data.getShort();
        var blink = data.get() != 0;
        var colour = data.get() & 15;
        var flags = data.get();
        if (w * h > 1 << 17) return 0;
        cursorX = cx;
        cursorY = cy;
        cursorBlink = blink;
        cursorColour = colour;
        alternate = (flags & 1) != 0;
        mouseReporting = (flags & 2) != 0;
        data.position(16);
        for (var i = 0; i < 16; i++) termPalette[i] = ((data.get() & 255) << 16) | ((data.get() & 255) << 8) | (data.get() & 255);
        termWidth = w;
        termHeight = h;
        text = new char[w * h];
        colours = new byte[w * h];
        data.order(ByteOrder.BIG_ENDIAN);
        for (var i = 0; i < w * h; i++) text[i] = data.getChar();
        data.get(colours);
        if (!graphics) dirty = true;
        if (data.remaining() < 24) return 0;

        var epoch = data.getInt();
        var start = data.getLong();
        var from = data.getLong();
        var count = data.getInt();
        if (epoch != historyEpoch || from != historyEnd()) {
            history.clear();
            historyEpoch = epoch;
            historyFirst = from;
            hasSelection = false;
        }
        for (var i = 0; i < count && data.remaining() >= 2; i++) {
            var len = Short.toUnsignedInt(data.getShort());
            var chars = new char[len];
            for (var j = 0; j < len; j++) chars[j] = data.getChar();
            var cols = new byte[len];
            data.get(cols);
            history.add(new Line(chars, cols));
        }
        var drop = (int) Math.max(Math.max(0, start - historyFirst), history.size() - MAX_HISTORY);
        if (drop > 0) {
            drop = Math.min(drop, history.size());
            history.subList(0, drop).clear();
            historyFirst += drop;
        }
        if (scrollOffset > history.size()) scrollOffset = history.size();
        return count;
    }

    public void applyFramebuffer(FbData d) {
        if (d.width() <= 0 || d.height() <= 0 || d.width() > 2048 || d.height() > 2048 || !Framebuffer.validBpp(d.bpp())) return;
        if (d.width() != fbWidth || d.height() != fbHeight) {
            fbWidth = d.width();
            fbHeight = d.height();
            pixels = new byte[fbWidth * fbHeight];
        }
        fbBpp = d.bpp();
        received.addLast(new long[]{System.currentTimeMillis(), d.packed().length + 16L + d.palette().length * 3L});
        if (received.size() > 4096) received.removeFirst();
        if (d.palette().length > 0) System.arraycopy(d.palette(), 0, fbPalette, 0, Math.min(256, d.palette().length));
        if (d.w() > 0 && d.h() > 0 && d.x() >= 0 && d.y() >= 0 && d.x() + d.w() <= fbWidth && d.y() + d.h() <= fbHeight) {
            var rect = new byte[d.w() * d.h()];
            Framebuffer.unpack(Compression.inflate(d.packed(), 1 << 22), d.bpp(), d.w(), d.h(), rect);
            for (var y = 0; y < d.h(); y++) System.arraycopy(rect, y * d.w(), pixels, (d.y() + y) * fbWidth + d.x(), d.w());
        }
        if (graphics) dirty = true;
    }

    // ---- History and view ------------------------------------------------------------------------------------------

    public long historyFirst() {
        return historyFirst;
    }

    public long historyEnd() {
        return historyFirst + history.size();
    }

    public int historySize() {
        return history.size();
    }

    public int scrollOffset() {
        return scrollOffset;
    }

    public void setScrollOffset(int offset) {
        offset = Math.max(0, Math.min(history.size(), offset));
        if (offset != scrollOffset) {
            scrollOffset = offset;
            dirty = true;
        }
    }

    /** Absolute line shown at a row of the view. */
    public long viewLine(int row) {
        return historyEnd() - scrollOffset + row;
    }

    private int lineLength(long line) {
        if (line >= historyEnd()) return line - historyEnd() < termHeight ? termWidth : 0;
        if (line < historyFirst) return 0;
        return history.get((int) (line - historyFirst)).text().length;
    }

    private char charAt(long line, int col) {
        if (line >= historyEnd()) {
            var row = (int) (line - historyEnd());
            return row < termHeight && col >= 0 && col < termWidth ? text[row * termWidth + col] : ' ';
        }
        if (line < historyFirst) return ' ';
        var l = history.get((int) (line - historyFirst)).text();
        return col >= 0 && col < l.length ? l[col] : ' ';
    }

    // ---- Selection -------------------------------------------------------------------------------------------------

    public void select(long anchorLine, int anchorCol, long line, int col) {
        hasSelection = true;
        if (anchorLine < line || (anchorLine == line && anchorCol <= col)) {
            selStartLine = anchorLine;
            selStartCol = anchorCol;
            selEndLine = line;
            selEndCol = col;
        } else {
            selStartLine = line;
            selStartCol = col;
            selEndLine = anchorLine;
            selEndCol = anchorCol;
        }
        dirty = true;
    }

    public void clearSelection() {
        if (hasSelection) {
            hasSelection = false;
            dirty = true;
        }
    }

    public boolean hasSelection() {
        return hasSelection;
    }

    private boolean selected(long line, int col) {
        if (!hasSelection || line < selStartLine || line > selEndLine) return false;
        if (line == selStartLine && col < selStartCol) return false;
        return line != selEndLine || col <= selEndCol;
    }

    private static boolean wordChar(char c) {
        return Character.isLetterOrDigit(c) || "_-./~:@%+=".indexOf(c) >= 0;
    }

    /** Select the word (double click) at a position. */
    public void selectWord(long line, int col) {
        if (!wordChar(charAt(line, col))) {
            select(line, col, line, col);
            return;
        }
        var from = col;
        var to = col;
        while (from > 0 && wordChar(charAt(line, from - 1))) from--;
        while (to + 1 < Math.max(termWidth, lineLength(line)) && wordChar(charAt(line, to + 1))) to++;
        select(line, from, line, to);
    }

    public void selectLines(long from, long to) {
        select(Math.min(from, to), 0, Math.max(from, to), Math.max(termWidth, 1) - 1);
    }

    public void selectAll() {
        selectLines(historyFirst, historyEnd() + termHeight - 1);
    }

    /** The selected text, with trailing spaces removed from each line. */
    public String selectedText() {
        if (!hasSelection) return "";
        var out = new StringBuilder();
        for (var line = selStartLine; line <= selEndLine; line++) {
            var from = line == selStartLine ? selStartCol : 0;
            var to = line == selEndLine ? selEndCol : Math.max(lineLength(line), termWidth) - 1;
            to = Math.min(to, Math.max(lineLength(line), 0) - 1);
            var sb = new StringBuilder();
            for (var col = from; col <= to; col++) sb.append(charAt(line, col));
            var end = sb.length();
            while (end > 0 && sb.charAt(end - 1) == ' ') end--;
            out.append(sb, 0, end);
            if (line != selEndLine) out.append('\n');
        }
        return out.toString();
    }

    // ---- Rasterising -----------------------------------------------------------------------------------------------

    private static int abgr(int rgb) {
        return 0xFF000000 | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
    }

    private void ensureImage(int w, int h) {
        if (image != null && image.getWidth() == w && image.getHeight() == h) return;
        if (texture != null) Minecraft.getInstance().getTextureManager().release(id);
        image = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        texture = new DynamicTexture(image);
        Minecraft.getInstance().getTextureManager().register(id, texture);
    }

    /** Upload changes for the computer GUI: the terminal view (one texel per font pixel) or the framebuffer. */
    public void updateScreen() {
        if (!dirty && texture != null) return;
        if (graphics) {
            updateMonitor();
            return;
        }
        dirty = false;
        ensureImage(Math.max(1, termWidth * TermFont.WIDTH), Math.max(1, termHeight * TermFont.HEIGHT));
        drawTerminal();
        texture.upload();
    }

    /** Upload changes of the framebuffer at its native resolution. */
    public void updateMonitor() {
        if (!dirty && texture != null) return;
        dirty = false;
        ensureImage(fbWidth, fbHeight);
        var palette = new int[256];
        for (var i = 0; i < 256; i++) palette[i] = abgr(fbPalette[i]);
        for (var y = 0; y < fbHeight; y++) {
            for (var x = 0; x < fbWidth; x++) image.setPixelRGBA(x, y, palette[pixels[y * fbWidth + x] & 255]);
        }
        texture.upload();
    }

    private void drawTerminal() {
        var font = TermFont.get();
        var palette = new int[16];
        for (var i = 0; i < 16; i++) palette[i] = abgr(termPalette[i]);
        image.fillRect(0, 0, image.getWidth(), image.getHeight(), palette[15]);
        var end = historyEnd();
        for (var row = 0; row < termHeight; row++) {
            var line = viewLine(row);
            char[] lineText;
            byte[] lineColours;
            int offset;
            int length;
            if (line >= end) {
                lineText = text;
                lineColours = colours;
                offset = (int) (line - end) * termWidth;
                length = termWidth;
            } else {
                var l = history.get((int) (line - historyFirst));
                lineText = l.text();
                lineColours = l.colours();
                offset = 0;
                length = Math.min(l.text().length, termWidth);
            }
            var py = row * TermFont.HEIGHT;
            for (var col = 0; col < termWidth; col++) {
                int fg;
                int bg;
                char c;
                if (col < length) {
                    var packed = lineColours[offset + col];
                    fg = palette[(packed >> 4) & 15];
                    bg = palette[packed & 15];
                    c = lineText[offset + col];
                } else {
                    fg = palette[0];
                    bg = palette[15];
                    c = ' ';
                }
                if (selected(line, col)) {
                    var t = fg;
                    fg = bg;
                    bg = t;
                    if (fg == bg) {
                        fg = palette[15];
                        bg = palette[0];
                    }
                }
                var px = col * TermFont.WIDTH;
                if (c == ' ') {
                    image.fillRect(px, py, TermFont.WIDTH, TermFont.HEIGHT, bg);
                    continue;
                }
                var glyph = font.glyph(c);
                for (var gy = 0; gy < TermFont.HEIGHT; gy++) {
                    for (var gx = 0; gx < TermFont.WIDTH; gx++) {
                        image.setPixelRGBA(px + gx, py + gy, TermFont.pixel(glyph, gx, gy) ? fg : bg);
                    }
                }
            }
        }
    }

    public int termPalette(int index) {
        return termPalette[index & 15];
    }

    @Override
    public void close() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(id);
            texture = null;
            image = null;
        }
    }
}
