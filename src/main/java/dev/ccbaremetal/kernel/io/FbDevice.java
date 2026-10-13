package dev.ccbaremetal.kernel.io;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.fs.FileType;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.Stat;
import dev.ccbaremetal.kernel.proc.Process;

import java.nio.ByteBuffer;

/**
 * /dev/fb0: the screen's framebuffer as a file, like Linux fbdev (WASI has no mmap, so it is read and written).
 * One byte per pixel, the palette index; byte offset = y * width + x (the stride is the width). The current mode is
 * in /sys/class/graphics/fb0 (virtual_size, bits_per_pixel, stride), and can be changed there or with fbset.
 *
 * <p>Writing shows the framebuffer on the screen. After the writing program exits the picture stays until a key
 * is pressed (like an image left on a Linux framebuffer console).
 */
public final class FbDevice extends OpenFile {
    private final Machine machine;
    private final java.util.function.Supplier<dev.ccbaremetal.kernel.gfx.Framebuffer> framebuffer;
    private final Runnable changed;
    private final String path;
    private long position;

    /** /dev/fb0: the computer's own screen. */
    public FbDevice(Machine machine) {
        this(machine, machine::framebuffer, null, "/dev/fb0");
    }

    /** Another framebuffer (a pixel monitor: /dev/fb1...); {@code changed} schedules its sync. */
    public FbDevice(Machine machine, java.util.function.Supplier<dev.ccbaremetal.kernel.gfx.Framebuffer> framebuffer, Runnable changed, String path) {
        this.machine = machine;
        this.framebuffer = framebuffer;
        this.changed = changed;
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

    private long size() {
        var fb = framebuffer.get();
        return (long) fb.width() * fb.height();
    }

    @Override
    public synchronized int read(ByteBuffer dst, boolean nonBlocking) throws FsException {
        var n = pread(dst, position);
        position += n;
        return n;
    }

    @Override
    public synchronized int write(ByteBuffer src, boolean nonBlocking) throws FsException {
        var n = pwrite(src, position);
        position += n;
        return n;
    }

    @Override
    public int pread(ByteBuffer dst, long pos) throws FsException {
        checkRevoked();
        var fb = framebuffer.get();
        int w = fb.width(), h = fb.height();
        var total = (long) w * h;
        if (pos >= total) return 0;
        var n = (int) Math.min(dst.remaining(), total - pos);
        var done = 0;
        while (done < n) {
            var p = pos + done;
            int y = (int) (p / w), x = (int) (p % w);
            var run = Math.min(n - done, w - x);
            dst.put(fb.read(x, y, run, 1));
            done += run;
        }
        return n;
    }

    @Override
    public int pwrite(ByteBuffer src, long pos) throws FsException {
        checkRevoked();
        var fb = framebuffer.get();
        int w = fb.width(), h = fb.height();
        var total = (long) w * h;
        if (pos >= total) {
            if (src.hasRemaining()) throw new FsException(Errno.ENOSPC);
            return 0;
        }
        var n = (int) Math.min(src.remaining(), total - pos);
        var bytes = new byte[n];
        src.get(bytes);
        var done = 0;
        while (done < n) {
            var p = pos + done;
            int y = (int) (p / w), x = (int) (p % w);
            var run = Math.min(n - done, w - x);
            fb.blit(bytes, done, run, x, y, run, 1);
            done += run;
        }
        if (changed != null) {
            changed.run();
        } else {
            var writer = Process.CURRENT.get();
            machine.showFramebuffer(writer == null ? 0 : writer.pid);
        }
        return n;
    }

    @Override
    public synchronized long seek(long offset, int whence) throws FsException {
        var base = switch (whence) {
            case SEEK_SET -> 0;
            case SEEK_CUR -> position;
            case SEEK_END -> size();
            default -> throw new FsException(Errno.EINVAL);
        };
        if (base + offset < 0) throw new FsException(Errno.EINVAL);
        return position = base + offset;
    }

    @Override
    public boolean isSeekable() {
        return true;
    }

    @Override
    public String path() {
        return path;
    }
}
