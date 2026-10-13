package dev.ccbaremetal.kernel.dev.drivers;

import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.dev.DeviceTree;
import dev.ccbaremetal.kernel.dev.Driver;
import dev.ccbaremetal.kernel.dev.GraphicsDevice;
import dev.ccbaremetal.kernel.dev.TouchscreenDevice;
import dev.ccbaremetal.kernel.gfx.FramebufferProvider;

/** Pixel monitors (any peripheral with a framebuffer): a framebuffer /dev/fbN and its touchscreen /dev/input/eventN. */
public final class PixelMonitorDriver implements Driver {
    private final Machine machine;

    public PixelMonitorDriver(Machine machine) {
        this.machine = machine;
    }

    @Override
    public String name() {
        return "pixel_monitor";
    }

    @Override
    public boolean matches(Peripheral p) {
        return p.object() instanceof FramebufferProvider;
    }

    @Override
    public void probe(DeviceTree tree, Peripheral p) {
        var provider = (FramebufferProvider) p.object();
        var fb = GraphicsDevice.monitor(machine, p.name(), provider::framebuffer, provider::framebufferChanged);
        tree.register(fb);
        tree.register(new TouchscreenDevice(p.name(), "pixel_monitor", "pixel_monitor_touch", 0, fb::name,
            () -> provider.framebuffer().width() + "," + provider.framebuffer().height()));
    }
}
