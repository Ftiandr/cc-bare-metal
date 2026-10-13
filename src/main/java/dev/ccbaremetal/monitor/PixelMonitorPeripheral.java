package dev.ccbaremetal.monitor;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.ccbaremetal.kernel.gfx.Framebuffer;
import dev.ccbaremetal.kernel.gfx.FramebufferProvider;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The "pixel_monitor" peripheral, usable from CC computers (Lua) and from WASM computers (Lua, C syscalls and the
 * fast {@link FramebufferProvider} path). Coordinates are 0-based pixels; colours are palette indices.
 */
public final class PixelMonitorPeripheral implements IPeripheral, FramebufferProvider {
    private final PixelMonitorBlockEntity owner;
    private final Set<IComputerAccess> computers = new CopyOnWriteArraySet<>();

    PixelMonitorPeripheral(PixelMonitorBlockEntity owner) {
        this.owner = owner;
    }

    @Override
    public String getType() {
        return "pixel_monitor";
    }

    @Override
    public Framebuffer framebuffer() {
        return owner.framebuffer();
    }

    @Override
    public void framebufferChanged() {
        owner.markDirty();
    }

    @Override
    public void attach(IComputerAccess computer) {
        computers.add(computer);
    }

    @Override
    public void detach(IComputerAccess computer) {
        computers.remove(computer);
    }

    void queueEvent(String name, Object... args) {
        for (var c : computers) {
            var full = new Object[args.length + 1];
            full[0] = c.getAttachmentName();
            System.arraycopy(args, 0, full, 1, args.length);
            try {
                c.queueEvent(name, full);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private Framebuffer fb() {
        return owner.framebuffer();
    }

    private <T> T changed(T value) {
        owner.markDirty();
        return value;
    }

    @LuaFunction
    public final Object[] getSize() {
        return new Object[]{fb().width(), fb().height()};
    }

    @LuaFunction
    public final Object[] getBlockSize() {
        return new Object[]{owner.groupWidth(), owner.groupHeight()};
    }

    @LuaFunction
    public final Object[] getMode() {
        return new Object[]{fb().width(), fb().height(), fb().bpp()};
    }

    /** setMode(bpp [, width, height [, grey]]): colour depth 1/2/4/8 and optional resolution. */
    @LuaFunction
    public final boolean setMode(int bpp, Optional<Integer> width, Optional<Integer> height, Optional<Boolean> grey) throws LuaException {
        if (!Framebuffer.validBpp(bpp)) throw new LuaException("bpp must be 1, 2, 4 or 8");
        var fb = fb();
        var ok = fb.setMode(width.orElse(fb.width()), height.orElse(fb.height()), bpp, grey.orElse(false) ? Framebuffer.PRESET_GRAY : 0);
        if (!ok) throw new LuaException("Resolution out of range (max " + fb.maxWidth() + "x" + fb.maxHeight() + ")");
        return changed(true);
    }

    @LuaFunction
    public final Object[] getMaxSize() {
        return new Object[]{fb().maxWidth(), fb().maxHeight()};
    }

    @LuaFunction
    public final void clear(Optional<Integer> colour) {
        fb().clear(colour.orElse(0));
        changed(null);
    }

    @LuaFunction
    public final void setPixel(int x, int y, int colour) {
        fb().setPixel(x, y, colour);
        changed(null);
    }

    @LuaFunction
    public final int getPixel(int x, int y) {
        return fb().getPixel(x, y);
    }

    @LuaFunction
    public final void fill(int x, int y, int w, int h, int colour) {
        fb().fill(x, y, w, h, colour);
        changed(null);
    }

    @LuaFunction
    public final void drawLine(int x0, int y0, int x1, int y1, int colour) {
        fb().line(x0, y0, x1, y1, colour);
        changed(null);
    }

    @LuaFunction
    public final void drawRect(int x, int y, int w, int h, int colour) {
        fb().rect(x, y, w, h, colour);
        changed(null);
    }

    @LuaFunction
    public final void drawCircle(int x, int y, int r, int colour, Optional<Boolean> filled) {
        fb().circle(x, y, r, colour, filled.orElse(false));
        changed(null);
    }

    /** drawText(x, y, text [, fg [, bg [, scale]]]): text in the 6x9 Unicode font; bg -1/nil is transparent. */
    @LuaFunction
    public final int drawText(IArguments args) throws LuaException {
        // Lua strings arrive one char per byte (as CC passes them); UTF-8 text is drawn as its characters.
        var text = dev.ccbaremetal.kernel.periph.CcStrings.toText(args.getString(2));
        var end = fb().text(args.getInt(0), args.getInt(1), text, args.optInt(3, 15), args.optInt(4, -1), args.optInt(5, 1));
        owner.markDirty();
        return end;
    }

    /** blit(x, y, w, h, data [, transparent]): one byte per pixel, row by row. */
    @LuaFunction
    public final void blit(int x, int y, int w, int h, ByteBuffer data, Optional<Integer> transparent) throws LuaException {
        if (w <= 0 || h <= 0) return;
        if ((long) w * h > 4L << 20) throw new LuaException("Image too large");
        if (data.remaining() < w * h) throw new LuaException("Expected " + w * h + " bytes of pixel data");
        var bytes = new byte[w * h];
        data.duplicate().get(bytes);
        if (transparent.isPresent()) fb().blitKeyed(bytes, 0, w, x, y, w, h, transparent.get());
        else fb().blit(bytes, 0, w, x, y, w, h);
        changed(null);
    }

    @LuaFunction
    public final ByteBuffer read(int x, int y, int w, int h) throws LuaException {
        if (w <= 0 || h <= 0 || (long) w * h > 4L << 20) throw new LuaException("Bad size");
        return ByteBuffer.wrap(fb().read(x, y, w, h));
    }

    /** setPalette(index, rgb) or setPalette(index, r, g, b) with components 0-1. */
    @LuaFunction
    public final void setPalette(IArguments args) throws LuaException {
        int rgb;
        if (args.count() <= 2) {
            rgb = args.getInt(1);
        } else {
            rgb = (byteOf(args.getDouble(1)) << 16) | (byteOf(args.getDouble(2)) << 8) | byteOf(args.getDouble(3));
        }
        fb().setPalette(args.getInt(0), rgb);
        changed(null);
    }

    private static int byteOf(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v * 255)));
    }

    @LuaFunction
    public final int getPalette(int index) {
        return fb().palette(index);
    }

    @LuaFunction
    public final void scroll(int dy, Optional<Integer> colour) {
        fb().scroll(dy, colour.orElse(0));
        changed(null);
    }

    /** Convenience for CC programs: write a string of '0'-'f' colours as one row of pixels. */
    @LuaFunction
    public final void blitHex(int x, int y, String row) {
        var bytes = row.getBytes(StandardCharsets.ISO_8859_1);
        var px = new byte[bytes.length];
        for (var i = 0; i < bytes.length; i++) {
            var c = bytes[i];
            px[i] = (byte) (c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : 0);
        }
        fb().blit(px, 0, px.length, x, y, px.length, 1);
        changed(null);
    }

    @LuaFunction
    public final MethodResult flush() {
        owner.markDirty();
        return MethodResult.of();
    }

    @Override
    public Object getTarget() {
        return owner;
    }

    @Override
    public boolean equals(IPeripheral other) {
        return other instanceof PixelMonitorPeripheral p && p.owner == owner;
    }
}
