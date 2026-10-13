package dev.ccbaremetal.computer;

import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * The menu behind the computer screen. Server side, it registers the player as a viewer of the computer's
 * {@link ComputerCore}; it stays open while {@code valid} holds (near the block, pocket computer still held...).
 */
public class ComputerMenu extends AbstractContainerMenu {
    private final int computerId;
    private final ComputerCore core;
    private final ServerPlayer player;
    private final Predicate<Player> valid;
    /** Server side: how much of the terminal history this viewer already has. */
    long historySent = -1;
    int historyEpochSent = -1;

    /** Client constructor (the computer block's menu). */
    public ComputerMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(ModRegistry.COMPUTER_MENU.get(), containerId, buf.readVarInt());
    }

    /** Client constructor. */
    protected ComputerMenu(MenuType<?> type, int containerId, int computerId) {
        super(type, containerId);
        this.computerId = computerId;
        core = null;
        player = null;
        valid = p -> true;
    }

    /** Server constructor. */
    public ComputerMenu(MenuType<?> type, int containerId, ComputerCore core, ServerPlayer player, Predicate<Player> valid) {
        super(type, containerId);
        this.computerId = core.computerId();
        this.core = core;
        this.player = player;
        this.valid = valid;
        core.addViewer(this);
    }

    public int computerId() {
        return computerId;
    }

    /** The computer (server side only). */
    public ComputerCore core() {
        return core;
    }

    public ServerPlayer player() {
        return player;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return valid.test(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (core != null) core.removeViewer(this);
    }
}
