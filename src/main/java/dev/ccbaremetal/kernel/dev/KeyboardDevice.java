package dev.ccbaremetal.kernel.dev;

import dev.ccbaremetal.kernel.io.EvdevFile;
import dev.ccbaremetal.kernel.io.InputHub;
import dev.ccbaremetal.kernel.io.OpenFile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * A player's keyboard (the keyboard item), plugged in while they hold the computer's input: /dev/input/eventN
 * (class input) with every key as Linux evdev records (EV_KEY, value 1 down, 2 repeat, 0 up) and a captured mouse,
 * relative like a real one: REL_X, REL_Y, REL_WHEEL, BTN_LEFT/RIGHT/MIDDLE. Attributes: name "keyboard", player.
 */
public final class KeyboardDevice extends Device {
    private static final int[] BUTTONS = {EvdevFile.BTN_LEFT, EvdevFile.BTN_RIGHT, EvdevFile.BTN_MIDDLE};

    private final Set<EvdevFile> readers = new CopyOnWriteArraySet<>();
    private final String player;
    private final Runnable changed;

    /** @param changed called after records were queued (wakes poll) */
    public KeyboardDevice(String player, Runnable changed) {
        super("input", "event", null);
        this.player = player;
        this.changed = changed;
        attribute("name", () -> "keyboard\n");
        attribute("player", () -> player + "\n");
    }

    public String player() {
        return player;
    }

    /** Whether a program has the device open. */
    public boolean hasReaders() {
        return !readers.isEmpty();
    }

    @Override
    public String node() {
        return "input/" + name();
    }

    @Override
    protected OpenFile open(String guestPath) {
        var holder = new EvdevFile[1];
        holder[0] = new EvdevFile(guestPath, EvdevFile.RECORD, () -> readers.remove(holder[0]));
        readers.add(holder[0]);
        return holder[0];
    }

    /** A key by its GLFW code (what CC key events carry): value 1 down, 2 repeat, 0 up. */
    public void key(int glfw, int value) {
        var code = InputHub.linuxKey(glfw);
        if (code > 0) send(EvdevFile.EV_KEY, code, value);
    }

    public void motion(int dx, int dy) {
        if (readers.isEmpty() || (dx == 0 && dy == 0)) return;
        var out = buffer(3);
        if (dx != 0) EvdevFile.record(out, EvdevFile.EV_REL, EvdevFile.REL_X, dx);
        if (dy != 0) EvdevFile.record(out, EvdevFile.EV_REL, EvdevFile.REL_Y, dy);
        push(out);
    }

    /** Button 0 left, 1 right, 2 middle. */
    public void button(int button, boolean down) {
        if (button >= 0 && button < BUTTONS.length) send(EvdevFile.EV_KEY, BUTTONS[button], down ? 1 : 0);
    }

    /** Positive: away from the user (up), as Linux. */
    public void wheel(int steps) {
        if (steps != 0) send(EvdevFile.EV_REL, EvdevFile.REL_WHEEL, steps);
    }

    private void send(int type, int code, int value) {
        if (readers.isEmpty()) return;
        var out = buffer(2);
        EvdevFile.record(out, type, code, value);
        push(out);
    }

    private static ByteBuffer buffer(int records) {
        return ByteBuffer.allocate(records * EvdevFile.RECORD).order(ByteOrder.LITTLE_ENDIAN);
    }

    private void push(ByteBuffer out) {
        EvdevFile.record(out, EvdevFile.EV_SYN, EvdevFile.SYN_REPORT, 0);
        var data = new byte[out.position()];
        out.flip().get(data);
        for (var r : readers) r.push(data);
        changed.run();
    }

    @Override
    protected void removed() {
        readers.clear();
    }
}
