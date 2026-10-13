package dev.ccbaremetal.kernel.lua;

import dan200.computercraft.api.lua.IDynamicLuaObject;
import dan200.computercraft.api.lua.ILuaFunction;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.core.methods.LuaMethod;
import dan200.computercraft.core.methods.MethodSupplier;
import dan200.computercraft.shared.computer.core.ServerContext;
import dev.ccbaremetal.kernel.periph.Peripherals;
import dev.ccbaremetal.kernel.proc.Process;
import org.squiddev.cobalt.Constants;
import org.squiddev.cobalt.LuaError;
import org.squiddev.cobalt.LuaString;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaValue;
import org.squiddev.cobalt.ValueFactory;
import org.squiddev.cobalt.Varargs;
import org.squiddev.cobalt.function.RegisteredFunction;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Java and Lua value conversion, including CC objects (functions, dynamic objects, @LuaFunction objects). */
public final class LuaConvert {
    private static volatile MethodSupplier<LuaMethod> luaMethods;

    private LuaConvert() {
    }

    /** CC's generator for {@code @LuaFunction} methods of arbitrary objects, fetched from CC's server context. */
    @SuppressWarnings("unchecked")
    private static MethodSupplier<LuaMethod> luaMethods(Process process) {
        var supplier = luaMethods;
        if (supplier != null) return supplier;
        try {
            var server = process.machine().server();
            if (server == null) return null;
            var ctx = ServerContext.get(server);
            var field = ServerContext.class.getDeclaredField("context");
            field.setAccessible(true);
            var computerContext = field.get(ctx);
            var method = computerContext.getClass().getMethod("luaMethods");
            supplier = (MethodSupplier<LuaMethod>) method.invoke(computerContext);
            luaMethods = supplier;
            return supplier;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Method names of an object returned by a peripheral or API (CC's {@code @LuaFunction} methods, dynamic objects,
     * functions as "call"), or null if it has none - then it is plain data.
     */
    public static java.util.List<String> methodNames(Process process, Object value) {
        if (value instanceof ILuaFunction) return java.util.List.of("call");
        if (value instanceof IDynamicLuaObject dynamic) return java.util.List.of(dynamic.getMethodNames());
        var supplier = luaMethods(process);
        if (supplier == null) return null;
        var names = new java.util.ArrayList<String>();
        var found = supplier.forEachMethod(value, (target, name, method, info) -> names.add(name));
        return found ? names : null;
    }

    /** Call a method of such an object (from a guest thread), waiting for it like a peripheral call. */
    public static Object[] invoke(Process process, Object value, String name, Object[] args) throws LuaException, InterruptedException {
        var context = new Peripherals.Context(process);
        var arguments = new LuaArgs(args);
        if (value instanceof ILuaFunction function) {
            if (!name.equals("call")) throw new LuaException("No such method " + name);
            return Peripherals.resolve(process, function.call(arguments));
        }
        if (value instanceof IDynamicLuaObject dynamic) {
            var names = dynamic.getMethodNames();
            for (var i = 0; i < names.length; i++) {
                if (names[i].equals(name)) return Peripherals.resolve(process, dynamic.callMethod(context, i, arguments));
            }
            throw new LuaException("No such method " + name);
        }
        var supplier = luaMethods(process);
        if (supplier == null) throw new LuaException("No such method " + name);
        var result = new MethodResult[1];
        var error = new LuaException[1];
        supplier.forEachMethod(value, (target, method, impl, info) -> {
            if (result[0] != null || error[0] != null || !method.equals(name)) return;
            try {
                result[0] = impl.apply(target, context, arguments);
            } catch (LuaException e) {
                error[0] = e;
            }
        });
        if (error[0] != null) throw error[0];
        if (result[0] == null) throw new LuaException("No such method " + name);
        return Peripherals.resolve(process, result[0]);
    }

    public static LuaValue toLua(Process process, Object value) {
        return toLua(process, value, new IdentityHashMap<>());
    }

    public static Varargs toLua(Process process, Object[] values) {
        if (values == null || values.length == 0) return Constants.NONE;
        var out = new LuaValue[values.length];
        var seen = new IdentityHashMap<Object, LuaValue>();
        for (var i = 0; i < values.length; i++) out[i] = toLua(process, values[i], seen);
        return ValueFactory.varargsOf(out);
    }

    private static LuaValue toLua(Process process, Object value, IdentityHashMap<Object, LuaValue> seen) {
        switch (value) {
            case null -> {
                return Constants.NIL;
            }
            case Boolean b -> {
                return ValueFactory.valueOf(b);
            }
            case Integer i -> {
                return ValueFactory.valueOf(i);
            }
            case Number n -> {
                return ValueFactory.valueOf(n.doubleValue());
            }
            case String s -> {
                return LuaText.encode(s);
            }
            case LuaArgs.Str s -> {
                return ValueFactory.valueOf(s.raw());
            }
            case byte[] b -> {
                return ValueFactory.valueOf(b.clone());
            }
            case ByteBuffer b -> {
                var bytes = new byte[b.remaining()];
                b.duplicate().get(bytes);
                return ValueFactory.valueOf(bytes);
            }
            case LuaValue v -> {
                return v;
            }
            default -> {
            }
        }
        var existing = seen.get(value);
        if (existing != null) return existing;

        if (value instanceof ILuaFunction function) {
            LuaValue fn = RegisteredFunction.ofV("function", (state, args) -> {
                try {
                    return toLua(process, Peripherals.resolve(process, function.call(fromLuaArgs(args))));
                } catch (LuaException e) {
                    throw new LuaError(e.getMessage());
                } catch (InterruptedException e) {
                    process.checkKilled();
                    throw new LuaError("Interrupted");
                }
            }).create();
            seen.put(value, fn);
            return fn;
        }
        if (value instanceof IDynamicLuaObject dynamic) {
            var table = new LuaTable();
            seen.put(value, table);
            var names = dynamic.getMethodNames();
            for (var i = 0; i < names.length; i++) {
                var index = i;
                table.rawset(names[i], RegisteredFunction.ofV(names[i], (state, args) -> call(process,
                    () -> dynamic.callMethod(new Peripherals.Context(process), index, fromLuaArgs(args)))).create());
            }
            return table;
        }
        if (value instanceof Map<?, ?> map) {
            var table = new LuaTable();
            seen.put(value, table);
            for (var entry : map.entrySet()) {
                var k = toLua(process, entry.getKey(), seen);
                var v = toLua(process, entry.getValue(), seen);
                if (!k.isNil() && !v.isNil()) {
                    try {
                        table.rawset(k, v);
                    } catch (LuaError ignored) {
                    }
                }
            }
            return table;
        }
        if (value instanceof Collection<?> list) {
            var table = new LuaTable();
            seen.put(value, table);
            var i = 1;
            for (var item : list) table.rawset(i++, toLua(process, item, seen));
            return table;
        }
        if (value instanceof Object[] array) {
            var table = new LuaTable();
            seen.put(value, table);
            for (var i = 0; i < array.length; i++) table.rawset(i + 1, toLua(process, array[i], seen));
            return table;
        }
        // Any other object: expose its @LuaFunction methods, like CC does for objects returned from peripherals.
        var supplier = luaMethods(process);
        if (supplier != null) {
            var table = new LuaTable();
            var found = supplier.forEachMethod(value, (target, name, method, info) ->
                table.rawset(name, RegisteredFunction.ofV(name, (state, args) -> call(process,
                    () -> method.apply(target, new Peripherals.Context(process), fromLuaArgs(args)))).create()));
            if (found) {
                seen.put(value, table);
                return table;
            }
        }
        return Constants.NIL;
    }

    @FunctionalInterface
    private interface Call {
        MethodResult call() throws LuaException;
    }

    private static Varargs call(Process process, Call call) throws LuaError {
        try {
            return toLua(process, Peripherals.resolve(process, call.call()));
        } catch (LuaException e) {
            throw new LuaError(e.getMessage());
        } catch (InterruptedException e) {
            process.checkKilled();
            throw new LuaError("Interrupted");
        }
    }

    public static LuaArgs fromLuaArgs(Varargs args) {
        var out = new Object[args.count()];
        for (var i = 0; i < out.length; i++) out[i] = fromLua(args.arg(i + 1), new IdentityHashMap<>(), true);
        return new LuaArgs(out);
    }

    /** Lua to plain Java: numbers become Double, strings String (decoded), tables Map. */
    public static Object toJava(LuaValue value) {
        return fromLua(value, new IdentityHashMap<>(), false);
    }

    private static Object fromLua(LuaValue value, IdentityHashMap<LuaValue, Object> seen, boolean keepRaw) {
        switch (value.type()) {
            case Constants.TNIL -> {
                return null;
            }
            case Constants.TBOOLEAN -> {
                return value.toBoolean();
            }
            case Constants.TNUMBER -> {
                return value.toDouble();
            }
            case Constants.TSTRING -> {
                var raw = LuaText.bytes(value);
                // Arguments for Java code (peripherals, addon objects): one char per byte, exactly as CC passes them.
                if (keepRaw) return new LuaArgs.Str(raw, new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1));
                return LuaText.decode(raw, 0, raw.length);
            }
            case Constants.TTABLE -> {
                var existing = seen.get(value);
                if (existing != null) return existing;
                var table = (LuaTable) value;
                var map = new HashMap<Object, Object>();
                seen.put(value, map);
                var k = Constants.NIL;
                try {
                    while (true) {
                        var next = table.next(k);
                        k = next.first();
                        if (k.isNil()) break;
                        var jk = fromLua(k, seen, false);
                        var jv = fromLua(next.arg(2), seen, keepRaw);
                        if (jk != null && jv != null) map.put(jk, jv instanceof LuaArgs.Str s ? s.text() : jv);
                    }
                } catch (LuaError ignored) {
                }
                return map;
            }
            default -> {
                return null;
            }
        }
    }

    public static LuaString str(String s) {
        return LuaText.encode(s);
    }
}
