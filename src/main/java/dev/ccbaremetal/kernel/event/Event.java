package dev.ccbaremetal.kernel.event;

import java.util.Arrays;

/**
 * A CC-style event: a name plus arguments. Arguments are plain Java values (null, Boolean, Number, String, byte[],
 * Map, List) so they can be handed to Lua, to CC callbacks or serialised to JSON for WASM programs.
 */
public record Event(String name, Object[] args) {
    public Event {
        args = args == null ? new Object[0] : args;
    }

    public static Event of(String name, Object... args) {
        return new Event(name, args);
    }

    public boolean isInput() {
        return switch (name) {
            case "key", "key_up", "char", "paste", "mouse_click", "mouse_up", "mouse_drag", "mouse_scroll", "terminate" -> true;
            default -> false;
        };
    }

    /** Name followed by arguments, as CC's ILuaCallback#resume expects. */
    public Object[] toArray() {
        var out = new Object[args.length + 1];
        out[0] = name;
        System.arraycopy(args, 0, out, 1, args.length);
        return out;
    }

    @Override
    public String toString() {
        return name + Arrays.toString(args);
    }
}
