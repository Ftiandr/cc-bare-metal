package dev.ccbaremetal.kernel.net;

import java.util.Map;

/** lo: the computer talking to itself (localhost, its own name). No modem hears its own computer, so no modem is used. */
public final class Loopback extends NetDevice {
    Loopback(Net net) {
        super(net, "lo", null);
    }

    @Override
    protected boolean numbered() {
        return false;
    }

    @Override
    protected String type() {
        return "loopback";
    }

    @Override
    protected void send(int to, int reply, Map<String, Object> packet) {
        received(packet);
    }

    @Override
    protected void listen(int channel, int previous) {
    }
}
