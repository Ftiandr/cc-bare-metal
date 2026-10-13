package dev.ccbaremetal.kernel.wasi;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.proc.Process;
import dev.ccbaremetal.kernel.proc.ProcessExit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Helpers for defining host functions: signatures, memory access and errno mapping. */
public final class Host {
    private Host() {
    }

    @FunctionalInterface
    public interface Body {
        /** Returns the i32 result (usually an errno). May throw {@link FsException} which becomes its errno. */
        long call(Instance instance, Memory memory, long[] args) throws FsException, InterruptedException;
    }

    /** Collects host functions for one import module. */
    public static final class Module {
        private final String name;
        private final Process process;
        private final List<HostFunction> functions = new ArrayList<>();

        public Module(String name, Process process) {
            this.name = name;
            this.process = process;
        }

        /** Signature string: one char per param ('i' = i32, 'I' = i64), then ':' and the result ("i", "I" or ""). */
        public Module fn(String symbol, String signature, Body body) {
            var parts = signature.split(":", -1);
            var params = types(parts[0]);
            var results = types(parts.length > 1 ? parts[1] : "i");
            var hasResult = !results.isEmpty();
            functions.add(new HostFunction(name, symbol, FunctionType.of(params, results), (instance, args) -> {
                process.checkKilled();
                long result;
                try {
                    result = body.call(instance, instance.memory(), args);
                } catch (FsException e) {
                    result = e.errno();
                } catch (InterruptedException e) {
                    process.checkKilled();
                    result = Errno.EINTR;
                } catch (ProcessExit e) {
                    throw e;
                } catch (IndexOutOfBoundsException | com.dylibso.chicory.wasm.ChicoryException e) {
                    if (e instanceof com.dylibso.chicory.runtime.ChicoryInterruptedException) throw e;
                    result = Errno.EFAULT;
                }
                return hasResult ? new long[]{result} : null;
            }));
            return this;
        }

        public HostFunction[] build() {
            return functions.toArray(new HostFunction[0]);
        }
    }

    private static List<ValType> types(String spec) {
        var out = new ArrayList<ValType>();
        for (var c : spec.toCharArray()) {
            out.add(switch (c) {
                case 'i' -> ValType.I32;
                case 'I' -> ValType.I64;
                case 'f' -> ValType.F32;
                case 'F' -> ValType.F64;
                default -> throw new IllegalArgumentException("Bad signature char " + c);
            });
        }
        return out;
    }

    public static int i32(long[] args, int index) {
        return (int) args[index];
    }

    /** Read a UTF-8 string of known length. */
    public static String string(Memory memory, long ptr, long len) throws FsException {
        if (len < 0 || len > 1 << 20) throw new FsException(Errno.EINVAL);
        return new String(memory.readBytes((int) ptr, (int) len), StandardCharsets.UTF_8);
    }

    public static byte[] bytes(Memory memory, long ptr, long len) throws FsException {
        if (len < 0 || len > 64 << 20) throw new FsException(Errno.EINVAL);
        return memory.readBytes((int) ptr, (int) len);
    }

    /** Split a buffer of NUL-terminated strings. */
    public static List<String> strings(Memory memory, long ptr, long len) throws FsException {
        var out = new ArrayList<String>();
        if (ptr == 0 || len <= 0) return out;
        var data = bytes(memory, ptr, len);
        var start = 0;
        for (var i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                out.add(new String(data, start, i - start, StandardCharsets.UTF_8));
                start = i + 1;
            }
        }
        if (start < data.length) out.add(new String(data, start, data.length - start, StandardCharsets.UTF_8));
        return out;
    }
}
