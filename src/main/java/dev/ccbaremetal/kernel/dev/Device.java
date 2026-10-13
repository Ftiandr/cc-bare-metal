package dev.ccbaremetal.kernel.dev;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.io.OpenFile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * A device of a class, as in Linux: {@code graphics} (fbN), {@code input} (eventN), {@code sound} (dspN),
 * {@code printer} (lpN), {@code tty} (monitorN), {@code net} (wlanN, ethN, lo), {@code redstone} (sides). The
 * {@link DeviceTree} names it (prefix plus the lowest free number), makes its /dev node (if it has one) and its
 * /sys/class/CLASS/NAME attributes. Drivers create devices for peripherals; the computer has built-in ones.
 */
public abstract class Device {
    /** A /sys attribute: a text value, writable if {@code write} is set (by anyone, or by root only). */
    public record Attribute(Supplier<String> read, Writer write, boolean anyone) {
    }

    @FunctionalInterface
    public interface Writer {
        void write(String value) throws FsException;
    }

    private final String deviceClass;
    private final String prefix;
    private final String peripheral;
    private final Map<String, Attribute> attributes = new LinkedHashMap<>();
    private final Set<OpenFile> openFiles = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    String name;

    /**
     * @param prefix     the name without its number ("fb" becomes fb0, fb1...), or the whole name of a device that
     *                   is not {@link #numbered()} (lo, mice, a redstone side)
     * @param peripheral the peripheral it belongs to, or null for the computer's own devices
     */
    protected Device(String deviceClass, String prefix, String peripheral) {
        this.deviceClass = deviceClass;
        this.prefix = prefix;
        this.peripheral = peripheral;
    }

    public final String deviceClass() {
        return deviceClass;
    }

    public final String prefix() {
        return prefix;
    }

    public final String name() {
        return name;
    }

    public final String peripheral() {
        return peripheral;
    }

    /** Whether the name gets a number (fb0, fb1...); false for single devices with a fixed name (lo, mice, sides). */
    protected boolean numbered() {
        return true;
    }

    /** The node's path below /dev ("fb1", "input/event2"), or null: net devices have none, as in Linux. */
    public String node() {
        return name;
    }

    /** Open the /dev node (called by the device tree, which revokes what is open when the device goes away). */
    protected OpenFile open(String guestPath) throws FsException {
        throw new FsException(Errno.ENODEV);
    }

    final OpenFile openTracked(String guestPath) throws FsException {
        var file = open(guestPath);
        openFiles.add(file);
        return file;
    }

    final void revokeFiles() {
        OpenFile[] files;
        synchronized (openFiles) {
            files = openFiles.toArray(new OpenFile[0]);
            openFiles.clear();
        }
        for (var f : files) f.revoke(Errno.ENODEV);
    }

    /** Declare a read-only attribute. Names may hold one folder: "statistics/rx_bytes". */
    protected final void attribute(String name, Supplier<String> read) {
        attributes.put(name, new Attribute(read, null, false));
    }

    /** Declare a writable attribute ({@code anyone}: every user, else root only). */
    protected final void attribute(String name, Supplier<String> read, Writer write, boolean anyone) {
        attributes.put(name, new Attribute(read, write, anyone));
    }

    public final Map<String, Attribute> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    /** A machine event (touches, modem messages...). True: taken by the device, not delivered to programs. */
    protected boolean onEvent(Event event) {
        return false;
    }

    /** The device was removed (its peripheral detached, or the computer stopped). */
    protected void removed() {
    }
}
