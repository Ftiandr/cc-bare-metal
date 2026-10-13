package dev.ccbaremetal.kernel.lua;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaValues;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Arguments passed from Lua to Java (peripheral methods). Strings are kept as raw bytes: {@link #getString} decodes
 * them (UTF-8 aware, see {@link LuaText}) while {@link #getBytes} returns the exact bytes, so binary data survives.
 */
public final class LuaArgs implements IArguments {
    /** A Lua string as it arrived: the raw bytes plus the decoded text. */
    public record Str(byte[] raw, String text) {
    }

    private final Object[] values;

    public LuaArgs(Object[] values) {
        this.values = values;
    }

    /** The raw values, for passing on to peripheral methods. */
    public Object[] raw() {
        return values;
    }

    @Override
    public int count() {
        return values.length;
    }

    @Override
    public Object get(int index) {
        if (index < 0 || index >= values.length) return null;
        var v = values[index];
        return v instanceof Str s ? s.text() : v;
    }

    @Override
    public String getType(int index) {
        var v = index < 0 || index >= values.length ? null : values[index];
        return switch (v) {
            case null -> "nil";
            case Str s -> "string";
            case String s -> "string";
            case Boolean b -> "boolean";
            case Number n -> "number";
            case java.util.Map<?, ?> m -> "table";
            default -> "userdata";
        };
    }

    @Override
    public IArguments drop(int count) {
        if (count <= 0) return this;
        if (count >= values.length) return new LuaArgs(new Object[0]);
        return new LuaArgs(Arrays.copyOfRange(values, count, values.length));
    }

    @Override
    public Object[] getAll() {
        var out = new Object[values.length];
        for (var i = 0; i < values.length; i++) out[i] = get(i);
        return out;
    }

    @Override
    public ByteBuffer getBytes(int index) throws LuaException {
        var v = index < 0 || index >= values.length ? null : values[index];
        if (v instanceof Str s) return ByteBuffer.wrap(s.raw()).asReadOnlyBuffer();
        if (v instanceof String s) return LuaValues.encode(s);
        throw LuaValues.badArgumentOf(this, index, "string");
    }
}
