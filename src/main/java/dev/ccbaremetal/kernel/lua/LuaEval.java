package dev.ccbaremetal.kernel.lua;

import dev.ccbaremetal.kernel.proc.Process;
import org.squiddev.cobalt.LuaError;
import org.squiddev.cobalt.LuaState;
import org.squiddev.cobalt.LuaThread;
import org.squiddev.cobalt.compiler.CompileException;
import org.squiddev.cobalt.compiler.LoadState;
import org.squiddev.cobalt.interrupt.InterruptAction;
import org.squiddev.cobalt.lib.Bit32Lib;
import org.squiddev.cobalt.lib.CoreLibraries;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A Lua state owned by a WASM process, for {@code lua_eval}: C programs can call into Lua (and through it into any
 * CC peripheral or Lua library) and get JSON results back. The state persists between calls.
 */
public final class LuaEval {
    private static final Map<Process, LuaEval> STATES = new WeakHashMap<>();

    private static final String PRELUDE = """
        colors = {white=1, orange=2, magenta=4, lightBlue=8, yellow=16, lime=32, pink=64, gray=128, lightGray=256,
          cyan=512, purple=1024, blue=2048, brown=4096, green=8192, red=16384, black=32768}
        colours = colors
        function peripheral.wrap(name)
          if not peripheral.isPresent(name) then return nil end
          local t = {}
          for _, m in ipairs(peripheral.getMethods(name)) do
            t[m] = function(...) return peripheral.call(name, m, ...) end
          end
          return t
        end
        function peripheral.getNames()
          local out = {}
          for _, side in ipairs({"top", "bottom", "left", "right", "front", "back"}) do
            if peripheral.isPresent(side) then out[#out + 1] = side end
          end
          return out
        end
        function peripheral.find(ty)
          local out = {}
          for _, name in ipairs(peripheral.getNames()) do
            if peripheral.hasType(name, ty) then out[#out + 1] = peripheral.wrap(name) end
          end
          return table.unpack(out)
        end
        """;

    public static final class EvalException extends Exception {
        EvalException(String message) {
            super(message, null, false, false);
        }
    }

    private final Process process;
    private final LuaState state;

    private LuaEval(Process process) throws LuaError {
        this.process = process;
        state = LuaState.builder()
            .interruptHandler(() -> {
                if (process.isKilled()) throw new LuaExit(137);
                return InterruptAction.CONTINUE;
            })
            .build();
        CoreLibraries.debugGlobals(state);
        Bit32Lib.add(state);
        CcApi.install(state, state.globals(), process, true);
        try {
            var prelude = LoadState.load(state, new ByteArrayInputStream(PRELUDE.getBytes(StandardCharsets.UTF_8)), "@prelude", state.globals());
            LuaThread.runMain(state, prelude);
        } catch (CompileException e) {
            throw new LuaError(e.getMessage());
        }
    }

    public static LuaEval forProcess(Process process) throws EvalException {
        synchronized (STATES) {
            var eval = STATES.get(process);
            if (eval == null) {
                try {
                    eval = new LuaEval(process);
                } catch (LuaError e) {
                    throw new EvalException(e.getMessage());
                }
                STATES.put(process, eval);
            }
            return eval;
        }
    }

    /** Run a chunk with the given arguments ("..."), returning its results as plain Java values. */
    public Object[] eval(String code, Object[] args) throws EvalException {
        try {
            var function = LoadState.load(state, new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8)), "=eval", state.globals());
            var results = LuaThread.runMain(state, function, LuaConvert.toLua(process, args));
            var out = new Object[results.count()];
            for (var i = 0; i < out.length; i++) out[i] = LuaConvert.toJava(results.arg(i + 1));
            return out;
        } catch (CompileException e) {
            throw new EvalException(e.getMessage());
        } catch (LuaError e) {
            throw new EvalException(e.getMessage());
        } catch (LuaExit e) {
            process.checkKilled();
            throw new EvalException("exit");
        }
    }
}
