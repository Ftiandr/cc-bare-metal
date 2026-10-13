package dev.ccbaremetal.turtle;

import dan200.computercraft.api.turtle.TurtleSide;
import dan200.computercraft.api.upgrades.UpgradeBase;
import dev.ccbaremetal.computer.ComputerMenu;
import dev.ccbaremetal.computer.ModulePanel;
import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The turtle's screen: the computer, plus the module panel with its two upgrade slots (left, right), its 16-slot
 * inventory and the player's inventory. The selected slot and the fuel level are synced to the client.
 */
public class TurtleMenu extends ComputerMenu implements ModulePanel {
    public static final int PANEL_WIDTH = 176;
    public static final int PANEL_HEIGHT = 187;
    private static final int MODULE_Y = 30;
    private static final int TURTLE_X = 80;
    private static final int TURTLE_Y = 18;
    private static final int INVENTORY_X = 8;
    private static final int INVENTORY_Y = 106;
    private static final int HOTBAR_Y = 164;
    /** Slot indices: 2 modules, 16 turtle slots, then the player's inventory. */
    public static final int TURTLE_SLOTS_START = 2;

    private final Player owner;
    private final ContainerData data;

    /** Client constructor. */
    public TurtleMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModRegistry.TURTLE_MENU.get(), containerId, buf.readVarInt());
        owner = inventory.player;
        data = new SimpleContainerData(3);
        addSlots(inventory, new SimpleContainer(2), new SimpleContainer(WasmTurtle.INVENTORY_SIZE));
    }

    /** Server constructor. */
    public TurtleMenu(int containerId, Inventory inventory, WasmTurtle turtle, ServerPlayer player) {
        super(ModRegistry.TURTLE_MENU.get(), containerId, turtle.core(), player, p -> !turtle.isRemoved()
            && p.distanceToSqr(turtle.getPosition().getCenter()) < 64);
        owner = player;
        data = new ContainerData() {
            @Override
            public int get(int index) {
                var fuel = turtle.getFuelLevel();
                return switch (index) {
                    case 0 -> turtle.getSelectedSlot();
                    case 1 -> fuel & 0xFFFF;
                    default -> fuel >>> 16;
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return 3;
            }
        };
        addSlots(inventory, new ModuleContainer(turtle), turtle.getInventory());
    }

    private void addSlots(Inventory inventory, Container modules, Container turtleInventory) {
        addSlot(new ModuleSlot(modules, 0, 20, MODULE_Y));
        addSlot(new ModuleSlot(modules, 1, 44, MODULE_Y));
        for (var i = 0; i < WasmTurtle.INVENTORY_SIZE; i++) {
            addSlot(new Slot(turtleInventory, i, TURTLE_X + (i % 4) * 18, TURTLE_Y + (i / 4) * 18));
        }
        for (var row = 0; row < 3; row++) {
            for (var col = 0; col < 9; col++) addSlot(new Slot(inventory, 9 + row * 9 + col, INVENTORY_X + col * 18, INVENTORY_Y + row * 18));
        }
        for (var col = 0; col < 9; col++) addSlot(new Slot(inventory, col, INVENTORY_X + col * 18, HOTBAR_Y));
        addDataSlots(data);
    }

    public int fuel() {
        return data.get(1) & 0xFFFF | data.get(2) << 16;
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
        return 2;
    }

    @Override
    public String moduleSlotName(int index) {
        return index == 0 ? "left" : "right";
    }

    @Override
    public List<? extends Holder.Reference<? extends UpgradeBase>> supportedModules(RegistryAccess access) {
        return TurtleUpgrades.all(access);
    }

    @Override
    public boolean allowsDuplicates() {
        return true;
    }

    @Override
    public int highlightedSlot() {
        return TURTLE_SLOTS_START + data.get(0);
    }

    @Override
    public List<Label> labels() {
        return List.of(
            new Label(Component.literal("L"), 24, MODULE_Y - 10),
            new Label(Component.literal("R"), 48, MODULE_Y - 10),
            new Label(Component.translatable("gui.ccbaremetal.fuel", fuel()), 8, MODULE_Y + 26),
            new Label(Component.translatable("container.inventory"), INVENTORY_X, INVENTORY_Y - 11));
    }

    // ---- Slots -----------------------------------------------------------------------------------------------------

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        var slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        var stack = slot.getItem();
        var original = stack.copy();
        var playerStart = TURTLE_SLOTS_START + WasmTurtle.INVENTORY_SIZE;
        if (index < playerStart) {
            if (!moveItemStackTo(stack, playerStart, slots.size(), true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, TURTLE_SLOTS_START, playerStart, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    private final class ModuleSlot extends Slot {
        ModuleSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return TurtleUpgrades.find(owner.level().registryAccess(), stack) != null;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    /** Server side: the turtle's two upgrades as slots. */
    private static final class ModuleContainer implements Container {
        private final WasmTurtle turtle;

        ModuleContainer(WasmTurtle turtle) {
            this.turtle = turtle;
        }

        private static TurtleSide side(int slot) {
            return slot == 0 ? TurtleSide.LEFT : TurtleSide.RIGHT;
        }

        @Override
        public int getContainerSize() {
            return 2;
        }

        @Override
        public boolean isEmpty() {
            return turtle.upgradeItem(TurtleSide.LEFT).isEmpty() && turtle.upgradeItem(TurtleSide.RIGHT).isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            return turtle.upgradeItem(side(slot));
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            var item = turtle.upgradeItem(side(slot)).copy();
            if (item.isEmpty() || amount <= 0) return ItemStack.EMPTY;
            turtle.setUpgradeItem(side(slot), ItemStack.EMPTY);
            return item;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return removeItem(slot, 1);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            turtle.setUpgradeItem(side(slot), stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void setChanged() {
        }

        @Override
        public boolean stillValid(Player player) {
            return !turtle.isRemoved();
        }

        @Override
        public void clearContent() {
            turtle.setUpgradeItem(TurtleSide.LEFT, ItemStack.EMPTY);
            turtle.setUpgradeItem(TurtleSide.RIGHT, ItemStack.EMPTY);
        }
    }
}
