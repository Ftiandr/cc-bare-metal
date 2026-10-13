package dev.ccbaremetal.kernel.io;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.fs.FileType;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.Stat;

import java.nio.ByteBuffer;

/** An anonymous pipe: a bounded ring buffer with separately reference-counted read and write ends. */
public final class Pipe {
    private static final int CAPACITY = 64 * 1024;

    private final byte[] buffer = new byte[CAPACITY];
    private final Notifier notifier;
    private int head;
    private int size;
    private boolean readerOpen = true;
    private boolean writerOpen = true;

    public final OpenFile readEnd = new End(false);
    public final OpenFile writeEnd = new End(true);

    public Pipe(Notifier notifier) {
        this.notifier = notifier;
    }

    private synchronized int read(ByteBuffer dst, boolean nonBlocking) throws FsException, InterruptedException {
        while (size == 0) {
            if (!writerOpen) return 0;
            if (nonBlocking) throw new FsException(Errno.EAGAIN);
            wait();
        }
        var n = Math.min(dst.remaining(), size);
        for (var i = 0; i < n; i++) {
            dst.put(buffer[head]);
            head = (head + 1) % CAPACITY;
        }
        size -= n;
        notifyAll();
        notifier.signal();
        return n;
    }

    private synchronized int write(ByteBuffer src, boolean nonBlocking) throws FsException, InterruptedException {
        var total = 0;
        while (src.hasRemaining()) {
            if (!readerOpen) {
                if (total > 0) return total;
                throw new FsException(Errno.EPIPE);
            }
            if (size == CAPACITY) {
                if (nonBlocking) {
                    if (total > 0) return total;
                    throw new FsException(Errno.EAGAIN);
                }
                wait();
                continue;
            }
            var n = Math.min(src.remaining(), CAPACITY - size);
            for (var i = 0; i < n; i++) buffer[(head + size + i) % CAPACITY] = src.get();
            size += n;
            total += n;
            notifyAll();
            notifier.signal();
        }
        return total;
    }

    private synchronized void close(boolean writer) {
        if (writer) writerOpen = false;
        else readerOpen = false;
        notifyAll();
        notifier.signal();
    }

    private final class End extends OpenFile {
        private final boolean writer;

        End(boolean writer) {
            this.writer = writer;
        }

        @Override
        public FileType type() {
            // WASI has no FIFO type; libc treats unknown as a stream which is what we want.
            return FileType.UNKNOWN;
        }

        @Override
        public Stat stat() {
            return new Stat(FileType.UNKNOWN, 0, 0, 0, 0);
        }

        @Override
        public int read(ByteBuffer dst, boolean nonBlocking) throws FsException, InterruptedException {
            if (writer) throw new FsException(Errno.EBADF);
            return Pipe.this.read(dst, nonBlocking);
        }

        @Override
        public int write(ByteBuffer src, boolean nonBlocking) throws FsException, InterruptedException {
            if (!writer) throw new FsException(Errno.EBADF);
            return Pipe.this.write(src, nonBlocking);
        }

        @Override
        public boolean readReady() {
            synchronized (Pipe.this) {
                return size > 0 || !writerOpen;
            }
        }

        @Override
        public boolean writeReady() {
            synchronized (Pipe.this) {
                return size < CAPACITY || !readerOpen;
            }
        }

        @Override
        protected void onClose() {
            Pipe.this.close(writer);
        }
    }
}
