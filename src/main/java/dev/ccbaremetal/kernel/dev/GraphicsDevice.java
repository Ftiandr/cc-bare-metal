package dev.ccbaremetal.kernel.dev;

import dev.ccbaremetal.kernel.Errno;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.fs.FsException;
import dev.ccbaremetal.kernel.gfx.Framebuffer;
import dev.ccbaremetal.kernel.io.FbDevice;
import dev.ccbaremetal.kernel.io.OpenFile;

import java.util.function.Supplier;

/**
 * A framebuffer (class graphics, /dev/fbN, Linux fbdev): the computer's screen (fb0) or a pixel monitor. Attributes:
 * <pre>
 *   virtual_size     "512,288" (write "320,200" to change the resolution; fb0 also takes "auto")
 *   bits_per_pixel   1, 2, 4 or 8 (2, 4, 16 or 256 colours)
 *   stride           bytes per line of the node (= width)
 *   palette          "INDEX RRGGBB" per line (write such lines to change colours)
 *   max_size, name
 *   display          fb0 only: "text" or "graphics", what the screen shows (write to switch)
 *   default_size     fb0 only: the common resolution filling the text area
 * </pre>
 * Every user may drive the screen of the computer they sit at, so the writable attributes are 0666.
 */
public final class GraphicsDevice extends Device {
    private final Machine machine;
    private final Supplier<Framebuffer> framebuffer;
    private final Runnable changed;
    private final boolean screen;

    /** The computer's own screen (fb0). */
    public static GraphicsDevice screen(Machine machine) {
        return new GraphicsDevice(machine, null, machine::framebuffer, () -> {
        }, true);
    }

    /** A pixel monitor: {@code changed} schedules its sync after direct drawing. */
    public static GraphicsDevice monitor(Machine machine, String peripheral, Supplier<Framebuffer> framebuffer, Runnable changed) {
        return new GraphicsDevice(machine, peripheral, framebuffer, changed, false);
    }

    private GraphicsDevice(Machine machine, String peripheral, Supplier<Framebuffer> framebuffer, Runnable changed, boolean screen) {
        super("graphics", "fb", peripheral);
        this.machine = machine;
        this.framebuffer = framebuffer;
        this.changed = changed;
        this.screen = screen;
        attribute("name", () -> (screen ? "ccbaremetal" : "pixel_monitor") + "\n");
        attribute("virtual_size", () -> framebuffer.get().width() + "," + framebuffer.get().height() + "\n", this::setSize, true);
        attribute("bits_per_pixel", () -> framebuffer.get().bpp() + "\n", this::setDepth, true);
        attribute("stride", () -> framebuffer.get().width() + "\n");
        attribute("palette", this::palette, this::setPalette, true);
        attribute("max_size", () -> framebuffer.get().maxWidth() + "," + framebuffer.get().maxHeight() + "\n");
        if (screen) {
            attribute("display", () -> (machine.display() == Machine.DisplayMode.GRAPHICS ? "graphics" : "text") + "\n", this::setDisplay, true);
            attribute("default_size", () -> {
                var size = machine.defaultFramebufferSize();
                return size[0] + "," + size[1] + "\n";
            });
        }
    }

    public Framebuffer framebuffer() {
        return framebuffer.get();
    }

    @Override
    protected OpenFile open(String guestPath) {
        return new FbDevice(machine, framebuffer, screen ? null : changed, guestPath);
    }

    private String palette() {
        var fb = framebuffer.get();
        var sb = new StringBuilder();
        for (var i = 0; i < 1 << fb.bpp(); i++) sb.append(i).append(' ').append(String.format("%06x", fb.palette(i))).append('\n');
        return sb.toString();
    }

    private static int number(String s) throws FsException {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new FsException(Errno.EINVAL);
        }
    }

    private void setSize(String value) throws FsException {
        var fb = framebuffer.get();
        if (screen && value.equals("auto")) {
            machine.setFramebufferCustom(false);
            return;
        }
        if (screen) machine.setFramebufferCustom(true);
        var parts = value.split("[,x ]+");
        if (parts.length != 2 || !fb.setMode(number(parts[0]), number(parts[1]), fb.bpp(), Framebuffer.PRESET_DEFAULT)) {
            throw new FsException(Errno.EINVAL);
        }
        changed.run();
    }

    private void setDepth(String value) throws FsException {
        var fb = framebuffer.get();
        if (screen) machine.setFramebufferCustom(true);
        if (!fb.setMode(fb.width(), fb.height(), number(value), Framebuffer.PRESET_DEFAULT)) throw new FsException(Errno.EINVAL);
        changed.run();
    }

    private void setPalette(String value) throws FsException {
        var fb = framebuffer.get();
        for (var line : value.split("\n")) {
            var parts = line.trim().split("\\s+");
            if (parts.length != 2) continue;
            try {
                fb.setPalette(Integer.parseInt(parts[0]), Integer.parseInt(parts[1].replace("#", ""), 16));
            } catch (NumberFormatException e) {
                throw new FsException(Errno.EINVAL);
            }
        }
        changed.run();
    }

    private void setDisplay(String value) throws FsException {
        switch (value) {
            case "text" -> machine.setDisplay(Machine.DisplayMode.TEXT, 0);
            case "graphics" -> machine.showFramebuffer(0);
            default -> throw new FsException(Errno.EINVAL);
        }
    }
}
