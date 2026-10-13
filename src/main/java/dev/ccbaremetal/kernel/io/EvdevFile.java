package dev.ccbaremetal.kernel.io;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.fs.FileType;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.Stat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * An open Linux input device (/dev/input/...): a queue of records the kernel pushes; reads block until there is
 * something and return whole records only (24-byte evdev {@code struct input_event}, or 3-byte PS/2 packets).
 * Nothing is queued for a device nobody has open.
 */
public class EvdevFile extends OpenFile {
    // Linux input-event-codes.h
    public static final int EV_SYN = 0, EV_KEY = 1, EV_REL = 2, EV_ABS = 3;
    public static final int SYN_REPORT = 0, REL_X = 0, REL_Y = 1, REL_WHEEL = 8, ABS_X = 0, ABS_Y = 1;
    public static final int BTN_LEFT = 0x110, BTN_RIGHT = 0x111, BTN_MIDDLE = 0x112, BTN_TOUCH = 0x14a;
    public static final int RECORD = 24;
    private static final int QUEUE_LIMIT = 64 * 1024;

    private final String path;
    private final int unit;
    private final Runnable onClose;
    private final ByteArrayOutputStream queue = new ByteArrayOutputStream();

    public EvdevFile(String path, int unit, Runnable onClose) {
        this.path = path;
        this.unit = unit;
        this.onClose = onClose;
    }

    /** Append one evdev record (time now). */
    public static void record(ByteBuffer out, int type, int code, int value) {
        var micros = System.currentTimeMillis() * 1000;
        out.putLong(micros / 1_000_000);
        out.putLong(micros % 1_000_000);
        out.putShort((short) type);
        out.putShort((short) code);
        out.putInt(value);
    }

    public synchronized void push(byte[] data) {
        // A reader that stopped reading: drop old events instead of growing without limit.
        if (queue.size() + data.length > QUEUE_LIMIT) queue.reset();
        queue.write(data, 0, data.length);
        notifyAll();
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
    public synchronized int read(ByteBuffer dst, boolean nonBlocking) throws FsException, InterruptedException {
        checkRevoked();
        while (queue.size() == 0) {
            if (nonBlocking) throw new FsException(Errno.EAGAIN);
            wait(250);
            checkRevoked();
        }
        var data = queue.toByteArray();
        var n = Math.min(dst.remaining(), data.length) / unit * unit;
        if (n == 0) throw new FsException(Errno.EINVAL);
        dst.put(data, 0, n);
        queue.reset();
        queue.write(data, n, data.length - n);
        return n;
    }

    @Override
    public int write(ByteBuffer src, boolean nonBlocking) {
        // Writes (LED state, PS/2 commands) are accepted and ignored.
        var n = src.remaining();
        src.position(src.limit());
        return n;
    }

    @Override
    public synchronized boolean readReady() {
        return queue.size() > 0 || revoked();
    }

    @Override
    protected void onClose() {
        onClose.run();
    }

    @Override
    public String path() {
        return path;
    }
}
