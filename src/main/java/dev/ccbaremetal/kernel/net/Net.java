package dev.ccbaremetal.kernel.net;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.kernel.fs.FileType;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.fs.Stat;
import dev.ccbaremetal.kernel.io.OpenFile;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stream sockets between computers over CC modems ("ccnet"): what TCP is to IP, on a medium without addresses.
 *
 * <p>Addressing: a computer is reached by its hostname. Every computer with a modem listens on one modem channel,
 * its address channel ({@link #addressChannel}: a hash of the name), so a packet only reaches computers whose name
 * hashes alike, and those drop packets for another name. The port travels inside the packet, as in a TCP header;
 * a connection is (local port, remote name, remote port, connection id), the id chosen by the client.
 *
 * <p>Packets are plain tables, so Lua programs on CC computers can speak it too:
 * {@code {ccnet=1, t=syn|synack|data|ack|fin|rst, src, sport, dst, dport, id, hid, seq, ack, win, data}}, sent
 * to the destination's address channel with the sender's address channel as the reply channel. {@code hid} is a
 * random id of the sending computer (per boot): two computers answering for one name are noticed by it. Data is
 * a byte string; sequence numbers count bytes (fin takes one), {@code ack} is cumulative and {@code win} the free
 * receive buffer. Lost packets (out of range, a modem removed for a moment) are sent again (go-back-N); a connection
 * that hears nothing for {@value #GIVE_UP_MS} ms while data is waiting fails with ETIMEDOUT.
 *
 * <p>The stack does not know modems: it sends through the network interfaces of the device tree (class net:
 * {@link NetDevice}; a modem's driver makes wlanN/ethN, {@link Loopback} is lo) and they hand it what arrives.
 *
 * <p>Threading: packets are handled and sent on one shared thread, never inside {@code queueEvent} (a transmit
 * delivers to the other computers synchronously; answering from there would recurse). Socket state is guarded by
 * this object; guest threads block on it.
 */
public final class Net {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "ccbaremetal-net");
        t.setDaemon(true);
        return t;
    });

    static final int MSS = 4096;
    static final int SEND_BUFFER = 256 * 1024;
    static final int RECEIVE_BUFFER = 64 * 1024;
    static final int IN_FLIGHT = 32 * 1024;
    static final long RTO_INITIAL_MS = 1000;
    static final long RTO_MAX_MS = 8000;
    static final long GIVE_UP_MS = 30_000;
    static final long LINGER_MS = 30_000;
    static final int BACKLOG = 16;
    public static final int EPHEMERAL_FIRST = 49152;

    private enum State { CONNECTING, OPEN, CLOSED }

    private final Machine machine;
    private String hid = newHid();
    private volatile String hostname = "";
    private volatile int channel = -1;
    private final Map<Integer, Listener> listeners = new HashMap<>();
    private final Map<String, Conn> conns = new LinkedHashMap<>();
    private final Loopback loopback = new Loopback(this);
    private int ticks;
    private int netTicks;
    private boolean tickQueued;

    public Net(Machine machine) {
        this.machine = machine;
    }

    private static String newHid() {
        return Long.toHexString(RANDOM.nextLong() & Long.MAX_VALUE);
    }

    /** The modem channel a computer called {@code name} listens on: FNV-1a of the lower-case name, 100..60099. */
    public static int addressChannel(String name) {
        var h = 0x811c9dc5;
        for (var b : name.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)) h = (h ^ (b & 0xFF)) * 0x01000193;
        return 100 + (int) (Integer.toUnsignedLong(h) % 60000);
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    public String hostname() {
        return hostname;
    }

    /** lo, registered in the device tree by the machine. */
    public NetDevice loopback() {
        return loopback;
    }

    private List<NetDevice> interfaces() {
        var out = new ArrayList<NetDevice>();
        for (var d : machine.devices().devices("net")) if (d instanceof NetDevice n) out.add(n);
        return out;
    }

    /** Some interface that reaches other computers is up. */
    private boolean online() {
        for (var d : interfaces()) if (d != loopback && d.isUp()) return true;
        return false;
    }

    // ---- Machine hooks ---------------------------------------------------------------------------------------------

    /** Boot: a new computer id for duplicate detection, the name, and the channel on every modem. */
    public void start() {
        synchronized (this) {
            hid = newHid();
        }
        EXEC.execute(this::refresh);
    }

    /** Shutdown: tell the peers (best effort) and drop every socket. */
    public void stop() {
        List<Map<String, Object>> resets = new ArrayList<>();
        synchronized (this) {
            for (var c : conns.values()) {
                if (c.state == State.OPEN) resets.add(c.packet("rst"));
                c.fail(Errno.ECONNRESET);
            }
            conns.clear();
            for (var l : listeners.values()) l.closed = true;
            listeners.clear();
            notifyAll();
        }
        // here, not on the net thread: the modems are detached right after this
        for (var p : resets) transmit(p, null);
        for (var d : interfaces()) d.setListening(-1);
        channel = -1;
        hostname = "";
    }

    /** Called every server tick: timers (retransmission, connect retries) five times a second. */
    public void tick() {
        if (++ticks % 4 != 0) return;
        synchronized (this) {
            if (tickQueued) return;
            tickQueued = true;
        }
        EXEC.execute(this::onTick);
    }

    /** Whether a modem message is a ccnet packet for this computer (an interface then hands it to {@link #receive}). */
    public boolean isForMe(Map<?, ?> msg) {
        if (msg.get("ccnet") == null) return false;
        var dst = str(msg.get("dst"));
        return dst != null && !hostname.isEmpty() && lower(dst).equals(lower(hostname));
    }

    /** A packet for this computer arrived through {@code device}. */
    void receive(NetDevice device, Map<?, ?> msg) {
        var via = device.name();
        EXEC.execute(() -> handle(via, msg));
    }

    /** An interface appeared or went up or down: it listens on the computer's channel (or on nothing). */
    public void interfaceChanged(NetDevice device) {
        EXEC.execute(() -> {
            if (channel >= 0) device.setListening(channel);
        });
    }

    /** The name (as cc::hostname: /etc/hostname, the label, computer-ID) and the channel open on every modem. */
    private void refresh() {
        if (!machine.isOn()) return;
        var name = machine.hostname();
        var newChannel = addressChannel(name);
        var oldChannel = channel;
        hostname = name;
        channel = newChannel;
        // every second: another program may have closed the channel (closeAll is for the whole computer)
        for (var d : interfaces()) d.setListening(newChannel);
    }

    /** Send through {@code via} (the interface the peer was heard on), else through every interface that is up. */
    private void transmit(Map<String, Object> packet, String via) {
        var dst = lower((String) packet.get("dst"));
        var to = addressChannel(dst);
        var reply = channel >= 0 ? channel : addressChannel(hostname);
        if (dst.equals(lower(hostname))) {
            loopback.transmit(to, reply, packet);
            return;
        }
        var all = interfaces();
        all.remove(loopback);
        if (via != null && all.stream().anyMatch(d -> d.name().equals(via))) all.removeIf(d -> !d.name().equals(via));
        for (var d : all) d.transmit(to, reply, packet);
    }

    // ---- The protocol ----------------------------------------------------------------------------------------------

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    private static long num(Object o, long fallback) {
        return o instanceof Number n ? n.longValue() : fallback;
    }

    private static byte[] bytesOf(Object o) {
        if (o instanceof String s) {
            var out = new byte[s.length()];
            for (var i = 0; i < out.length; i++) out[i] = (byte) s.charAt(i);
            return out;
        }
        if (o instanceof byte[] b) return b;
        if (o instanceof ByteBuffer b) {
            var out = new byte[b.remaining()];
            b.duplicate().get(out);
            return out;
        }
        return new byte[0];
    }

    private static String key(int localPort, String remote, int remotePort, long id) {
        return localPort + "/" + lower(remote) + "/" + remotePort + "/" + id;
    }

    private void handle(String side, Map<?, ?> msg) {
        List<Runnable> after = new ArrayList<>();
        synchronized (this) {
            handleLocked(side, msg, after);
        }
        for (var r : after) r.run();
        machine.notifier().signal();
    }

    private void handleLocked(String side, Map<?, ?> msg, List<Runnable> after) {
        var t = str(msg.get("t"));
        var src = str(msg.get("src"));
        var peerHid = str(msg.get("hid"));
        var sport = (int) num(msg.get("sport"), -1);
        var dport = (int) num(msg.get("dport"), -1);
        var id = num(msg.get("id"), -1);
        if (t == null || src == null || sport < 0 || dport < 0) return;
        var conn = conns.get(key(dport, src, sport, id));
        switch (t) {
            case "syn" -> {
                if (conn != null) {
                    if (conn.peerHid != null && conn.peerHid.equals(peerHid)) after.add(() -> transmit(conn.packet("synack"), conn.via));
                    return;
                }
                var l = listeners.get(dport);
                if (l == null || l.backlog.size() >= BACKLOG) {
                    var rst = reply(msg, "rst");
                    after.add(() -> transmit(rst, side));
                    return;
                }
                var c = new Conn(dport, src, sport, id, l.uid);
                c.state = State.OPEN;
                c.peerHid = peerHid;
                c.via = side;
                c.sndEdge = num(msg.get("win"), 0);
                conns.put(c.key(), c);
                l.backlog.add(c);
                notifyAll();
                after.add(() -> transmit(c.packet("synack"), c.via));
            }
            case "rst" -> {
                if (conn == null) return;
                if (conn.state == State.CONNECTING) {
                    conn.fail(Errno.ECONNREFUSED);
                } else if (conn.peerHid != null && peerHid != null && !conn.peerHid.equals(peerHid)) {
                    // another computer with the same name does not know this connection
                    conn.fail(Errno.EADDRINUSE);
                } else {
                    conn.fail(Errno.ECONNRESET);
                }
                conns.remove(conn.key());
                notifyAll();
            }
            default -> {
                if (conn == null || conn.state == State.CLOSED) {
                    var rst = reply(msg, "rst");
                    after.add(() -> transmit(rst, side));
                    return;
                }
                if (t.equals("synack")) {
                    if (conn.state == State.CONNECTING) {
                        conn.state = State.OPEN;
                        conn.peerHid = peerHid;
                        conn.via = side;
                        conn.sndEdge = num(msg.get("win"), 0);
                        notifyAll();
                        return;
                    }
                }
                if (conn.peerHid != null && peerHid != null && !conn.peerHid.equals(peerHid)) {
                    // two computers answer for this name: neither can be trusted to be the one meant
                    conn.fail(Errno.EADDRINUSE);
                    conns.remove(conn.key());
                    var rst = conn.packet("rst");
                    after.add(() -> transmit(rst, null));
                    notifyAll();
                    return;
                }
                if (t.equals("synack")) return;
                conn.onAck(num(msg.get("ack"), -1), num(msg.get("win"), 0));
                var sendAck = false;
                if (t.equals("data")) {
                    if (conn.localClosed) {
                        // nobody will read it any more
                        conn.fail(Errno.ECONNRESET);
                        conns.remove(conn.key());
                        var rst = conn.packet("rst");
                        after.add(() -> transmit(rst, conn.via));
                        return;
                    }
                    conn.onData(num(msg.get("seq"), -1), bytesOf(msg.get("data")));
                    sendAck = true;
                } else if (t.equals("fin")) {
                    conn.onFin(num(msg.get("seq"), -1));
                    sendAck = true;
                }
                if (sendAck) {
                    var ack = conn.packet("ack");
                    after.add(() -> transmit(ack, conn.via));
                }
                pumpLocked(conn, after);
                if (conn.finished()) conns.remove(conn.key());
                notifyAll();
            }
        }
    }

    /** An answer to a packet without a connection (rst): addressed back to its sender. */
    private Map<String, Object> reply(Map<?, ?> msg, String type) {
        var p = new LinkedHashMap<String, Object>();
        p.put("ccnet", 1);
        p.put("t", type);
        p.put("src", hostname);
        p.put("sport", num(msg.get("dport"), 0));
        p.put("dst", str(msg.get("src")));
        p.put("dport", num(msg.get("sport"), 0));
        p.put("id", num(msg.get("id"), 0));
        p.put("hid", hid);
        return p;
    }

    /** Send what the window allows (and the fin after the data). */
    private void pumpLocked(Conn c, List<Runnable> after) {
        if (c.state != State.OPEN) return;
        var now = System.currentTimeMillis();
        var dataEnd = c.sndUna + c.send.size();
        while (c.sndNxt < dataEnd && c.sndNxt < c.sndEdge && c.sndNxt - c.sndUna < IN_FLIGHT) {
            var n = (int) Math.min(Math.min(MSS, dataEnd - c.sndNxt), Math.min(c.sndEdge - c.sndNxt, IN_FLIGHT - (c.sndNxt - c.sndUna)));
            if (c.sndNxt == c.sndUna) c.lastProgress = now;
            var p = c.packet("data");
            p.put("seq", c.sndNxt);
            var bytes = c.send.peek((int) (c.sndNxt - c.sndUna), n);
            p.put("data", new String(bytes, StandardCharsets.ISO_8859_1));
            c.sndNxt += n;
            after.add(() -> transmit(p, c.via));
        }
        if (c.finQueued && !c.finSent && c.sndNxt == dataEnd) {
            if (c.sndNxt == c.sndUna) c.lastProgress = now;
            c.finSeq = dataEnd;
            c.finSent = true;
            c.sndNxt = dataEnd + 1;
            var p = c.packet("fin");
            p.put("seq", c.finSeq);
            after.add(() -> transmit(p, c.via));
        }
    }

    private void pump(Conn c) {
        EXEC.execute(() -> {
            List<Runnable> after = new ArrayList<>();
            synchronized (this) {
                pumpLocked(c, after);
            }
            for (var r : after) r.run();
        });
    }

    private void onTick() {
        synchronized (this) {
            tickQueued = false;
        }
        if (!machine.isOn()) return;
        if (++netTicks % 5 == 0) refresh();
        var now = System.currentTimeMillis();
        List<Runnable> after = new ArrayList<>();
        synchronized (this) {
            for (var it = conns.values().iterator(); it.hasNext(); ) {
                var c = it.next();
                switch (c.state) {
                    case CONNECTING -> {
                        if (now - c.lastSyn >= RTO_INITIAL_MS) {
                            c.lastSyn = now;
                            var syn = c.packet("syn");
                            after.add(() -> transmit(syn, null));
                        }
                    }
                    case OPEN -> {
                        var inFlight = c.sndNxt > c.sndUna;
                        var waiting = c.send.size() > 0 || (c.finQueued && !c.finAcked);
                        if (inFlight && now - c.lastProgress > c.rto) {
                            if (now - c.stalledSince > GIVE_UP_MS) {
                                c.fail(Errno.ETIMEDOUT);
                                it.remove();
                                var rst = c.packet("rst");
                                after.add(() -> transmit(rst, c.via));
                                continue;
                            }
                            // go back: send everything not acknowledged again
                            c.sndNxt = c.sndUna;
                            c.finSent = false;
                            c.rto = Math.min(c.rto * 2, RTO_MAX_MS);
                        } else if (!inFlight && waiting && c.sndNxt >= c.sndEdge && now - c.lastProbe > RTO_INITIAL_MS) {
                            // the peer's window is closed: ask again in case its update got lost
                            c.lastProbe = now;
                            var probe = c.packet("data");
                            probe.put("seq", c.sndNxt);
                            probe.put("data", "");
                            after.add(() -> transmit(probe, c.via));
                        }
                        if (!waiting) c.stalledSince = now;
                        pumpLocked(c, after);
                        if (c.localClosed && c.finAcked && (c.peerFin || now - c.closedAt > LINGER_MS)) {
                            c.state = State.CLOSED;
                            it.remove();
                        }
                    }
                    case CLOSED -> it.remove();
                }
            }
            notifyAll();
        }
        for (var r : after) r.run();
        machine.notifier().signal();
    }

    // ---- Guest API -------------------------------------------------------------------------------------------------

    /** A listening socket on {@code port}; ports below 1024 need root (as on Linux). */
    public synchronized Listener listen(int port, int uid, boolean root) throws FsException {
        if (port <= 0 || port > 65535) throw new FsException(Errno.EINVAL);
        if (port < 1024 && !root) throw new FsException(Errno.EACCES, "ports below 1024 need root");
        if (listeners.containsKey(port)) throw new FsException(Errno.EADDRINUSE);
        var l = new Listener(port, uid);
        listeners.put(port, l);
        return l;
    }

    /** Connect to {@code host}:{@code port}; blocks up to {@code timeoutMs}. */
    public Conn connect(String host, int port, int uid, long timeoutMs) throws FsException, InterruptedException {
        if (port <= 0 || port > 65535 || host.isEmpty() || host.length() > 64) throw new FsException(Errno.EINVAL);
        if (lower(host).equals("localhost")) host = hostname.isEmpty() ? machine.hostname() : hostname;
        var isLoopback = lower(host).equals(lower(hostname.isEmpty() ? machine.hostname() : hostname));
        if (!isLoopback && !online()) throw new FsException(Errno.ENETDOWN, "no network interface is up (a modem is needed)");
        if (hostname.isEmpty()) refresh();
        Conn c;
        synchronized (this) {
            int local;
            do {
                local = EPHEMERAL_FIRST + RANDOM.nextInt(65536 - EPHEMERAL_FIRST);
            } while (listeners.containsKey(local) || portUsed(local));
            c = new Conn(local, host, port, RANDOM.nextInt(Integer.MAX_VALUE), uid);
            c.lastSyn = System.currentTimeMillis();
            conns.put(c.key(), c);
        }
        var syn = c.packet("syn");
        EXEC.execute(() -> transmit(syn, null));
        var deadline = System.currentTimeMillis() + Math.max(100, timeoutMs);
        synchronized (this) {
            try {
                while (c.state == State.CONNECTING) {
                    var left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        conns.remove(c.key());
                        throw new FsException(Errno.ETIMEDOUT);
                    }
                    wait(left);
                }
            } catch (InterruptedException e) {
                conns.remove(c.key());
                throw e;
            }
            if (c.state != State.OPEN) throw new FsException(c.error);
        }
        return c;
    }

    private boolean portUsed(int port) {
        for (var c : conns.values()) if (c.localPort == port) return true;
        return false;
    }

    /** The sockets of this computer, for ss/netstat. */
    public synchronized List<Map<String, Object>> describe() {
        var out = new ArrayList<Map<String, Object>>();
        for (var l : listeners.values()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("state", "LISTEN");
            m.put("local", hostname);
            m.put("lport", l.port);
            m.put("remote", "*");
            m.put("rport", 0);
            m.put("uid", l.uid);
            m.put("queue", l.backlog.size());
            out.add(m);
        }
        for (var c : conns.values()) out.add(c.describe());
        return out;
    }

    public static Map<String, Object> describe(OpenFile file) {
        if (file instanceof Conn c) return c.describe();
        if (file instanceof Listener l) {
            var m = new LinkedHashMap<String, Object>();
            m.put("state", "LISTEN");
            m.put("local", l.net().hostname);
            m.put("lport", l.port);
            m.put("remote", "*");
            m.put("rport", 0);
            m.put("uid", l.uid);
            return m;
        }
        return null;
    }

    // ---- Sockets ---------------------------------------------------------------------------------------------------

    /** A listening socket; readable when a connection is waiting for accept. */
    public final class Listener extends OpenFile {
        final int port;
        final int uid;
        final ArrayDeque<Conn> backlog = new ArrayDeque<>();
        boolean closed;

        Listener(int port, int uid) {
            this.port = port;
            this.uid = uid;
        }

        Net net() {
            return Net.this;
        }

        @Override
        public FileType type() {
            return FileType.SOCKET_STREAM;
        }

        @Override
        public Stat stat() {
            return new Stat(FileType.SOCKET_STREAM, 0, 0, 0, 0);
        }

        /** The next connection, blocking unless {@code nonBlocking}. */
        public Conn accept(boolean nonBlocking) throws FsException, InterruptedException {
            synchronized (Net.this) {
                while (backlog.isEmpty()) {
                    if (closed) throw new FsException(Errno.EINVAL);
                    if (nonBlocking) throw new FsException(Errno.EAGAIN);
                    Net.this.wait();
                }
                return backlog.poll();
            }
        }

        @Override
        public boolean readReady() {
            synchronized (Net.this) {
                return !backlog.isEmpty() || closed;
            }
        }

        @Override
        protected void onClose() {
            List<Map<String, Object>> resets = new ArrayList<>();
            synchronized (Net.this) {
                closed = true;
                if (listeners.get(port) == this) listeners.remove(port);
                for (var c : backlog) {
                    resets.add(c.packet("rst"));
                    c.fail(Errno.ECONNRESET);
                    conns.remove(c.key());
                }
                backlog.clear();
                Net.this.notifyAll();
            }
            EXEC.execute(() -> {
                for (var p : resets) transmit(p, null);
            });
        }
    }

    /** One end of a connection: read and write as a pipe, closing sends fin. */
    public final class Conn extends OpenFile {
        final int localPort;
        final String remote;
        final int remotePort;
        final long id;
        final int uid;
        State state = State.CONNECTING;
        String peerHid;
        String via;
        int error;

        final ByteQueue send = new ByteQueue();
        long sndUna, sndNxt, sndEdge;
        boolean finQueued, finSent, finAcked;
        long finSeq = -1;

        final ByteQueue receive = new ByteQueue();
        long rcvNxt;
        long advertisedEdge;
        boolean peerFin;

        boolean localClosed;
        long closedAt;
        long lastProgress = System.currentTimeMillis();
        long stalledSince = System.currentTimeMillis();
        long lastProbe, lastSyn;
        long rto = RTO_INITIAL_MS;

        Conn(int localPort, String remote, int remotePort, long id, int uid) {
            this.localPort = localPort;
            this.remote = remote;
            this.remotePort = remotePort;
            this.id = id;
            this.uid = uid;
        }

        String key() {
            return Net.key(localPort, remote, remotePort, id);
        }

        Map<String, Object> packet(String type) {
            var p = new LinkedHashMap<String, Object>();
            p.put("ccnet", 1);
            p.put("t", type);
            p.put("src", hostname);
            p.put("sport", localPort);
            p.put("dst", remote);
            p.put("dport", remotePort);
            p.put("id", id);
            p.put("hid", hid);
            p.put("ack", rcvNxt);
            var win = RECEIVE_BUFFER - receive.size();
            p.put("win", win);
            advertisedEdge = rcvNxt + win;
            return p;
        }

        void fail(int errno) {
            if (state == State.CLOSED && error != 0) return;
            state = State.CLOSED;
            error = errno;
        }

        boolean finished() {
            return localClosed && finAcked && peerFin;
        }

        void onAck(long ack, long win) {
            if (ack < 0) return;
            if (ack > sndUna && ack <= sndNxt) {
                var data = (int) (Math.min(ack, sndUna + send.size()) - sndUna);
                send.drop(data);
                sndUna += data;
                if (finSent && ack == finSeq + 1) {
                    finAcked = true;
                    sndUna = ack;
                }
                var now = System.currentTimeMillis();
                lastProgress = now;
                stalledSince = now;
                rto = RTO_INITIAL_MS;
            }
            if (ack >= sndUna) sndEdge = Math.max(sndEdge, ack + win);
        }

        void onData(long seq, byte[] data) {
            if (seq != rcvNxt || peerFin) return;
            var n = Math.min(data.length, RECEIVE_BUFFER - receive.size());
            receive.add(data, 0, n);
            rcvNxt += n;
        }

        void onFin(long seq) {
            if (seq != rcvNxt || peerFin) return;
            peerFin = true;
            rcvNxt++;
        }

        Map<String, Object> describe() {
            var m = new LinkedHashMap<String, Object>();
            m.put("state", switch (state) {
                case CONNECTING -> "SYN-SENT";
                case OPEN -> localClosed || finQueued ? (peerFin ? "LAST-ACK" : "FIN-WAIT") : peerFin ? "CLOSE-WAIT" : "ESTAB";
                case CLOSED -> "CLOSED";
            });
            m.put("local", hostname);
            m.put("lport", localPort);
            m.put("remote", remote);
            m.put("rport", remotePort);
            m.put("uid", uid);
            m.put("recvq", receive.size());
            m.put("sendq", send.size());
            return m;
        }

        @Override
        public FileType type() {
            return FileType.SOCKET_STREAM;
        }

        @Override
        public Stat stat() {
            return new Stat(FileType.SOCKET_STREAM, 0, 0, 0, 0);
        }

        @Override
        public int read(ByteBuffer dst, boolean nonBlocking) throws FsException, InterruptedException {
            boolean update;
            int n;
            synchronized (Net.this) {
                while (receive.isEmpty()) {
                    if (peerFin) return 0;
                    if (error != 0) throw new FsException(error);
                    if (nonBlocking) throw new FsException(Errno.EAGAIN);
                    Net.this.wait();
                }
                n = receive.take(dst);
                // the window opened noticeably: tell the sender, which may be waiting for it
                update = state == State.OPEN && rcvNxt + RECEIVE_BUFFER - receive.size() - advertisedEdge >= RECEIVE_BUFFER / 4;
            }
            if (update) {
                EXEC.execute(() -> {
                    Map<String, Object> ack;
                    synchronized (Net.this) {
                        if (state != State.OPEN) return;
                        ack = packet("ack");
                    }
                    transmit(ack, via);
                });
            }
            return n;
        }

        @Override
        public int write(ByteBuffer src, boolean nonBlocking) throws FsException, InterruptedException {
            var total = 0;
            synchronized (Net.this) {
                while (src.hasRemaining()) {
                    if (error != 0) {
                        if (total > 0) break;
                        throw new FsException(error == Errno.ECONNRESET ? Errno.EPIPE : error);
                    }
                    if (finQueued) throw new FsException(Errno.EPIPE);
                    if (send.size() >= SEND_BUFFER) {
                        if (nonBlocking) {
                            if (total > 0) break;
                            throw new FsException(Errno.EAGAIN);
                        }
                        pump(this);
                        Net.this.wait();
                        continue;
                    }
                    var n = Math.min(src.remaining(), SEND_BUFFER - send.size());
                    send.add(src, n);
                    total += n;
                }
            }
            pump(this);
            return total;
        }

        @Override
        public boolean readReady() {
            synchronized (Net.this) {
                return !receive.isEmpty() || peerFin || error != 0;
            }
        }

        @Override
        public boolean writeReady() {
            synchronized (Net.this) {
                return send.size() < SEND_BUFFER || error != 0;
            }
        }

        /** No more data from this side; the peer reads end of file (shutdown(SHUT_WR)). */
        public void shutdownWrite() {
            synchronized (Net.this) {
                if (finQueued || state != State.OPEN) return;
                finQueued = true;
            }
            pump(this);
        }

        @Override
        protected void onClose() {
            synchronized (Net.this) {
                localClosed = true;
                closedAt = System.currentTimeMillis();
                receive.clear();
                if (state != State.OPEN || finished()) {
                    conns.remove(key());
                    return;
                }
                finQueued = true;
            }
            pump(this);
        }
    }
}
