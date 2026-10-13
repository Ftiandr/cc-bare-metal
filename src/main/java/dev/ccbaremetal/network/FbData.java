package dev.ccbaremetal.network;

import dev.ccbaremetal.kernel.gfx.Framebuffer;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** A framebuffer update on the wire: mode, optional palette and a deflated, bit-packed dirty rectangle. */
public record FbData(int width, int height, int bpp, int[] palette, int x, int y, int w, int h, byte[] packed) {
    public static final StreamCodec<ByteBuf, FbData> CODEC = new StreamCodec<>() {
        @Override
        public FbData decode(ByteBuf buf) {
            var width = ByteBufCodecs.VAR_INT.decode(buf);
            var height = ByteBufCodecs.VAR_INT.decode(buf);
            var bpp = buf.readUnsignedByte();
            var paletteSize = ByteBufCodecs.VAR_INT.decode(buf);
            if (paletteSize > 256) throw new IllegalArgumentException("Palette too large");
            var palette = new int[paletteSize];
            for (var i = 0; i < paletteSize; i++) palette[i] = buf.readMedium();
            var x = ByteBufCodecs.VAR_INT.decode(buf);
            var y = ByteBufCodecs.VAR_INT.decode(buf);
            var w = ByteBufCodecs.VAR_INT.decode(buf);
            var h = ByteBufCodecs.VAR_INT.decode(buf);
            var packed = ByteBufCodecs.byteArray(4 << 20).decode(buf);
            return new FbData(width, height, bpp, palette, x, y, w, h, packed);
        }

        @Override
        public void encode(ByteBuf buf, FbData d) {
            ByteBufCodecs.VAR_INT.encode(buf, d.width);
            ByteBufCodecs.VAR_INT.encode(buf, d.height);
            buf.writeByte(d.bpp);
            ByteBufCodecs.VAR_INT.encode(buf, d.palette.length);
            for (var c : d.palette) buf.writeMedium(c);
            ByteBufCodecs.VAR_INT.encode(buf, d.x);
            ByteBufCodecs.VAR_INT.encode(buf, d.y);
            ByteBufCodecs.VAR_INT.encode(buf, d.w);
            ByteBufCodecs.VAR_INT.encode(buf, d.h);
            ByteBufCodecs.byteArray(4 << 20).encode(buf, d.packed);
        }
    };

    public static FbData of(Framebuffer.Update u) {
        return new FbData(u.width(), u.height(), u.bpp(), u.palette() == null ? new int[0] : u.palette(), u.x(), u.y(), u.w(), u.h(),
            Compression.deflate(u.packed()));
    }
}
