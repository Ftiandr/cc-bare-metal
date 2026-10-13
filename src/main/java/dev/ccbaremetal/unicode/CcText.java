package dev.ccbaremetal.unicode;

import java.nio.charset.StandardCharsets;

/**
 * CC: Tweaked keeps labels and titles (computer labels, disk labels, printout titles) as Lua does: one char per byte.
 * UTF-8 text therefore shows as "ÐŸÑ€Ð¸Ð²ÐµÑ‚" wherever Minecraft draws it. {@link #display} turns such a string into the
 * text it means, for names and tooltips; {@link #toBytes} turns it back when CC reads a name (so Lua still gets the
 * same bytes it stored).
 */
public final class CcText {
    /** Mixin target: Component.literal(String). */
    public static final String LITERAL = "Lnet/minecraft/network/chat/Component;literal(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;";

    private CcText() {
    }

    private static int sequence(String s, int i) {
        int b0 = s.charAt(i);
        int length;
        if (b0 >= 0xC2 && b0 <= 0xDF) length = 2;
        else if (b0 >= 0xE0 && b0 <= 0xEF) length = 3;
        else if (b0 >= 0xF0 && b0 <= 0xF4) length = 4;
        else return 0;
        if (i + length > s.length()) return 0;
        for (var k = 1; k < length; k++) {
            int b = s.charAt(i + k);
            if (b > 0xFF || (b & 0xC0) != 0x80) return 0;
        }
        int b1 = s.charAt(i + 1);
        if (b0 == 0xE0 && b1 < 0xA0 || b0 == 0xED && b1 > 0x9F || b0 == 0xF0 && b1 < 0x90 || b0 == 0xF4 && b1 > 0x8F) return 0;
        return length;
    }

    /** CC's byte string to display text: valid UTF-8 sequences become their characters, other bytes stay. */
    public static String display(String bytes) {
        if (bytes == null || !UnicodeConfig.enabled()) return bytes;
        StringBuilder out = null;
        for (var i = 0; i < bytes.length(); ) {
            var length = bytes.charAt(i) <= 0xFF ? sequence(bytes, i) : 0;
            if (length == 0) {
                if (out != null) out.append(bytes.charAt(i));
                i++;
                continue;
            }
            if (out == null) out = new StringBuilder(bytes.substring(0, i));
            var raw = new byte[length];
            for (var k = 0; k < length; k++) raw[k] = (byte) bytes.charAt(i + k);
            out.append(new String(raw, StandardCharsets.UTF_8));
            i += length;
        }
        return out == null ? bytes : out.toString();
    }

    /** Text (possibly with characters above U+00FF) to CC's byte string (UTF-8 bytes, one char each). */
    public static String toBytes(String text) {
        if (text == null || !UnicodeConfig.enabled()) return text;
        var wide = false;
        for (var i = 0; i < text.length() && !wide; i++) wide = text.charAt(i) > 0xFF;
        if (!wide) return text;
        var out = new StringBuilder(text.length() * 2);
        text.codePoints().forEach(cp -> {
            if (cp <= 0xFF) {
                out.append((char) cp);
            } else {
                for (var b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) out.append((char) (b & 0xFF));
            }
        });
        return out.toString();
    }

    /**
     * CC's label rules (printable ASCII and Latin-1 except '§', otherwise '?', at most 32 characters), plus whole
     * UTF-8 characters kept as their bytes; text with wider characters is converted to such bytes first.
     */
    public static String normaliseLabel(String text) {
        text = toBytes(text);
        var out = new StringBuilder();
        var characters = 0;
        for (var i = 0; i < text.length() && characters < 32; characters++) {
            var length = sequence(text, i);
            if (length > 0) {
                out.append(text, i, i + length);
                i += length;
                continue;
            }
            var c = text.charAt(i++);
            out.append((c >= ' ' && c <= '~') || (c >= 161 && c <= 255 && c != 167) ? c : '?');
        }
        return out.toString();
    }
}
