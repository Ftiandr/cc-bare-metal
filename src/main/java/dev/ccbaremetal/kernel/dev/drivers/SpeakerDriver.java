package dev.ccbaremetal.kernel.dev.drivers;

import com.dylibso.chicory.runtime.Memory;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Speakers: /dev/dspN (class sound), signed 8-bit PCM at 48 kHz, mono, as CC's playAudio takes it.
 *
 * <p>Every open file is a stream with its own queue (up to a second; writes wait for room). One shared kernel thread
 * mixes the streams of each speaker and feeds it. A CC speaker's players keep about half a second buffered and never
 * drop lag once that buffer runs dry, so the mixer keeps it as full as CC allows (one chunk waiting) and, before
 * feeding a speaker that ran dry, stops it, which makes the players start afresh instead of lagging. Closing waits
 * until the stream's audio is handed over (as OSS does); a killed process's audio is dropped at once.
 *
 * <p>OSS ioctls: SETFMT (signed 8-bit, the speaker's own, unsigned 8-bit or signed 16-bit little-endian), CHANNELS /
 * STEREO (1 or 2, mixed down), SPEED (4000-96000 Hz, resampled to 48 kHz), GETODELAY (bytes written but not heard),
 * GETOSPACE, RESET, SYNC. Without them a stream is what the speaker takes: s8, mono, 48000.
 */
public final class SpeakerDriver implements Driver {
    private static final Logger LOG = LoggerFactory.getLogger(SpeakerDriver.class);

    static final int RATE = 48000;
    static final int QUEUE = RATE;            // a stream's queue: one second
    static final int CHUNK = RATE / 4;        // at most this much per playAudio
    static final long AHEAD = (RATE / 2 + CHUNK) * 1_000_000_000L / RATE; // keep the players this far ahead (ns)
    static final long SLACK = 50_000_000L;    // a speaker this far past its audio has run dry (ns)

    private static final ScheduledExecutorService MIXER = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "ccbaremetal-audio");
        t.setDaemon(true);
        return t;
    });
    private static final Set<Dsp> ACTIVE = ConcurrentHashMap.newKeySet();

    static {
        MIXER.scheduleAtFixedRate(() -> {
            for (var dsp : ACTIVE) dsp.pump();
        }, 20, 20, TimeUnit.MILLISECONDS);
    }

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

    // ---- the device: its streams and the mixer -----------------------------------------------------------------

    static final class Dsp extends Device {
        private final Machine machine;
        private final List<DspFile> streams = new ArrayList<>();
        private long queuedUntil;   // System.nanoTime() when the players run out of what they were given
        private boolean streaming;  // fed since it was last stopped
        private byte[] waiting;     // mixed but not taken yet (the speaker still held its previous chunk)
        private int owedEvents;     // speaker_audio_empty events CC still owes for chunks the driver handed over

        Dsp(Machine machine, String peripheral) {
            super("sound", "dsp", peripheral);
            this.machine = machine;
            attribute("format", () -> "s8 48000 mono\n");
            attribute("streams", () -> {
                synchronized (this) {
                    return streams.size() + "\n";
                }
            });
        }

        @Override
        protected OpenFile open(String guestPath) {
            var f = new DspFile(this, guestPath);
            synchronized (this) {
                streams.add(f);
            }
            return f;
        }

        @Override
        protected void removed() {
            ACTIVE.remove(this);
        }

        /**
         * speaker_audio_empty for this speaker: CC sends one for every chunk it takes. Those for the driver's chunks are
         * its own and not delivered to programs (the next chunk goes out at once); a program calling playAudio itself
         * gets its events as usual.
         */
        @Override
        protected boolean onEvent(dev.ccbaremetal.kernel.event.Event event) {
            var args = event.args();
            if (!event.name().equals("speaker_audio_empty") || args.length < 1 || !peripheral().equals(args[0])) return false;
            synchronized (this) {
                if (owedEvents == 0) return false;
                owedEvents--;
            }
            wake();
            return true;
        }

        synchronized void remove(DspFile f) {
            streams.remove(f);
            notifyAll();
        }

        /** Seconds of audio the players still have. */
        synchronized double buffered() {
            long left = queuedUntil - System.nanoTime();
            return left > 0 ? left / 1e9 : 0;
        }

        void wake() {
            ACTIVE.add(this);
            MIXER.execute(this::pump);
        }

        private Object[] call(String method, Object... args) throws PeripheralException {
            return machine.peripherals().callKernel(peripheral(), method, args);
        }

        /** Drops what the players hold (and their lag). */
        synchronized void stopSpeaker() {
            stopPlayers();
            queuedUntil = 0;
            streaming = false;
            waiting = null;
        }

        /** Mixes and hands over the next chunk when the players are running low. Runs on the mixer thread. */
        synchronized void pump() {
            try {
                if (waiting != null && !offer(waiting)) return;
                waiting = null;
                long now = System.nanoTime();
                if (queuedUntil - now > AHEAD - (long) CHUNK * 1_000_000_000L / RATE) return;
                int n = 0;
                for (var s : streams) n = Math.max(n, s.available());
                if (n == 0) {
                    if (streams.isEmpty() && queuedUntil + SLACK < now) {
                        // all played out: the speaker is free again (for programs that use it directly, too)
                        streaming = false;
                        ACTIVE.remove(this);
                    }
                    return;
                }
                n = Math.min(n, CHUNK);
                var mix = new int[n];
                for (var s : streams) s.mixInto(mix);
                var chunk = new byte[n];
                for (var i = 0; i < n; i++) chunk[i] = (byte) Math.max(-128, Math.min(127, mix[i]));
                notifyAll(); // room in the queues
                if (streaming && queuedUntil + SLACK < now) {
                    // ran dry while playing: start the players afresh, or they would stay behind from now on
                    stopPlayers();
                    streaming = false;
                    queuedUntil = 0;
                }
                if (!offer(chunk)) waiting = chunk;
            } catch (PeripheralException e) {
                LOG.debug("Speaker {}: {}", peripheral(), e.getMessage());
                for (var s : List.copyOf(streams)) s.revoke(Errno.ENODEV);
                ACTIVE.remove(this);
            } catch (RuntimeException e) {
                LOG.warn("Speaker {} mixer", peripheral(), e);
            }
        }

        /** The speaker's stop(), which makes its players drop what they hold; only when someone can hear it. */
        private void stopPlayers() {
            if (!machine.host().canStopSpeakers()) return;
            try {
                call("stop");
            } catch (PeripheralException ignored) {
            }
            owedEvents = 0; // a chunk CC had not taken yet is dropped without an event
        }

        private boolean offer(byte[] chunk) throws PeripheralException {
            var samples = new LinkedHashMap<Object, Object>(chunk.length * 2);
            for (var i = 0; i < chunk.length; i++) samples.put((double) (i + 1), (double) chunk[i]);
            var r = call("playAudio", samples);
            if (r.length == 0 || !Boolean.TRUE.equals(r[0])) return false;
            long now = System.nanoTime();
            queuedUntil = Math.max(queuedUntil, now) + (long) chunk.length * 1_000_000_000L / RATE;
            streaming = true;
            owedEvents++;
            return true;
        }

        /** Waits (on this monitor) until the condition holds; false if the caller was killed or the file revoked. */
        void await(DspFile f, java.util.function.BooleanSupplier done) throws InterruptedException {
            synchronized (this) {
                while (!done.getAsBoolean() && !f.revoked()) {
                    var p = Process.CURRENT.get();
                    if (p != null && p.isKilled()) return;
                    wait(50);
                }
            }
        }
    }

    // ---- one open file: a stream --------------------------------------------------------------------------------

    static final class DspFile extends OpenFile {
        // OSS ioctl requests (Linux values)
        static final int SNDCTL_DSP_RESET = 0x5000, SNDCTL_DSP_SYNC = 0x5001, SNDCTL_DSP_SPEED = 0xc0045002,
            SNDCTL_DSP_STEREO = 0xc0045003, SNDCTL_DSP_GETBLKSIZE = 0xc0045004, SNDCTL_DSP_SETFMT = 0xc0045005,
            SNDCTL_DSP_CHANNELS = 0xc0045006, SNDCTL_DSP_SETFRAGMENT = 0xc004500a, SNDCTL_DSP_GETFMTS = 0x8004500b,
            SNDCTL_DSP_GETOSPACE = 0x8010500c, SNDCTL_DSP_NONBLOCK = 0x500e, SNDCTL_DSP_GETCAPS = 0x8004500f,
            SNDCTL_DSP_GETODELAY = 0x80045017, SNDCTL_DSP_POST = 0x5008;
        static final int AFMT_U8 = 0x08, AFMT_S16_LE = 0x10, AFMT_S8 = 0x40;

        private final Dsp dsp;
        private final String guest;
        private final byte[] ring = new byte[QUEUE];
        private int head, size; // guarded by dsp
        // the stream's format (OSS), converted to the speaker's as it is written
        private int format = AFMT_S8, channels = 1, rate = RATE;
        private final byte[] partial = new byte[4]; // the start of a frame split between writes
        private int partialLength;
        private double phase;   // resampling: position between prev and the next input sample
        private float prev;

        private int frameBytes() {
            return (format == AFMT_S16_LE ? 2 : 1) * channels;
        }

        /** One frame of input as a sample in [-1, 1] (channels mixed down). */
        private float decode(byte[] b, int off) {
            float sum = 0;
            for (var c = 0; c < channels; c++) {
                sum += switch (format) {
                    case AFMT_U8 -> ((b[off + c] & 0xFF) - 128) / 128f;
                    case AFMT_S16_LE -> (short) ((b[off + c * 2] & 0xFF) | b[off + c * 2 + 1] << 8) / 32768f;
                    default -> b[off + c] / 128f;
                };
            }
            return sum / channels;
        }

        /** Resamples one input sample into the queue (holding the device's monitor; room for 16 samples). */
        private void push(float x) {
            double step = (double) rate / RATE;
            while (phase < 1) {
                float v = prev + (x - prev) * (float) phase;
                ring[(head + size) % QUEUE] = (byte) Math.max(-128, Math.min(127, Math.round(v * 127)));
                size++;
                phase += step;
            }
            phase -= 1;
            prev = x;
        }

        DspFile(Dsp dsp, String guest) {
            this.dsp = dsp;
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

        int available() {
            return size;
        }

        /** Adds this stream's next samples into mix (called by the mixer, holding the device's monitor). */
        void mixInto(int[] mix) {
            int n = Math.min(size, mix.length);
            for (var i = 0; i < n; i++) mix[i] += ring[(head + i) % QUEUE];
            head = (head + n) % QUEUE;
            size -= n;
        }

        @Override
        public int write(ByteBuffer src, boolean nonBlocking) throws FsException, InterruptedException {
            checkRevoked();
            boolean converted;
            synchronized (dsp) {
                converted = format != AFMT_S8 || channels != 1 || rate != RATE;
            }
            if (converted) return writeConverted(src, nonBlocking);
            int total = 0;
            while (src.hasRemaining()) {
                synchronized (dsp) {
                    while (size == QUEUE) {
                        if (nonBlocking || total > 0) {
                            if (total > 0) return total;
                            throw new FsException(Errno.EAGAIN);
                        }
                        var p = Process.CURRENT.get();
                        if (p != null) p.checkKilled();
                        checkRevoked();
                        dsp.wait(50);
                    }
                    int n = Math.min(src.remaining(), QUEUE - size);
                    for (var i = 0; i < n; i++) ring[(head + size + i) % QUEUE] = src.get();
                    size += n;
                    total += n;
                }
                dsp.wake();
            }
            return total;
        }

        /** write() for a stream in another format: frame by frame, converted and resampled into the queue. */
        private int writeConverted(ByteBuffer src, boolean nonBlocking) throws FsException, InterruptedException {
            int total = 0;
            var frame = new byte[4];
            while (src.hasRemaining()) {
                synchronized (dsp) {
                    while (QUEUE - size < 16) {
                        if (nonBlocking || total > 0) {
                            if (total > 0) return total;
                            throw new FsException(Errno.EAGAIN);
                        }
                        var p = Process.CURRENT.get();
                        if (p != null) p.checkKilled();
                        checkRevoked();
                        dsp.wait(50);
                    }
                    int fb = frameBytes();
                    while (src.hasRemaining() && QUEUE - size >= 16) {
                        partial[partialLength++] = src.get();
                        total++;
                        if (partialLength == fb) {
                            System.arraycopy(partial, 0, frame, 0, fb);
                            partialLength = 0;
                            push(decode(frame, 0));
                        }
                    }
                }
                dsp.wake();
            }
            return total;
        }

        @Override
        public boolean writeReady() {
            synchronized (dsp) {
                return size < QUEUE || revoked();
            }
        }

        @Override
        public int ioctl(int request, Memory memory, int arg) throws FsException, InterruptedException {
            switch (request) {
                case SNDCTL_DSP_RESET -> reset();
                case SNDCTL_DSP_SYNC -> dsp.await(this, () -> size == 0);
                case SNDCTL_DSP_POST, SNDCTL_DSP_NONBLOCK, SNDCTL_DSP_SETFRAGMENT -> {
                }
                case SNDCTL_DSP_SPEED -> {
                    synchronized (dsp) {
                        rate = Math.max(4000, Math.min(96000, memory.readInt(arg)));
                        memory.writeI32(arg, rate);
                    }
                }
                case SNDCTL_DSP_STEREO -> {
                    synchronized (dsp) {
                        channels = memory.readInt(arg) != 0 ? 2 : 1;
                        partialLength = 0;
                        memory.writeI32(arg, channels - 1);
                    }
                }
                case SNDCTL_DSP_CHANNELS -> {
                    synchronized (dsp) {
                        channels = memory.readInt(arg) >= 2 ? 2 : 1;
                        partialLength = 0;
                        memory.writeI32(arg, channels);
                    }
                }
                case SNDCTL_DSP_SETFMT -> {
                    synchronized (dsp) {
                        var want = memory.readInt(arg);
                        if (want == AFMT_S8 || want == AFMT_U8 || want == AFMT_S16_LE) format = want;
                        partialLength = 0;
                        memory.writeI32(arg, format);
                    }
                }
                case SNDCTL_DSP_GETFMTS -> memory.writeI32(arg, AFMT_S8 | AFMT_U8 | AFMT_S16_LE);
                case SNDCTL_DSP_GETBLKSIZE -> memory.writeI32(arg, 4096);
                case SNDCTL_DSP_GETCAPS -> memory.writeI32(arg, 0x200); // DSP_CAP_TRIGGER-free, realtime enough
                case SNDCTL_DSP_GETODELAY -> {
                    double samples;
                    int bytesPerSecond;
                    synchronized (dsp) {
                        samples = size + dsp.buffered() * RATE;
                        bytesPerSecond = rate * frameBytes();
                    }
                    memory.writeI32(arg, (int) (samples / RATE * bytesPerSecond));
                }
                case SNDCTL_DSP_GETOSPACE -> {
                    int free;
                    synchronized (dsp) {
                        free = (int) ((long) (QUEUE - size) * rate / RATE) * frameBytes();
                    }
                    // audio_buf_info: fragments, fragstotal, fragsize, bytes
                    memory.writeI32(arg, free / 4096);
                    memory.writeI32(arg + 4, QUEUE / 4096);
                    memory.writeI32(arg + 8, 4096);
                    memory.writeI32(arg + 12, free);
                }
                default -> throw new FsException(Errno.ENOTTY);
            }
            return 0;
        }

        /** Drops this stream's queued audio; if it is the only one, what the players hold too. */
        private void reset() {
            boolean alone;
            synchronized (dsp) {
                head = size = 0;
                partialLength = 0;
                alone = dsp.streams.size() == 1;
                dsp.notifyAll();
            }
            if (alone) dsp.stopSpeaker();
        }

        @Override
        protected void onClose() {
            var p = Process.CURRENT.get();
            boolean killed = p != null && p.isKilled();
            if (!killed && !revoked()) {
                // as OSS: closing waits until the audio is handed over
                try {
                    dsp.await(this, () -> size == 0);
                } catch (InterruptedException e) {
                    killed = true;
                }
                if (p != null && p.isKilled()) killed = true;
            }
            if (killed) reset();
            dsp.remove(this);
        }

        @Override
        public String path() {
            return guest;
        }
    }
}
