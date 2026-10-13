package dev.ccbaremetal.pocket;

import dan200.computercraft.api.ComputerCraftAPI;
import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * The running pocket computers, by computer id (server thread only). A pocket computer runs while its item is ticked
 * in an inventory; one that has not been ticked for {@link #TIMEOUT_TICKS} (put in a chest, dropped) is stopped, and
 * boots again when it is back in an inventory, if it was on.
 */
public final class PocketCores {
    private static final int TIMEOUT_TICKS = 40;
    private static final Map<Integer, PocketCore> CORES = new HashMap<>();

    private PocketCores() {
    }

    /** The pocket computer of this item, created (and the item given a computer id) if needed; ticks it. */
    public static PocketCore update(ServerLevel level, ItemStack stack, Entity holder) {
        var core = get(level, stack);
        core.update(level, stack, holder);
        return core;
    }

    /** The pocket computer of this item, created (and the item given a computer id) if needed. */
    public static PocketCore get(ServerLevel level, ItemStack stack) {
        var id = stack.get(ModRegistry.COMPUTER_ID.get());
        if (id == null) {
            id = ComputerCraftAPI.createUniqueNumberedSaveDir(level.getServer(), "ccbaremetal/computer");
            stack.set(ModRegistry.COMPUTER_ID.get(), id);
        }
        var core = CORES.get(id);
        if (core == null) {
            core = new PocketCore(id, stack);
            CORES.put(id, core);
        }
        return core;
    }

    /** A running pocket computer by id, or null. */
    public static PocketCore byId(int id) {
        return CORES.get(id);
    }

    public static void tick(MinecraftServer server) {
        if (CORES.isEmpty()) return;
        var now = server.getTickCount();
        CORES.values().removeIf(core -> {
            if (now - core.lastTick <= TIMEOUT_TICKS || core.core().hasViewers()) return false;
            core.stop();
            return true;
        });
    }

    public static void clear() {
        for (var core : CORES.values()) core.stop();
        CORES.clear();
    }
}
