package dev.ccbaremetal.turtle;

import dan200.computercraft.api.turtle.ITurtleUpgrade;
import dan200.computercraft.api.turtle.TurtleCommandResult;
import dan200.computercraft.api.turtle.TurtleSide;
import dan200.computercraft.api.turtle.TurtleVerb;
import dan200.computercraft.api.upgrades.UpgradeData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.List;

/**
 * What a turtle does in the world, done the way a player would (a NeoForge fake player owned by the turtle's owner,
 * so protection mods see the break and place events): digging and attacking with a tool upgrade's item, placing,
 * dropping into containers or the world, sucking items up, refuelling. Containers are found through NeoForge's item
 * handler capability, so every mod's inventories work.
 */
final class TurtleTools {
    private TurtleTools() {
    }

    private static FakePlayer player(WasmTurtle turtle, Direction dir, ItemStack held) {
        var level = (ServerLevel) turtle.getLevel();
        var player = FakePlayerFactory.get(level, turtle.getOwningPlayer());
        var pos = Vec3.atCenterOf(turtle.getPosition());
        float yaw = dir.getAxis() == Direction.Axis.Y ? turtle.getDirection().toYRot() : dir.toYRot();
        float pitch = dir == Direction.UP ? -90 : dir == Direction.DOWN ? 90 : 0;
        player.moveTo(pos.x, pos.y - player.getEyeHeight(), pos.z, yaw, pitch);
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        return player;
    }

    /** A tool upgrade CC's useTool refused (it needs CC's own turtle): dig or attack with its item. */
    static TurtleCommandResult use(WasmTurtle turtle, TurtleSide side, UpgradeData<ITurtleUpgrade> upgrade, TurtleVerb verb, Direction dir) {
        var tool = upgrade.getUpgradeItem();
        return verb == TurtleVerb.DIG ? dig(turtle, tool, dir) : attack(turtle, tool, dir);
    }

    private static TurtleCommandResult dig(WasmTurtle turtle, ItemStack tool, Direction dir) {
        var level = (ServerLevel) turtle.getLevel();
        var pos = turtle.getPosition().relative(dir);
        var state = level.getBlockState(pos);
        if (state.isAir() || state.liquid()) return TurtleCommandResult.failure("Nothing to dig here");
        var hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) return TurtleCommandResult.failure("Cannot break unbreakable block");
        var correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        if (!correct && hardness > 0) return TurtleCommandResult.failure("Cannot break block with this tool");
        var player = player(turtle, dir, tool.copy());
        try {
            if (NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, player)).isCanceled()) {
                return TurtleCommandResult.failure("Cannot break protected block");
            }
            var drops = correct ? Block.getDrops(state, level, pos, level.getBlockEntity(pos), player, tool) : List.<ItemStack>of();
            level.destroyBlock(pos, false, player);
            for (var drop : drops) store(turtle, drop);
            return TurtleCommandResult.success();
        } finally {
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    private static TurtleCommandResult attack(WasmTurtle turtle, ItemStack tool, Direction dir) {
        var level = (ServerLevel) turtle.getLevel();
        var pos = turtle.getPosition().relative(dir);
        var targets = level.getEntities((Entity) null, new AABB(pos),
            e -> e.isAlive() && e.isAttackable() && !e.isSpectator() && e instanceof LivingEntity);
        if (targets.isEmpty()) return TurtleCommandResult.failure("Nothing to attack here");
        var player = player(turtle, dir, tool.copy());
        try {
            // The tool's attack damage (the fake player's attributes do not follow its hand).
            var damage = new double[]{1};
            tool.getAttributeModifiers().forEach(EquipmentSlotGroup.MAINHAND, (attribute, modifier) -> {
                if (attribute.is(Attributes.ATTACK_DAMAGE)) damage[0] += modifier.amount();
            });
            var target = targets.get(0);
            if (!target.hurt(level.damageSources().playerAttack(player), (float) damage[0])) {
                return TurtleCommandResult.failure("Nothing to attack here");
            }
            return TurtleCommandResult.success();
        } finally {
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    /** Place the selected item in front / above / below, as a player clicking that spot. */
    static TurtleCommandResult place(WasmTurtle turtle, Direction dir) {
        var inventory = turtle.getInventory();
        var slot = turtle.getSelectedSlot();
        var stack = inventory.getItem(slot);
        if (stack.isEmpty()) return TurtleCommandResult.failure("No items to place");
        var level = (ServerLevel) turtle.getLevel();
        var pos = turtle.getPosition().relative(dir);
        var target = level.getBlockState(pos);
        if (!target.isAir() && !target.canBeReplaced()) return TurtleCommandResult.failure("Cannot place block here");
        var player = player(turtle, dir, stack);
        try {
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), dir.getOpposite(), pos, false);
            var result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            inventory.setItem(slot, player.getMainHandItem());
            return result.consumesAction() ? TurtleCommandResult.success() : TurtleCommandResult.failure("Cannot place block here");
        } finally {
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    /** Put items into the turtle, from the selected slot onwards; returns what did not fit. */
    static ItemStack store(WasmTurtle turtle, ItemStack stack, boolean simulate) {
        var inventory = turtle.getInventory();
        var remaining = stack.copy();
        var size = inventory.getContainerSize();
        for (var pass = 0; pass < 2 && !remaining.isEmpty(); pass++) {
            for (var k = 0; k < size && !remaining.isEmpty(); k++) {
                var slot = (turtle.getSelectedSlot() + k) % size;
                var current = inventory.getItem(slot);
                // First pass: merge into matching stacks; second: empty slots.
                if (pass == 0 && (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, remaining))) continue;
                if (pass == 1 && !current.isEmpty()) continue;
                var limit = Math.min(remaining.getMaxStackSize(), inventory.getMaxStackSize());
                var move = Math.min(remaining.getCount(), limit - current.getCount());
                if (move <= 0) continue;
                if (!simulate) {
                    if (current.isEmpty()) inventory.setItem(slot, remaining.copyWithCount(move));
                    else current.grow(move);
                }
                remaining.shrink(move);
            }
        }
        if (!simulate) inventory.setChanged();
        return remaining;
    }

    /** Store, dropping what does not fit at the turtle. */
    static void store(WasmTurtle turtle, ItemStack stack) {
        var left = store(turtle, stack, false);
        if (!left.isEmpty()) Block.popResource(turtle.getLevel(), turtle.getPosition(), left);
    }

    static TurtleCommandResult drop(WasmTurtle turtle, Direction dir, int count) {
        var inventory = turtle.getInventory();
        var stack = inventory.getItem(turtle.getSelectedSlot());
        if (stack.isEmpty()) return TurtleCommandResult.failure("No items to drop");
        var level = (ServerLevel) turtle.getLevel();
        var target = turtle.getPosition().relative(dir);
        var amount = Math.min(count, stack.getCount());
        var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, target, dir.getOpposite());
        if (handler != null) {
            var left = ItemHandlerHelper.insertItemStacked(handler, stack.copyWithCount(amount), false);
            var moved = amount - left.getCount();
            if (moved == 0) return TurtleCommandResult.failure("No space for items");
            stack.shrink(moved);
        } else {
            var centre = Vec3.atCenterOf(target);
            var entity = new ItemEntity(level, centre.x, centre.y, centre.z, stack.split(amount));
            entity.setDeltaMovement(dir.getStepX() * 0.1, dir.getStepY() * 0.1, dir.getStepZ() * 0.1);
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
        inventory.setChanged();
        return TurtleCommandResult.success();
    }

    static TurtleCommandResult suck(WasmTurtle turtle, Direction dir, int count) {
        var level = (ServerLevel) turtle.getLevel();
        var target = turtle.getPosition().relative(dir);
        var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, target, dir.getOpposite());
        if (handler != null) {
            for (var slot = 0; slot < handler.getSlots(); slot++) {
                var offered = handler.extractItem(slot, count, true);
                if (offered.isEmpty()) continue;
                var fits = offered.getCount() - store(turtle, offered, true).getCount();
                if (fits <= 0) continue;
                store(turtle, handler.extractItem(slot, fits, false), false);
                return TurtleCommandResult.success();
            }
            return TurtleCommandResult.failure("No items to take");
        }
        for (var item : level.getEntitiesOfClass(ItemEntity.class, new AABB(target))) {
            if (!item.isAlive()) continue;
            var stack = item.getItem();
            var take = stack.copyWithCount(Math.min(count, stack.getCount()));
            var fits = take.getCount() - store(turtle, take, true).getCount();
            if (fits <= 0) continue;
            store(turtle, stack.split(fits), false);
            if (stack.isEmpty()) item.discard();
            else item.setItem(stack);
            return TurtleCommandResult.success();
        }
        return TurtleCommandResult.failure("No items to take");
    }

    /** Burn {@code count} items of the selected slot (0: only check). Fuel per item as on CC: burn time / 20. */
    static TurtleCommandResult refuel(WasmTurtle turtle, int count) {
        var inventory = turtle.getInventory();
        var slot = turtle.getSelectedSlot();
        var stack = inventory.getItem(slot);
        if (stack.isEmpty()) return TurtleCommandResult.failure("No items to combust");
        var perItem = stack.getBurnTime(RecipeType.SMELTING) * 5 / 100;
        if (perItem <= 0) return TurtleCommandResult.failure("Items not combustible");
        if (count == 0) return TurtleCommandResult.success();
        var used = Math.min(count, stack.getCount());
        var remainder = stack.getCraftingRemainingItem();
        turtle.addFuel(perItem * used);
        stack.shrink(used);
        if (!remainder.isEmpty()) {
            remainder.setCount(used);
            if (stack.isEmpty()) inventory.setItem(slot, remainder);
            else store(turtle, remainder);
        }
        inventory.setChanged();
        return TurtleCommandResult.success();
    }

    /** Whether there is a solid thing (not air or a liquid) at a position. */
    static boolean solid(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return !state.isAir() && !state.liquid();
    }
}
