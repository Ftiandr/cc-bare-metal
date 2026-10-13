package dev.ccbaremetal.pocket;

import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The WASM pocket computer. It runs while it is in a player's inventory. Right click opens it; with an upgrade in
 * the other hand, Shift + right click installs that upgrade into the first free module slot.
 */
public class PocketComputerItem extends Item {
    public PocketComputerItem(Properties properties) {
        super(properties);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level instanceof ServerLevel serverLevel) PocketCores.update(serverLevel, stack, entity);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        var other = player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        if (player.isShiftKeyDown() && !other.isEmpty()) {
            install(serverLevel, serverPlayer, stack, other);
            return InteractionResultHolder.consume(stack);
        }
        var pocket = PocketCores.get(serverLevel, stack);
        pocket.core().ensureMachine();
        var pocketSlot = hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : Inventory.SLOT_OFFHAND;
        var title = stack.getHoverName();
        serverPlayer.openMenu(new SimpleMenuProvider((id, inventory, p) -> new PocketComputerMenu(id, inventory, pocket, serverPlayer, pocketSlot), title),
            buf -> {
                buf.writeVarInt(pocket.core().computerId());
                buf.writeVarInt(pocketSlot);
            });
        return InteractionResultHolder.consume(stack);
    }

    /** Shift + right click with an upgrade in the other hand: into the first free slot, unless it is installed already. */
    private static void install(ServerLevel level, ServerPlayer player, ItemStack pocket, ItemStack upgrade) {
        var holder = PocketUpgrades.find(level.registryAccess(), upgrade);
        if (holder == null) {
            player.displayClientMessage(Component.translatable("message.ccbaremetal.not_a_module", upgrade.getHoverName()), true);
            return;
        }
        var items = slots(pocket);
        var free = -1;
        for (var i = 0; i < items.size(); i++) {
            var installed = PocketUpgrades.find(level.registryAccess(), items.get(i));
            if (installed != null && installed.key().equals(holder.key())) {
                player.displayClientMessage(Component.translatable("message.ccbaremetal.module_installed_already", upgrade.getHoverName()), true);
                return;
            }
            if (items.get(i).isEmpty() && free < 0) free = i;
        }
        if (free < 0) {
            player.displayClientMessage(Component.translatable("message.ccbaremetal.no_free_slot"), true);
            return;
        }
        items.set(free, upgrade.split(1));
        pocket.set(ModRegistry.POCKET_UPGRADES.get(), ItemContainerContents.fromItems(items));
        player.displayClientMessage(Component.translatable("message.ccbaremetal.module_added", items.get(free).getHoverName(), PocketCore.slotName(free)), true);
    }

    /** The module slots of a pocket computer item. */
    public static NonNullList<ItemStack> slots(ItemStack pocket) {
        var items = NonNullList.withSize(PocketCore.SLOTS, ItemStack.EMPTY);
        pocket.getOrDefault(ModRegistry.POCKET_UPGRADES.get(), ItemContainerContents.EMPTY).copyInto(items);
        return items;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        var items = slots(stack);
        if (items.stream().anyMatch(s -> !s.isEmpty())) {
            tooltip.add(Component.translatable("tooltip.ccbaremetal.modules").withStyle(ChatFormatting.GRAY));
            for (var i = 0; i < items.size(); i++) {
                if (!items.get(i).isEmpty()) {
                    tooltip.add(Component.literal("  " + PocketCore.slotName(i) + ": ").withStyle(ChatFormatting.DARK_GRAY)
                        .append(items.get(i).getHoverName().copy().withStyle(ChatFormatting.GRAY)));
                }
            }
        } else {
            tooltip.add(Component.translatable("tooltip.ccbaremetal.no_modules").withStyle(ChatFormatting.DARK_GRAY));
        }
        var id = stack.get(ModRegistry.COMPUTER_ID.get());
        if (flag.isAdvanced() && id != null) tooltip.add(Component.translatable("tooltip.ccbaremetal.computer_id", id).withStyle(ChatFormatting.DARK_GRAY));
    }
}
