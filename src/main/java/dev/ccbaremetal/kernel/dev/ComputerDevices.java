package dev.ccbaremetal.kernel.dev;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.io.InputHub;
import dev.ccbaremetal.kernel.io.OpenFile;

/**
 * The computer's own devices, there from the start: the screen (graphics fb0), the keyboard and the mouse (input
 * event0, event1, and mice: PS/2 packets) and the six redstone sides (class redstone: input read only, output 0-15).
 */
public final class ComputerDevices {
    private ComputerDevices() {
    }

    public static void register(Machine machine, DeviceTree tree, InputHub input) {
        tree.register(GraphicsDevice.screen(machine));
        tree.register(new InputDevice(input, InputHub.Kind.KEYBOARD, "keyboard"));
        tree.register(new InputDevice(input, InputHub.Kind.MOUSE, "mouse"));
        tree.register(new InputDevice(input, InputHub.Kind.MICE, "mice"));
        for (var i = 0; i < Machine.SIDES.size(); i++) tree.register(new Redstone(machine, i));
    }

    /** The keyboard and mouse as evdev devices, and the mouse as /dev/input/mice. */
    private static final class InputDevice extends Device {
        private final InputHub hub;
        private final InputHub.Kind kind;

        InputDevice(InputHub hub, InputHub.Kind kind, String name) {
            super("input", kind == InputHub.Kind.MICE ? "mice" : "event", null);
            this.hub = hub;
            this.kind = kind;
            attribute("name", () -> name + "\n");
        }

        @Override
        protected boolean numbered() {
            return kind != InputHub.Kind.MICE;
        }

        @Override
        public String node() {
            return "input/" + name();
        }

        @Override
        protected OpenFile open(String guestPath) {
            return hub.open(kind, guestPath);
        }
    }

    /** One side's redstone: /sys/class/redstone/SIDE/input and output. */
    private static final class Redstone extends Device {
        Redstone(Machine machine, int side) {
            super("redstone", Machine.SIDES.get(side), null);
            attribute("input", () -> machine.redstoneInput(side) + "\n");
            attribute("output", () -> machine.redstoneOutput(side) + "\n", value -> {
                try {
                    machine.setRedstoneOutput(side, Integer.parseInt(value.trim()));
                } catch (NumberFormatException e) {
                    throw new FsException(Errno.EINVAL);
                }
            }, true);
        }

        @Override
        protected boolean numbered() {
            return false;
        }

        @Override
        public String node() {
            return null;
        }
    }
}
