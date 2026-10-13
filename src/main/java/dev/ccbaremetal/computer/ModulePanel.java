package dev.ccbaremetal.computer;

import dan200.computercraft.api.upgrades.UpgradeBase;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A computer menu with a module panel (pocket computer, turtle): its first {@link #moduleSlots()} slots take upgrades,
 * then come its other slots and the player's inventory. Positions are in GUI units relative to the panel.
 */
public interface ModulePanel {
    /** A text drawn on the panel. */
    record Label(Component text, int x, int y) {
    }

    int panelWidth();

    int panelHeight();

    int moduleSlots();

    /** Peripheral name of module slot {@code index} (shown in its tooltip). */
    String moduleSlotName(int index);

    /** The upgrades the module slots take (for the tooltip of an empty slot). */
    List<? extends Holder.Reference<? extends UpgradeBase>> supportedModules(RegistryAccess access);

    /** Whether the same upgrade may be in two module slots. */
    boolean allowsDuplicates();

    /** Player inventory slot that is locked (the pocket computer itself), or -1. */
    default int lockedInventorySlot() {
        return -1;
    }

    /** Menu slot to highlight (the turtle's selected slot), or -1. */
    default int highlightedSlot() {
        return -1;
    }

    /** Texts besides the title, e.g. "Inventory", "Fuel: 80". */
    List<Label> labels();
}
