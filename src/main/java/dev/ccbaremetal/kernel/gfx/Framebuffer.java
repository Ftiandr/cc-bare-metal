package dev.ccbaremetal.kernel.gfx;

import dev.ccbaremetal.kernel.tty.Terminal;

import java.util.Arrays;

/**
 * An indexed-colour framebuffer. Pixels are stored one byte each, but only {@code bpp} bits are significant, and
 * that is all that goes over the network: 1 bpp (2 colours), 2 bpp (4), 4 bpp (16) or 8 bpp (256).
 *
 * <p>Thread-safe. Writers mark a dirty rectangle; the sync code takes it with {@link #takeDirty()}.
 */
public final class Framebuffer {
    public static final int PRESET_DEFAULT = 0;
    public static final int PRESET_GRAY = 1;

    private final int maxWidth;
    private final int maxHeight;
    private int width;
    private int height;
    private int bpp;
    private byte[] pixels;
    private final int[] palette = new int[256];

    private int dirtyX0;
    private int dirtyY0;
    private int dirtyX1;
    private int dirtyY1;
    private boolean paletteDirty;
    private boolean modeDirty;
    private long version;

    public Framebuffer(int maxWidth, int maxHeight, int width, int height, int bpp) {
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        setMode(width, height, bpp, PRESET_DEFAULT);
    }

    /** Common resolutions (16:9, 16:10, 4:3, 3:2) used for the default mode, smallest first. */
    private static final int[][] NICE_SIZES = {
        {128, 72}, {160, 90}, {160, 100}, {160, 120}, {192, 108}, {240, 135}, {256, 144}, {256, 160}, {256, 192},
        {320, 180}, {320, 200}, {320, 240}, {384, 216}, {400, 240}, {400, 300}, {480, 270}, {480, 320}, {512, 288},
        {512, 320}, {512, 384}, {640, 360}, {640, 400}, {640, 480}, {768, 432}, {800, 450}, {800, 480}, {800, 600},
        {960, 540}, {960, 600}, {1024, 576}, {1024, 640}, {1024, 768}, {1152, 648}, {1280, 720}, {1280, 800},
    };

    /**
     * The largest common resolution that fits in {@code w x h} (the terminal's area in font pixels) and in the
     * maximum, preferring the closest aspect ratio among equally large ones.
     */
    public static int[] niceSize(int w, int h, int maxW, int maxH) {
        int[] best = null;
        var bestArea = 0L;
        var target = (double) w / Math.max(1, h);
        for (var size : NICE_SIZES) {
            if (size[0] > w || size[1] > h || size[0] > maxW || size[1] > maxH) continue;
            var area = (long) size[0] * size[1];
            if (best == null || area > bestArea * 1.05
                || (area >= bestArea * 0.95 && Math.abs((double) size[0] / size[1] - target) < Math.abs((double) best[0] / best[1] - target))) {
                best = size;
                bestArea = area;
            }
        }
        if (best == null) return new int[]{Math.max(16, Math.min(maxW, w / 16 * 16)), Math.max(8, Math.min(maxH, h / 8 * 8))};
        return best.clone();
    }

    public static boolean validBpp(int bpp) {
        return bpp == 1 || bpp == 2 || bpp == 4 || bpp == 8;
    }

    public synchronized boolean setMode(int w, int h, int newBpp, int preset) {
        if (w < 1 || h < 1 || w > maxWidth || h > maxHeight || !validBpp(newBpp)) return false;
        width = w;
        height = h;
        bpp = newBpp;
        pixels = new byte[w * h];
        defaultPalette(palette, newBpp, preset);
        modeDirty = true;
        paletteDirty = true;
        markAll();
        return true;
    }

    /** Fill {@code out} with the default palette for a colour depth. */
    public static void defaultPalette(int[] out, int bpp, int preset) {
        Arrays.fill(out, 0);
        var colours = 1 << bpp;
        if (preset == PRESET_GRAY || bpp <= 2) {
            for (var i = 0; i < colours; i++) {
                var v = colours == 1 ? 0 : i * 255 / (colours - 1);
                out[i] = (v << 16) | (v << 8) | v;
            }
            return;
        }
        // 16 CC colours first (index 0 = black so cleared buffers are black), then a 6x6x6 cube and a grey ramp.
        int[] cc = Terminal.DEFAULT_PALETTE;
        out[0] = cc[15];
        for (var i = 1; i < 16; i++) out[i] = cc[15 - i];
        if (bpp == 8) {
            int[] levels = {0, 95, 135, 175, 215, 255};
            var i = 16;
            for (var r = 0; r < 6; r++) {
                for (var g = 0; g < 6; g++) {
                    for (var b = 0; b < 6; b++) out[i++] = (levels[r] << 16) | (levels[g] << 8) | levels[b];
                }
            }
            for (var g = 0; g < 24; g++) {
                var v = 8 + g * 10;
                out[i++] = (v << 16) | (v << 8) | v;
            }
        }
    }

    public synchronized int width() {
        return width;
    }

    public synchronized int height() {
        return height;
    }

    public synchronized int bpp() {
        return bpp;
    }

    public int maxWidth() {
        return maxWidth;
    }

    public int maxHeight() {
        return maxHeight;
    }

    private int mask() {
        return (1 << bpp) - 1;
    }

    private void markAll() {
        dirtyX0 = 0;
        dirtyY0 = 0;
        dirtyX1 = width;
        dirtyY1 = height;
        version++;
    }

    private void mark(int x0, int y0, int x1, int y1) {
        if (dirtyX1 <= dirtyX0 || dirtyY1 <= dirtyY0) {
            dirtyX0 = x0;
            dirtyY0 = y0;
            dirtyX1 = x1;
            dirtyY1 = y1;
        } else {
            dirtyX0 = Math.min(dirtyX0, x0);
            dirtyY0 = Math.min(dirtyY0, y0);
            dirtyX1 = Math.max(dirtyX1, x1);
            dirtyY1 = Math.max(dirtyY1, y1);
        }
        version++;
    }

    public synchronized long version() {
        return version;
    }

    public synchronized void setPalette(int index, int rgb) {
        if (index < 0 || index > 255) return;
        palette[index] = rgb & 0xFFFFFF;
        paletteDirty = true;
        version++;
    }

    public synchronized int palette(int index) {
        return palette[index & 255];
    }

    public synchronized int[] paletteCopy() {
        return palette.clone();
    }

    public synchronized void clear(int colour) {
        Arrays.fill(pixels, (byte) (colour & mask()));
        markAll();
    }

    public synchronized void setPixel(int x, int y, int colour) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        pixels[y * width + x] = (byte) (colour & mask());
        mark(x, y, x + 1, y + 1);
    }

    public synchronized int getPixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return -1;
        return pixels[y * width + x] & 0xFF;
    }

    public synchronized void fill(int x, int y, int w, int h, int colour) {
        var x0 = Math.max(0, x);
        var y0 = Math.max(0, y);
        var x1 = Math.min(width, x + w);
        var y1 = Math.min(height, y + h);
        if (x1 <= x0 || y1 <= y0) return;
        var c = (byte) (colour & mask());
        for (var yy = y0; yy < y1; yy++) Arrays.fill(pixels, yy * width + x0, yy * width + x1, c);
        mark(x0, y0, x1, y1);
    }

    /** Copy a block of 8-bit indices into the framebuffer, clipped. */
    public synchronized void blit(byte[] src, int srcOffset, int stride, int x, int y, int w, int h) {
        var x0 = Math.max(0, x);
        var y0 = Math.max(0, y);
        var x1 = Math.min(width, x + w);
        var y1 = Math.min(height, y + h);
        if (x1 <= x0 || y1 <= y0) return;
        var m = mask();
        for (var yy = y0; yy < y1; yy++) {
            var s = srcOffset + (yy - y) * stride + (x0 - x);
            var d = yy * width + x0;
            if (m == 0xFF) {
                System.arraycopy(src, s, pixels, d, x1 - x0);
            } else {
                for (var i = 0; i < x1 - x0; i++) pixels[d + i] = (byte) (src[s + i] & m);
            }
        }
        mark(x0, y0, x1, y1);
    }

    /** Blit with a transparent colour index that is skipped. */
    public synchronized void blitKeyed(byte[] src, int srcOffset, int stride, int x, int y, int w, int h, int key) {
        var x0 = Math.max(0, x);
        var y0 = Math.max(0, y);
        var x1 = Math.min(width, x + w);
        var y1 = Math.min(height, y + h);
        if (x1 <= x0 || y1 <= y0) return;
        var m = mask();
        for (var yy = y0; yy < y1; yy++) {
            var s = srcOffset + (yy - y) * stride + (x0 - x);
            var d = yy * width + x0;
            for (var i = 0; i < x1 - x0; i++) {
                var v = src[s + i] & 0xFF;
                if (v != key) pixels[d + i] = (byte) (v & m);
            }
        }
        mark(x0, y0, x1, y1);
    }

    public synchronized byte[] read(int x, int y, int w, int h) {
        var out = new byte[Math.max(0, w) * Math.max(0, h)];
        for (var yy = 0; yy < h; yy++) {
            for (var xx = 0; xx < w; xx++) {
                var px = x + xx;
                var py = y + yy;
                if (px >= 0 && py >= 0 && px < width && py < height) out[yy * w + xx] = pixels[py * width + px];
            }
        }
        return out;
    }

    public synchronized void line(int x0, int y0, int x1, int y1, int colour) {
        var dx = Math.abs(x1 - x0);
        var dy = -Math.abs(y1 - y0);
        var sx = x0 < x1 ? 1 : -1;
        var sy = y0 < y1 ? 1 : -1;
        var err = dx + dy;
        var c = (byte) (colour & mask());
        var steps = 0;
        while (steps++ < 1 << 16) {
            if (x0 >= 0 && y0 >= 0 && x0 < width && y0 < height) pixels[y0 * width + x0] = c;
            if (x0 == x1 && y0 == y1) break;
            var e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
        }
        mark(0, 0, width, height);
    }

    public synchronized void rect(int x, int y, int w, int h, int colour) {
        if (w <= 0 || h <= 0) return;
        fill(x, y, w, 1, colour);
        fill(x, y + h - 1, w, 1, colour);
        fill(x, y, 1, h, colour);
        fill(x + w - 1, y, 1, h, colour);
    }

    public synchronized void circle(int cx, int cy, int r, int colour, boolean filled) {
        if (r < 0 || r > 4096) return;
        for (var y = -r; y <= r; y++) {
            var span = (int) Math.round(Math.sqrt((double) r * r - (double) y * y));
            if (filled) {
                fill(cx - span, cy + y, span * 2 + 1, 1, colour);
            } else {
                setPixel(cx - span, cy + y, colour);
                setPixel(cx + span, cy + y, colour);
            }
        }
        if (!filled) {
            for (var x = -r; x <= r; x++) {
                var span = (int) Math.round(Math.sqrt((double) r * r - (double) x * x));
                setPixel(cx + x, cy - span, colour);
                setPixel(cx + x, cy + span, colour);
            }
        }
    }

    /** Draw text with the terminal font. {@code bg < 0} means transparent. Returns the end x. */
    public synchronized int text(int x, int y, String text, int fg, int bg, int scale) {
        var font = TermFont.get();
        scale = Math.max(1, Math.min(8, scale));
        var m = mask();
        for (var i = 0; i < text.length(); ) {
            var cp = text.codePointAt(i);
            i += Character.charCount(cp);
            var glyph = font.glyph(cp);
            for (var gy = 0; gy < TermFont.HEIGHT; gy++) {
                for (var gx = 0; gx < TermFont.WIDTH; gx++) {
                    var on = TermFont.pixel(glyph, gx, gy);
                    if (!on && bg < 0) continue;
                    var c = (byte) ((on ? fg : bg) & m);
                    for (var sy = 0; sy < scale; sy++) {
                        var py = y + gy * scale + sy;
                        if (py < 0 || py >= height) continue;
                        for (var sx = 0; sx < scale; sx++) {
                            var px = x + gx * scale + sx;
                            if (px >= 0 && px < width) pixels[py * width + px] = c;
                        }
                    }
                }
            }
            x += TermFont.WIDTH * scale;
        }
        mark(0, Math.max(0, y), width, Math.min(height, y + TermFont.HEIGHT * scale));
        return x;
    }

    /** Scroll the whole image up by {@code dy} pixels (down if negative), filling with {@code colour}. */
    public synchronized void scroll(int dy, int colour) {
        if (dy == 0) return;
        if (Math.abs(dy) >= height) {
            clear(colour);
            return;
        }
        if (dy > 0) {
            System.arraycopy(pixels, dy * width, pixels, 0, (height - dy) * width);
            Arrays.fill(pixels, (height - dy) * width, height * width, (byte) (colour & mask()));
        } else {
            System.arraycopy(pixels, 0, pixels, -dy * width, (height + dy) * width);
            Arrays.fill(pixels, 0, -dy * width, (byte) (colour & mask()));
        }
        markAll();
    }

    /** A packed update for the network. */
    public record Update(int width, int height, int bpp, int[] palette, int x, int y, int w, int h, byte[] packed) {
    }

    /** Take the pending change, if any: the dirty rectangle packed at the current bit depth, plus the palette. */
    public synchronized Update takeDirty() {
        var hasPixels = dirtyX1 > dirtyX0 && dirtyY1 > dirtyY0;
        if (!hasPixels && !paletteDirty && !modeDirty) return null;
        var update = pack(hasPixels ? dirtyX0 : 0, hasPixels ? dirtyY0 : 0,
            hasPixels ? dirtyX1 - dirtyX0 : 0, hasPixels ? dirtyY1 - dirtyY0 : 0, paletteDirty || modeDirty);
        dirtyX0 = dirtyY0 = dirtyX1 = dirtyY1 = 0;
        paletteDirty = modeDirty = false;
        notifyAll();
        return update;
    }

    /**
     * Frame pacing for {@code present()}: wait until pending changes have been picked up by the network sync (or the
     * timeout passes). Keeps animation loops at the sync rate instead of burning CPU.
     */
    public synchronized void awaitPresented(long timeoutMs) throws InterruptedException {
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (dirtyX1 > dirtyX0 || paletteDirty || modeDirty) {
            var remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return;
            wait(remaining);
        }
    }

    /** A full snapshot, for players who just started watching. */
    public synchronized Update full() {
        return pack(0, 0, width, height, true);
    }

    private Update pack(int x, int y, int w, int h, boolean withPalette) {
        var perByte = 8 / bpp;
        var rowBytes = (w + perByte - 1) / perByte;
        var packed = new byte[rowBytes * h];
        var m = mask();
        for (var yy = 0; yy < h; yy++) {
            var base = (y + yy) * width + x;
            var o = yy * rowBytes;
            if (bpp == 8) {
                System.arraycopy(pixels, base, packed, o, w);
                continue;
            }
            for (var xx = 0; xx < w; xx++) {
                var shift = (perByte - 1 - (xx % perByte)) * bpp;
                packed[o + xx / perByte] |= (byte) ((pixels[base + xx] & m) << shift);
            }
        }
        int[] pal = null;
        if (withPalette) pal = Arrays.copyOf(palette, 1 << bpp);
        return new Update(width, height, bpp, pal, x, y, w, h, packed);
    }

    /** Inverse of the packing in {@link #pack}: used by the client. */
    public static void unpack(byte[] packed, int bpp, int w, int h, byte[] out) {
        var perByte = 8 / bpp;
        var rowBytes = (w + perByte - 1) / perByte;
        var m = (1 << bpp) - 1;
        for (var yy = 0; yy < h; yy++) {
            for (var xx = 0; xx < w; xx++) {
                var shift = (perByte - 1 - (xx % perByte)) * bpp;
                out[yy * w + xx] = (byte) ((packed[yy * rowBytes + xx / perByte] >> shift) & m);
            }
        }
    }
}
