package dev.ccbaremetal.kernel.proc;

import com.dylibso.chicory.runtime.ByteArrayMemory;
import com.dylibso.chicory.runtime.ChicoryInterruptedException;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.TrapException;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.UninstantiableException;
import com.dylibso.chicory.wasm.UnlinkableException;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.sys.Syscalls;
import dev.ccbaremetal.kernel.wasi.Wasi;

import java.util.List;
import java.util.Map;

/** A process running a WASI command module. */
public final class WasmProcess extends Process {
    private final byte[] binary;
    private final int maxPages;
    private volatile Instance instance;

    public WasmProcess(Machine machine, int pid, int ppid, int pgid, String name, List<String> argv, Map<String, String> env,
                       byte[] binary, int maxPages) {
        super(machine, pid, ppid, pgid, name, argv, env);
        this.binary = binary;
        this.maxPages = maxPages;
    }

    public Instance instance() {
        return instance;
    }

    @Override
    protected int run() throws Exception {
        ModuleCache.Compiled compiled;
        try {
            compiled = ModuleCache.get(binary);
        } catch (ChicoryException e) {
            stderr(name + ": invalid WebAssembly module: " + e.getMessage() + "\n");
            return 126;
        }

        var maxPages = this.maxPages;
        var imports = ImportValues.builder()
            .addFunction(Wasi.functions(this))
            .addFunction(Syscalls.functions(this))
            .build();

        try {
            instance = Instance.builder(compiled.module())
                .withImportValues(imports)
                .withMachineFactory(compiled.machines())
                .withMemoryFactory(limits -> {
                    var max = Math.min(limits.maximumPages(), maxPages);
                    if (limits.initialPages() > max) throw new UninstantiableException("module needs more memory than allowed");
                    return new ByteArrayMemory(new MemoryLimits(limits.initialPages(), max));
                })
                .withStart(false)
                .build();
        } catch (UnlinkableException e) {
            stderr(name + ": cannot link: " + e.getMessage() + "\n");
            return 127;
        } catch (UninstantiableException e) {
            stderr(name + ": cannot start: " + e.getMessage() + "\n");
            return 126;
        }

        com.dylibso.chicory.runtime.ExportFunction start;
        try {
            start = instance.exports().function("_start");
        } catch (ChicoryException e) {
            start = null;
        }
        if (start == null) {
            stderr(name + ": not a command module (no _start)\n");
            return 126;
        }
        try {
            start.apply();
            return 0;
        } catch (ChicoryInterruptedException e) {
            throw new ProcessExit(128 + SIGKILL);
        } catch (TrapException e) {
            var exit = ProcessExit.find(e);
            if (exit != null) throw exit;
            stderr(name + ": trap: " + e.getMessage() + "\n");
            return 134;
        }
    }

    @Override
    protected void cleanup() {
        instance = null;
    }
}
