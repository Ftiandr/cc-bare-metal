package dev.ccbaremetal.kernel.dev.drivers;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.dev.Device;
import dev.ccbaremetal.kernel.dev.DeviceTree;
import dev.ccbaremetal.kernel.dev.Driver;
import dev.ccbaremetal.kernel.fs.FileType;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.Stat;
import dev.ccbaremetal.kernel.io.OpenFile;
import dev.ccbaremetal.kernel.periph.PeripheralException;
import dev.ccbaremetal.kernel.proc.Process;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;

/** Speakers: /dev/dspN (class sound), signed 8-bit PCM at 48 kHz mono, as CC's playAudio takes it. */
public final class SpeakerDriver implements Driver {
    private final Machine machine;

    public SpeakerDriver(Machine machine) {
        this.machine = machine;
    }

    @Override
    public String name() {
        return "speaker";
    }

    @Override
    public boolean matches(Peripheral p) {
        return p.is("speaker");
    }

    @Override
    public void probe(DeviceTree tree, Peripheral p) {
        tree.register(new Dsp(machine, p.name()));
    }

    private static final class Dsp extends Device {
        private final Machine machine;

        Dsp(Machine machine, String peripheral) {
            super("sound", "dsp", peripheral);
            this.machine = machine;
            attribute("format", () -> "s8 48000 mono\n");
        }

        @Override
        protected OpenFile open(String guestPath) {
            return new DspFile(machine, peripheral(), guestPath);
        }
    }

    /** Writes block (politely) while the speaker's buffer is full. */
    private static final class DspFile extends OpenFile {
        private static final int CHUNK = 16 * 1024;
        private final Machine machine;
        private final String name;
        private final String guest;

        DspFile(Machine machine, String name, String guest) {
            this.machine = machine;
            this.name = name;
            this.guest = guest;
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
        public int write(ByteBuffer src, boolean nonBlocking) throws FsException, InterruptedException {
            checkRevoked();
            var p = Process.CURRENT.get();
            if (p == null) throw new FsException(Errno.EIO);
            var total = 0;
            while (src.hasRemaining()) {
                var n = Math.min(CHUNK, src.remaining());
                var samples = new LinkedHashMap<Object, Object>(n * 2);
                for (var i = 0; i < n; i++) samples.put((double) (i + 1), (double) src.get(src.position() + i));
                try {
                    while (true) {
                        var r = machine.peripherals().call(p, name, "playAudio", new Object[]{samples});
                        if (r.length > 0 && Boolean.TRUE.equals(r[0])) break;
                        if (nonBlocking) {
                            if (total > 0) return total;
                            throw new FsException(Errno.EAGAIN);
                        }
                        Thread.sleep(50); // buffer full: about 2.7 s of audio queued
                        p.checkKilled();
                        checkRevoked();
                    }
                } catch (PeripheralException e) {
                    p.stderr(name + ": " + e.getMessage() + "\n");
                    throw new FsException(Errno.EIO);
                }
                src.position(src.position() + n);
                total += n;
            }
            return total;
        }

        @Override
        public String path() {
            return guest;
        }
    }
}
