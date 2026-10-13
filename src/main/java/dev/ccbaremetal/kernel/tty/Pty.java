package dev.ccbaremetal.kernel.tty;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.kernel.fs.FileType;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.Stat;
import dev.ccbaremetal.kernel.io.Notifier;
import dev.ccbaremetal.kernel.io.OpenFile;
import dev.ccbaremetal.kernel.proc.Process;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.function.IntConsumer;

/**
 * A pseudo-terminal pair, as Linux's /dev/ptmx: programs on the slave side see an ordinary terminal (line
 * discipline, foreground job, Ctrl+C, window size); the master side (sshd) reads what they print and writes what the
 * remote user types. The master's owner is told by a "pty" event when output is waiting, so it needs no polling.
 */
public final class Pty {
    private static final int LIMIT = 1 << 20;

    private final Notifier notifier;
    private final Process owner;
    private final int id;
    public final Tty slave;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private boolean slaveClosed;
    private boolean masterClosed;

    /** {@code signal}: delivers Ctrl+C etc. to the slave's foreground job. */
    private final java.util.function.Consumer<Tty> onHangup;

    public Pty(Notifier notifier, Process owner, int id, java.util.function.BiConsumer<Tty, Integer> signal,
               java.util.function.Consumer<Tty> onHangup) {
        this.onHangup = onHangup;
        this.notifier = notifier;
        this.owner = owner;
        this.id = id;
        Tty[] self = new Tty[1];
        IntConsumer sig = s -> signal.accept(self[0], s);
        this.slave = new Tty(notifier, sig, this::fromSlave);
        self[0] = slave;
        slave.onLastClose(this::slaveGone);
    }

    public int id() {
        return id;
    }

    private void fromSlave(byte[] data) {
        boolean wasEmpty;
        synchronized (this) {
            if (masterClosed) return;
            // a master that stopped reading: drop old output rather than grow without limit
            if (output.size() + data.length > LIMIT) output.reset();
            wasEmpty = output.size() == 0;
            output.writeBytes(data);
            notifyAll();
        }
        notifier.signal();
        if (wasEmpty) owner.deliver(Event.of("pty", id));
    }

    private void slaveGone() {
        synchronized (this) {
            slaveClosed = true;
            notifyAll();
        }
        notifier.signal();
        owner.deliver(Event.of("pty", id));
    }

    public OpenFile master() {
        return new Master();
    }

    /** The pseudo-terminal of a master file, or null. */
    public static Pty of(OpenFile file) {
        return file instanceof Master m ? m.pty() : null;
    }

    /** The master side: read the slave's output, write the remote user's input. */
    private final class Master extends OpenFile {
        Pty pty() {
            return Pty.this;
        }

        @Override
        public FileType type() {
            return FileType.CHARACTER_DEVICE;
        }

        @Override
        public Stat stat() {
            return Stat.device().withOwner(0620, owner.cred.euid(), 0);
        }

        @Override
        public int read(ByteBuffer dst, boolean nonBlocking) throws FsException, InterruptedException {
            synchronized (Pty.this) {
                while (output.size() == 0) {
                    // every slave descriptor closed: the session ended (Linux reports EIO here)
                    if (slaveClosed) throw new FsException(Errno.EIO);
                    if (nonBlocking) throw new FsException(Errno.EAGAIN);
                    Pty.this.wait();
                }
                var data = output.toByteArray();
                var n = Math.min(dst.remaining(), data.length);
                dst.put(data, 0, n);
                output.reset();
                output.write(data, n, data.length - n);
                return n;
            }
        }

        @Override
        public int write(ByteBuffer src, boolean nonBlocking) {
            var bytes = new byte[src.remaining()];
            src.get(bytes);
            slave.input(bytes);
            return bytes.length;
        }

        @Override
        public boolean readReady() {
            synchronized (Pty.this) {
                return output.size() > 0 || slaveClosed;
            }
        }

        @Override
        protected void onClose() {
            synchronized (Pty.this) {
                masterClosed = true;
                output.reset();
            }
            slave.hangup();
            onHangup.accept(slave);
        }
    }
}
