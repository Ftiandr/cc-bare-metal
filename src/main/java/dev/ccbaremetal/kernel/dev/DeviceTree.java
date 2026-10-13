package dev.ccbaremetal.kernel.dev;

import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.io.OpenFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The computer's devices (Linux's device model in small): the peripheral bus, the drivers bound to its peripherals,
 * and the devices by class. /dev ({@code DevBackend}) and /sys ({@code SysBackend}) only show this tree:
 * <pre>
 *   /dev/NODE                                  each device's node (fb1, dsp0, input/event2...)
 *   /sys/class/CLASS/NAME/ATTRIBUTE            each device's attributes (plus uevent: DEVNAME=its node, and
 *                                              device: its peripheral)
 *   /sys/bus/peripheral/devices/NAME/type      a peripheral's CC types, one per line
 *   /sys/bus/peripheral/devices/NAME/driver    the driver that took it ("" if none: /dev/periph only)
 *   /sys/bus/peripheral/devices/NAME/devices   its devices, "CLASS/NAME" per line
 * </pre>
 * Peripherals come and go with the machine's "peripheral" / "peripheral_detach" events (sides and wired networks
 * alike); their devices' open files then fail with ENODEV, as after unplugging a device on Linux.
 */
public final class DeviceTree {
    private static final Logger LOG = LoggerFactory.getLogger(DeviceTree.class);

    private final Machine machine;
    private final List<Driver> drivers = new ArrayList<>();
    /** By class, then name. */
    private final Map<String, Map<String, Device>> classes = new LinkedHashMap<>();
    /** The peripherals on the bus, with the driver that took each ("" if none). */
    private final Map<String, Driver.Peripheral> bus = new LinkedHashMap<>();
    private final Map<String, String> boundDriver = new LinkedHashMap<>();

    public DeviceTree(Machine machine) {
        this.machine = machine;
    }

    public void addDriver(Driver driver) {
        drivers.add(driver);
    }

    // ---- Devices ----------------------------------------------------------------------------------------------------

    /** Name the device (prefix + lowest free number) and add it; its node and attributes appear at once. */
    public synchronized void register(Device device) {
        var byName = classes.computeIfAbsent(device.deviceClass(), k -> new LinkedHashMap<>());
        if (device.numbered()) {
            var n = 0;
            while (nameTaken(device.prefix() + n)) n++;
            device.name = device.prefix() + n;
        } else {
            device.name = device.prefix();
        }
        byName.put(device.name, device);
        machine.notifier().signal();
    }

    /** Names are unique across classes too (a node like fb1 must not appear twice in /dev). */
    private boolean nameTaken(String name) {
        for (var byName : classes.values()) if (byName.containsKey(name)) return true;
        return false;
    }

    public synchronized void unregister(Device device) {
        var byName = classes.get(device.deviceClass());
        if (byName == null || byName.get(device.name()) != device) return;
        byName.remove(device.name());
        removeQuietly(device);
    }

    private void removeQuietly(Device device) {
        device.revokeFiles();
        try {
            device.removed();
        } catch (RuntimeException e) {
            LOG.warn("Removing device {} failed", device.name(), e);
        }
        machine.notifier().signal();
    }

    /** The devices of a class, in name order. */
    public synchronized List<Device> devices(String deviceClass) {
        var byName = classes.get(deviceClass);
        if (byName == null) return List.of();
        var out = new ArrayList<>(byName.values());
        out.sort((a, b) -> compareNames(a.name(), b.name()));
        return out;
    }

    public synchronized Device get(String deviceClass, String name) {
        var byName = classes.get(deviceClass);
        return byName == null ? null : byName.get(name);
    }

    /** The devices of a peripheral. */
    public synchronized List<Device> devicesOf(String peripheral) {
        var out = new ArrayList<Device>();
        for (var byName : classes.values()) {
            for (var d : byName.values()) if (peripheral.equals(d.peripheral())) out.add(d);
        }
        return out;
    }

    /** fb2 after fb10 would be wrong: compare the numbers as numbers. */
    private static int compareNames(String a, String b) {
        var pa = a.replaceAll("\\d+$", "");
        var pb = b.replaceAll("\\d+$", "");
        if (!pa.equals(pb) || pa.length() == a.length() || pb.length() == b.length()) return a.compareTo(b);
        return Integer.compare(Integer.parseInt(a.substring(pa.length())), Integer.parseInt(b.substring(pb.length())));
    }

    // ---- The peripheral bus ---------------------------------------------------------------------------------------

    /** A peripheral appeared: offer it to the drivers. */
    private void attach(String name) {
        Driver.Peripheral p;
        synchronized (this) {
            if (bus.containsKey(name)) return;
        }
        var local = machine.peripherals().get(name);
        var types = machine.peripherals().typesDirect(name);
        if (types == null) return; // gone already
        p = new Driver.Peripheral(name, types, local == null ? null : local.peripheral());
        var driverName = "";
        for (var d : drivers) {
            if (!d.matches(p)) continue;
            try {
                d.probe(this, p);
                driverName = d.name();
            } catch (RuntimeException e) {
                LOG.warn("Driver {} failed on {}", d.name(), name, e);
            }
            break;
        }
        synchronized (this) {
            bus.put(name, p);
            boundDriver.put(name, driverName);
        }
    }

    /** A peripheral went away: its devices too. */
    public synchronized void detach(String name) {
        bus.remove(name);
        boundDriver.remove(name);
        for (var byName : classes.values()) {
            for (var it = byName.values().iterator(); it.hasNext(); ) {
                var d = it.next();
                if (!name.equals(d.peripheral())) continue;
                it.remove();
                removeQuietly(d);
            }
        }
    }

    /** The computer stopped: every peripheral is gone (the computer's own devices stay). */
    public synchronized void detachAll() {
        for (var name : List.copyOf(bus.keySet())) detach(name);
    }

    /**
     * Every machine event passes here: the bus follows peripherals, devices see the rest. True if a device took the
     * event (it is then not delivered to programs).
     */
    public boolean onEvent(Event event) {
        var args = event.args();
        if (event.name().equals("peripheral") && args.length > 0) {
            attach(String.valueOf(args[0]));
            return false;
        }
        if (event.name().equals("peripheral_detach") && args.length > 0) {
            detach(String.valueOf(args[0]));
            return false;
        }
        List<Device> all;
        synchronized (this) {
            all = new ArrayList<>();
            for (var byName : classes.values()) all.addAll(byName.values());
        }
        var taken = false;
        for (var d : all) taken |= d.onEvent(event);
        return taken;
    }

    // ---- /dev -------------------------------------------------------------------------------------------------------

    private synchronized Device byNode(String node) {
        for (var byName : classes.values()) {
            for (var d : byName.values()) if (node.equals(d.node())) return d;
        }
        return null;
    }

    public boolean hasNode(String node) {
        return byNode(node) != null;
    }

    /** Folders of nodes below /dev ("input"). */
    public synchronized boolean isNodeDirectory(String path) {
        for (var byName : classes.values()) {
            for (var d : byName.values()) if (d.node() != null && d.node().startsWith(path + "/")) return true;
        }
        return false;
    }

    /** Entries of a /dev folder ("" or "input") that come from devices. */
    public synchronized List<String> nodes(String dir) {
        var prefix = dir.isEmpty() ? "" : dir + "/";
        var out = new TreeSet<String>(DeviceTree::compareNames);
        for (var byName : classes.values()) {
            for (var d : byName.values()) {
                var node = d.node();
                if (node == null || !node.startsWith(prefix)) continue;
                var rest = node.substring(prefix.length());
                var slash = rest.indexOf('/');
                out.add(slash < 0 ? rest : rest.substring(0, slash));
            }
        }
        return new ArrayList<>(out);
    }

    public OpenFile open(String node, String guestPath) throws FsException {
        var d = byNode(node);
        if (d == null) throw new FsException(dev.ccbaremetal.kernel.Errno.ENOENT);
        return d.openTracked(guestPath);
    }

    // ---- /sys -------------------------------------------------------------------------------------------------------

    public synchronized List<String> classNames() {
        var out = new ArrayList<String>();
        for (var e : classes.entrySet()) if (!e.getValue().isEmpty()) out.add(e.getKey());
        return out;
    }

    /**
     * The attributes of a device, plus those every device has: "uevent" (DEVNAME=its node below /dev, as in Linux)
     * and, for a peripheral's devices, "device" (the peripheral's name).
     */
    public Map<String, Device.Attribute> attributes(Device d) {
        var out = new LinkedHashMap<>(d.attributes());
        out.put("uevent", new Device.Attribute(() -> d.node() == null ? "" : "DEVNAME=" + d.node() + "\n", null, false));
        if (d.peripheral() != null && !out.containsKey("device")) out.put("device", new Device.Attribute(() -> d.peripheral() + "\n", null, false));
        return out;
    }

    public synchronized List<String> peripherals() {
        return new ArrayList<>(bus.keySet());
    }

    /** /sys/bus/peripheral/devices/NAME/ATTRIBUTE, or null. */
    public synchronized String busAttribute(String peripheral, String attribute) {
        var p = bus.get(peripheral);
        if (p == null) return null;
        return switch (attribute) {
            case "type" -> String.join("\n", p.types()) + "\n";
            case "driver" -> boundDriver.getOrDefault(peripheral, "") + "\n";
            case "devices" -> {
                var sb = new StringBuilder();
                for (var d : devicesOf(peripheral)) sb.append(d.deviceClass()).append('/').append(d.name()).append('\n');
                yield sb.toString();
            }
            default -> null;
        };
    }

    public static final List<String> BUS_ATTRIBUTES = List.of("type", "driver", "devices");
}
