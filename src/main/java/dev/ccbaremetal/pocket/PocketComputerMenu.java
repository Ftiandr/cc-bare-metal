package dev.ccbaremetal.pocket;

import dev.ccbaremetal.computer.ComputerMenu;
import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * The pocket computer's menu: the computer, plus its module slots (4 upgrade slots) and the player's inventory, used
 * by the module screen on the client. Positions are in GUI units relative to the module panel's top-left corner.
 */
public class PocketComputerMenu extends ComputerMenu implements dev.ccbaremetal.computer.ModulePanel {
    public static final int PANEL_WIDTH = 176;
    public static final int PANEL_HEIGHT = 152;
    /** Top-left item position of the 2x2 module slots, the inventory and the hotbar. */
    public static final int UPGRADES_X = 71;
    public static final int UPGRADES_Y = 18;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 70;
    public static final int HOTBAR_Y = 128;

    private final Player owner;
    private final int pocketSlot;

    /** Client constructor. */
    public PocketComputerMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModRegistry.POCKET_MENU.get(), containerId, buf.readVarInt());
        owner = inventory.player;
        pocketSlot = buf.readVarInt();
        addSlots(inventory, new SimpleContainer(PocketCore.SLOTS));
    }

    /** Server constructor. {@code pocketSlot}: where the pocket computer is in the player's inventory. */
    public PocketComputerMenu(int containerId, Inventory inventory, PocketCore pocket, ServerPlayer player, int pocketSlot) {
        super(ModRegistry.POCKET_MENU.get(), containerId, pocket.core(), player, p -> holds(p, pocketSlot, pocket.core().computerId()));
        owner = player;
        this.pocketSlot = pocketSlot;
        addSlots(inventory, new UpgradeContainer(player, pocketSlot));
    }

    private static boolean holds(Player player, int slot, int computerId) {
        var stack = player.getInventory().getItem(slot);
        var id = stack.get(ModRegistry.COMPUTER_ID.get());
        return stack.is(ModRegistry.WASM_POCKET_COMPUTER.get()) && id != null && id == computerId;
    }

    private void addSlots(Inventory inventory, Container upgrades) {
        for (var i = 0; i < PocketCore.SLOTS; i++) {
            addSlot(new UpgradeSlot(upgrades, i, UPGRADES_X + (i % 2) * 18, UPGRADES_Y + (i / 2) * 18));
        }
        for (var row = 0; row < 3; row++) {
            for (var col = 0; col < 9; col++) {
                var index = 9 + row * 9 + col;
                addSlot(new InventorySlot(inventory, index, INVENTORY_X + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (var col = 0; col < 9; col++) addSlot(new InventorySlot(inventory, col, INVENTORY_X + col * 18, HOTBAR_Y));
    }

    public int pocketSlot() {
        return pocketSlot;
    }

    // ---- ModulePanel -------------------------------------------------------------------------------------------------

    @Override
    public int panelWidth() {
        return PANEL_WIDTH;
    }

    @Override
    public int panelHeight() {
        return PANEL_HEIGHT;
    }

    @Override
    public int moduleSlots() {
        return PocketCore.SLOTS;
    }

    @Override
    public String moduleSlotName(int index) {
        return PocketCore.slotName(index);
    }

    @Override
    public java.util.List<? extends net.minecraft.core.Holder.Reference<? extends dan200.computercraft.api.upgrades.UpgradeBase>> supportedModules(
        net.minecraft.core.RegistryAccess access) {
        return PocketUpgrades.all(access);
    }

    @Override
    public boolean allowsDuplicates() {
        return false;
    }

    @Override
    public int lockedInventorySlot() {
        return pocketSlot;
    }

    @Override
    public java.util.List<Label> labels() {
        return java.util.List.of(new Label(Component.translatable("container.inventory"), INVENTORY_X, INVENTORY_Y - 11));
    }

    /** Whether {@code stack} may go into module slot {@code index}: a pocket upgrade not already in another slot. */
    public boolean accepts(int index, ItemStack stack) {
        var upgrade = PocketUpgrades.find(owner.level().registryAccess(), stack);
        if (upgrade == null) return false;
        for (var i = 0; i < PocketCore.SLOTS; i++) {
            if (i == index) continue;
            var other = PocketUpgrades.find(owner.level().registryAccess(), slots.get(i).getItem());
            if (other != null && other.key().equals(upgrade.key())) return false;
        }
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        var slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        var stack = slot.getItem();
        var original = stack.copy();
        if (index < PocketCore.SLOTS) {
            if (!moveItemStackTo(stack, PocketCore.SLOTS, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            var moved = false;
            for (var i = 0; i < PocketCore.SLOTS && !moved; i++) {
                if (!slots.get(i).hasItem() && accepts(i, stack)) {
                    slots.get(i).setByPlayer(stack.split(1));
                    moved = true;
                }
            }
            if (!moved) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    private final class UpgradeSlot extends Slot {
        UpgradeSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return accepts(getContainerSlot(), stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    /** A slot of the player's inventory; the pocket computer's own slot is locked while its screen is open. */
    private final class InventorySlot extends Slot {
        InventorySlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        private boolean locked() {
            return getContainerSlot() == pocketSlot;
        }

        @Override
        public boolean mayPickup(Player player) {
            return !locked();
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !locked();
        }
    }

    /**
     * Server side: the module slots, stored in the pocket computer item (component {@code pocket_upgrades}). Items
     * are cached and re-read when the component changed elsewhere (the running computer writing upgrade state).
     */
    private static final class UpgradeContainer implements Container {
        private final Player player;
        private final int pocketSlot;
        private final NonNullList<ItemStack> items = NonNullList.withSize(PocketCore.SLOTS, ItemStack.EMPTY);
        private ItemContainerContents loaded;

        UpgradeContainer(Player player, int pocketSlot) {
            this.player = player;
            this.pocketSlot = pocketSlot;
        }

        private ItemStack pocket() {
            var stack = player.getInventory().getItem(pocketSlot);
            return stack.is(ModRegistry.WASM_POCKET_COMPUTER.get()) ? stack : ItemStack.EMPTY;
        }

        private NonNullList<ItemStack> items() {
            var contents = pocket().getOrDefault(ModRegistry.POCKET_UPGRADES.get(), ItemContainerContents.EMPTY);
            if (contents != loaded) {
                loaded = contents;
                for (var i = 0; i < items.size(); i++) items.set(i, ItemStack.EMPTY);
                contents.copyInto(items);
            }
            return items;
        }

        @Override
        public int getContainerSize() {
            return PocketCore.SLOTS;
        }

        @Override
        public boolean isEmpty() {
            return items().stream().allMatch(ItemStack::isEmpty);
        }

        @Override
        public ItemStack getItem(int slot) {
            return items().get(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            var stack = items().get(slot);
            if (stack.isEmpty() || amount <= 0) return ItemStack.EMPTY;
            var taken = stack.split(amount);
            setChanged();
            return taken;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            var stack = items().get(slot);
            items.set(slot, ItemStack.EMPTY);
            setChanged();
            return stack;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            items().set(slot, stack);
            setChanged();
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void setChanged() {
            var pocket = pocket();
            if (pocket.isEmpty()) return;
            loaded = ItemContainerContents.fromItems(items);
            pocket.set(ModRegistry.POCKET_UPGRADES.get(), loaded);
        }

        @Override
        public boolean stillValid(Player player) {
            return !pocket().isEmpty();
        }

        @Override
        public void clearContent() {
            items.replaceAll(s -> ItemStack.EMPTY);
            setChanged();
        }
    }
}
