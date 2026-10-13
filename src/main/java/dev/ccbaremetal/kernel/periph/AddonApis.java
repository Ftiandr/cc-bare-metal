package dev.ccbaremetal.kernel.periph;

import dan200.computercraft.api.component.ComputerComponent;
import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.WorkMonitor;
import dan200.computercraft.impl.ApiFactories;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.kernel.fs.CcMountBackend;
import dev.ccbaremetal.kernel.fs.FsException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Global APIs that addons register with {@code ComputerCraftAPI.registerAPIFactory} (the same mechanism CC uses for
 * every computer), plus CC's own {@code http} API: created for each machine at boot, ticked, shut down with it. Lua
 * programs get them as globals, WebAssembly programs as object handles (cc::Api in C++). No per-addon code: whatever
 * an addon registers works.
 */
public final class AddonApis {
    private static final Logger LOG = LoggerFactory.getLogger(AddonApis.class);

    private static final dan200.computercraft.core.metrics.MetricsObserver NO_METRICS = dan200.computercraft.core.metrics.MetricsObserver.discard();

    private final Machine machine;
    private final List<ILuaAPI> apis = new ArrayList<>();
    private final Map<String, ILuaAPI> byName = new LinkedHashMap<>();
    private final List<String> mounts = new ArrayList<>();

    public AddonApis(Machine machine) {
        this.machine = machine;
    }

    public synchronized void start() {
        stop();
        if (machine.host().server() == null) return;
        var system = new System();
        for (var factory : ApiFactories.getAll()) {
            try {
                var api = factory.create(system);
                if (api == null) continue;
                apis.add(api);
                for (var name : api.getNames()) byName.putIfAbsent(name, api);
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("Addon API factory {} failed on computer {}", factory.getClass().getName(), machine.id(), e);
            }
        }
        // CC's own HTTP API (as on CC's computers: only when HTTP is enabled in its config).
        if (dan200.computercraft.core.CoreConfig.httpEnabled && !byName.containsKey("http")) {
            try {
                var http = new dan200.computercraft.core.apis.HTTPAPI(new HttpEnvironment(machine));
                apis.add(http);
                byName.put("http", http);
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("CC's HTTP API is not available on computer {}", machine.id(), e);
            }
        }
        for (var api : apis) {
            try {
                api.startup();
            } catch (RuntimeException e) {
                LOG.warn("Addon API {} failed to start", api.getClass().getName(), e);
            }
        }
    }

    /** Server tick. */
    public synchronized void update() {
        for (var api : apis) {
            try {
                api.update();
            } catch (RuntimeException e) {
                LOG.debug("Addon API {} update failed", api.getClass().getName(), e);
            }
        }
    }

    public synchronized void stop() {
        for (var api : apis) {
            try {
                api.shutdown();
            } catch (RuntimeException e) {
                LOG.debug("Addon API {} shutdown failed", api.getClass().getName(), e);
            }
        }
        apis.clear();
        byName.clear();
        for (var m : mounts) machine.vfs().unmount(m);
        mounts.clear();
    }

    public synchronized Map<String, ILuaAPI> all() {
        return new LinkedHashMap<>(byName);
    }

    public synchronized ILuaAPI get(String name) {
        return byName.get(name);
    }

    /** What an addon API sees of the computer: like CC's own ComputerSystem. */
    private final class System implements IComputerSystem {
        @Override
        public ServerLevel getLevel() {
            return machine.host().level();
        }

        @Override
        public BlockPos getPosition() {
            return machine.host().position();
        }

        @Override
        public String getLabel() {
            return machine.label();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getComponent(ComputerComponent<T> component) {
            // CC's own factories expect metrics on every computer.
            if (component == dan200.computercraft.shared.computer.core.ServerComputer.METRICS) return (T) NO_METRICS;
            return null;
        }

        private String doMount(String desired, Mount mount, String driveName) {
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
            synchronized (AddonApis.this) {
                mounts.add(location);
            }
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
        public void unmount(String location) {
            if (location == null) return;
            var path = "/" + location.replaceAll("^/+", "");
            synchronized (AddonApis.this) {
                if (mounts.remove(path)) machine.vfs().unmount(path);
            }
        }

        @Override
        public int getID() {
            return machine.id();
        }

        @Override
        public void queueEvent(String event, Object... arguments) {
            machine.queueEvent(new Event(event, arguments == null ? new Object[0] : arguments.clone()));
        }

        @Override
        public String getAttachmentName() {
            return "computer";
        }

        @Override
        public Map<String, IPeripheral> getAvailablePeripherals() {
            return machine.peripherals().available();
        }

        @Override
        public IPeripheral getAvailablePeripheral(String name) {
            var p = machine.peripherals().get(name);
            return p == null ? null : p.peripheral();
        }

        @Override
        public WorkMonitor getMainThreadMonitor() {
            return new WorkMonitor() {
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
    }
}
