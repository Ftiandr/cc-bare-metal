package dev.ccbaremetal.kernel.proc;

import com.dylibso.chicory.runtime.ByteArrayMemory;
import com.dylibso.chicory.runtime.ChicoryInterruptedException;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportMemory;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.TrapException;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.UninstantiableException;
import com.dylibso.chicory.wasm.UnlinkableException;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.MemoryImport;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.ValType;
import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.sys.Syscalls;
import dev.ccbaremetal.kernel.wasi.Wasi;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A process running a WASI command module. Programs built for wasm32-wasip1-threads (pthreads) import a shared memory
 * and wasi::thread-spawn: each new thread is another instance of the same module on that memory, run on its own
 * Java thread from its export wasi_thread_start(tid, arg). exit() in any thread, or the end of main, ends them all.
 */
public final class WasmProcess extends Process {
    private final byte[] binary;
    private final int maxPages;
    private final int maxThreads;
    private final AtomicInteger nextTid = new AtomicInteger(1);
    private volatile Instance instance;

    public WasmProcess(Machine machine, int pid, int ppid, int pgid, String name, List<String> argv, Map<String, String> env,
                       byte[] binary, int maxPages, int maxThreads) {
        super(machine, pid, ppid, pgid, name, argv, env);
        this.binary = binary;
        this.maxPages = maxPages;
        this.maxThreads = maxThreads;
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

        // A module that imports its memory (a threaded program: shared) gets one made here, the same for every thread.
        MemoryImport memoryImport = null;
        for (var i = 0; i < compiled.module().importSection().importCount(); i++) {
            if (compiled.module().importSection().getImport(i) instanceof MemoryImport m) memoryImport = m;
        }
        Memory sharedMemory = null;
        if (memoryImport != null) {
            var limits = memoryImport.limits();
            var max = Math.min(limits.maximumPages(), maxPages);
            if (limits.initialPages() > max) {
                stderr(name + ": cannot start: module needs more memory than allowed\n");
                return 126;
            }
            sharedMemory = new ByteArrayMemory(new MemoryLimits(limits.initialPages(), max, limits.shared()));
        }

        var builder = ImportValues.builder()
            .addFunction(Wasi.functions(this))
            .addFunction(Syscalls.functions(this))
            .addFunction(threadSpawn(compiled));
        if (memoryImport != null) builder.addMemory(new ImportMemory(memoryImport.module(), memoryImport.name(), sharedMemory));
        var imports = builder.build();
        this.imports = imports;

        try {
            instance = instantiate(compiled, imports);
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

    private volatile ImportValues imports;

    /** A shared memory the module defines itself (not imported): made once, the same for every thread. */
    private volatile Memory ownShared;

    private Instance instantiate(ModuleCache.Compiled compiled, ImportValues imports) {
        var maxPages = this.maxPages;
        return Instance.builder(compiled.module())
            .withImportValues(imports)
            .withMachineFactory(compiled.machines())
            .withMemoryFactory(limits -> {
                var max = Math.min(limits.maximumPages(), maxPages);
                if (limits.initialPages() > max) throw new UninstantiableException("module needs more memory than allowed");
                if (!limits.shared()) return new ByteArrayMemory(new MemoryLimits(limits.initialPages(), max));
                synchronized (this) {
                    if (ownShared == null) ownShared = new ByteArrayMemory(new MemoryLimits(limits.initialPages(), max, true));
                    return ownShared;
                }
            })
            .withStart(false)
            .build();
    }

    /** wasi::thread-spawn(start_arg) -> tid (> 0), or a negative errno. */
    private HostFunction threadSpawn(ModuleCache.Compiled compiled) {
        return new HostFunction("wasi", "thread-spawn", FunctionType.of(List.of(ValType.I32), List.of(ValType.I32)), (inst, args) -> {
            checkKilled();
            if (threadCount() >= maxThreads) return new long[]{-Errno.EAGAIN};
            var tid = nextTid.getAndIncrement() & 0x1FFFFFFF;
            var startArg = (int) args[0];
            startThread("t" + tid, () -> runThread(compiled, tid, startArg));
            return new long[]{tid};
        });
    }

    private void runThread(ModuleCache.Compiled compiled, int tid, int startArg) {
        try {
            var thread = instantiate(compiled, imports);
            thread.exports().function("wasi_thread_start").apply(tid, startArg);
        } catch (ProcessExit e) {
            kill(e.code()); // exit() from a thread ends the process
        } catch (ChicoryInterruptedException e) {
            // the process is ending
        } catch (Throwable t) {
            var exit = ProcessExit.find(t);
            if (exit != null) {
                kill(exit.code());
            } else if (!isKilled()) {
                stderr(name + ": thread " + tid + ": " + (t instanceof TrapException ? "trap: " : "")
                    + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()) + "\n");
                kill(134);
            }
        }
    }

    @Override
    protected void cleanup() {
        // main is over: the other threads end too (their blocking calls are interrupted, not resumed)
        if (!isKilled() && threadCount() > 1) kill(exitCode());
        stopThreads();
        instance = null;
        imports = null;
        ownShared = null;
    }
}
