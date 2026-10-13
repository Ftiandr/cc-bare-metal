package dev.ccbaremetal.kernel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.zip.ZipInputStream;

/**
 * The C/C++ toolchain (clang, lld, libc, libc++): about 100 MB, so instead of living in the in-memory system image
 * it is shipped as {@code ccbaremetal/toolchain.zip}, unpacked once per installation into a directory named after its
 * hash, and mounted read-only at /usr/lib/llvm and /usr/lib/wasi.
 */
public final class Toolchain {
    private static final Logger LOG = LoggerFactory.getLogger(Toolchain.class);
    private static final String RESOURCE = "/ccbaremetal/toolchain.zip";

    private static volatile Path baseDir;
    private static volatile Path unpacked;
    private static volatile boolean failed;

    private Toolchain() {
    }

    /** Where unpacked toolchains are kept (one subdirectory per version). */
    public static void setDirectory(Path dir) {
        baseDir = dir;
    }

    /** The unpacked toolchain (with llvm/ and wasi/ inside), unpacking it on first use; null if unavailable. */
    public static Path get() {
        var result = unpacked;
        if (result != null || failed) return result;
        synchronized (Toolchain.class) {
            if (unpacked == null && !failed) {
                try {
                    unpacked = unpack();
                } catch (IOException | RuntimeException e) {
                    failed = true;
                    LOG.error("Could not unpack the C/C++ toolchain; cc/c++ will not work", e);
                }
            }
            return unpacked;
        }
    }

    private static Path unpack() throws IOException {
        var base = baseDir;
        if (base == null) return null;
        String hash;
        try (var in = Toolchain.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOG.warn("{} is not in the mod jar; cc/c++ will not work", RESOURCE);
                return null;
            }
            hash = sha256(in);
        }
        var target = base.resolve(hash.substring(0, 16));
        if (Files.exists(target.resolve(".complete"))) return target;

        var start = System.currentTimeMillis();
        Files.createDirectories(base);
        var tmp = Files.createTempDirectory(base, "unpack");
        try (var in = Toolchain.class.getResourceAsStream(RESOURCE); var zip = new ZipInputStream(in)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                var name = entry.getName();
                if (name.contains("..") || name.startsWith("/")) continue;
                var out = tmp.resolve(name).normalize();
                if (!out.startsWith(tmp)) continue;
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.writeString(tmp.resolve(".complete"), hash);
            deleteTree(target);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            deleteTree(tmp);
        }
        // Older versions are no longer needed.
        try (var others = Files.list(base)) {
            for (var other : others.toList()) {
                if (!other.equals(target) && Files.isDirectory(other)) deleteTree(other);
            }
        }
        LOG.info("Unpacked the C/C++ toolchain to {} in {} ms", target, System.currentTimeMillis() - start);
        return target;
    }

    private static String sha256(InputStream in) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var din = new DigestInputStream(in, digest)) {
                din.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
