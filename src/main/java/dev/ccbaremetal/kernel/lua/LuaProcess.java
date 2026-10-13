package dev.ccbaremetal.kernel.lua;

import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.kernel.proc.Process;
import net.neoforged.fml.ModList;
import org.squiddev.cobalt.Constants;
import org.squiddev.cobalt.LuaError;
import org.squiddev.cobalt.LuaState;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaThread;
import org.squiddev.cobalt.ValueFactory;
import org.squiddev.cobalt.Varargs;
import org.squiddev.cobalt.compiler.CompileException;
import org.squiddev.cobalt.compiler.LoadState;
import org.squiddev.cobalt.interrupt.InterruptAction;
import org.squiddev.cobalt.lib.Bit32Lib;
import org.squiddev.cobalt.lib.CoreLibraries;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * CraftOS running as a process of our OS: CC's own bios.lua and ROM on top of our native APIs ({@link CcApi}).
 *
 * <p>Interactive mode ({@code craftos}) starts the CC shell; script mode ({@code lua file args...}) runs one program
 * through the CC shell, so programs get the exact environment they expect (shell, require, multishell APIs...).
 */
public final class LuaProcess extends Process {
    private static final String RUN_PATCH_FROM = "os.run({}, sShell)";
    private static final String RUN_WRAPPER = "/usr/lib/ccbaremetal/run.lua";
    private static final String RUN_PATCH_TO = "os.run({}, sShell, table.unpack(_BAREMETAL_ARGS or {}))";

    /** Helpers for {@link #UTF8_READ}: byte lengths of UTF-8 characters around a position (1 for anything else). */
    private static final String UTF8_HELPERS = """
        local function cont(b) return b and b >= 0x80 and b < 0xC0 end
        local function fwd(s, p)
          local b = string.byte(s, p + 1) or 0
          local n = b >= 0xF0 and b <= 0xF4 and 4 or b >= 0xE0 and b <= 0xEF and 3 or b >= 0xC2 and b <= 0xDF and 2 or 1
          for k = 1, n - 1 do if not cont(string.byte(s, p + 1 + k)) then return 1 end end
          return n
        end
        local function back(s, p)
          local q = p
          while q > 1 and cont(string.byte(s, q)) and p - q < 3 do q = q - 1 end
          if q < p and fwd(s, q - 1) == p - q + 1 then return p - q + 1 end
          return 1
        end
        local function snap(s, p)
          while p > 0 and p < #s and cont(string.byte(s, p + 1)) do p = p - 1 end
          return p
        end
        local function up(s, p)
          while p > 0 and p < #s and cont(string.byte(s, p + 1)) do p = p + 1 end
          return p
        end
        _BAREMETAL_UTF8 = { fwd = fwd, back = back, snap = snap, up = up }
        """;

    /**
     * read() of CC's bios moves by bytes; with UTF-8 text (typed Cyrillic arrives as "paste" events) that splits
     * letters. These edits make it move by characters. Applied only if every piece matches this CC version exactly;
     * otherwise read() stays as CC has it.
     */
    private static final String[][] UTF8_READ = {
        {"""
            sLine = string.sub(sLine, 1, nPos) .. param .. string.sub(sLine, nPos + 1)
            nPos = nPos + 1
""", """
            sLine = string.sub(sLine, 1, nPos) .. param .. string.sub(sLine, nPos + 1)
            nPos = nPos + #param
"""},
        {"""
                    clear()
                    nPos = nPos - 1
                    recomplete()
""", """
                    clear()
                    nPos = nPos - _BAREMETAL_UTF8.back(sLine, nPos)
                    recomplete()
"""},
        {"""
                    clear()
                    nPos = nPos + 1
                    recomplete()
""", """
                    clear()
                    nPos = nPos + _BAREMETAL_UTF8.fwd(sLine, nPos)
                    recomplete()
"""},
        {"""
                    sLine = string.sub(sLine, 1, nPos - 1) .. string.sub(sLine, nPos + 1)
                    nPos = nPos - 1
                    if nScroll > 0 then nScroll = nScroll - 1 end
""", """
                    local nBytes = _BAREMETAL_UTF8.back(sLine, nPos) sLine = string.sub(sLine, 1, nPos - nBytes) .. string.sub(sLine, nPos + 1)
                    nPos = nPos - nBytes
                    if nScroll > 0 then nScroll = math.max(0, nScroll - nBytes) end
"""},
        {"sLine = string.sub(sLine, 1, nPos) .. string.sub(sLine, nPos + 2)",
            "sLine = string.sub(sLine, 1, nPos) .. string.sub(sLine, nPos + 1 + _BAREMETAL_UTF8.fwd(sLine, nPos))"},
        {"nPos = math.min(math.max(nScroll + param1 - sx, 0), #sLine)",
            "nPos = _BAREMETAL_UTF8.snap(sLine, math.min(math.max(nScroll + param1 - sx, 0), #sLine))"},
        {"nScroll = sx + nPos - w", "nScroll = _BAREMETAL_UTF8.up(sLine, sx + nPos - w)"},
    };

    private final boolean interactive;
    private volatile LuaState state;

    private LuaProcess(Machine machine, int pid, int ppid, int pgid, String name, List<String> argv, Map<String, String> env, boolean interactive) {
        super(machine, pid, ppid, pgid, name, argv, env);
        this.interactive = interactive;
        wantsEvents = true;
        inputEvents = true;
        catchSignals = true;
    }

    public static void register() {
        Machine.registerNative("craftos", (m, pid, ppid, pgid, name, argv, env) -> new LuaProcess(m, pid, ppid, pgid, name, argv, env, true));
        Machine.registerNative("lua", (m, pid, ppid, pgid, name, argv, env) -> new LuaProcess(m, pid, ppid, pgid, name, argv, env, false));
    }

    static byte[] bios() throws IOException {
        var file = ModList.get().getModFileById("computercraft").getFile();
        return Files.readAllBytes(file.findResource("data", "computercraft", "lua", "bios.lua"));
    }

    @Override
    protected int run() throws Exception {
        var state = this.state = LuaState.builder()
            .interruptHandler(() -> {
                if (isKilled()) throw new LuaExit(137);
                return InterruptAction.CONTINUE;
            })
            .build();
        CoreLibraries.debugGlobals(state);
        Bit32Lib.add(state);
        var globals = state.globals();
        CcApi.install(state, globals, this, false);

        // Program arguments for script mode: "lua" alone opens CC's Lua REPL.
        var args = new LuaTable();
        if (!interactive) {
            // Run through /usr/lib/ccbaremetal/run.lua, which first moves the CraftOS shell to our working directory.
            args.rawset(1, ValueFactory.valueOf(RUN_WRAPPER));
            args.rawset(2, LuaText.encode(quote(cwd())));
            if (argv.size() > 1) {
                for (var i = 1; i < argv.size(); i++) args.rawset(i + 2, LuaText.encode(quote(argv.get(i))));
            } else {
                args.rawset(3, ValueFactory.valueOf("lua"));
            }
            globals.rawset("_CC_DEFAULT_SETTINGS", ValueFactory.valueOf(
                "bios.use_multishell=false,shell.allow_startup=false,shell.allow_disk_startup=false,motd.enable=false"));
        } else {
            globals.rawset("_CC_DEFAULT_SETTINGS", ValueFactory.valueOf("shell.allow_disk_startup=false"));
        }
        globals.rawset("_BAREMETAL_ARGS", args);

        var source = new String(bios(), StandardCharsets.UTF_8);
        if (!interactive) {
            // Script mode: pass the program to the CC shell, and exit right after it (no "Goodbye", no 1s sleep).
            source = source.replace(RUN_PATCH_FROM, RUN_PATCH_TO)
                .replace("os.run({}, \"rom/programs/shutdown.lua\")", "os.shutdown()");
        }
        source = utf8Read(source);
        LuaThread main;
        try {
            var helpers = LoadState.load(state, new ByteArrayInputStream(UTF8_HELPERS.getBytes(StandardCharsets.UTF_8)), "=ccbaremetal", globals);
            LuaThread.runMain(state, helpers);
            var function = LoadState.load(state, new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "@bios.lua", globals);
            main = new LuaThread(state, function);
        } catch (CompileException e) {
            stderr("craftos: cannot load bios: " + e.getMessage() + "\n");
            return 1;
        }

        if (interactive) machine.terminal().clear();
        if (interactive) machine.terminal().setCursor(0, 0);
        try {
            Varargs resume = Constants.NONE;
            while (true) {
                var results = LuaThread.run(main, resume);
                if (!main.isAlive()) return 0;
                if (results == null) {
                    resume = Constants.NONE;
                    continue;
                }
                var filter = results.first().isString() ? results.first().toString() : null;
                Event event;
                do {
                    event = events.take(null, -1);
                    checkKilled();
                } while (filter != null && !filter.equals(event.name()) && !event.name().equals("terminate"));
                resume = LuaConvert.toLua(this, CcApi.forLua(event).toArray());
            }
        } catch (LuaExit e) {
            return e.code();
        } catch (LuaError e) {
            stderr("craftos: " + e.getMessage() + "\n");
            return 1;
        }
    }

    /** {@link #UTF8_READ} applied to the bios source, or the source unchanged if any piece is not found exactly once. */
    static String utf8Read(String source) {
        var out = source;
        for (var edit : UTF8_READ) {
            var at = out.indexOf(edit[0]);
            if (at < 0 || out.indexOf(edit[0], at + 1) >= 0) return source;
            out = out.replace(edit[0], edit[1]);
        }
        return out;
    }

    /** The CC shell joins its arguments with spaces and splits them again; keep arguments with spaces together. */
    private static String quote(String arg) {
        return arg.indexOf(' ') >= 0 && arg.indexOf('"') < 0 ? '"' + arg + '"' : arg;
    }

    @Override
    protected void onKill() {
        var s = state;
        if (s != null) s.interrupt();
    }

    @Override
    protected void cleanup() {
        state = null;
    }
}
