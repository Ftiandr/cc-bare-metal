package dev.ccbaremetal.client;

import dev.ccbaremetal.monitor.PixelMonitorBlockEntity;
import dev.ccbaremetal.network.Payloads;
import net.minecraft.client.Minecraft;

/** Client-side packet handlers (main thread). */
public final class ClientNetwork {
    private ClientNetwork() {
    }

    /** The open computer window for this menu (also behind the pocket computer's module screen), or null. */
    private static ComputerScreen<?> screen(int containerId) {
        var current = Minecraft.getInstance().screen;
        if (current instanceof ModulesScreen<?> modules) current = modules.parent();
        return current instanceof ComputerScreen<?> s && s.getMenu().containerId == containerId ? s : null;
    }

    public static void terminal(Payloads.Terminal p) {
        var s = screen(p.containerId());
        if (s != null) s.onTerminal(p.data());
    }

    public static void framebuffer(Payloads.Framebuffer p) {
        var s = screen(p.containerId());
        if (s != null) s.image().applyFramebuffer(p.data());
    }

    public static void clipboard(Payloads.Clipboard p) {
        var s = screen(p.containerId());
        if (s != null) s.programCopied(p.text());
    }

    public static void uploadReply(Payloads.UploadReply p) {
        // the overwrite question may be on screen instead of the computer window
        var current = Minecraft.getInstance().screen;
        if (current instanceof ComputerScreen<?> s) s.onUploadReply(p);
    }

    public static void screenState(Payloads.ScreenState p) {
        var s = screen(p.containerId());
        if (s != null) s.setState(p.on(), p.graphics(), p.mouse(), p.motion());
    }

    public static void monitor(Payloads.Monitor p) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        if (level.getBlockEntity(p.pos()) instanceof PixelMonitorBlockEntity be) {
            ScreenImage image;
            if (be.clientState instanceof ScreenImage existing) {
                image = existing;
            } else {
                image = new ScreenImage("monitor");
                image.setGraphics(true);
                be.clientState = image;
            }
            image.applyFramebuffer(p.data());
        }
    }
}
