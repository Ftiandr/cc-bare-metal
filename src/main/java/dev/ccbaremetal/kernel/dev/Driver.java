package dev.ccbaremetal.kernel.dev;

import dan200.computercraft.api.peripheral.IPeripheral;

import java.util.List;

/**
 * A driver turns a kind of peripheral into devices (Linux: a driver bound to a device on a bus). The device tree
 * offers every peripheral that appears to each driver in turn; the first that matches probes it. Devices it
 * registers for the peripheral are removed with it. Peripherals no driver takes are still reachable through
 * /dev/periph (every method of every mod).
 */
public interface Driver {
    /** A peripheral on the bus: its name, CC types, and the object (null when reached through a wired network). */
    record Peripheral(String name, List<String> types, IPeripheral object) {
        public boolean is(String type) {
            return types.contains(type);
        }
    }

    /** The name in /sys/bus/peripheral/devices/NAME/driver. */
    String name();

    boolean matches(Peripheral p);

    /** Register the peripheral's devices with {@code tree}. */
    void probe(DeviceTree tree, Peripheral p);
}
