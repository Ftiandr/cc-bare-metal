package dev.ccbaremetal;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.ccbaremetal.computer.ComputerMenu;
import dev.ccbaremetal.computer.WasmComputerBlockEntity;
import dev.ccbaremetal.kernel.lua.LuaProcess;
import dev.ccbaremetal.kernel.periph.MainThread;
import dev.ccbaremetal.kernel.proc.ModuleCache;
import dev.ccbaremetal.monitor.PixelMonitorBlockEntity;
import dev.ccbaremetal.network.Payloads;
import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import net.neoforged.fml.loading.FMLPaths;
import dev.ccbaremetal.kernel.Toolchain;
import org.slf4j.LoggerFactory;

/**
 * CC: Bare Metal - WebAssembly computers for CC: Tweaked.
 */
@Mod(BareMetal.MOD_ID)
public final class BareMetal {
    public static final String MOD_ID = "ccbaremetal";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public BareMetal(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, BareMetalConfig.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, BareMetalClientConfig.SPEC);
        ModRegistry.register(modBus);
        modBus.addListener(BareMetal::registerCapabilities);
        modBus.addListener(BareMetal::registerPayloads);

        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            MainThread.drain();
            dev.ccbaremetal.pocket.PocketCores.tick(event.getServer());
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> {
            dev.ccbaremetal.pocket.PocketCores.clear();
            WasmComputerBlockEntity.shutdownAll();
            MainThread.clear();
            ModuleCache.clear();
        });
        NeoForge.EVENT_BUS.addListener(BareMetal::onChunkWatch);

        LuaProcess.register();
        // Compiled WASM bytecode survives restarts; versioned because it depends on the bundled Chicory.
        var data = FMLPaths.GAMEDIR.get().resolve("ccbaremetal");
        ModuleCache.setDiskCache(data.resolve("jit-cache").resolve(container.getModInfo().getVersion().toString()));
        Toolchain.setDirectory(data.resolve("toolchain"));
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(PeripheralCapability.get(), ModRegistry.WASM_COMPUTER_BE.get(), (be, side) -> be.peripheral());
        // Hoppers and pipes reach the turtle's inventory, like CC's turtles.
        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, ModRegistry.WASM_TURTLE_BE.get(),
            (be, side) -> new net.neoforged.neoforge.items.wrapper.InvWrapper(be.turtle().getInventory()));
        event.registerBlockEntity(PeripheralCapability.get(), ModRegistry.PIXEL_MONITOR_BE.get(), (be, side) ->
            side == null || side != be.facing() ? be.peripheral() : null);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        // Client bound: handled by client-only code, reached through lambdas so dedicated servers never load it.
        registrar.playToClient(Payloads.Terminal.TYPE, Payloads.Terminal.CODEC,
            (p, c) -> dev.ccbaremetal.client.ClientNetwork.terminal(p));
        registrar.playToClient(Payloads.Framebuffer.TYPE, Payloads.Framebuffer.CODEC,
            (p, c) -> dev.ccbaremetal.client.ClientNetwork.framebuffer(p));
        registrar.playToClient(Payloads.ScreenState.TYPE, Payloads.ScreenState.CODEC,
            (p, c) -> dev.ccbaremetal.client.ClientNetwork.screenState(p));
        registrar.playToClient(Payloads.Clipboard.TYPE, Payloads.Clipboard.CODEC,
            (p, c) -> dev.ccbaremetal.client.ClientNetwork.clipboard(p));
        registrar.playToClient(Payloads.Monitor.TYPE, Payloads.Monitor.CODEC,
            (p, c) -> dev.ccbaremetal.client.ClientNetwork.monitor(p));
        // Server bound.
        registrar.playToServer(Payloads.Input.TYPE, Payloads.Input.CODEC, (p, c) -> {
            // typing with the keyboard item, or in the computer's window
            if (c.player() instanceof ServerPlayer player && player.containerMenu instanceof dev.ccbaremetal.computer.KeyboardMenu keyboard
                && keyboard.containerId == p.containerId() && keyboard.stillValid(player)) {
                keyboard.input(p);
            } else {
                withComputer(c, p.containerId(), core -> core.handleInput(p));
            }
        });
        registrar.playToServer(Payloads.Power.TYPE, Payloads.Power.CODEC, (p, c) -> withComputer(c, p.containerId(),
            core -> core.handlePower(p.action())));
        registrar.playToServer(Payloads.UploadStart.TYPE, Payloads.UploadStart.CODEC, (p, c) -> withComputer(c, p.containerId(),
            core -> core.uploads().start(c.player().getUUID(), p, r -> c.reply(r))));
        registrar.playToServer(Payloads.UploadData.TYPE, Payloads.UploadData.CODEC, (p, c) -> withComputer(c, p.containerId(),
            core -> core.uploads().data(c.player().getUUID(), p, r -> c.reply(r))));
        registrar.playToClient(Payloads.UploadReply.TYPE, Payloads.UploadReply.CODEC,
            (p, c) -> dev.ccbaremetal.client.ClientNetwork.uploadReply(p));
    }

    private static void withComputer(IPayloadContext context, int containerId, java.util.function.Consumer<dev.ccbaremetal.computer.ComputerCore> action) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (player.containerMenu instanceof ComputerMenu menu && menu.containerId == containerId && menu.core() != null
            && menu.stillValid(player)) {
            action.accept(menu.core());
        }
    }

    private static void onChunkWatch(ChunkWatchEvent.Sent event) {
        for (var be : event.getChunk().getBlockEntities().values()) {
            if (be instanceof PixelMonitorBlockEntity monitor && monitor.isOrigin()) monitor.sendFull(event.getPlayer());
        }
    }
}
