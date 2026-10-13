package dev.ccbaremetal.kernel.periph;

import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.NotAttachedException;
import dan200.computercraft.api.peripheral.WorkMonitor;
import dan200.computercraft.core.methods.PeripheralMethod;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.kernel.fs.CcMountBackend;
import dev.ccbaremetal.kernel.fs.FsException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A peripheral attached to a machine. Implements CC's {@link IComputerAccess}, so peripherals from any mod can
 * mount file systems (disk drives), queue events (modems, monitors) and look up other peripherals.
 */
public final class Attached implements IComputerAccess {
    private final Machine machine;
    private final String name;
    private final IPeripheral peripheral;
    private final Map<String, PeripheralMethod> methods;
    private final List<String> mounts = new ArrayList<>();
    private volatile boolean attached;

    Attached(Machine machine, String name, IPeripheral peripheral, Map<String, PeripheralMethod> methods) {
        this.machine = machine;
        this.name = name;
        this.peripheral = peripheral;
        this.methods = methods;
    }

    public String name() {
        return name;
    }

    public IPeripheral peripheral() {
        return peripheral;
    }

    public Map<String, PeripheralMethod> methods() {
        return methods;
    }

    public List<String> types() {
        var out = new ArrayList<String>();
        out.add(peripheral.getType());
        for (var t : peripheral.getAdditionalTypes()) if (!out.contains(t)) out.add(t);
        return out;
    }

    void attach() {
        attached = true;
        peripheral.attach(this);
    }

    void detach() {
        try {
            peripheral.detach(this);
        } finally {
            synchronized (this) {
                for (var m : mounts) machine.vfs().unmount(m);
                mounts.clear();
            }
            attached = false;
        }
    }

    public boolean isAttached() {
        return attached;
    }

    private void check() {
        if (!attached) throw new NotAttachedException();
    }

    private synchronized String doMount(String desired, Mount mount, String driveName) {
        check();
        var base = "/" + desired.replaceAll("^/+", "");
        var location = base;
        for (var i = 2; machine.vfs().exists(location) || machine.vfs().isMountPoint(location); i++) {
            location = base + i;
            if (i > 64) return null;
        }
        try {
            machine.vfs().mount(location, new CcMountBackend(mount, driveName));
        } catch (FsException e) {
            return null;
        }
        mounts.add(location);
        return location.substring(1);
    }

    @Override
    public String mount(String desiredLocation, Mount mount, String driveName) {
        return doMount(desiredLocation, mount, driveName);
    }

    @Override
    public String mountWritable(String desiredLocation, WritableMount mount, String driveName) {
        return doMount(desiredLocation, mount, driveName);
    }

    @Override
    public synchronized void unmount(String location) {
        check();
        if (location == null) return;
        var path = "/" + location.replaceAll("^/+", "");
        if (mounts.remove(path)) machine.vfs().unmount(path);
    }

    @Override
    public int getID() {
        check();
        return machine.id();
    }

    @Override
    public void queueEvent(String event, Object... arguments) {
        check();
        machine.queueEvent(new Event(event, arguments == null ? new Object[0] : arguments.clone()));
    }

    @Override
    public String getAttachmentName() {
        check();
        return name;
    }

    @Override
    public Map<String, IPeripheral> getAvailablePeripherals() {
        check();
        return machine.peripherals().available();
    }

    @Override
    public IPeripheral getAvailablePeripheral(String other) {
        check();
        var p = machine.peripherals().get(other);
        return p == null ? null : p.peripheral();
    }

    @Override
    public WorkMonitor getMainThreadMonitor() {
        check();
        return MONITOR;
    }

    private static final WorkMonitor MONITOR = new WorkMonitor() {
        @Override
        public boolean canWork() {
            return true;
        }

        @Override
        public boolean shouldWork() {
            return true;
        }

        @Override
        public void trackWork(long time, TimeUnit unit) {
        }
    };
}
