package dev.ccbaremetal.turtle;

import dan200.computercraft.api.detail.BlockReference;
import dan200.computercraft.api.detail.VanillaDetailRegistries;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.turtle.TurtleAnimation;
import dan200.computercraft.api.turtle.TurtleCommandResult;
import dan200.computercraft.api.turtle.TurtleSide;
import dan200.computercraft.api.turtle.TurtleVerb;
import dev.ccbaremetal.kernel.periph.HostApi;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * The turtle API, with CC's names, arguments and results ({@code turtle.forward()} gives {@code true} or
 * {@code false, "reason"}), so CC turtle programs run unchanged. It is the peripheral "turtle" of the turtle's
 * machine: the global {@code turtle} in CraftOS, /dev/periph/turtle and {@code cc::Peripheral("turtle")} for WASM
 * programs. Every action waits for its turn on the server, like CC's.
 */
public final class TurtleApi implements IPeripheral, HostApi {
    private final WasmTurtle turtle;

    TurtleApi(WasmTurtle turtle) {
        this.turtle = turtle;
    }

    @Override
    public String getType() {
        return "turtle";
    }

    @Override
    public boolean equals(IPeripheral other) {
        return other == this;
    }

    static Object[] toLua(TurtleCommandResult result) {
        if (!result.isSuccess()) return new Object[]{false, result.getErrorMessage()};
        var extra = result.getResults();
        if (extra == null || extra.length == 0) return new Object[]{true};
        var out = new Object[extra.length + 1];
        out[0] = true;
        System.arraycopy(extra, 0, out, 1, extra.length);
        return out;
    }

    /** An action of this turtle, run as a turtle command (on the server, in turn). */
    @FunctionalInterface
    private interface Action {
        TurtleCommandResult run(WasmTurtle turtle);
    }

    private TurtleCommandResult submit(Action action) throws LuaException {
        return turtle.submit(access -> action.run(turtle));
    }

    private Object[] run(Action action) throws LuaException {
        return toLua(submit(action));
    }

    /** A command whose answer is a single value (counts). */
    private Object value(Action action) throws LuaException {
        var result = submit(action);
        if (!result.isSuccess()) return false;
        var values = result.getResults();
        return values == null || values.length == 0 ? true : values[0];
    }

    private static int slot(int luaSlot) throws LuaException {
        if (luaSlot < 1 || luaSlot > WasmTurtle.INVENTORY_SIZE) throw new LuaException("Slot out of range (expected between 1 and 16)");
        return luaSlot - 1;
    }

    private static TurtleSide side(Optional<String> side) throws LuaException {
        if (side.isEmpty()) return null;
        return switch (side.get()) {
            case "left" -> TurtleSide.LEFT;
            case "right" -> TurtleSide.RIGHT;
            default -> throw new LuaException("Invalid side");
        };
    }

    private static int count(Optional<Integer> count) throws LuaException {
        var n = count.orElse(64);
        if (n < 0 || n > 64) throw new LuaException("Item count " + n + " out of range");
        return n;
    }

    // ---- Movement --------------------------------------------------------------------------------------------------

    private static TurtleCommandResult move(WasmTurtle t, Direction dir, TurtleAnimation animation) {
        var level = (ServerLevel) t.getLevel();
        var to = t.getPosition().relative(dir);
        if (level.isOutsideBuildHeight(to)) return TurtleCommandResult.failure(dir == Direction.UP ? "Too high to move" : "Too low to move");
        if (!level.isLoaded(to)) return TurtleCommandResult.failure("Cannot leave loaded world");
        var state = level.getBlockState(to);
        if (!state.isAir() && !state.canBeReplaced()) return TurtleCommandResult.failure("Movement obstructed");
        if (!level.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(to),
            e -> e.isAlive() && !e.isSpectator() && e.blocksBuilding).isEmpty()) {
            return TurtleCommandResult.failure("Movement obstructed");
        }
        if (t.isFuelNeeded() && t.getFuelLevel() < 1) return TurtleCommandResult.failure("Out of fuel");
        if (!t.teleportTo(level, to)) return TurtleCommandResult.failure("Movement obstructed");
        t.consumeFuel(1);
        t.playAnimation(animation);
        return TurtleCommandResult.success();
    }

    @LuaFunction
    public final Object[] forward() throws LuaException {
        return run(t -> move(t, t.getDirection(), TurtleAnimation.MOVE_FORWARD));
    }

    @LuaFunction
    public final Object[] back() throws LuaException {
        return run(t -> move(t, t.getDirection().getOpposite(), TurtleAnimation.MOVE_BACK));
    }

    @LuaFunction
    public final Object[] up() throws LuaException {
        return run(t -> move(t, Direction.UP, TurtleAnimation.MOVE_UP));
    }

    @LuaFunction
    public final Object[] down() throws LuaException {
        return run(t -> move(t, Direction.DOWN, TurtleAnimation.MOVE_DOWN));
    }

    @LuaFunction
    public final Object[] turnLeft() throws LuaException {
        return run(t -> {
            t.setDirection(t.getDirection().getCounterClockWise());
            t.playAnimation(TurtleAnimation.TURN_LEFT);
            return TurtleCommandResult.success();
        });
    }

    @LuaFunction
    public final Object[] turnRight() throws LuaException {
        return run(t -> {
            t.setDirection(t.getDirection().getClockWise());
            t.playAnimation(TurtleAnimation.TURN_RIGHT);
            return TurtleCommandResult.success();
        });
    }

    // ---- Tools -----------------------------------------------------------------------------------------------------

    @LuaFunction
    public final Object[] dig(Optional<String> side) throws LuaException {
        var s = side(side);
        return run(t -> t.useTool(TurtleVerb.DIG, t.getDirection(), s));
    }

    @LuaFunction
    public final Object[] digUp(Optional<String> side) throws LuaException {
        var s = side(side);
        return run(t -> t.useTool(TurtleVerb.DIG, Direction.UP, s));
    }

    @LuaFunction
    public final Object[] digDown(Optional<String> side) throws LuaException {
        var s = side(side);
        return run(t -> t.useTool(TurtleVerb.DIG, Direction.DOWN, s));
    }

    @LuaFunction
    public final Object[] attack(Optional<String> side) throws LuaException {
        var s = side(side);
        return run(t -> t.useTool(TurtleVerb.ATTACK, t.getDirection(), s));
    }

    @LuaFunction
    public final Object[] attackUp(Optional<String> side) throws LuaException {
        var s = side(side);
        return run(t -> t.useTool(TurtleVerb.ATTACK, Direction.UP, s));
    }

    @LuaFunction
    public final Object[] attackDown(Optional<String> side) throws LuaException {
        var s = side(side);
        return run(t -> t.useTool(TurtleVerb.ATTACK, Direction.DOWN, s));
    }

    private static TurtleCommandResult placed(WasmTurtle t, Direction dir) {
        var result = TurtleTools.place(t, dir);
        if (result.isSuccess()) t.playAnimation(TurtleAnimation.WAIT);
        return result;
    }

    @LuaFunction
    public final Object[] place(Optional<String> text) throws LuaException {
        return run(t -> placed(t, t.getDirection()));
    }

    @LuaFunction
    public final Object[] placeUp(Optional<String> text) throws LuaException {
        return run(t -> placed(t, Direction.UP));
    }

    @LuaFunction
    public final Object[] placeDown(Optional<String> text) throws LuaException {
        return run(t -> placed(t, Direction.DOWN));
    }

    // ---- Looking ---------------------------------------------------------------------------------------------------

    private static TurtleCommandResult detect(WasmTurtle t, Direction dir) {
        return TurtleTools.solid((ServerLevel) t.getLevel(), t.getPosition().relative(dir))
            ? TurtleCommandResult.success() : TurtleCommandResult.failure();
    }

    @LuaFunction
    public final boolean detect() throws LuaException {
        return submit(t -> detect(t, t.getDirection())).isSuccess();
    }

    @LuaFunction
    public final boolean detectUp() throws LuaException {
        return submit(t -> detect(t, Direction.UP)).isSuccess();
    }

    @LuaFunction
    public final boolean detectDown() throws LuaException {
        return submit(t -> detect(t, Direction.DOWN)).isSuccess();
    }

    private static TurtleCommandResult inspect(WasmTurtle t, Direction dir) {
        var level = t.getLevel();
        var pos = t.getPosition().relative(dir);
        if (level.getBlockState(pos).isAir()) return TurtleCommandResult.failure("No block to inspect");
        return TurtleCommandResult.success(new Object[]{VanillaDetailRegistries.BLOCK_IN_WORLD.getDetails(new BlockReference(level, pos))});
    }

    @LuaFunction
    public final Object[] inspect() throws LuaException {
        return run(t -> inspect(t, t.getDirection()));
    }

    @LuaFunction
    public final Object[] inspectUp() throws LuaException {
        return run(t -> inspect(t, Direction.UP));
    }

    @LuaFunction
    public final Object[] inspectDown() throws LuaException {
        return run(t -> inspect(t, Direction.DOWN));
    }

    private static TurtleCommandResult compare(WasmTurtle t, Direction dir) {
        var stack = t.getInventory().getItem(t.getSelectedSlot());
        var state = t.getLevel().getBlockState(t.getPosition().relative(dir));
        boolean same;
        if (stack.isEmpty()) same = state.isAir();
        else same = stack.getItem() instanceof BlockItem block && block.getBlock() == state.getBlock();
        return same ? TurtleCommandResult.success() : TurtleCommandResult.failure();
    }

    @LuaFunction
    public final boolean compare() throws LuaException {
        return submit(t -> compare(t, t.getDirection())).isSuccess();
    }

    @LuaFunction
    public final boolean compareUp() throws LuaException {
        return submit(t -> compare(t, Direction.UP)).isSuccess();
    }

    @LuaFunction
    public final boolean compareDown() throws LuaException {
        return submit(t -> compare(t, Direction.DOWN)).isSuccess();
    }

    // ---- Items -----------------------------------------------------------------------------------------------------

    @LuaFunction
    public final Object[] drop(Optional<Integer> count) throws LuaException {
        var n = count(count);
        return run(t -> TurtleTools.drop(t, t.getDirection(), n));
    }

    @LuaFunction
    public final Object[] dropUp(Optional<Integer> count) throws LuaException {
        var n = count(count);
        return run(t -> TurtleTools.drop(t, Direction.UP, n));
    }

    @LuaFunction
    public final Object[] dropDown(Optional<Integer> count) throws LuaException {
        var n = count(count);
        return run(t -> TurtleTools.drop(t, Direction.DOWN, n));
    }

    @LuaFunction
    public final Object[] suck(Optional<Integer> count) throws LuaException {
        var n = count(count);
        return run(t -> TurtleTools.suck(t, t.getDirection(), n));
    }

    @LuaFunction
    public final Object[] suckUp(Optional<Integer> count) throws LuaException {
        var n = count(count);
        return run(t -> TurtleTools.suck(t, Direction.UP, n));
    }

    @LuaFunction
    public final Object[] suckDown(Optional<Integer> count) throws LuaException {
        var n = count(count);
        return run(t -> TurtleTools.suck(t, Direction.DOWN, n));
    }

    @LuaFunction
    public final Object[] select(int luaSlot) throws LuaException {
        var s = slot(luaSlot);
        return run(t -> {
            t.setSelectedSlot(s);
            return TurtleCommandResult.success();
        });
    }

    @LuaFunction
    public final int getSelectedSlot() {
        return turtle.getSelectedSlot() + 1;
    }

    @LuaFunction
    public final int getItemCount(Optional<Integer> luaSlot) throws LuaException {
        var s = luaSlot.isPresent() ? slot(luaSlot.get()) : -1;
        return (int) value(t -> TurtleCommandResult.success(new Object[]{t.getInventory().getItem(s < 0 ? t.getSelectedSlot() : s).getCount()}));
    }

    @LuaFunction
    public final int getItemSpace(Optional<Integer> luaSlot) throws LuaException {
        var s = luaSlot.isPresent() ? slot(luaSlot.get()) : -1;
        return (int) value(t -> {
            var stack = t.getInventory().getItem(s < 0 ? t.getSelectedSlot() : s);
            return TurtleCommandResult.success(new Object[]{stack.isEmpty() ? 64 : stack.getMaxStackSize() - stack.getCount()});
        });
    }

    @LuaFunction
    public final Object[] getItemDetail(Optional<Integer> luaSlot, Optional<Boolean> detailed) throws LuaException {
        var s = luaSlot.isPresent() ? slot(luaSlot.get()) : -1;
        var full = detailed.orElse(false);
        var result = submit(t -> {
            var stack = t.getInventory().getItem(s < 0 ? t.getSelectedSlot() : s).copy();
            if (stack.isEmpty()) return TurtleCommandResult.success(new Object[]{null});
            return TurtleCommandResult.success(new Object[]{full
                ? VanillaDetailRegistries.ITEM_STACK.getDetails(stack) : VanillaDetailRegistries.ITEM_STACK.getBasicDetails(stack)});
        });
        var values = result.getResults();
        return values == null || values.length == 0 || values[0] == null ? new Object[]{null} : values;
    }

    @LuaFunction
    public final Object[] transferTo(int luaSlot, Optional<Integer> count) throws LuaException {
        var to = slot(luaSlot);
        var n = count(count);
        return run(t -> {
            var inventory = t.getInventory();
            var from = t.getSelectedSlot();
            var stack = inventory.getItem(from);
            if (stack.isEmpty() || from == to || n == 0) return TurtleCommandResult.success();
            var target = inventory.getItem(to);
            if (!target.isEmpty() && !ItemStack.isSameItemSameComponents(stack, target)) return TurtleCommandResult.failure("No space for items");
            var move = Math.min(Math.min(n, stack.getCount()), stack.getMaxStackSize() - target.getCount());
            if (move <= 0) return TurtleCommandResult.failure("No space for items");
            if (target.isEmpty()) inventory.setItem(to, stack.copyWithCount(move));
            else target.grow(move);
            stack.shrink(move);
            inventory.setChanged();
            return TurtleCommandResult.success();
        });
    }

    @LuaFunction
    public final boolean compareTo(int luaSlot) throws LuaException {
        var other = slot(luaSlot);
        return submit(t -> ItemStack.isSameItemSameComponents(t.getInventory().getItem(t.getSelectedSlot()), t.getInventory().getItem(other))
            ? TurtleCommandResult.success() : TurtleCommandResult.failure()).isSuccess();
    }

    // ---- Fuel ------------------------------------------------------------------------------------------------------

    @LuaFunction
    public final Object getFuelLevel() {
        return turtle.isFuelNeeded() ? (Object) turtle.getFuelLevel() : "unlimited";
    }

    @LuaFunction
    public final Object getFuelLimit() {
        return turtle.isFuelNeeded() ? (Object) turtle.getFuelLimit() : "unlimited";
    }

    @LuaFunction
    public final Object[] refuel(Optional<Integer> count) throws LuaException {
        var n = count(count);
        return run(t -> TurtleTools.refuel(t, n));
    }

    // ---- Upgrades --------------------------------------------------------------------------------------------------

    private static TurtleCommandResult equip(WasmTurtle t, TurtleSide side) {
        var inventory = t.getInventory();
        var slot = t.getSelectedSlot();
        var stack = inventory.getItem(slot);
        if (!stack.isEmpty() && TurtleUpgrades.find(t.getLevel().registryAccess(), stack) == null) {
            return TurtleCommandResult.failure("Not a valid upgrade");
        }
        var old = t.upgradeItem(side).copy();
        var fresh = stack.isEmpty() ? ItemStack.EMPTY : stack.split(1);
        t.setUpgradeItem(side, fresh);
        inventory.setChanged();
        if (!old.isEmpty()) TurtleTools.store(t, old);
        return TurtleCommandResult.success();
    }

    @LuaFunction
    public final Object[] equipLeft() throws LuaException {
        return run(t -> equip(t, TurtleSide.LEFT));
    }

    @LuaFunction
    public final Object[] equipRight() throws LuaException {
        return run(t -> equip(t, TurtleSide.RIGHT));
    }

    private Object[] equipped(TurtleSide side) throws LuaException {
        var result = submit(t -> {
            var item = t.upgradeItem(side);
            return TurtleCommandResult.success(new Object[]{item.isEmpty() ? null : VanillaDetailRegistries.ITEM_STACK.getBasicDetails(item.copy())});
        });
        var values = result.getResults();
        return values == null ? new Object[]{null} : values;
    }

    @LuaFunction
    public final Object[] getEquippedLeft() throws LuaException {
        return equipped(TurtleSide.LEFT);
    }

    @LuaFunction
    public final Object[] getEquippedRight() throws LuaException {
        return equipped(TurtleSide.RIGHT);
    }
}
