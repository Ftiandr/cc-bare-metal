package dev.ccbaremetal.kernel.dev;

import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.kernel.io.EvdevFile;
import dev.ccbaremetal.kernel.io.OpenFile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Supplier;

/**
 * A monitor's touchscreen (class input, /dev/input/eventN): every touch as evdev records like Linux, ABS_X, ABS_Y,
 * BTN_TOUCH 1, SYN, then BTN_TOUCH 0, SYN, positions from 0 (pixels, or cells for CC monitors). Attributes: name
 * ("pixel_monitor" or "monitor"), fb (the framebuffer of the same monitor, if any) and size ("W,H").
 */
public final class TouchscreenDevice extends Device {
    private final String touchEvent;
    private final int base;
    private final Set<EvdevFile> readers = new CopyOnWriteArraySet<>();

    /**
     * @param touchEvent the CC event of a touch on it ({@code pixel_monitor_touch}, {@code monitor_touch})
     * @param base       the coordinate of the first pixel or cell in that event (CC's monitor_touch counts from 1)
     */
    public TouchscreenDevice(String peripheral, String kind, String touchEvent, int base, Supplier<String> fb, Supplier<String> size) {
        super("input", "event", peripheral);
        this.touchEvent = touchEvent;
        this.base = base;
        attribute("name", () -> kind + "\n");
        attribute("fb", () -> fb.get() + "\n");
        attribute("size", () -> size.get() + "\n");
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

    @Override
    protected boolean onEvent(Event event) {
        if (readers.isEmpty() || !event.name().equals(touchEvent)) return false;
        var args = event.args();
        if (args.length < 3 || !peripheral().equals(String.valueOf(args[0])) || !(args[1] instanceof Number x) || !(args[2] instanceof Number y)) {
            return false;
        }
        var out = ByteBuffer.allocate(6 * EvdevFile.RECORD).order(ByteOrder.LITTLE_ENDIAN);
        EvdevFile.record(out, EvdevFile.EV_ABS, EvdevFile.ABS_X, x.intValue() - base);
        EvdevFile.record(out, EvdevFile.EV_ABS, EvdevFile.ABS_Y, y.intValue() - base);
        EvdevFile.record(out, EvdevFile.EV_KEY, EvdevFile.BTN_TOUCH, 1);
        EvdevFile.record(out, EvdevFile.EV_SYN, EvdevFile.SYN_REPORT, 0);
        EvdevFile.record(out, EvdevFile.EV_KEY, EvdevFile.BTN_TOUCH, 0);
        EvdevFile.record(out, EvdevFile.EV_SYN, EvdevFile.SYN_REPORT, 0);
        var data = out.array();
        for (var r : readers) r.push(data);
        return false; // programs still get the CC event
    }

    @Override
    protected void removed() {
        readers.clear();
    }
}
