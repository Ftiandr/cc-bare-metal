package dev.ccbaremetal.jittool;

import com.dylibso.chicory.compiler.Cache;
import com.dylibso.chicory.compiler.InterpreterFallback;
import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.wasm.Parser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Build-time tool for big WebAssembly programs (the clang toolchain):
 *
 * <ol>
 *   <li>translates the module to JVM bytecode with Chicory (minutes and gigabytes of heap - not something a game
 *       server should do), with the same options as the runtime's ModuleCache;</li>
 *   <li>writes a slimmed copy of the module: functions that now exist as bytecode get a one-instruction body and
 *       custom sections (names, debug info) are dropped. Chicory keeps every instruction of a parsed module as an
 *       object, which for clang is about 2 GB of heap; the slim module is what the game loads;</li>
 *   <li>stores the bytecode as a Chicory cache entry under the slim module's digest, bundled in the mod jar
 *       (ccbaremetal/jit/), so the runtime finds it without compiling anything.</li>
 * </ol>
 *
 * <p>Usage: Precompile JIT_DIR FULL.wasm SLIM_OUT.wasm [FULL.wasm SLIM_OUT.wasm]...
 */
public final class Precompile {
    private static final Pattern INTERPRETED = Pattern.compile("(?i)interpret\\D*(\\d+)");

    private Precompile() {
    }

    static String fileName(String key) {
        return key.replaceAll("[^A-Za-z0-9._-]", "_") + ".bin";
    }

    public static void main(String[] args) throws IOException {
        var jitDir = Path.of(args[0]);
        Files.createDirectories(jitDir);
        try (var old = Files.list(jitDir)) {
            for (var f : old.toList()) Files.delete(f);
        }
        for (var i = 1; i + 1 < args.length; i += 2) {
            precompile(Path.of(args[i]), Path.of(args[i + 1]), jitDir);
        }
    }

    private static void precompile(Path fullPath, Path slimPath, Path jitDir) throws IOException {
        var start = System.currentTimeMillis();
        var full = Files.readAllBytes(fullPath);
        var module = Parser.parse(full);
        var imported = module.importSection().count(com.dylibso.chicory.wasm.types.ExternalType.FUNCTION);

        // Compile, capturing the cache entry and the functions Chicory leaves to its interpreter (it reports them
        // on stderr in WARN mode).
        var bytecode = new byte[1][];
        Cache capture = new Cache() {
            @Override
            public byte[] get(String key) {
                return null;
            }

            @Override
            public void putIfAbsent(String key, byte[] data) {
                bytecode[0] = data;
            }
        };
        var log = new ByteArrayOutputStream();
        var stderr = System.err;
        System.setErr(new PrintStream(new TeeStream(log, stderr), true, StandardCharsets.UTF_8));
        try {
            MachineFactoryCompiler.builder(module).withInterpreterFallback(InterpreterFallback.WARN).withCache(capture).compile();
        } finally {
            System.setErr(stderr);
        }
        Set<Integer> interpreted = new HashSet<>();
        var matcher = INTERPRETED.matcher(log.toString(StandardCharsets.UTF_8));
        while (matcher.find()) interpreted.add(Integer.parseInt(matcher.group(1)));
        module = null; // let the 2 GB go before parsing the slim module

        var slim = slim(full, imported, interpreted);
        Files.createDirectories(slimPath.getParent());
        Files.write(slimPath, slim);
        var digest = Parser.parse(slim).digest();
        Files.write(jitDir.resolve(fileName(digest)), bytecode[0]);
        System.out.printf("%s: %d s, %d interpreted functions, %d -> %d KiB%n", fullPath.getFileName(),
            (System.currentTimeMillis() - start) / 1000, interpreted.size(), full.length / 1024, slim.length / 1024);
    }

    /** Copy of the module with stub bodies for compiled functions and without custom sections. */
    static byte[] slim(byte[] wasm, int importedFunctions, Set<Integer> keep) {
        var out = new ByteArrayOutputStream(wasm.length / 2);
        out.write(wasm, 0, 8);
        var p = new int[]{8};
        while (p[0] < wasm.length) {
            int id = wasm[p[0]++] & 0xFF;
            int size = (int) leb(wasm, p);
            int bodyStart = p[0];
            p[0] += size;
            if (id == 0) continue; // custom section
            if (id != 10) {
                out.write(id);
                writeLeb(out, size);
                out.write(wasm, bodyStart, size);
                continue;
            }
            var section = new ByteArrayOutputStream(size / 4);
            var q = new int[]{bodyStart};
            long count = leb(wasm, q);
            writeLeb(section, count);
            for (var i = 0; i < count; i++) {
                int bodySize = (int) leb(wasm, q);
                if (keep.contains(importedFunctions + i)) {
                    writeLeb(section, bodySize);
                    section.write(wasm, q[0], bodySize);
                } else {
                    section.write(new byte[]{3, 0, 0x00, 0x0B}, 0, 4); // no locals; unreachable; end
                }
                q[0] += bodySize;
            }
            out.write(id);
            writeLeb(out, section.size());
            out.write(section.toByteArray(), 0, section.size());
        }
        return out.toByteArray();
    }

    private static long leb(byte[] b, int[] p) {
        long r = 0;
        int shift = 0;
        while (true) {
            int x = b[p[0]++] & 0xFF;
            r |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return r;
            shift += 7;
        }
    }

    private static void writeLeb(OutputStream out, long v) {
        try {
            do {
                int b = (int) (v & 0x7F);
                v >>>= 7;
                out.write(v != 0 ? b | 0x80 : b);
            } while (v != 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class TeeStream extends OutputStream {
        private final OutputStream a;
        private final OutputStream b;

        TeeStream(OutputStream a, OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public void write(int c) throws IOException {
            a.write(c);
            b.write(c);
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            a.write(buf, off, len);
            b.write(buf, off, len);
        }
    }
}
