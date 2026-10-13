package dev.ccbaremetal.client;

import dev.ccbaremetal.BareMetal;
import dev.ccbaremetal.registry.ModRegistry;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Client-only registrations. */
@EventBusSubscriber(modid = BareMetal.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {
    }

    @SubscribeEvent
    public static void clientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        DevPreview.register();
        // Pocket computer model: on (blinking cursor) or off.
        event.enqueueWork(() -> net.minecraft.client.renderer.item.ItemProperties.register(ModRegistry.WASM_POCKET_COMPUTER.get(),
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(BareMetal.MOD_ID, "on"),
            (stack, level, entity, seed) -> stack.getOrDefault(ModRegistry.POCKET_ON.get(), false) ? 1 : 0));
    }

    /** The pocket computer's light (layer 2) in the colour its upgrades set; dark when none. */
    @SubscribeEvent
    public static void itemColours(net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Item event) {
        event.register((stack, layer) -> layer == 2 ? 0xFF000000 | stack.getOrDefault(ModRegistry.POCKET_LIGHT.get(), 0x1E1B1B) : -1,
            ModRegistry.WASM_POCKET_COMPUTER.get());
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModRegistry.COMPUTER_MENU.get(), ComputerScreen<dev.ccbaremetal.computer.ComputerMenu>::new);
        event.register(ModRegistry.POCKET_MENU.get(), ComputerScreen<dev.ccbaremetal.pocket.PocketComputerMenu>::new);
        event.register(ModRegistry.TURTLE_MENU.get(), ComputerScreen<dev.ccbaremetal.turtle.TurtleMenu>::new);
        event.register(ModRegistry.KEYBOARD_MENU.get(), KeyboardScreen::new);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModRegistry.PIXEL_MONITOR_BE.get(), PixelMonitorRenderer::new);
        event.registerBlockEntityRenderer(ModRegistry.WASM_TURTLE_BE.get(), WasmTurtleRenderer::new);
    }
}
