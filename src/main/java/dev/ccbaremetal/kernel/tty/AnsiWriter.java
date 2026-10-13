package dev.ccbaremetal.kernel.tty;

/**
 * Feeds a byte stream (UTF-8 with ANSI/VT100 escape sequences) into a {@link Terminal}. Supports what xterm-style
 * programs (ncurses-like full-screen tools included) use: cursor movement, erase, SGR colours, scrolling regions,
 * insert/delete of lines and characters, cursor visibility, private modes, and the status/position reports.
 */
public final class AnsiWriter {
    /** ANSI colour (0-7 normal, 8-15 bright) to CC palette index. */
    private static final int[] ANSI_TO_CC = {15, 14, 13, 12, 11, 10, 9, 8, 7, 14, 5, 4, 3, 2, 9, 0};

    private final Terminal term;
    private int state;
    private final StringBuilder params = new StringBuilder();
    private final StringBuilder osc = new StringBuilder();
    private static final int MAX_OSC = 1 << 20;
    private int utf8Remaining;
    private int utf8Codepoint;
    private boolean bold;
    private int fgAnsi = -1;
    private int savedX;
    private int savedY;

    private static final int NORMAL = 0;
    private static final int ESCAPE = 1;
    private static final int CSI = 2;
    private static final int OSC = 3;
    /** ESC ( B and friends (character set selection, ESC # n): the next byte is consumed and ignored. */
    private static final int SKIP_ONE = 4;

    /** Replies to queries (ESC[6n, ESC[c ...), taken by the TTY and put into the input outside the terminal lock. */
    private final java.io.ByteArrayOutputStream replies = new java.io.ByteArrayOutputStream();

    public AnsiWriter(Terminal term) {
        this.term = term;
    }

    /** OSC 52;c;BASE64 - put text on the clipboard of the players viewing the screen. */
    private void osc(String command) {
        if (!command.startsWith("52;")) return;
        var semi = command.indexOf(';', 3);
        if (semi < 0) return;
        var data = command.substring(semi + 1);
        if (data.equals("?")) return; // reading the clipboard is not allowed
        try {
            var text = new String(java.util.Base64.getDecoder().decode(data), java.nio.charset.StandardCharsets.UTF_8);
            if (text.length() <= 1 << 16) term.requestClipboard(text);
        } catch (IllegalArgumentException ignored) {
        }
    }

    /** Bytes the terminal answers with (cursor position reports and the like), or null if there are none. */
    public byte[] takeReplies() {
        synchronized (term) {
            if (replies.size() == 0) return null;
            var out = replies.toByteArray();
            replies.reset();
            return out;
        }
    }

    private void reply(String s) {
        if (replies.size() < 4096) replies.writeBytes(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    public void write(byte[] data, int offset, int length) {
        synchronized (term) {
            for (var i = 0; i < length; i++) feed(data[offset + i] & 0xFF);
        }
    }

    private void feed(int b) {
        switch (state) {
            case ESCAPE -> {
                if (b == '[') {
                    state = CSI;
                    params.setLength(0);
                } else if (b == ']') {
                    state = OSC;
                    osc.setLength(0);
                } else if (b == '\\') {
                    state = NORMAL; // ST after an OSC
                } else if (b == '(' || b == ')' || b == '*' || b == '+' || b == '#') {
                    state = SKIP_ONE;
                } else {
                    if (b == 'c') {
                        term.reset();
                        bold = false;
                        fgAnsi = -1;
                    } else if (b == '7') {
                        savedX = term.cursorX();
                        savedY = term.cursorY();
                    } else if (b == '8') {
                        term.setCursor(savedX, savedY);
                    } else if (b == 'M') {
                        term.reverseIndex();
                    } else if (b == 'D') {
                        term.index();
                    } else if (b == 'E') {
                        term.newline();
                    }
                    state = NORMAL;
                }
                return;
            }
            case CSI -> {
                if ((b >= '0' && b <= '9') || b == ';' || (b >= '<' && b <= '?') || (b >= 0x20 && b <= 0x2F)) {
                    // Parameters, private markers (? > = <) and intermediates (space, ! ...) are kept as they are.
                    if (params.length() < 64) params.append((char) b);
                } else if (b >= 0x40 && b <= 0x7E) {
                    csi((char) b);
                    state = NORMAL;
                } else if (b < 0x20 || b > 0x7E) {
                    state = NORMAL;
                }
                return;
            }
            case SKIP_ONE -> {
                state = NORMAL;
                return;
            }
            case OSC -> {
                // Operating system commands until BEL or ESC \\: only 52 (clipboard) is used, titles etc. are ignored.
                if (b == 7 || b == 27) {
                    osc(osc.toString());
                    osc.setLength(0);
                    state = b == 7 ? NORMAL : ESCAPE;
                } else if (osc.length() < MAX_OSC) {
                    osc.append((char) b);
                }
                return;
            }
            default -> {
            }
        }

        if (utf8Remaining > 0) {
            if ((b & 0xC0) == 0x80) {
                utf8Codepoint = (utf8Codepoint << 6) | (b & 0x3F);
                if (--utf8Remaining == 0) put(utf8Codepoint);
                return;
            }
            utf8Remaining = 0;
            put('?');
        }

        if (b >= 0x80) {
            if ((b & 0xE0) == 0xC0) {
                utf8Remaining = 1;
                utf8Codepoint = b & 0x1F;
            } else if ((b & 0xF0) == 0xE0) {
                utf8Remaining = 2;
                utf8Codepoint = b & 0x0F;
            } else if ((b & 0xF8) == 0xF0) {
                utf8Remaining = 3;
                utf8Codepoint = b & 0x07;
            } else {
                put('?');
            }
            return;
        }

        switch (b) {
            case 27 -> state = ESCAPE;
            case '\n' -> term.newline();
            case '\r' -> term.setCursor(0, term.cursorY());
            case '\b' -> term.setCursor(Math.max(0, term.cursorX() - 1), term.cursorY());
            case '\t' -> {
                var target = Math.min(term.width() - 1, (term.cursorX() / 8 + 1) * 8);
                while (term.cursorX() < target) term.putWrapping(' ');
            }
            case 7, 0 -> {
            }
            default -> {
                if (b >= 32 && b != 127) put(b);
            }
        }
    }

    private void put(int codepoint) {
        term.putWrapping(codepoint <= 0xFFFF ? (char) codepoint : '\uFFFD');
    }

    private int[] parseParams() {
        var raw = params.toString().replaceAll("[^0-9;]", "");
        if (raw.isEmpty()) return new int[0];
        var parts = raw.split(";", -1);
        var out = new int[parts.length];
        for (var i = 0; i < parts.length; i++) {
            try {
                out[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static int arg(int[] p, int index, int def) {
        return index < p.length && p[index] != 0 ? p[index] : def;
    }

    private void csi(char command) {
        var p = parseParams();
        var x = term.cursorX();
        var y = term.cursorY();
        var w = term.width();
        var h = term.height();
        var privateMode = params.indexOf("?") >= 0;
        // Sequences with other markers or intermediates (ESC[>c, ESC[!p, ESC[ q ...): only those handled below.
        var marker = params.length() > 0 && !Character.isDigit(params.charAt(0)) && params.charAt(0) != ';' && params.charAt(0) != '?'
            ? params.charAt(0) : 0;
        var intermediate = params.chars().anyMatch(c -> c >= 0x20 && c <= 0x2F);
        if (intermediate) {
            if (command == 'p' && params.indexOf("!") >= 0) softReset();
            return;
        }
        if (marker != 0) {
            if (marker == '>' && command == 'c') reply("\u001b[>0;0;0c"); // secondary device attributes
            return;
        }
        // Inside the scrolling region, vertical movement stops at its margins (as in xterm).
        var top = y >= term.scrollTop() && y <= term.scrollBottom() ? term.scrollTop() : 0;
        var bottom = y >= term.scrollTop() && y <= term.scrollBottom() ? term.scrollBottom() : h - 1;
        switch (command) {
            case 'A' -> term.setCursor(x, Math.max(top, y - arg(p, 0, 1)));
            case 'B', 'e' -> term.setCursor(x, Math.min(bottom, y + arg(p, 0, 1)));
            case 'C', 'a' -> term.setCursor(Math.min(w - 1, x + arg(p, 0, 1)), y);
            case 'D' -> term.setCursor(Math.max(0, x - arg(p, 0, 1)), y);
            case 'E' -> term.setCursor(0, Math.min(h - 1, y + arg(p, 0, 1)));
            case 'F' -> term.setCursor(0, Math.max(0, y - arg(p, 0, 1)));
            case 'G', '`' -> term.setCursor(clamp(arg(p, 0, 1) - 1, w), y);
            case 'd' -> term.setCursor(x, clamp(arg(p, 0, 1) - 1, h));
            case 'H', 'f' -> term.setCursor(clamp(arg(p, 1, 1) - 1, w), clamp(arg(p, 0, 1) - 1, h));
            case 'J' -> {
                var mode = p.length > 0 ? p[0] : 0;
                if (mode == 3) term.clearHistory();
                else if (mode == 2) term.clear();
                else term.clearScreenPart(mode);
            }
            case 'K' -> {
                var mode = p.length > 0 ? p[0] : 0;
                if (mode == 2) term.clearLine();
                else term.clearLinePart(mode);
            }
            case 'S' -> term.scrollRegion(arg(p, 0, 1));
            case 'T' -> term.scrollRegion(-arg(p, 0, 1));
            case 'L' -> term.insertLines(arg(p, 0, 1), false);
            case 'M' -> term.insertLines(arg(p, 0, 1), true);
            case '@' -> term.insertChars(arg(p, 0, 1), false);
            case 'P' -> term.insertChars(arg(p, 0, 1), true);
            case 'X' -> term.eraseChars(arg(p, 0, 1));
            case 'r' -> {
                if (privateMode) break;
                term.setScrollRegion(arg(p, 0, 1) - 1, arg(p, 1, h) - 1);
                term.setCursor(0, 0);
            }
            case 'n' -> {
                if (privateMode) break;
                var what = p.length > 0 ? p[0] : 0;
                if (what == 5) reply("\u001b[0n");
                else if (what == 6) reply("\u001b[" + (Math.min(y, h - 1) + 1) + ";" + (Math.min(x, w - 1) + 1) + "R");
            }
            case 'c' -> {
                if (!privateMode && (p.length == 0 || p[0] == 0)) reply("\u001b[?1;2c"); // VT100 with advanced video
            }
            case 's' -> {
                savedX = x;
                savedY = y;
            }
            case 'u' -> term.setCursor(savedX, savedY);
            case 'h', 'l' -> {
                if (!privateMode) break;
                var set = command == 'h';
                for (var mode : p) {
                    switch (mode) {
                        case 1 -> term.setAppCursor(set);
                        case 25 -> term.setCursorBlink(set);
                        case 47, 1047, 1049 -> {
                            if (mode == 1049 && set) {
                                savedX = x;
                                savedY = y;
                            }
                            term.setAlternate(set);
                            if (mode == 1049 && !set) term.setCursor(savedX, savedY);
                            // A full-screen program that left without ESC[?2004l: the shell does not want it.
                            if (!set) term.setBracketedPaste(false);
                        }
                        case 1000, 1002, 1003 -> term.setMouseMode(mode, set);
                        case 1006 -> term.setSgrMouse(set);
                        case 2004 -> term.setBracketedPaste(set);
                        default -> {
                        }
                    }
                }
            }
            case 'm' -> sgr(p);
            default -> {
            }
        }
    }

    /** DECSTR (ESC[!p): modes and attributes back to defaults, the screen itself is kept. */
    private void softReset() {
        term.setScrollRegion(0, term.height() - 1);
        term.setAppCursor(false);
        term.setCursorBlink(true);
        bold = false;
        fgAnsi = -1;
        term.setTextColour(0);
        term.setBackgroundColour(15);
    }

    private static int clamp(int v, int size) {
        return Math.max(0, Math.min(size - 1, v));
    }

    private void sgr(int[] p) {
        if (p.length == 0) p = new int[]{0};
        for (var i = 0; i < p.length; i++) {
            var code = p[i];
            if (code == 0) {
                bold = false;
                fgAnsi = -1;
                term.setTextColour(0);
                term.setBackgroundColour(15);
            } else if (code == 1) {
                bold = true;
                if (fgAnsi >= 0 && fgAnsi < 8) term.setTextColour(ANSI_TO_CC[fgAnsi + 8]);
            } else if (code == 22) {
                bold = false;
            } else if (code == 7) {
                var f = term.textColour();
                term.setTextColour(term.backgroundColour());
                term.setBackgroundColour(f);
            } else if (code >= 30 && code <= 37) {
                fgAnsi = code - 30;
                term.setTextColour(ANSI_TO_CC[bold ? fgAnsi + 8 : fgAnsi]);
            } else if (code == 39) {
                fgAnsi = -1;
                term.setTextColour(0);
            } else if (code >= 40 && code <= 47) {
                term.setBackgroundColour(ANSI_TO_CC[code - 40]);
            } else if (code == 49) {
                term.setBackgroundColour(15);
            } else if (code >= 90 && code <= 97) {
                fgAnsi = code - 90 + 8;
                term.setTextColour(ANSI_TO_CC[fgAnsi]);
            } else if (code >= 100 && code <= 107) {
                term.setBackgroundColour(ANSI_TO_CC[code - 100 + 8]);
            } else if ((code == 38 || code == 48) && i + 2 < p.length && p[i + 1] == 5) {
                // 256-colour: 0-15 use the ANSI table, and our extension: 256+n selects CC palette index n directly.
                var n = p[i + 2];
                var cc = n < 16 ? ANSI_TO_CC[n] : approximate(n);
                if (code == 38) term.setTextColour(cc);
                else term.setBackgroundColour(cc);
                i += 2;
            } else if ((code == 38 || code == 48) && i + 4 < p.length && p[i + 1] == 2) {
                var cc = nearest((p[i + 2] << 16) | (p[i + 3] << 8) | p[i + 4]);
                if (code == 38) term.setTextColour(cc);
                else term.setBackgroundColour(cc);
                i += 4;
            }
        }
    }

    private int approximate(int n) {
        if (n >= 232) {
            var v = 8 + (n - 232) * 10;
            return nearest((v << 16) | (v << 8) | v);
        }
        n -= 16;
        var r = n / 36;
        var g = (n / 6) % 6;
        var b = n % 6;
        int[] levels = {0, 95, 135, 175, 215, 255};
        return nearest((levels[r] << 16) | (levels[g] << 8) | levels[b]);
    }

    private int nearest(int rgb) {
        var best = 0;
        var bestDist = Integer.MAX_VALUE;
        for (var i = 0; i < 16; i++) {
            var c = term.palette(i);
            var dr = ((c >> 16) & 255) - ((rgb >> 16) & 255);
            var dg = ((c >> 8) & 255) - ((rgb >> 8) & 255);
            var db = (c & 255) - (rgb & 255);
            var d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }
}
