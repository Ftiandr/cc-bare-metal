package dev.ccbaremetal.kernel.gfx;

import dev.ccbaremetal.kernel.tty.Terminal;

import java.util.Arrays;

/**
 * A framebuffer. Indexed modes store one byte per pixel, of which only {@code bpp} bits are significant (and that is
 * all that goes over the network): 1 bpp (2 colours), 2 bpp (4), 4 bpp (16) or 8 bpp (256), with a palette. Direct
 * colour modes have no palette, colours are 0xRRGGBB: 16 bpp stores RGB565 (2 bytes, little-endian), 32 bpp
 * XRGB8888 (4 bytes: blue, green, red, unused), as Linux framebuffers do; 32 bpp goes over the network as 3 bytes.
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
        return bpp == 1 || bpp == 2 || bpp == 4 || bpp == 8 || bpp == 16 || bpp == 32;
    }

    /** Bytes one pixel takes in memory (and in blit/read data): 1 in indexed modes, 2 or 4 in direct colour. */
    public static int bytesPerPixel(int bpp) {
        return bpp <= 8 ? 1 : bpp / 8;
    }

    public synchronized int bytesPerPixel() {
        return bytesPerPixel(bpp);
    }

    /** Direct colour (16 or 32 bpp): colours are RGB, there is no palette. */
    public synchronized boolean direct() {
        return bpp > 8;
    }

    public synchronized boolean setMode(int w, int h, int newBpp, int preset) {
        if (w < 1 || h < 1 || w > maxWidth || h > maxHeight || !validBpp(newBpp)) return false;
        width = w;
        height = h;
        bpp = newBpp;
        pixels = new byte[w * h * bytesPerPixel(newBpp)];
        defaultPalette(palette, newBpp, preset);
        modeDirty = true;
        paletteDirty = true;
        markAll();
        return true;
    }

    /** Fill {@code out} with the default palette for a colour depth. */
    public static void defaultPalette(int[] out, int bpp, int preset) {
        Arrays.fill(out, 0);
        if (bpp > 8) bpp = 8; // direct colour: the palette is not used; keep the 8 bpp one for a later switch
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
        return bpp >= 8 ? 0xFF : (1 << bpp) - 1;
    }

    /** Stores colour (an index, or 0xRRGGBB in direct colour) at pixel number i. */
    private void put(int i, int colour) {
        switch (bpp) {
            case 16 -> {
                int v = ((colour >> 8) & 0xF800) | ((colour >> 5) & 0x07E0) | ((colour >> 3) & 0x001F);
                pixels[i * 2] = (byte) v;
                pixels[i * 2 + 1] = (byte) (v >> 8);
            }
            case 32 -> {
                pixels[i * 4] = (byte) colour;
                pixels[i * 4 + 1] = (byte) (colour >> 8);
                pixels[i * 4 + 2] = (byte) (colour >> 16);
                pixels[i * 4 + 3] = 0;
            }
            default -> pixels[i] = (byte) (colour & mask());
        }
    }

    /** The colour at pixel number i: an index, or 0xRRGGBB in direct colour. */
    private int get(int i) {
        return switch (bpp) {
            case 16 -> {
                int v = (pixels[i * 2] & 0xFF) | (pixels[i * 2 + 1] & 0xFF) << 8;
                int r = (v >> 11) & 31, g = (v >> 5) & 63, b = v & 31;
                yield (r << 3 | r >> 2) << 16 | (g << 2 | g >> 4) << 8 | (b << 3 | b >> 2);
            }
            case 32 -> (pixels[i * 4 + 2] & 0xFF) << 16 | (pixels[i * 4 + 1] & 0xFF) << 8 | (pixels[i * 4] & 0xFF);
            default -> pixels[i] & 0xFF;
        };
    }

    /** Fills pixels [from, to) with colour. */
    private void putRun(int from, int to, int colour) {
        if (bpp <= 8) {
            Arrays.fill(pixels, from, to, (byte) (colour & mask()));
            return;
        }
        if (to <= from) return;
        put(from, colour);
        int bpb = bytesPerPixel(bpp);
        // double the filled part until the run is full
        for (int done = 1; done < to - from; ) {
            int n = Math.min(done, to - from - done);
            System.arraycopy(pixels, from * bpb, pixels, (from + done) * bpb, n * bpb);
            done += n;
        }
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
        putRun(0, width * height, colour);
        markAll();
    }

    public synchronized void setPixel(int x, int y, int colour) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        put(y * width + x, colour);
        mark(x, y, x + 1, y + 1);
    }

    public synchronized int getPixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return -1;
        return get(y * width + x);
    }

    public synchronized void fill(int x, int y, int w, int h, int colour) {
        var x0 = Math.max(0, x);
        var y0 = Math.max(0, y);
        var x1 = Math.min(width, x + w);
        var y1 = Math.min(height, y + h);
        if (x1 <= x0 || y1 <= y0) return;
        for (var yy = y0; yy < y1; yy++) putRun(yy * width + x0, yy * width + x1, colour);
        mark(x0, y0, x1, y1);
    }

    /**
     * Copy a block of pixels in this mode's format (a byte per pixel in indexed modes, 2 or 4 bytes in direct colour)
     * into the framebuffer, clipped. stride: pixels per source row.
     */
    public synchronized void blit(byte[] src, int srcOffset, int stride, int x, int y, int w, int h) {
        var x0 = Math.max(0, x);
        var y0 = Math.max(0, y);
        var x1 = Math.min(width, x + w);
        var y1 = Math.min(height, y + h);
        if (x1 <= x0 || y1 <= y0) return;
        var m = mask();
        var bpb = bytesPerPixel(bpp);
        for (var yy = y0; yy < y1; yy++) {
            var s = srcOffset + ((yy - y) * stride + (x0 - x)) * bpb;
            var d = (yy * width + x0) * bpb;
            if (m == 0xFF) {
                System.arraycopy(src, s, pixels, d, (x1 - x0) * bpb);
            } else {
                for (var i = 0; i < x1 - x0; i++) pixels[d + i] = (byte) (src[s + i] & m);
            }
            if (bpp == 32) for (var i = 0; i < x1 - x0; i++) pixels[d + i * 4 + 3] = 0;
        }
        mark(x0, y0, x1, y1);
    }

    /** Blit with a transparent colour (an index, or 0xRRGGBB in direct colour) that is skipped. */
    public synchronized void blitKeyed(byte[] src, int srcOffset, int stride, int x, int y, int w, int h, int key) {
        var x0 = Math.max(0, x);
        var y0 = Math.max(0, y);
        var x1 = Math.min(width, x + w);
        var y1 = Math.min(height, y + h);
        if (x1 <= x0 || y1 <= y0) return;
        var m = mask();
        var bpb = bytesPerPixel(bpp);
        for (var yy = y0; yy < y1; yy++) {
            var s = srcOffset + ((yy - y) * stride + (x0 - x)) * bpb;
            var d = yy * width + x0;
            for (var i = 0; i < x1 - x0; i++) {
                int v = switch (bpp) {
                    case 16 -> {
                        int raw = (src[s + i * 2] & 0xFF) | (src[s + i * 2 + 1] & 0xFF) << 8;
                        int r = (raw >> 11) & 31, g = (raw >> 5) & 63, b = raw & 31;
                        yield (r << 3 | r >> 2) << 16 | (g << 2 | g >> 4) << 8 | (b << 3 | b >> 2);
                    }
                    case 32 -> (src[s + i * 4 + 2] & 0xFF) << 16 | (src[s + i * 4 + 1] & 0xFF) << 8 | (src[s + i * 4] & 0xFF);
                    default -> src[s + i] & 0xFF;
                };
                if (v != (bpp > 8 ? key & 0xFFFFFF : key)) put(d + i, bpp > 8 ? v : v & m);
            }
        }
        mark(x0, y0, x1, y1);
    }

    /** A block of pixels in this mode's format (see {@link #blit}); outside the image reads as zero. */
    public synchronized byte[] read(int x, int y, int w, int h) {
        var bpb = bytesPerPixel(bpp);
        var out = new byte[Math.max(0, w) * Math.max(0, h) * bpb];
        for (var yy = 0; yy < h; yy++) {
            var py = y + yy;
            if (py < 0 || py >= height) continue;
            var px0 = Math.max(0, x);
            var px1 = Math.min(width, x + w);
            if (px1 <= px0) continue;
            System.arraycopy(pixels, (py * width + px0) * bpb, out, (yy * w + (px0 - x)) * bpb, (px1 - px0) * bpb);
        }
        return out;
    }

    /** Bytes of pixel memory (/dev/fbN's size): width * height * bytesPerPixel. */
    public synchronized long rawSize() {
        return pixels.length;
    }

    /** Pixel memory as /dev/fbN reads it, from byte offset off. Returns how many bytes were copied. */
    public synchronized int readRaw(long off, byte[] dst, int dstOff, int n) {
        if (off >= pixels.length) return 0;
        n = (int) Math.min(n, pixels.length - off);
        System.arraycopy(pixels, (int) off, dst, dstOff, n);
        return n;
    }

    /** Writes pixel memory at byte offset off (in this mode's format). Returns how many bytes were written. */
    public synchronized int writeRaw(long off, byte[] src, int srcOff, int n) {
        if (off >= pixels.length) return 0;
        n = (int) Math.min(n, pixels.length - off);
        System.arraycopy(src, srcOff, pixels, (int) off, n);
        if (bpp < 8) {
            var m = (byte) mask();
            for (var i = 0; i < n; i++) pixels[(int) off + i] &= m;
        }
        var rowBytes = (long) width * bytesPerPixel(bpp);
        mark(0, (int) (off / rowBytes), width, (int) Math.min(height, (off + n - 1) / rowBytes + 1));
        return n;
    }

    public synchronized void line(int x0, int y0, int x1, int y1, int colour) {
        var dx = Math.abs(x1 - x0);
        var dy = -Math.abs(y1 - y0);
        var sx = x0 < x1 ? 1 : -1;
        var sy = y0 < y1 ? 1 : -1;
        var err = dx + dy;
        var steps = 0;
        while (steps++ < 1 << 16) {
            if (x0 >= 0 && y0 >= 0 && x0 < width && y0 < height) put(y0 * width + x0, colour);
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
        for (var i = 0; i < text.length(); ) {
            var cp = text.codePointAt(i);
            i += Character.charCount(cp);
            var glyph = font.glyph(cp);
            for (var gy = 0; gy < TermFont.HEIGHT; gy++) {
                for (var gx = 0; gx < TermFont.WIDTH; gx++) {
                    var on = TermFont.pixel(glyph, gx, gy);
                    if (!on && bg < 0) continue;
                    var c = on ? fg : bg;
                    for (var sy = 0; sy < scale; sy++) {
                        var py = y + gy * scale + sy;
                        if (py < 0 || py >= height) continue;
                        for (var sx = 0; sx < scale; sx++) {
                            var px = x + gx * scale + sx;
                            if (px >= 0 && px < width) put(py * width + px, c);
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
        var bpb = bytesPerPixel(bpp);
        if (dy > 0) {
            System.arraycopy(pixels, dy * width * bpb, pixels, 0, (height - dy) * width * bpb);
            putRun((height - dy) * width, height * width, colour);
        } else {
            System.arraycopy(pixels, 0, pixels, -dy * width * bpb, (height + dy) * width * bpb);
            putRun(0, -dy * width, colour);
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
        if (bpp > 8) {
            // direct colour: RGB565 as stored (2 bytes), XRGB8888 as R, G, B (3 bytes)
            var out = bpp == 16 ? 2 : 3;
            var packed = new byte[w * h * out];
            for (var yy = 0; yy < h; yy++) {
                var base = (y + yy) * width + x;
                if (bpp == 16) {
                    System.arraycopy(pixels, base * 2, packed, yy * w * 2, w * 2);
                    continue;
                }
                for (var xx = 0; xx < w; xx++) {
                    var s = (base + xx) * 4;
                    var o = (yy * w + xx) * 3;
                    packed[o] = pixels[s + 2];
                    packed[o + 1] = pixels[s + 1];
                    packed[o + 2] = pixels[s];
                }
            }
            return new Update(width, height, bpp, withPalette ? new int[0] : null, x, y, w, h, packed);
        }
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

    /** Inverse of the direct-colour packing in {@link #pack}: 0xRRGGBB per pixel. Used by the client. */
    public static void unpackRgb(byte[] packed, int bpp, int w, int h, int[] out) {
        for (var i = 0; i < w * h; i++) {
            if (bpp == 16) {
                int v = (packed[i * 2] & 0xFF) | (packed[i * 2 + 1] & 0xFF) << 8;
                int r = (v >> 11) & 31, g = (v >> 5) & 63, b = v & 31;
                out[i] = (r << 3 | r >> 2) << 16 | (g << 2 | g >> 4) << 8 | (b << 3 | b >> 2);
            } else {
                out[i] = (packed[i * 3] & 0xFF) << 16 | (packed[i * 3 + 1] & 0xFF) << 8 | (packed[i * 3 + 2] & 0xFF);
            }
        }
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
