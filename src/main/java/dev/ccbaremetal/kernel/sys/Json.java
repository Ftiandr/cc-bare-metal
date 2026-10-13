package dev.ccbaremetal.kernel.sys;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON for the WASM syscall boundary. Maps become objects when all keys are strings, arrays when keys are
 * 1..n (Lua-style lists); byte arrays and buffers become strings.
 */
public final class Json {
    private Json() {
    }

    public static String write(Object value) {
        return write(value, null);
    }

    /**
     * Write JSON; values that are not plain data (objects with methods returned by peripherals and APIs) go through
     * {@code encoder}, which may replace them with plain data (object handles). Without it they are written as text.
     */
    public static String write(Object value, java.util.function.Function<Object, Object> encoder) {
        var sb = new StringBuilder();
        var previous = ENCODER.get();
        ENCODER.set(encoder);
        try {
            write(sb, value, 0);
        } finally {
            ENCODER.set(previous);
        }
        return sb.toString();
    }

    private static final ThreadLocal<java.util.function.Function<Object, Object>> ENCODER = new ThreadLocal<>();

    private static void write(StringBuilder sb, Object value, int depth) {
        if (depth > 64) {
            sb.append("null");
            return;
        }
        switch (value) {
            case null -> sb.append("null");
            case Boolean b -> sb.append(b);
            case Number n -> {
                var d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
                else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
                else sb.append(d);
            }
            case String s -> string(sb, dev.ccbaremetal.kernel.periph.CcStrings.toText(s)); // CC's byte strings as text
            // Lua strings as bytes (CC returns them so to keep them exact): UTF-8 text is decoded, other bytes kept
            case byte[] b -> string(sb, dev.ccbaremetal.kernel.periph.CcStrings.toText(new String(b, StandardCharsets.ISO_8859_1)));
            case ByteBuffer b -> {
                var bytes = new byte[b.remaining()];
                b.duplicate().get(bytes);
                string(sb, dev.ccbaremetal.kernel.periph.CcStrings.toText(new String(bytes, StandardCharsets.ISO_8859_1)));
            }
            case Map<?, ?> map -> {
                if (isList(map)) {
                    sb.append('[');
                    for (var i = 1; i <= map.size(); i++) {
                        if (i > 1) sb.append(',');
                        write(sb, lookup(map, i), depth + 1);
                    }
                    sb.append(']');
                } else {
                    sb.append('{');
                    var first = true;
                    for (var entry : map.entrySet()) {
                        if (!first) sb.append(',');
                        first = false;
                        string(sb, String.valueOf(entry.getKey() instanceof Double d && d == Math.rint(d) ? (Object) (long) (double) d : entry.getKey()));
                        sb.append(':');
                        write(sb, entry.getValue(), depth + 1);
                    }
                    sb.append('}');
                }
            }
            case Collection<?> list -> {
                sb.append('[');
                var first = true;
                for (var item : list) {
                    if (!first) sb.append(',');
                    first = false;
                    write(sb, item, depth + 1);
                }
                sb.append(']');
            }
            case Object[] array -> write(sb, List.of(nullSafe(array)), depth);
            default -> {
                var encoder = ENCODER.get();
                var replaced = encoder == null || value == NULL ? null : encoder.apply(value);
                if (replaced != null) write(sb, replaced, depth + 1);
                else string(sb, value.toString());
            }
        }
    }

    private static Object[] nullSafe(Object[] array) {
        var out = new Object[array.length];
        for (var i = 0; i < array.length; i++) out[i] = array[i] == null ? NULL : array[i];
        return out;
    }

    /** Placeholder so List.of accepts nulls; written as JSON null. */
    private static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    private static Object lookup(Map<?, ?> map, int i) {
        var v = map.get((double) i);
        if (v == null) v = map.get(i);
        if (v == null) v = map.get((long) i);
        return v;
    }

    private static boolean isList(Map<?, ?> map) {
        if (map.isEmpty()) return false;
        for (var i = 1; i <= map.size(); i++) {
            if (lookup(map, i) == null) return false;
        }
        return true;
    }

    private static void string(StringBuilder sb, String s) {
        if (s.equals("null") && s == NULL.toString()) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    public static Object parse(String text) {
        var p = new Parser(text);
        p.ws();
        var value = p.value(0);
        p.ws();
        if (p.pos != text.length()) throw new IllegalArgumentException("Trailing data at " + p.pos);
        return value;
    }

    /**
     * Parse a JSON array (or a single value) into a list of call arguments, in CC's representation: nested arrays
     * become tables with keys 1..n (CC peripherals and Lua accept Maps, not Lists, as tables).
     */
    public static List<Object> parseArgs(String text) {
        return parseArgs(text, null);
    }

    /** As {@link #parseArgs(String)}; object handles ({"__object": id}) are turned back into their objects. */
    public static List<Object> parseArgs(String text, java.util.function.IntFunction<Object> objects) {
        if (text == null || text.isBlank()) return new ArrayList<>();
        var value = parse(text);
        var out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (var v : list) out.add(toCc(v, objects));
        } else {
            out.add(toCc(value, objects));
        }
        return out;
    }

    public static Object toCc(Object value) {
        return toCc(value, null);
    }

    /** JSON value to CC's Lua value representation: arrays become 1-based Maps, recursively. */
    public static Object toCc(Object value, java.util.function.IntFunction<Object> objects) {
        if (value instanceof List<?> list) {
            var table = new LinkedHashMap<Object, Object>();
            for (var i = 0; i < list.size(); i++) table.put((double) (i + 1), toCc(list.get(i), objects));
            return table;
        }
        if (value instanceof Map<?, ?> map) {
            if (objects != null && map.get("__object") instanceof Number id) {
                var object = objects.apply(id.intValue());
                if (object != null) return object;
            }
            var table = new LinkedHashMap<Object, Object>();
            for (var e : map.entrySet()) table.put(e.getKey(), toCc(e.getValue(), objects));
            return table;
        }
        return value;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        void ws() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at " + pos);
        }

        Object value(int depth) {
            if (depth > 64) throw error("Nesting too deep");
            if (pos >= s.length()) throw error("Unexpected end");
            var c = s.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) throw error("Unexpected token");
            pos += word.length();
            return value;
        }

        Object number() {
            var start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            if (start == pos) throw error("Unexpected character");
            return Double.parseDouble(s.substring(start, pos));
        }

        String string() {
            pos++;
            var sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw error("Unterminated string");
                var c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) throw error("Bad escape");
                var e = s.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > s.length()) throw error("Bad unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(e);
                }
            }
        }

        List<Object> array(int depth) {
            pos++;
            var out = new ArrayList<>();
            ws();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return out;
            }
            while (true) {
                ws();
                out.add(value(depth + 1));
                ws();
                if (pos >= s.length()) throw error("Unterminated array");
                var c = s.charAt(pos++);
                if (c == ']') return out;
                if (c != ',') throw error("Expected ','");
            }
        }

        Map<String, Object> object(int depth) {
            pos++;
            var out = new LinkedHashMap<String, Object>();
            ws();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return out;
            }
            while (true) {
                ws();
                if (pos >= s.length() || s.charAt(pos) != '"') throw error("Expected key");
                var key = string();
                ws();
                if (pos >= s.length() || s.charAt(pos++) != ':') throw error("Expected ':'");
                ws();
                out.put(key, value(depth + 1));
                ws();
                if (pos >= s.length()) throw error("Unterminated object");
                var c = s.charAt(pos++);
                if (c == '}') return out;
                if (c != ',') throw error("Expected ','");
            }
        }
    }
}
