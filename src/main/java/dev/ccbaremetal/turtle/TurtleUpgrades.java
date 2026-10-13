package dev.ccbaremetal.turtle;

import dan200.computercraft.api.turtle.ITurtleUpgrade;
import dev.ccbaremetal.computer.UpgradeIndex;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Which items are turtle upgrades (CC's turtle upgrade registry: its tools, modems, speaker... and addons'). */
public final class TurtleUpgrades {
    private static final UpgradeIndex<ITurtleUpgrade> INDEX = new UpgradeIndex<>(ITurtleUpgrade.REGISTRY);

    private TurtleUpgrades() {
    }

    public static Holder.Reference<ITurtleUpgrade> find(RegistryAccess access, ItemStack stack) {
        return INDEX.find(access, stack);
    }

    public static List<Holder.Reference<ITurtleUpgrade>> all(RegistryAccess access) {
        return INDEX.all(access);
    }
}
