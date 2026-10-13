package dev.ccbaremetal.kernel.io;

import dev.ccbaremetal.kernel.event.Event;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Linux input devices fed from the computer's keyboard and mouse, for programs that want raw input as files:
 * <ul>
 *   <li>/dev/input/event0 - keyboard, /dev/input/event1 - mouse: evdev {@code struct input_event} records (24 bytes:
 *       int64 seconds, int64 microseconds, u16 type, u16 code, s32 value), Linux key codes;</li>
 *   <li>/dev/input/mice - the mouse as 3-byte PS/2 packets (buttons, relative dx/dy).</li>
 * </ul>
 * Mouse positions are absolute (ABS_X/ABS_Y): framebuffer pixels in graphics mode, character cells (from 0) in
 * text mode. Every reader gets every event (like Linux without EVIOCGRAB); the terminal still gets keys as well.
 */
public final class InputHub {
    public enum Kind { KEYBOARD, MOUSE, MICE }

    private static final int EV_SYN = EvdevFile.EV_SYN, EV_KEY = EvdevFile.EV_KEY, EV_REL = EvdevFile.EV_REL, EV_ABS = EvdevFile.EV_ABS;
    private static final int SYN_REPORT = EvdevFile.SYN_REPORT, REL_WHEEL = EvdevFile.REL_WHEEL, ABS_X = EvdevFile.ABS_X, ABS_Y = EvdevFile.ABS_Y;
    private static final int BTN_LEFT = EvdevFile.BTN_LEFT, BTN_RIGHT = EvdevFile.BTN_RIGHT, BTN_MIDDLE = EvdevFile.BTN_MIDDLE;

    private record Device(Kind kind, EvdevFile file) {
    }

    private final Notifier notifier;
    private final Set<Device> devices = new CopyOnWriteArraySet<>();
    private int mouseX;
    private int mouseY;
    private int buttons; // bit 0 left, 1 right, 2 middle

    public InputHub(Notifier notifier) {
        this.notifier = notifier;
    }

    public OpenFile open(Kind kind, String path) {
        var holder = new Device[1];
        var file = new EvdevFile(path, kind == Kind.MICE ? 3 : EvdevFile.RECORD, () -> devices.remove(holder[0]));
        holder[0] = new Device(kind, file);
        devices.add(holder[0]);
        return file;
    }

    /** Whether a program reads the mouse: then the GUI sends mouse motion and all mouse events. */
    public boolean mouseOpen() {
        for (var d : devices) {
            if (d.kind() != Kind.KEYBOARD) return true;
        }
        return false;
    }

    public boolean isEmpty() {
        return devices.isEmpty();
    }

    /** An input event of the machine (key, key_up, mouse_*), with coordinates as the machine receives them. */
    public synchronized void feed(Event event, boolean graphics) {
        if (devices.isEmpty()) return;
        var args = event.args();
        switch (event.name()) {
            case "key" -> {
                var code = linuxKey(((Number) args[0]).intValue());
                if (code > 0) keyboard(code, args.length > 1 && Boolean.TRUE.equals(args[1]) ? 2 : 1);
            }
            case "key_up" -> {
                var code = linuxKey(((Number) args[0]).intValue());
                if (code > 0) keyboard(code, 0);
            }
            case "mouse_click", "mouse_up", "mouse_drag", "mouse_move" -> {
                var button = ((Number) args[0]).intValue();
                int x = ((Number) args[1]).intValue(), y = ((Number) args[2]).intValue();
                if (!graphics) {
                    x--;
                    y--;
                }
                var bit = switch (button) {
                    case 2 -> 2;
                    case 3 -> 4;
                    default -> 1;
                };
                var oldButtons = buttons;
                if (event.name().equals("mouse_click")) buttons |= bit;
                if (event.name().equals("mouse_up")) buttons &= ~bit;
                mouse(x, y, oldButtons, buttons, 0);
            }
            case "mouse_scroll" -> {
                int x = ((Number) args[1]).intValue(), y = ((Number) args[2]).intValue();
                if (!graphics) {
                    x--;
                    y--;
                }
                // CC: 1 = down; Linux REL_WHEEL: positive = up
                mouse(x, y, buttons, buttons, -((Number) args[0]).intValue());
            }
            default -> {
            }
        }
    }

    private void keyboard(int code, int value) {
        var out = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        record(out, EV_KEY, code, value);
        record(out, EV_SYN, SYN_REPORT, 0);
        send(Kind.KEYBOARD, out.array());
    }

    private void mouse(int x, int y, int oldButtons, int newButtons, int wheel) {
        var out = ByteBuffer.allocate(24 * 8).order(ByteOrder.LITTLE_ENDIAN);
        if (x != mouseX) record(out, EV_ABS, ABS_X, x);
        if (y != mouseY) record(out, EV_ABS, ABS_Y, y);
        int[] codes = {BTN_LEFT, BTN_RIGHT, BTN_MIDDLE};
        for (var i = 0; i < 3; i++) {
            if (((oldButtons ^ newButtons) >> i & 1) != 0) record(out, EV_KEY, codes[i], newButtons >> i & 1);
        }
        if (wheel != 0) record(out, EV_REL, REL_WHEEL, wheel);
        if (out.position() > 0) {
            record(out, EV_SYN, SYN_REPORT, 0);
            var bytes = new byte[out.position()];
            out.flip().get(bytes);
            send(Kind.MOUSE, bytes);
        }
        // PS/2: relative motion (y up), split into steps of at most 127
        int dx = x - mouseX, dy = mouseY - y;
        var ps2 = new java.io.ByteArrayOutputStream();
        do {
            int sx = Math.max(-127, Math.min(127, dx)), sy = Math.max(-127, Math.min(127, dy));
            ps2.write(0x08 | (newButtons & 7) | (sx < 0 ? 0x10 : 0) | (sy < 0 ? 0x20 : 0));
            ps2.write(sx & 0xFF);
            ps2.write(sy & 0xFF);
            dx -= sx;
            dy -= sy;
        } while (dx != 0 || dy != 0);
        if (x != mouseX || y != mouseY || oldButtons != newButtons) send(Kind.MICE, ps2.toByteArray());
        mouseX = x;
        mouseY = y;
    }

    private static void record(ByteBuffer out, int type, int code, int value) {
        EvdevFile.record(out, type, code, value);
    }

    private void send(Kind kind, byte[] data) {
        for (var d : devices) {
            if (d.kind() == kind) d.file().push(data);
        }
        notifier.signal();
    }

    /** GLFW key code (what CC "key" events carry) to a Linux KEY_* code; 0 if unknown. */
    public static int linuxKey(int glfw) {
        if (glfw >= 'A' && glfw <= 'Z') {
            return new int[]{30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44}[glfw - 'A'];
        }
        if (glfw >= '1' && glfw <= '9') return glfw - '1' + 2;
        if (glfw >= 290 && glfw <= 301) return new int[]{59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 87, 88}[glfw - 290];
        if (glfw >= 320 && glfw <= 329) return new int[]{82, 79, 80, 81, 75, 76, 77, 71, 72, 73}[glfw - 320];
        return switch (glfw) {
            case '0' -> 11;
            case ' ' -> 57;
            case '\'' -> 40;
            case ',' -> 51;
            case '-' -> 12;
            case '.' -> 52;
            case '/' -> 53;
            case ';' -> 39;
            case '=' -> 13;
            case '[' -> 26;
            case '\\' -> 43;
            case ']' -> 27;
            case '`' -> 41;
            case 256 -> 1;   // escape
            case 257 -> 28;  // enter
            case 258 -> 15;  // tab
            case 259 -> 14;  // backspace
            case 260 -> 110; // insert
            case 261 -> 111; // delete
            case 262 -> 106; // right
            case 263 -> 105; // left
            case 264 -> 108; // down
            case 265 -> 103; // up
            case 266 -> 104; // page up
            case 267 -> 109; // page down
            case 268 -> 102; // home
            case 269 -> 107; // end
            case 280 -> 58;  // caps lock
            case 281 -> 70;  // scroll lock
            case 282 -> 69;  // num lock
            case 283 -> 99;  // print screen
            case 284 -> 119; // pause
            case 330 -> 83;  // keypad .
            case 331 -> 98;  // keypad /
            case 332 -> 55;  // keypad *
            case 333 -> 74;  // keypad -
            case 334 -> 78;  // keypad +
            case 335 -> 96;  // keypad enter
            case 336 -> 117; // keypad =
            case 340 -> 42;  // left shift
            case 341 -> 29;  // left ctrl
            case 342 -> 56;  // left alt
            case 343 -> 125; // left super
            case 344 -> 54;  // right shift
            case 345 -> 97;  // right ctrl
            case 346 -> 100; // right alt
            case 347 -> 126; // right super
            case 348 -> 127; // menu
            default -> 0;
        };
    }
}
