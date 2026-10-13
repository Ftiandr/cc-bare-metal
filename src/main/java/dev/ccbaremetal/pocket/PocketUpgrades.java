package dev.ccbaremetal.pocket;

import dan200.computercraft.api.pocket.IPocketUpgrade;
import dev.ccbaremetal.computer.UpgradeIndex;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Which items are pocket upgrades (CC's pocket upgrade registry, addons included). */
public final class PocketUpgrades {
    private static final UpgradeIndex<IPocketUpgrade> INDEX = new UpgradeIndex<>(IPocketUpgrade.REGISTRY);

    private PocketUpgrades() {
    }

    /** The upgrade this item installs, or null. */
    public static Holder.Reference<IPocketUpgrade> find(RegistryAccess access, ItemStack stack) {
        return INDEX.find(access, stack);
    }

    /** Every pocket upgrade, sorted by id (for the list of supported items). */
    public static List<Holder.Reference<IPocketUpgrade>> all(RegistryAccess access) {
        return INDEX.all(access);
    }
}
