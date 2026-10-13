package dev.ccbaremetal.kernel.lua;

import org.squiddev.cobalt.LuaString;
import org.squiddev.cobalt.LuaValue;
import org.squiddev.cobalt.ValueFactory;

import java.nio.charset.StandardCharsets;

/**
 * Conversion between Lua byte strings and Java text.
 *
 * <p>CC treats strings as bytes in its own Latin-1 based charset. We keep that behaviour, and additionally accept
 * UTF-8: a Lua string that is valid UTF-8 and contains multi-byte sequences is decoded as UTF-8, anything else as
 * Latin-1 (so bytes 0x80-0x9F are still CC's drawing characters). Java to Lua uses Latin-1 when every character
 * fits, else UTF-8.
 */
public final class LuaText {
    private LuaText() {
    }

    public static String decode(byte[] bytes, int offset, int length) {
        var multibyte = false;
        var i = offset;
        var end = offset + length;
        while (i < end) {
            var b = bytes[i] & 0xFF;
            int extra;
            if (b < 0x80) {
                i++;
                continue;
            } else if ((b & 0xE0) == 0xC0 && b >= 0xC2) {
                extra = 1;
            } else if ((b & 0xF0) == 0xE0) {
                extra = 2;
            } else if ((b & 0xF8) == 0xF0 && b <= 0xF4) {
                extra = 3;
            } else {
                return latin1(bytes, offset, length);
            }
            if (i + extra >= end) return latin1(bytes, offset, length);
            for (var k = 1; k <= extra; k++) {
                if ((bytes[i + k] & 0xC0) != 0x80) return latin1(bytes, offset, length);
            }
            multibyte = true;
            i += extra + 1;
        }
        return multibyte ? new String(bytes, offset, length, StandardCharsets.UTF_8) : latin1(bytes, offset, length);
    }

    private static String latin1(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.ISO_8859_1);
    }

    public static String decode(LuaValue value) {
        if (value instanceof LuaString s) {
            var bytes = new byte[s.length()];
            s.copyTo(bytes, 0);
            return decode(bytes, 0, bytes.length);
        }
        return value.toString();
    }

    public static byte[] bytes(LuaValue value) {
        if (value instanceof LuaString s) {
            var bytes = new byte[s.length()];
            s.copyTo(bytes, 0);
            return bytes;
        }
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static LuaString encode(String s) {
        for (var i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0xFF) return ValueFactory.valueOf(s.getBytes(StandardCharsets.UTF_8));
        }
        return ValueFactory.valueOf(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
