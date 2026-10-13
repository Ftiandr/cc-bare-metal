package dev.ccbaremetal.network;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Deflate helpers for screen data. */
public final class Compression {
    private Compression() {
    }

    public static byte[] deflate(byte[] data) {
        var deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(data);
            deflater.finish();
            var out = new ByteArrayOutputStream(Math.max(64, data.length / 4));
            var buffer = new byte[16384];
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    public static byte[] inflate(byte[] data, int maxSize) {
        var inflater = new Inflater();
        try {
            inflater.setInput(data);
            var out = new ByteArrayOutputStream(Math.max(64, data.length * 4));
            var buffer = new byte[16384];
            while (!inflater.finished()) {
                var n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                out.write(buffer, 0, n);
                if (out.size() > maxSize) throw new IllegalArgumentException("Screen data too large");
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("Corrupt screen data", e);
        } finally {
            inflater.end();
        }
    }
}
