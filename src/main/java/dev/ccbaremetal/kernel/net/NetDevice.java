package dev.ccbaremetal.kernel.net;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.dev.Device;
import dev.ccbaremetal.kernel.fs.FsException;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A network interface (class net, /sys/class/net/NAME; no /dev node, as in Linux): what the network stack ({@link Net})
 * sends packets through and receives them from. Drivers make them (a modem: {@code ModemDriver}); {@link Loopback}
 * is the computer itself. Attributes:
 * <pre>
 *   type        loopback, wireless, ender, wired
 *   operstate   up or down (root writes it: ip link set NAME down)
 *   address     the modem channel the computer listens on (derived from its hostname)
 *   mtu         the largest data in one packet
 *   statistics/rx_packets, rx_bytes, tx_packets, tx_bytes    bytes count packet data
 * </pre>
 * Its methods run on the network thread only.
 */
public abstract class NetDevice extends Device {
    protected final Net net;
    private volatile boolean up = true;
    private volatile int listening = -1;
    private final AtomicLong rxPackets = new AtomicLong(), rxBytes = new AtomicLong(), txPackets = new AtomicLong(), txBytes = new AtomicLong();

    protected NetDevice(Net net, String prefix, String peripheral) {
        super("net", prefix, peripheral);
        this.net = net;
        attribute("type", () -> type() + "\n");
        attribute("operstate", () -> (up ? "up" : "down") + "\n", this::setState, false);
        attribute("address", () -> (listening >= 0 ? Integer.toString(listening) : "") + "\n");
        attribute("mtu", () -> Net.MSS + "\n");
        attribute("statistics/rx_packets", () -> rxPackets + "\n");
        attribute("statistics/rx_bytes", () -> rxBytes + "\n");
        attribute("statistics/tx_packets", () -> txPackets + "\n");
        attribute("statistics/tx_bytes", () -> txBytes + "\n");
    }

    /** loopback, wireless, ender, wired... */
    protected abstract String type();

    /** Send {@code packet} to whoever listens on channel {@code to}; answers go to {@code reply}. */
    protected abstract void send(int to, int reply, Map<String, Object> packet);

    /** Listen on {@code channel} (-1: on nothing) instead of {@code previous}; again if another program closed it. */
    protected abstract void listen(int channel, int previous);

    @Override
    public final String node() {
        return null;
    }

    public final boolean isUp() {
        return up;
    }

    private void setState(String value) throws FsException {
        switch (value.trim()) {
            case "up" -> up = true;
            case "down" -> up = false;
            default -> throw new FsException(Errno.EINVAL);
        }
        net.interfaceChanged(this);
    }

    final void transmit(int to, int reply, Map<String, Object> packet) {
        if (!up) return;
        txPackets.incrementAndGet();
        txBytes.addAndGet(dataLength(packet));
        send(to, reply, packet);
    }

    final void setListening(int channel) {
        var previous = listening;
        listening = up ? channel : -1;
        listen(listening, previous);
    }

    /** A packet for this computer arrived: count it and hand it to the stack. */
    protected final void received(Map<?, ?> packet) {
        if (!up) return;
        rxPackets.incrementAndGet();
        rxBytes.addAndGet(dataLength(packet));
        net.receive(this, packet);
    }

    private static int dataLength(Map<?, ?> packet) {
        return packet.get("data") instanceof String s ? s.length() : 0;
    }
}
