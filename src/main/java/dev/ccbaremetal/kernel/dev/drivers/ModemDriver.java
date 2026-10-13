package dev.ccbaremetal.kernel.dev.drivers;

import dan200.computercraft.shared.peripheral.modem.wireless.WirelessModemPeripheral;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.dev.DeviceTree;
import dev.ccbaremetal.kernel.dev.Driver;
import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.kernel.net.Net;
import dev.ccbaremetal.kernel.net.NetDevice;

import java.util.Map;

/**
 * Modems (CC's wireless, ender and wired ones, and any other peripheral of type "modem" with CC's modem methods):
 * a network interface, wlanN for wireless and ender modems, ethN for wired ones. It opens the computer's address
 * channel on the modem (again, if another program closed it) and passes the packets for this computer to the stack;
 * other modem messages go on to programs as usual.
 */
public final class ModemDriver implements Driver {
    private final Machine machine;

    public ModemDriver(Machine machine) {
        this.machine = machine;
    }

    @Override
    public String name() {
        return "modem";
    }

    @Override
    public boolean matches(Peripheral p) {
        // only modems on the computer itself: a modem's methods are called without a guest process
        var attached = machine.peripherals().get(p.name());
        if (!p.is("modem") || attached == null) return false;
        var m = attached.methods();
        return m.containsKey("transmit") && m.containsKey("open") && m.containsKey("isOpen") && m.containsKey("close");
    }

    @Override
    public void probe(DeviceTree tree, Peripheral p) {
        var wireless = Boolean.TRUE.equals(first(call(p.name(), "isWireless")));
        var type = !wireless ? "wired"
            : p.object() instanceof WirelessModemPeripheral w && w.isInterdimensional() ? "ender" : "wireless";
        var device = new ModemInterface(machine, p.name(), wireless ? "wlan" : "eth", type,
            p.object() instanceof WirelessModemPeripheral w ? w::getRange : null);
        tree.register(device);
        machine.net().interfaceChanged(device);
    }

    private Object[] call(String modem, String method, Object... args) {
        try {
            return machine.peripherals().callDirect(modem, method, args);
        } catch (Exception e) {
            return new Object[0];
        }
    }

    private static Object first(Object[] values) {
        return values.length > 0 ? values[0] : null;
    }

    private final class ModemInterface extends NetDevice {
        private final String type;

        ModemInterface(Machine machine, String peripheral, String prefix, String type, java.util.function.DoubleSupplier range) {
            super(machine.net(), prefix, peripheral);
            this.type = type;
            if (range != null) attribute("range", () -> (type.equals("ender") ? "unlimited" : Integer.toString((int) range.getAsDouble())) + "\n");
        }

        @Override
        protected String type() {
            return type;
        }

        @Override
        protected void send(int to, int reply, Map<String, Object> packet) {
            call(peripheral(), "transmit", to, reply, packet);
        }

        @Override
        protected void listen(int channel, int previous) {
            if (previous >= 0 && previous != channel) call(peripheral(), "close", previous);
            if (channel >= 0 && !Boolean.TRUE.equals(first(call(peripheral(), "isOpen", channel)))) call(peripheral(), "open", channel);
        }

        @Override
        protected boolean onEvent(Event event) {
            // modem_message: side, channel, reply channel, message, distance
            var args = event.args();
            if (!event.name().equals("modem_message") || args.length < 4 || !peripheral().equals(args[0])) return false;
            if (!(args[3] instanceof Map<?, ?> msg) || !net.isForMe(msg)) return false;
            received(msg);
            return true;
        }
    }
}
