package dev.ccbaremetal.kernel.gfx;

/** Implemented by peripherals that expose a framebuffer (pixel monitors) for direct, fast drawing. */
public interface FramebufferProvider {
    Framebuffer framebuffer();

    /** Called after direct drawing so the owner can schedule a network sync. */
    default void framebufferChanged() {
    }
}
