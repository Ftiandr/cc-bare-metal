package dev.ccbaremetal.kernel.proc;

import dev.ccbaremetal.kernel.lua.LuaConvert;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Objects with methods that a WebAssembly program received (from peripherals of any mod, addon APIs, events): a
 * program cannot hold Java objects, so it gets {"__object": id, "methods": [...]} and calls them by id (obj_call).
 * Per process; the oldest handles are dropped when there are too many.
 */
public final class ObjectTable {
    private static final int MAX = 4096;

    private final Process process;
    private final Map<Integer, Object> objects = new LinkedHashMap<>();
    private final Map<Object, Integer> ids = new IdentityHashMap<>();
    private int next = 1;

    ObjectTable(Process process) {
        this.process = process;
    }

    /** JSON encoder hook: a handle for objects with methods, null for anything else. */
    public Object encode(Object value) {
        var methods = LuaConvert.methodNames(process, value);
        if (methods == null) return null;
        int id;
        synchronized (this) {
            var existing = ids.get(value);
            if (existing != null) {
                id = existing;
            } else {
                id = next++;
                objects.put(id, value);
                ids.put(value, id);
                while (objects.size() > MAX) {
                    var oldest = objects.entrySet().iterator().next();
                    ids.remove(oldest.getValue());
                    objects.remove(oldest.getKey());
                }
            }
        }
        var handle = new LinkedHashMap<String, Object>();
        handle.put("__object", id);
        handle.put("methods", methods);
        return handle;
    }

    /** Register an object and return its handle (or null if it has no methods). */
    public Object handle(Object value) {
        return encode(value);
    }

    public synchronized Object get(int id) {
        return objects.get(id);
    }

    public synchronized void free(int id) {
        var value = objects.remove(id);
        if (value != null) ids.remove(value);
    }
}
