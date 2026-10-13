package dev.ccbaremetal.kernel.io;

import dev.ccbaremetal.kernel.fs.FileType;
import dev.ccbaremetal.kernel.fs.Stat;

import java.nio.ByteBuffer;
import java.security.SecureRandom;

/** Simple character devices: /dev/null, /dev/zero, /dev/random. */
public final class DeviceFile extends OpenFile {
    public enum Kind { NULL, ZERO, RANDOM }

    private static final SecureRandom RANDOM = new SecureRandom();
    private final Kind kind;
    private final String path;

    public DeviceFile(Kind kind, String path) {
        this.kind = kind;
        this.path = path;
    }

    @Override
    public FileType type() {
        return FileType.CHARACTER_DEVICE;
    }

    @Override
    public Stat stat() {
        return Stat.device();
    }

    @Override
    public int read(ByteBuffer dst, boolean nonBlocking) {
        var n = dst.remaining();
        switch (kind) {
            case NULL -> {
                return 0;
            }
            case ZERO -> {
                for (var i = 0; i < n; i++) dst.put((byte) 0);
            }
            case RANDOM -> {
                var bytes = new byte[n];
                RANDOM.nextBytes(bytes);
                dst.put(bytes);
            }
        }
        return n;
    }

    @Override
    public int write(ByteBuffer src, boolean nonBlocking) {
        var n = src.remaining();
        src.position(src.limit());
        return n;
    }

    @Override
    public String path() {
        return path;
    }
}
