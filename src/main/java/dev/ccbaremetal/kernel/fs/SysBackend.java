package dev.ccbaremetal.kernel.fs;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.dev.Device;
import dev.ccbaremetal.kernel.dev.DeviceTree;
import dev.ccbaremetal.kernel.io.OpenFile;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /sys, as in Linux, made from the device tree:
 * <pre>
 *   /sys/class/CLASS/DEVICE/ATTRIBUTE                text files (graphics/fb0/virtual_size, net/wlan0/operstate...)
 *   /sys/bus/peripheral/devices/PERIPHERAL/type      its CC types; driver, devices: what it became
 * </pre>
 * What each attribute means is documented with its device (GraphicsDevice, NetDevice...). Writable attributes are
 * 0666 (the screen, redstone: every user at the computer) or 0644 (root: network interfaces).
 */
public final class SysBackend implements FsBackend {
    private static final String BUS = "bus/peripheral/devices";

    private final DeviceTree devices;

    public SysBackend(DeviceTree devices) {
        this.devices = devices;
    }

    /** A device's attributes below {@code dir} ("" or "statistics"): files and sub-folders. */
    private static List<String> entries(Map<String, Device.Attribute> attributes, String dir) {
        var prefix = dir.isEmpty() ? "" : dir + "/";
        var out = new ArrayList<String>();
        for (var name : attributes.keySet()) {
            if (!name.startsWith(prefix)) continue;
            var rest = name.substring(prefix.length());
            var slash = rest.indexOf('/');
            var entry = slash < 0 ? rest : rest.substring(0, slash);
            if (!out.contains(entry)) out.add(entry);
        }
        return out;
    }

    /** The listing of a folder, or null if {@code path} is not one. */
    private List<String> dir(String path) {
        var parts = path.isEmpty() ? new String[0] : path.split("/");
        if (parts.length == 0) return List.of("bus", "class");
        if (parts[0].equals("class")) {
            if (parts.length == 1) return devices.classNames();
            if (parts.length == 2) {
                var names = new ArrayList<String>();
                for (var d : devices.devices(parts[1])) names.add(d.name());
                return names.isEmpty() ? null : names;
            }
            var d = devices.get(parts[1], parts[2]);
            if (d == null) return null;
            var out = entries(devices.attributes(d), String.join("/", java.util.Arrays.copyOfRange(parts, 3, parts.length)));
            return out.isEmpty() || devices.attributes(d).containsKey(String.join("/", java.util.Arrays.copyOfRange(parts, 3, parts.length))) ? null : out;
        }
        if (parts[0].equals("bus")) {
            if (path.equals("bus")) return List.of("peripheral");
            if (path.equals("bus/peripheral")) return List.of("devices");
            if (path.equals(BUS)) return devices.peripherals();
            if (path.startsWith(BUS + "/") && parts.length == 4 && devices.peripherals().contains(parts[3])) return DeviceTree.BUS_ATTRIBUTES;
        }
        return null;
    }

    /** An attribute file: its value and how it is written. */
    private record Attr(String path, Device.Attribute attribute) {
    }

    private Attr attribute(String path) {
        var parts = path.split("/");
        if (parts.length >= 4 && parts[0].equals("class")) {
            var d = devices.get(parts[1], parts[2]);
            if (d == null) return null;
            var a = devices.attributes(d).get(String.join("/", java.util.Arrays.copyOfRange(parts, 3, parts.length)));
            return a == null ? null : new Attr(path, a);
        }
        if (parts.length == 5 && path.startsWith(BUS + "/") && DeviceTree.BUS_ATTRIBUTES.contains(parts[4])) {
            if (devices.busAttribute(parts[3], parts[4]) == null) return null;
            return new Attr(path, new Device.Attribute(() -> {
                var v = devices.busAttribute(parts[3], parts[4]);
                return v == null ? "" : v;
            }, null, false));
        }
        return null;
    }

    private static int mode(Device.Attribute a) {
        return a.write() == null ? 0444 : a.anyone() ? 0666 : 0644;
    }

    @Override
    public Stat stat(String path) throws FsException {
        if (dir(path) != null) return Stat.dir(0);
        var a = attribute(path);
        if (a == null) throw new FsException(Errno.ENOENT);
        return Stat.file(a.attribute().read().get().length(), 0).withOwner(mode(a.attribute()), 0, 0);
    }

    @Override
    public List<String> list(String path) throws FsException {
        var entries = dir(path);
        if (entries == null) throw new FsException(attribute(path) != null ? Errno.ENOTDIR : Errno.ENOENT);
        return entries;
    }

    @Override
    public OpenFile open(String path, String guestPath, int flags) throws FsException {
        var a = attribute(path);
        if (a == null) throw new FsException(dir(path) != null ? Errno.EISDIR : Errno.ENOENT);
        return new AttributeFile(a.attribute(), guestPath);
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String describe() {
        return "sysfs";
    }

    /** An attribute file: reads a snapshot, applies what is written (line by line, the rest on close). */
    private static final class AttributeFile extends OpenFile {
        private final Device.Attribute attribute;
        private final String guestPath;
        private final byte[] snapshot;
        private final StringBuilder pending = new StringBuilder();
        private long position;

        AttributeFile(Device.Attribute attribute, String guestPath) {
            this.attribute = attribute;
            this.guestPath = guestPath;
            this.snapshot = attribute.read().get().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public FileType type() {
            return FileType.REGULAR_FILE;
        }

        @Override
        public Stat stat() {
            return Stat.file(snapshot.length, 0).withOwner(mode(attribute), 0, 0);
        }

        @Override
        public synchronized int read(ByteBuffer dst, boolean nonBlocking) {
            if (position >= snapshot.length) return 0;
            var n = (int) Math.min(dst.remaining(), snapshot.length - position);
            dst.put(snapshot, (int) position, n);
            position += n;
            return n;
        }

        private void apply(String text) throws FsException {
            if (attribute.write() == null) throw new FsException(Errno.EACCES);
            attribute.write().write(text.strip());
        }

        @Override
        public synchronized int write(ByteBuffer src, boolean nonBlocking) throws FsException {
            var n = src.remaining();
            var bytes = new byte[n];
            src.get(bytes);
            pending.append(new String(bytes, StandardCharsets.UTF_8));
            var nl = pending.lastIndexOf("\n");
            if (nl >= 0) {
                var text = pending.substring(0, nl + 1);
                pending.delete(0, nl + 1);
                apply(text);
            }
            return n;
        }

        @Override
        public void truncate(long size) {
        }

        @Override
        protected synchronized void onClose() throws FsException {
            if (!pending.toString().isBlank()) apply(pending.toString());
            pending.setLength(0);
        }

        @Override
        public String path() {
            return guestPath;
        }
    }
}
