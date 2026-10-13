package dev.ccbaremetal.kernel.periph;

import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaTask;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.core.methods.PeripheralMethod;
import dan200.computercraft.shared.computer.core.ServerContext;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.kernel.proc.Process;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The peripherals attached to a machine, and the machinery to call their methods from guest threads.
 *
 * <p>Methods are discovered with CC's own method generator ({@code @LuaFunction}, generic peripherals,
 * {@code IDynamicPeripheral}), so every peripheral that works on a CC computer works here too.
 */
public final class Peripherals {
    private static final AtomicLong TASK_IDS = new AtomicLong();

    private final Machine machine;
    private final Map<String, Attached> attached = new ConcurrentHashMap<>();

    public Peripherals(Machine machine) {
        this.machine = machine;
    }

    private static Map<String, PeripheralMethod> methodsOf(MinecraftServer server, IPeripheral peripheral) {
        return Collections.unmodifiableMap(ServerContext.get(server).peripheralMethods().getSelfMethods(peripheral));
    }

    /** Attach, replace or remove ({@code peripheral == null}) the peripheral called {@code name}. Server thread. */
    public void set(String name, IPeripheral peripheral) {
        var old = attached.get(name);
        if (old != null && peripheral != null && old.peripheral().equals(peripheral)) return;
        if (old != null) {
            attached.remove(name);
            try {
                old.detach();
            } catch (RuntimeException e) {
                // A peripheral that fails while detaching (an addon's bug) must not break the computer.
                org.slf4j.LoggerFactory.getLogger(Peripherals.class).warn("Detaching peripheral {} failed", name, e);
            }
            machine.queueEvent(Event.of("peripheral_detach", name));
        }
        if (peripheral != null && machine.isOn()) {
            var server = machine.server();
            if (server == null) return;
            var a = new Attached(machine, name, peripheral, methodsOf(server, peripheral));
            attached.put(name, a);
            a.attach();
            machine.queueEvent(Event.of("peripheral", name));
        }
    }

    public void detachAll() {
        for (var a : List.copyOf(attached.values())) {
            attached.remove(a.name());
            try {
                a.detach();
            } catch (RuntimeException ignored) {
            }
        }
    }

    public Attached get(String name) {
        return attached.get(name);
    }

    public List<Attached> list() {
        var out = new ArrayList<>(attached.values());
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return out;
    }

    public Map<String, IPeripheral> available() {
        var out = new HashMap<String, IPeripheral>();
        for (var a : attached.values()) out.put(a.name(), a.peripheral());
        return out;
    }

    /** Find a peripheral by name, also looking through wired modems for remote peripherals. */
    public boolean isPresent(Process caller, String name) throws PeripheralException {
        if (attached.containsKey(name)) return true;
        for (var modem : modems()) {
            var result = call(caller, modem.name(), "isPresentRemote", new Object[]{name});
            if (result.length > 0 && Boolean.TRUE.equals(result[0])) return true;
        }
        return false;
    }

    private List<Attached> modems() {
        var out = new ArrayList<Attached>();
        for (var a : attached.values()) {
            if (a.methods().containsKey("isPresentRemote") && a.methods().containsKey("callRemote")) out.add(a);
        }
        return out;
    }

    public List<String> types(Process caller, String name) throws PeripheralException {
        var a = attached.get(name);
        if (a != null) return a.types();
        for (var modem : modems()) {
            var present = call(caller, modem.name(), "isPresentRemote", new Object[]{name});
            if (present.length > 0 && Boolean.TRUE.equals(present[0])) {
                var types = call(caller, modem.name(), "getTypeRemote", new Object[]{name});
                var out = new ArrayList<String>();
                for (var t : types) if (t instanceof String s) out.add(s);
                return out;
            }
        }
        throw new PeripheralException("No such peripheral " + name);
    }

    public List<String> methodNames(Process caller, String name) throws PeripheralException {
        var a = attached.get(name);
        if (a != null) {
            var names = new ArrayList<>(a.methods().keySet());
            names.sort(null);
            return names;
        }
        for (var modem : modems()) {
            var present = call(caller, modem.name(), "isPresentRemote", new Object[]{name});
            if (present.length > 0 && Boolean.TRUE.equals(present[0])) {
                var result = call(caller, modem.name(), "getMethodsRemote", new Object[]{name});
                var out = new ArrayList<String>();
                if (result.length > 0 && result[0] instanceof Map<?, ?> map) {
                    for (var v : map.values()) out.add(String.valueOf(v));
                }
                out.sort(null);
                return out;
            }
        }
        throw new PeripheralException("No such peripheral " + name);
    }

    /**
     * Call a peripheral method from a guest thread, blocking until it completes. Methods that must run on the
     * server thread are scheduled there; methods that wait for events (yield) consume them from the caller's queue.
     */
    /** Strings in {@code args} must be in CC's form, one char per byte (see {@link CcStrings}). */
    public Object[] call(Process caller, String name, String method, Object[] args) throws PeripheralException {
        var a = attached.get(name);
        if (a == null) {
            for (var modem : modems()) {
                var present = call(caller, modem.name(), "isPresentRemote", new Object[]{name});
                if (present.length > 0 && Boolean.TRUE.equals(present[0])) {
                    var forwarded = new Object[args.length + 2];
                    forwarded[0] = name;
                    forwarded[1] = method;
                    System.arraycopy(args, 0, forwarded, 2, args.length);
                    return call(caller, modem.name(), "callRemote", forwarded);
                }
            }
            throw new PeripheralException("No such peripheral " + name);
        }
        var m = a.methods().get(method);
        if (m == null) throw new PeripheralException("No such method " + method);

        var context = new Context(caller);
        try {
            var result = m.apply(a.peripheral(), context, a, new dev.ccbaremetal.kernel.lua.LuaArgs(args));
            return resolve(caller, result);
        } catch (LuaException e) {
            throw new PeripheralException(e.getMessage() == null ? "error" : e.getMessage());
        } catch (InterruptedException e) {
            caller.checkKilled();
            throw new PeripheralException("Interrupted");
        } catch (RuntimeException e) {
            if (e instanceof dan200.computercraft.api.peripheral.NotAttachedException) {
                throw new PeripheralException("Peripheral detached");
            }
            throw new PeripheralException("Java exception thrown: " + e);
        }
    }

    /**
     * Call a method of a directly attached peripheral for the kernel itself (no guest process): only for methods
     * that neither yield nor need the server thread, such as a modem's open/isOpen/transmit/close.
     */
    public Object[] callDirect(String name, String method, Object... args) throws PeripheralException {
        var a = attached.get(name);
        if (a == null) throw new PeripheralException("No such peripheral " + name);
        var m = a.methods().get(method);
        if (m == null) throw new PeripheralException("No such method " + method);
        try {
            var result = m.apply(a.peripheral(), KERNEL_CONTEXT, a, new dev.ccbaremetal.kernel.lua.LuaArgs(args));
            if (result.getCallback() != null) throw new PeripheralException(method + " would wait");
            var values = result.getResult();
            return values == null ? new Object[0] : values;
        } catch (LuaException e) {
            throw new PeripheralException(e.getMessage() == null ? "error" : e.getMessage());
        } catch (RuntimeException e) {
            throw new PeripheralException("Java exception thrown: " + e);
        }
    }

    /**
     * {@link #callDirect} for a peripheral on a side or on a wired network (through its modem's callRemote): for the
     * kernel's own drivers (the speaker driver feeds speakers from its own thread).
     */
    public Object[] callKernel(String name, String method, Object... args) throws PeripheralException {
        if (attached.containsKey(name)) return callDirect(name, method, args);
        for (var modem : modems()) {
            var present = callDirect(modem.name(), "isPresentRemote", name);
            if (present.length == 0 || !Boolean.TRUE.equals(present[0])) continue;
            var forwarded = new Object[args.length + 2];
            forwarded[0] = name;
            forwarded[1] = method;
            System.arraycopy(args, 0, forwarded, 2, args.length);
            return callDirect(modem.name(), "callRemote", forwarded);
        }
        throw new PeripheralException("No such peripheral " + name);
    }

    /** The types of a peripheral for the kernel (no guest process): on a side, or on a wired network; null if gone. */
    public List<String> typesDirect(String name) {
        var a = attached.get(name);
        if (a != null) return a.types();
        for (var modem : modems()) {
            try {
                var present = callDirect(modem.name(), "isPresentRemote", name);
                if (present.length == 0 || !Boolean.TRUE.equals(present[0])) continue;
                var out = new ArrayList<String>();
                for (var t : callDirect(modem.name(), "getTypeRemote", name)) if (t instanceof String s) out.add(s);
                return out;
            } catch (PeripheralException ignored) {
            }
        }
        return null;
    }

    private static final ILuaContext KERNEL_CONTEXT = task -> {
        throw new LuaException("not available to the kernel");
    };

    /** Drive a {@link MethodResult} to completion, feeding it events while it asks for them. */
    public static Object[] resolve(Process caller, MethodResult result) throws LuaException, InterruptedException {
        var guard = 0;
        while (result.getCallback() != null) {
            if (guard++ > 1_000_000) throw new LuaException("Too many yields");
            var request = result.getResult();
            var filter = request != null && request.length > 0 && request[0] instanceof String s ? s : null;
            var event = caller.events.take(filter, -1);
            caller.checkKilled();
            result = result.getCallback().resume(event.toArray());
        }
        var values = result.getResult();
        return values == null ? new Object[0] : values;
    }

    /** The ILuaContext given to peripheral methods: main thread tasks report back to the calling process. */
    public static final class Context implements ILuaContext {
        private final Process caller;

        public Context(Process caller) {
            this.caller = caller;
        }

        @Override
        public long issueMainThreadTask(LuaTask task) {
            var id = TASK_IDS.incrementAndGet();
            MainThread.submit(() -> {
                if (caller.state() != Process.State.RUNNING) return;
                Event event;
                try {
                    var results = task.execute();
                    var args = new Object[2 + (results == null ? 0 : results.length)];
                    args[0] = id;
                    args[1] = true;
                    if (results != null) System.arraycopy(results, 0, args, 2, results.length);
                    event = new Event("task_complete", args);
                } catch (LuaException e) {
                    event = Event.of("task_complete", id, false, e.getMessage());
                } catch (Throwable t) {
                    event = Event.of("task_complete", id, false, "Java exception thrown: " + t);
                }
                caller.deliver(event);
            });
            return id;
        }
    }

    /** Describe peripherals as plain data (for JSON / Lua). */
    public List<Map<String, Object>> describe() {
        var out = new ArrayList<Map<String, Object>>();
        for (var a : list()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", a.name());
            entry.put("types", a.types());
            out.add(entry);
        }
        return out;
    }
}
