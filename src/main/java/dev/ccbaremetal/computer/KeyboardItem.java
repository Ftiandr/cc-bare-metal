package dev.ccbaremetal.computer;

import dev.ccbaremetal.registry.ModRegistry;
import dev.ccbaremetal.turtle.WasmTurtleBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * The keyboard: right-click a WASM computer or turtle to link it and start typing; later right-click anywhere within
 * {@link KeyboardMenu#RANGE} blocks of it, or place the linked keyboard (on a desk) and right-click the block.
 */
public final class KeyboardItem extends BlockItem {
    public KeyboardItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        var be = context.getLevel().getBlockEntity(context.getClickedPos());
        ComputerCore core = be instanceof WasmComputerBlockEntity c ? c.core() : be instanceof WasmTurtleBlockEntity t && t.turtle() != null ? t.turtle().core() : null;
        var stack = context.getItemInHand();
        if (core == null) {
            // placing it: only once it knows its computer
            if (stack.get(ModRegistry.COMPUTER_ID.get()) != null) return super.useOn(context);
            if (context.getPlayer() instanceof ServerPlayer player) {
                player.displayClientMessage(Component.translatable("message.ccbaremetal.keyboard_unlinked"), true);
            }
            return InteractionResult.FAIL;
        }
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.SUCCESS;
        if (!core.isOn() || core.computerId() < 0) {
            player.displayClientMessage(Component.translatable("message.ccbaremetal.keyboard_off"), true);
            return InteractionResult.CONSUME;
        }
        if (!Integer.valueOf(core.computerId()).equals(stack.get(ModRegistry.COMPUTER_ID.get()))) {
            stack.set(ModRegistry.COMPUTER_ID.get(), core.computerId());
            player.displayClientMessage(Component.translatable("message.ccbaremetal.keyboard_linked", core.computerId()), true);
        }
        KeyboardMenu.open(player, core, KeyboardMenu.holding(core));
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (!(player instanceof ServerPlayer server) || !(level instanceof ServerLevel serverLevel)) return InteractionResultHolder.success(stack);
        var id = stack.get(ModRegistry.COMPUTER_ID.get());
        if (id == null) {
            server.displayClientMessage(Component.translatable("message.ccbaremetal.keyboard_unlinked"), true);
            return InteractionResultHolder.fail(stack);
        }
        var core = ComputerCore.find(id, serverLevel);
        if (core == null || player.distanceToSqr(core.position().getCenter()) > KeyboardMenu.RANGE * KeyboardMenu.RANGE) {
            server.displayClientMessage(Component.translatable("message.ccbaremetal.keyboard_far", id), true);
            return InteractionResultHolder.fail(stack);
        }
        KeyboardMenu.open(server, core, KeyboardMenu.holding(core));
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        var id = stack.get(ModRegistry.COMPUTER_ID.get());
        if (id == null) {
            tooltip.add(Component.translatable("item.ccbaremetal.keyboard.unlinked"));
            return;
        }
        tooltip.add(Component.translatable("item.ccbaremetal.keyboard.linked", id));
        tooltip.add(Component.translatable("item.ccbaremetal.keyboard.place"));
    }
}
