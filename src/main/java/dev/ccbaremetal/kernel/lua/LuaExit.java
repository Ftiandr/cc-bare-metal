package dev.ccbaremetal.kernel.lua;

/**
 * Unwinds the Lua VM (os.shutdown, kill). An {@link Error} so that pcall cannot catch it, the same trick CC uses.
 */
public final class LuaExit extends Error {
    private final int code;

    public LuaExit(int code) {
        super("exit " + code, null, false, false);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
