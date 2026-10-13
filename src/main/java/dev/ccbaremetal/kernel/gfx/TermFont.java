package dev.ccbaremetal.kernel.gfx;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Our own 6x9 Unicode pixel font, shared by the server (text drawn into framebuffers) and the client (terminal
 * rendering). A glyph is a 54-bit mask in a {@code long}: bit {@code y * 6 + x}.
 *
 * <p>Sources, in order: hand-drawn glyphs ({@code glyphs.txt}), generated accented letters ({@code compose.txt},
 * base glyph + combining mark), and procedural ranges: box drawing, block elements, braille and the CC teletext
 * characters U+0080-U+009F (2x3 "sub-pixel" blocks, for compatibility with CC drawing programs).
 */
public final class TermFont {
    public static final int WIDTH = 6;
    public static final int HEIGHT = 9;

    private static final long TOFU = tofu();
    private static volatile TermFont instance;

    private final Map<Integer, Long> glyphs = new HashMap<>();

    private TermFont() {
        try {
            loadGlyphs();
            loadCompose();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load terminal font", e);
        }
    }

    public static TermFont get() {
        var font = instance;
        if (font != null) return font;
        synchronized (TermFont.class) {
            if (instance == null) instance = new TermFont();
            return instance;
        }
    }

    /** The glyph for a code point; unknown characters render as a hollow box. */
    public long glyph(int cp) {
        var g = glyphs.get(cp);
        if (g != null) return g;
        var generated = procedural(cp);
        if (generated != null) {
            synchronized (glyphs) {
                glyphs.put(cp, generated);
            }
            return generated;
        }
        return cp < 32 ? 0 : TOFU;
    }

    public boolean has(int cp) {
        return glyphs.containsKey(cp) || procedural(cp) != null;
    }

    public static boolean pixel(long glyph, int x, int y) {
        return (glyph >>> (y * WIDTH + x) & 1) != 0;
    }

    private static long set(long g, int x, int y) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return g;
        return g | 1L << (y * WIDTH + x);
    }

    private static long tofu() {
        long g = 0;
        for (var y = 1; y <= 6; y++) {
            for (var x = 0; x <= 4; x++) {
                if (y == 1 || y == 6 || x == 0 || x == 4) g = set(g, x, y);
            }
        }
        return g;
    }

    private static InputStream open(String name) throws IOException {
        var stream = TermFont.class.getResourceAsStream("/ccbaremetal/font/" + name);
        if (stream == null) throw new IOException("Missing font resource " + name);
        return stream;
    }

    private void loadGlyphs() throws IOException {
        Map<Integer, Integer> aliases = new HashMap<>();
        try (var reader = new BufferedReader(new InputStreamReader(open("glyphs.txt"), StandardCharsets.UTF_8))) {
            Integer current = null;
            long bits = 0;
            var row = 0;
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith("@")) {
                    if (current != null) glyphs.put(current, bits);
                    current = Integer.parseInt(line.substring(1).trim(), 16);
                    bits = 0;
                    row = 0;
                } else if (line.startsWith("= ")) {
                    var parts = line.substring(2).trim().split("\\s+");
                    aliases.put(Integer.parseInt(parts[0], 16), Integer.parseInt(parts[1], 16));
                } else if (current != null && !line.isEmpty() && (line.charAt(0) == '.' || line.charAt(0) == '#')) {
                    for (var x = 0; x < Math.min(WIDTH, line.length()); x++) {
                        if (line.charAt(x) == '#') bits = set(bits, x, row);
                    }
                    row++;
                }
            }
            if (current != null) glyphs.put(current, bits);
        }
        for (var entry : aliases.entrySet()) {
            var target = entry.getValue();
            var g = glyphs.containsKey(target) ? glyphs.get(target) : procedural(target);
            if (g != null) glyphs.put(entry.getKey(), g);
        }
    }

    private void loadCompose() throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(open("compose.txt"), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (!line.startsWith("+ ")) continue;
                var parts = line.substring(2).trim().split("\\s+");
                var cp = Integer.parseInt(parts[0], 16);
                var base = glyphs.get(Integer.parseInt(parts[1], 16));
                if (base == null || glyphs.containsKey(cp)) continue;
                var upper = Character.isUpperCase(cp);
                glyphs.put(cp, compose(base, Integer.parseInt(parts[2], 16), upper));
            }
        }
    }

    // ---- Accents -------------------------------------------------------------------------------------------------

    /** Two-row accent shapes (rows 0-1) for marks above; one-row variants are the second row when the first is blank. */
    private static String[] accent(int mark) {
        return switch (mark) {
            case 0x0300 -> new String[]{".#...", "..#.."};
            case 0x0301 -> new String[]{"...#.", "..#.."};
            case 0x0302 -> new String[]{"..#..", ".#.#."};
            case 0x0303 -> new String[]{".##.#", "#.##."};
            case 0x0304 -> new String[]{".....", ".###."};
            case 0x0306 -> new String[]{"#...#", ".###."};
            case 0x0307 -> new String[]{".....", "..#.."};
            case 0x0308 -> new String[]{".....", ".#.#."};
            case 0x030A -> new String[]{".###.", ".#.#."};
            case 0x030B -> new String[]{"..#.#", ".#.#."};
            case 0x030C -> new String[]{".#.#.", "..#.."};
            // Marks below, drawn on rows 7-8.
            case 0x0323 -> new String[]{"..#..", "....."};
            case 0x0326 -> new String[]{"..#..", ".#..."};
            case 0x0327 -> new String[]{"..#..", ".##.."};
            case 0x0328 -> new String[]{"..#..", "...##"};
            default -> null;
        };
    }

    private static boolean below(int mark) {
        return mark == 0x0323 || mark == 0x0326 || mark == 0x0327 || mark == 0x0328;
    }

    private static long compose(long base, int mark, boolean upper) {
        var shape = accent(mark);
        if (shape == null) return base;
        if (below(mark)) return draw(base, shape, 7);

        var oneRow = shape[0].equals(".....");
        if (!upper) {
            // Lowercase letters leave rows 0-1 free (x-height starts at row 2).
            return draw(base, shape, 0);
        }
        // Capitals fill rows 0-6, so squeeze the letter down to make room for the accent.
        var rows = oneRow ? 1 : 2;
        var squeezed = squeeze(base, rows);
        return draw(squeezed, oneRow ? new String[]{shape[1]} : shape, 0);
    }

    private static long draw(long g, String[] rows, int y0) {
        for (var r = 0; r < rows.length; r++) {
            for (var x = 0; x < rows[r].length(); x++) {
                if (rows[r].charAt(x) == '#') g = set(g, x, y0 + r);
            }
        }
        return g;
    }

    private static int row(long g, int y) {
        return (int) (g >>> (y * WIDTH) & 0x3F);
    }

    /** Remove {@code count} rows from the cap area (rows 0-6), shifting the letter down; prefers duplicate rows. */
    private static long squeeze(long g, int count) {
        var rows = new int[7];
        for (var y = 0; y < 7; y++) rows[y] = row(g, y);
        var list = new java.util.ArrayList<Integer>();
        for (var r : rows) list.add(r);
        for (var n = 0; n < count; n++) {
            var removed = false;
            for (var y = list.size() - 2; y >= 1 && !removed; y--) {
                if (list.get(y).equals(list.get(y + 1)) || list.get(y).equals(list.get(y - 1))) {
                    list.remove(y);
                    removed = true;
                }
            }
            if (!removed) list.remove(list.size() / 2);
        }
        long out = g & ~((1L << (7 * WIDTH)) - 1);
        var offset = 7 - list.size();
        for (var y = 0; y < list.size(); y++) out |= (long) list.get(y) << ((y + offset) * WIDTH);
        return out;
    }

    // ---- Procedural ranges ---------------------------------------------------------------------------------------

    private static Long procedural(int cp) {
        if (cp >= 0x80 && cp <= 0x9F) return teletext(cp - 0x80);
        if (cp >= 0x2500 && cp <= 0x257F) return box(cp);
        if (cp >= 0x2580 && cp <= 0x259F) return block(cp);
        if (cp >= 0x2800 && cp <= 0x28FF) return braille(cp - 0x2800);
        return null;
    }

    /** CC drawing characters: bits 0-4 light the 2x3 cells top-left, top-right, mid-left, mid-right, bottom-left. */
    private static long teletext(int bits) {
        long g = 0;
        for (var cell = 0; cell < 5; cell++) {
            if ((bits & (1 << cell)) == 0) continue;
            var cx = cell % 2;
            var cy = cell / 2;
            for (var y = cy * 3; y < cy * 3 + 3; y++) {
                for (var x = cx * 3; x < cx * 3 + 3; x++) g = set(g, x, y);
            }
        }
        return g;
    }

    private static long braille(int bits) {
        int[][] dots = {{0, 0}, {0, 1}, {0, 2}, {1, 0}, {1, 1}, {1, 2}, {0, 3}, {1, 3}};
        long g = 0;
        for (var i = 0; i < 8; i++) {
            if ((bits & (1 << i)) == 0) continue;
            var x = 1 + dots[i][0] * 3;
            var y = 1 + dots[i][1] * 2;
            g = set(g, x, y);
            g = set(g, x + 1, y);
        }
        return g;
    }

    private static long fillRect(long g, int x0, int y0, int x1, int y1) {
        for (var y = y0; y < y1; y++) {
            for (var x = x0; x < x1; x++) g = set(g, x, y);
        }
        return g;
    }

    private static long block(int cp) {
        if (cp == 0x2580) return fillRect(0, 0, 0, 6, 5);
        if (cp >= 0x2581 && cp <= 0x2588) {
            var eighths = cp - 0x2580;
            return fillRect(0, 0, HEIGHT - Math.round(eighths * HEIGHT / 8f), 6, HEIGHT);
        }
        if (cp >= 0x2589 && cp <= 0x258F) {
            var eighths = 0x2590 - cp;
            return fillRect(0, 0, 0, Math.max(1, Math.round(eighths * WIDTH / 8f)), HEIGHT);
        }
        if (cp == 0x2590) return fillRect(0, 3, 0, 6, HEIGHT);
        if (cp >= 0x2591 && cp <= 0x2593) {
            long g = 0;
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    var on = switch (cp) {
                        case 0x2591 -> (x % 2 == 0) && (y % 2 == 0);
                        case 0x2592 -> (x + y) % 2 == 0;
                        default -> !((x % 2 == 0) && (y % 2 == 0));
                    };
                    if (on) g = set(g, x, y);
                }
            }
            return g;
        }
        if (cp == 0x2594) return fillRect(0, 0, 0, 6, 1);
        if (cp == 0x2595) return fillRect(0, 5, 0, 6, HEIGHT);
        // Quadrants: UL, UR, LL, LR.
        int[] quads = {
            0b0100, 0b1000, 0b0001, 0b1101, 0b1001, 0b0111, 0b1011, 0b0010, 0b0110, 0b1110,
        };
        var q = quads[cp - 0x2596];
        long g = 0;
        if ((q & 1) != 0) g = fillRect(g, 0, 0, 3, 5);
        if ((q & 2) != 0) g = fillRect(g, 3, 0, 6, 5);
        if ((q & 4) != 0) g = fillRect(g, 0, 5, 3, HEIGHT);
        if ((q & 8) != 0) g = fillRect(g, 3, 5, 6, HEIGHT);
        return g;
    }

    // Box drawing: each character is four arms (up, right, down, left) of weight 0 none, 1 light, 2 heavy, 3 double.
    private static final int U = 0;
    private static final int R = 1;
    private static final int D = 2;
    private static final int L = 3;

    private static int[] arms(int cp) {
        int[] a = new int[4];
        switch (cp) {
            case 0x2500, 0x2504, 0x2508, 0x254C -> { a[L] = 1; a[R] = 1; }
            case 0x2501, 0x2505, 0x2509, 0x254D -> { a[L] = 2; a[R] = 2; }
            case 0x2502, 0x2506, 0x250A, 0x254E -> { a[U] = 1; a[D] = 1; }
            case 0x2503, 0x2507, 0x250B, 0x254F -> { a[U] = 2; a[D] = 2; }
            case 0x2550 -> { a[L] = 3; a[R] = 3; }
            case 0x2551 -> { a[U] = 3; a[D] = 3; }
            case 0x256D -> { a[D] = 1; a[R] = 1; }
            case 0x256E -> { a[D] = 1; a[L] = 1; }
            case 0x256F -> { a[U] = 1; a[L] = 1; }
            case 0x2570 -> { a[U] = 1; a[R] = 1; }
            case 0x2574 -> a[L] = 1;
            case 0x2575 -> a[U] = 1;
            case 0x2576 -> a[R] = 1;
            case 0x2577 -> a[D] = 1;
            case 0x2578 -> a[L] = 2;
            case 0x2579 -> a[U] = 2;
            case 0x257A -> a[R] = 2;
            case 0x257B -> a[D] = 2;
            case 0x257C -> { a[L] = 1; a[R] = 2; }
            case 0x257D -> { a[U] = 1; a[D] = 2; }
            case 0x257E -> { a[L] = 2; a[R] = 1; }
            case 0x257F -> { a[U] = 2; a[D] = 1; }
            default -> {
                if (cp >= 0x250C && cp <= 0x254B) return lightHeavy(cp);
                if (cp >= 0x2552 && cp <= 0x256C) return doubles(cp);
                return null;
            }
        }
        return a;
    }

    private static int[] lightHeavy(int cp) {
        int[] a = new int[4];
        if (cp <= 0x251B) {
            // Corners: four groups of four (light/light, h1 heavy, v heavy, both heavy).
            var group = (cp - 0x250C) / 4;
            var variant = (cp - 0x250C) % 4;
            int h = group == 0 || group == 2 ? R : L;
            int v = group < 2 ? D : U;
            a[h] = (variant & 1) != 0 ? 2 : 1;
            a[v] = (variant & 2) != 0 ? 2 : 1;
            return a;
        }
        if (cp <= 0x252B) {
            // Vertical tees: the stem is right (├) or left (┤).
            var stem = cp <= 0x2523 ? R : L;
            var variant = (cp - (stem == R ? 0x251C : 0x2524));
            a[U] = 1;
            a[D] = 1;
            a[stem] = 1;
            switch (variant) {
                case 1 -> a[stem] = 2;
                case 2 -> a[U] = 2;
                case 3 -> a[D] = 2;
                case 4 -> { a[U] = 2; a[D] = 2; }
                case 5 -> { a[U] = 2; a[stem] = 2; }
                case 6 -> { a[D] = 2; a[stem] = 2; }
                case 7 -> { a[U] = 2; a[D] = 2; a[stem] = 2; }
                default -> {
                }
            }
            return a;
        }
        if (cp <= 0x253B) {
            // Horizontal tees: the stem is down (┬) or up (┴).
            var stem = cp <= 0x2533 ? D : U;
            var variant = cp - (stem == D ? 0x252C : 0x2534);
            a[L] = 1;
            a[R] = 1;
            a[stem] = 1;
            switch (variant) {
                case 1 -> a[L] = 2;
                case 2 -> a[R] = 2;
                case 3 -> { a[L] = 2; a[R] = 2; }
                case 4 -> a[stem] = 2;
                case 5 -> { a[L] = 2; a[stem] = 2; }
                case 6 -> { a[R] = 2; a[stem] = 2; }
                case 7 -> { a[L] = 2; a[R] = 2; a[stem] = 2; }
                default -> {
                }
            }
            return a;
        }
        // Crosses ┼ (0x253C - 0x254B): which arms are heavy.
        String[] heavy = {"", "L", "R", "LR", "U", "D", "UD", "UL", "UR", "DL", "DR", "ULR", "DLR", "UDL", "UDR", "UDLR"};
        a[U] = a[R] = a[D] = a[L] = 1;
        for (var c : heavy[cp - 0x253C].toCharArray()) {
            a[switch (c) {
                case 'U' -> U;
                case 'R' -> R;
                case 'D' -> D;
                default -> L;
            }] = 2;
        }
        return a;
    }

    private static int[] doubles(int cp) {
        // 0x2552-0x256C: [vertical weight, horizontal weight, arms] per character.
        String[] table = {
            "D1R3", "D3R1", "D3R3", "D1L3", "D3L1", "D3L3", "U1R3", "U3R1", "U3R3", "U1L3", "U3L1", "U3L3",
            "U1D1R3", "U3D3R1", "U3D3R3", "U1D1L3", "U3D3L1", "U3D3L3", "L3R3D1", "L1R1D3", "L3R3D3",
            "L3R3U1", "L1R1U3", "L3R3U3", "L3R3U1D1", "L1R1U3D3", "L3R3U3D3",
        };
        int[] a = new int[4];
        var spec = table[cp - 0x2552];
        for (var i = 0; i < spec.length(); i += 2) {
            var w = spec.charAt(i + 1) - '0';
            a[switch (spec.charAt(i)) {
                case 'U' -> U;
                case 'R' -> R;
                case 'D' -> D;
                default -> L;
            }] = w;
        }
        return a;
    }

    private static Long box(int cp) {
        if (cp >= 0x2571 && cp <= 0x2573) {
            long g = 0;
            for (var y = 0; y < HEIGHT; y++) {
                var x = Math.round(y * (WIDTH - 1) / (float) (HEIGHT - 1));
                if (cp != 0x2572) g = set(g, WIDTH - 1 - x, y);
                if (cp != 0x2571) g = set(g, x, y);
            }
            return g;
        }
        var a = arms(cp);
        if (a == null) return null;
        // Centre lines: light uses column 2 / row 4; heavy adds column 3 / row 5; double uses columns 1,3 / rows 3,5.
        long g = 0;
        var anyDoubleV = a[U] == 3 || a[D] == 3;
        var anyDoubleH = a[L] == 3 || a[R] == 3;
        for (var arm = 0; arm < 4; arm++) {
            var w = a[arm];
            if (w == 0) continue;
            var vertical = arm == U || arm == D;
            int[] lines = switch (w) {
                case 1 -> vertical ? new int[]{2} : new int[]{4};
                case 2 -> vertical ? new int[]{2, 3} : new int[]{4, 5};
                default -> vertical ? new int[]{1, 3} : new int[]{3, 5};
            };
            for (var line : lines) {
                if (vertical) {
                    // Stop double lines at the inner edge of a perpendicular double so corners stay open.
                    int from = arm == U ? 0 : 4;
                    int to = arm == U ? 4 : HEIGHT - 1;
                    if (w == 3 && anyDoubleH) {
                        if (arm == U) to = line == 1 ? (a[L] == 3 ? 3 : 5) : (a[R] == 3 ? 3 : 5);
                        else from = line == 1 ? (a[L] == 3 ? 5 : 3) : (a[R] == 3 ? 5 : 3);
                    }
                    for (var y = from; y <= to; y++) g = set(g, line, y);
                } else {
                    int from = arm == L ? 0 : 2;
                    int to = arm == L ? 2 : WIDTH - 1;
                    if (w == 3 && anyDoubleV) {
                        if (arm == L) to = line == 3 ? (a[U] == 3 ? 1 : 3) : (a[D] == 3 ? 1 : 3);
                        else from = line == 3 ? (a[U] == 3 ? 3 : 1) : (a[D] == 3 ? 3 : 1);
                    }
                    for (var x = from; x <= to; x++) g = set(g, x, line);
                }
            }
        }
        // Dashed variants: knock out every third pixel along the line.
        if ((cp >= 0x2504 && cp <= 0x250B) || (cp >= 0x254C && cp <= 0x254F)) {
            var horizontal = a[L] > 0;
            for (var i = 2; i < (horizontal ? WIDTH : HEIGHT); i += 3) {
                for (var j = 0; j < (horizontal ? HEIGHT : WIDTH); j++) {
                    var bit = horizontal ? j * WIDTH + i : i * WIDTH + j;
                    g &= ~(1L << bit);
                }
            }
        }
        return g;
    }
}
