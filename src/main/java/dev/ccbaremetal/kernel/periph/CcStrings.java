package dev.ccbaremetal.kernel.periph;

import dev.ccbaremetal.kernel.lua.LuaText;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strings as CC: Tweaked passes them to Java: a Lua string is bytes, and Java code (peripherals, addon APIs) gets one
 * char per byte. "Привет" from a CC computer arrives as its 12 UTF-8 bytes; addons that store, compare or send strings
 * rely on that. Our programs work with text, so text is converted to that form on the way in ({@link #fromText})
 * and back on the way out ({@link #toText}) - the same strings a CC computer would exchange.
 */
public final class CcStrings {
    private CcStrings() {
    }

    /** Text (from C++ / JSON) to CC's byte-per-char strings, also inside tables. */
    public static Object[] fromText(Object[] args) {
        var out = new Object[args.length];
        for (var i = 0; i < args.length; i++) out[i] = fromText(args[i]);
        return out;
    }

    public static Object fromText(Object value) {
        if (value instanceof String s) return bytes(s);
        if (value instanceof Map<?, ?> map) {
            var table = new LinkedHashMap<Object, Object>();
            for (var e : map.entrySet()) table.put(fromText(e.getKey()), fromText(e.getValue()));
            return table;
        }
        return value;
    }

    public static String bytes(String text) {
        for (var i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x80) return new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        }
        return text;
    }

    /** CC's byte-per-char string to text: valid UTF-8 is decoded, other strings are kept (CC's Latin-1 charset). */
    public static String toText(String s) {
        var high = false;
        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            if (c > 0xFF) return s; // already text
            if (c >= 0x80) high = true;
        }
        if (!high) return s;
        var raw = s.getBytes(StandardCharsets.ISO_8859_1);
        return LuaText.decode(raw, 0, raw.length);
    }
}
