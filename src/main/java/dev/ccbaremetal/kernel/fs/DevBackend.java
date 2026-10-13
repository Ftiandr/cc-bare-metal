package dev.ccbaremetal.kernel.fs;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.dev.DeviceTree;
import dev.ccbaremetal.kernel.io.DeviceFile;
import dev.ccbaremetal.kernel.io.OpenFile;
import dev.ccbaremetal.kernel.periph.PeriphFiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * /dev: the fixed nodes (null, zero, random, urandom, tty, console), the nodes of the device tree (fb0, input/event0,
 * dsp0...) and /dev/periph (every peripheral's methods, see {@link PeriphFiles}).
 */
public final class DevBackend implements FsBackend {
    private final Map<String, Supplier<OpenFile>> fixed = new LinkedHashMap<>();
    private final DeviceTree devices;
    private final PeriphFiles periph;

    /** @param tty the caller's controlling terminal (/dev/tty; /dev/console too) */
    public DevBackend(Supplier<OpenFile> tty, DeviceTree devices, PeriphFiles periph) {
        this.devices = devices;
        this.periph = periph;
        fixed.put("null", () -> new DeviceFile(DeviceFile.Kind.NULL, "/dev/null"));
        fixed.put("zero", () -> new DeviceFile(DeviceFile.Kind.ZERO, "/dev/zero"));
        fixed.put("random", () -> new DeviceFile(DeviceFile.Kind.RANDOM, "/dev/random"));
        fixed.put("urandom", () -> new DeviceFile(DeviceFile.Kind.RANDOM, "/dev/urandom"));
        fixed.put("tty", tty);
        fixed.put("console", tty);
    }

    private boolean isDirectory(String path) {
        return path.isEmpty() || devices.isNodeDirectory(path) || periph.isDirectory(path);
    }

    private boolean isNode(String path) {
        return fixed.containsKey(path) || devices.hasNode(path) || periph.isFile(path);
    }

    @Override
    public Stat stat(String path) throws FsException {
        if (isDirectory(path)) return Stat.dir(0);
        if (isNode(path)) return Stat.device();
        throw new FsException(Errno.ENOENT);
    }

    @Override
    public List<String> list(String path) throws FsException {
        if (periph.isDirectory(path)) return periph.list(path);
        if (!isDirectory(path)) throw new FsException(isNode(path) ? Errno.ENOTDIR : Errno.ENOENT);
        var out = new ArrayList<String>();
        if (path.isEmpty()) {
            out.addAll(fixed.keySet());
            out.add("periph");
        }
        for (var e : devices.nodes(path)) if (!out.contains(e)) out.add(e);
        return out;
    }

    @Override
    public OpenFile open(String path, String guestPath, int flags) throws FsException {
        var f = fixed.get(path);
        if (f != null) return f.get();
        if (isDirectory(path)) throw new FsException(Errno.EISDIR);
        if (devices.hasNode(path)) return devices.open(path, guestPath);
        if (periph.isFile(path)) return periph.open(path);
        throw new FsException(OpenFlags.has(flags, OpenFlags.CREATE) ? Errno.EACCES : Errno.ENOENT);
    }

    @Override
    public String describe() {
        return "devfs";
    }
}
