package dev.ccbaremetal.turtle;

import com.mojang.authlib.GameProfile;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dan200.computercraft.api.turtle.ITurtleAccess;
import dan200.computercraft.api.turtle.ITurtleUpgrade;
import dan200.computercraft.api.turtle.TurtleAnimation;
import dan200.computercraft.api.turtle.TurtleCommand;
import dan200.computercraft.api.turtle.TurtleCommandResult;
import dan200.computercraft.api.turtle.TurtleSide;
import dan200.computercraft.api.turtle.TurtleVerb;
import dan200.computercraft.api.upgrades.UpgradeData;
import dev.ccbaremetal.computer.ComputerCore;
import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A WASM turtle: the computer ({@link ComputerCore}), its inventory, fuel, direction and its two upgrades (left and
 * right). It outlives the block entity that holds it: when the turtle moves, a new block entity at the new position
 * takes it over and the machine keeps running.
 *
 * <p>It is CC's {@link ITurtleAccess}, so CC's turtle upgrades and those of any addon work on it: peripherals
 * ({@code createPeripheral}), tools ({@code useTool}) and their per-tick updates. Commands run on the server thread
 * one at a time, like CC's: movement and turns take {@link #MOVE_TICKS} ticks (the animation), tools and placing
 * {@link #SWING_TICKS}.
 */
public final class WasmTurtle implements ITurtleAccess, ComputerCore.Owner {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WasmTurtle.class);
    public static final int INVENTORY_SIZE = 16;
    public static final int MOVE_TICKS = 8;
    public static final int SWING_TICKS = 8;
    public static final GameProfile DEFAULT_PROFILE = new GameProfile(java.util.UUID.fromString("0d0c4ca0-4ff1-11e4-916c-0800200c9a66"), "[WASM turtle]");

    private WasmTurtleBlockEntity owner;
    private final ComputerCore core;
    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
    private int selected;
    private int fuel;
    private int colour = -1;
    private GameProfile owningPlayer;
    private boolean on;

    /** Upgrade items (what is saved), the upgrades they are, and their peripherals; index = TurtleSide ordinal. */
    private final ItemStack[] upgradeItems = {ItemStack.EMPTY, ItemStack.EMPTY};
    private final UpgradeData<ITurtleUpgrade>[] upgrades = newUpgradeArray();
    private final IPeripheral[] upgradePeripherals = new IPeripheral[2];
    private boolean upgradesResolved;
    private boolean peripheralsDirty = true;

    private record Pending(TurtleCommand command, CompletableFuture<TurtleCommandResult> result) {
    }

    private final ConcurrentLinkedQueue<Pending> commands = new ConcurrentLinkedQueue<>();
    private int busyTicks;
    private final TurtleApi api = new TurtleApi(this);

    @SuppressWarnings("unchecked")
    private static UpgradeData<ITurtleUpgrade>[] newUpgradeArray() {
        return new UpgradeData[2];
    }

    WasmTurtle(WasmTurtleBlockEntity owner) {
        this.owner = owner;
        core = new ComputerCore(this, -1, null, 80, 30);
        inventory.addListener(c -> {
            if (this.owner != null) this.owner.setChanged();
        });
    }

    // ---- State -----------------------------------------------------------------------------------------------------

    public ComputerCore core() {
        return core;
    }

    WasmTurtleBlockEntity owner() {
        return owner;
    }

    /** The block entity that holds the turtle now (it moved into a new one). */
    void setOwner(WasmTurtleBlockEntity owner) {
        this.owner = owner;
        core.setOwner(this);
    }

    public boolean isOnSaved() {
        return on;
    }

    void setOnSaved(boolean on) {
        this.on = on;
    }

    void setFuel(int fuel) {
        this.fuel = fuel;
    }

    void setOwningPlayer(GameProfile profile) {
        owningPlayer = profile;
    }

    public ItemStack upgradeItem(TurtleSide side) {
        return upgradeItems[side.ordinal()];
    }

    /** Install the upgrade this item is (or remove it: empty), e.g. from the module slots. */
    public void setUpgradeItem(TurtleSide side, ItemStack item) {
        var i = side.ordinal();
        if (ItemStack.isSameItemSameComponents(upgradeItems[i], item)) return;
        upgradeItems[i] = item.isEmpty() ? ItemStack.EMPTY : item.copyWithCount(1);
        resolve(side);
        changed(true);
    }

    /** Upgrade items were loaded (from the world or an item): resolved when the turtle has a world. */
    void loadUpgradeItems(ItemStack left, ItemStack right) {
        upgradeItems[0] = left.isEmpty() ? ItemStack.EMPTY : left.copyWithCount(1);
        upgradeItems[1] = right.isEmpty() ? ItemStack.EMPTY : right.copyWithCount(1);
        upgradesResolved = false;
    }

    private void resolve(TurtleSide side) {
        var i = side.ordinal();
        detachUpgradePeripheral(side);
        upgrades[i] = null;
        var level = getLevel();
        if (upgradeItems[i].isEmpty() || level == null) return;
        var holder = TurtleUpgrades.find(level.registryAccess(), upgradeItems[i]);
        if (holder == null) return;
        upgrades[i] = UpgradeData.of(holder, holder.value().getUpgradeData(upgradeItems[i]));
        if (holder.value().getUpgradeType().isPeripheral()) {
            try {
                upgradePeripherals[i] = holder.value().createPeripheral(this, side);
            } catch (RuntimeException e) {
                LOG.warn("Turtle upgrade {} failed to create its peripheral", holder.key().location(), e);
            }
        }
        peripheralsDirty = true;
    }

    private void detachUpgradePeripheral(TurtleSide side) {
        if (upgradePeripherals[side.ordinal()] != null) {
            upgradePeripherals[side.ordinal()] = null;
            peripheralsDirty = true;
        }
    }

    /** Something to save changed; with {@code sync}, also what clients draw (upgrades, direction). */
    private void changed(boolean sync) {
        if (owner == null) return;
        owner.setChanged();
        if (sync) owner.sync();
    }

    public void markPeripheralsDirty() {
        peripheralsDirty = true;
    }

    // ---- Ticking ---------------------------------------------------------------------------------------------------

    void tick() {
        var level = getLevel();
        if (level == null) return;
        if (!upgradesResolved) {
            upgradesResolved = true;
            for (var side : TurtleSide.values()) resolve(side);
            owner.sync();
        }
        if (core.machine() == null && on) core.turnOn();
        core.tick();
        var machine = core.machine();
        if (machine != null && machine.isOn() && peripheralsDirty) refreshPeripherals();
        for (var side : TurtleSide.values()) {
            var data = upgrades[side.ordinal()];
            if (data == null) continue;
            try {
                data.upgrade().update(this, side);
            } catch (RuntimeException e) {
                LOG.warn("Turtle upgrade {} failed to update", data.holder().key().location(), e);
            }
        }
        if (busyTicks > 0) {
            busyTicks--;
            return;
        }
        var next = commands.poll();
        if (next != null) next.result().complete(run(next.command()));
    }

    private TurtleCommandResult run(TurtleCommand command) {
        try {
            return command.execute(this);
        } catch (RuntimeException e) {
            LOG.warn("Turtle command failed", e);
            return TurtleCommandResult.failure("Java exception thrown: " + e);
        }
    }

    /** Peripherals around the turtle (sides relative to its direction); an upgrade's peripheral takes its side. */
    private void refreshPeripherals() {
        peripheralsDirty = false;
        var machine = core.machine();
        if (!(getLevel() instanceof ServerLevel level)) return;
        machine.peripherals().set("turtle", api);
        var pos = getPosition();
        for (var dir : Direction.values()) {
            var name = sideName(dir);
            IPeripheral found = null;
            if (name.equals("left")) found = upgradePeripherals[TurtleSide.LEFT.ordinal()];
            if (name.equals("right")) found = upgradePeripherals[TurtleSide.RIGHT.ordinal()];
            if (found == null) {
                var neighbour = pos.relative(dir);
                if (level.isLoaded(neighbour)) {
                    found = level.getCapability(PeripheralCapability.get(), neighbour, dir.getOpposite());
                    if (found == null) {
                        found = dan200.computercraft.impl.Peripherals.getGenericPeripheral(level, neighbour, dir.getOpposite(),
                            level.getBlockEntity(neighbour));
                    }
                }
            }
            machine.peripherals().set(name, found);
        }
    }

    /** CC side name of a world direction, as the turtle sees it (its front is where it faces). */
    private String sideName(Direction dir) {
        var front = getDirection();
        if (dir == front) return "front";
        if (dir == front.getOpposite()) return "back";
        if (dir == front.getCounterClockWise()) return "left";
        if (dir == front.getClockWise()) return "right";
        return dir == Direction.UP ? "top" : "bottom";
    }

    void stop() {
        core.stop();
        Pending p;
        while ((p = commands.poll()) != null) p.result().complete(TurtleCommandResult.failure("Turtle stopped"));
    }

    // ---- Commands ----------------------------------------------------------------------------------------------------

    /**
     * Run a command on the server thread (after the ones before it) and wait for its result. Called from the
     * program's thread; while waiting, a kill of the program still ends it.
     */
    TurtleCommandResult submit(TurtleCommand command) throws LuaException {
        var pending = new Pending(command, new CompletableFuture<>());
        commands.add(pending);
        var process = dev.ccbaremetal.kernel.proc.Process.CURRENT.get();
        while (true) {
            try {
                return pending.result().get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (process != null) process.checkKilled();
                if (owner == null || owner.isRemoved() && core.machine() == null) throw new LuaException("Turtle is gone");
            } catch (InterruptedException e) {
                if (process != null) process.checkKilled();
                throw new LuaException("Interrupted");
            } catch (java.util.concurrent.ExecutionException e) {
                throw new LuaException("Java exception thrown: " + e.getCause());
            }
        }
    }

    // ---- ITurtleAccess ------------------------------------------------------------------------------------------------

    @Override
    public Level getLevel() {
        return owner == null ? null : owner.getLevel();
    }

    @Override
    public BlockPos getPosition() {
        return owner == null ? BlockPos.ZERO : owner.getBlockPos();
    }

    @Override
    public boolean isRemoved() {
        return owner == null || owner.isRemoved();
    }

    /** Move the turtle into another block (empty or replaceable); the machine keeps running. */
    @Override
    public boolean teleportTo(Level world, BlockPos pos) {
        if (owner == null || world != getLevel() || !world.isLoaded(pos)) return false;
        var target = world.getBlockState(pos);
        if (!target.isAir() && !target.canBeReplaced()) return false;
        var old = owner;
        var oldPos = old.getBlockPos();
        var state = ModRegistry.WASM_TURTLE.get().defaultBlockState().setValue(WasmTurtleBlock.FACING, getDirection());
        if (!world.setBlock(pos, state, 3)) return false;
        if (!(world.getBlockEntity(pos) instanceof WasmTurtleBlockEntity next)) {
            world.removeBlock(pos, false);
            return false;
        }
        next.adopt(this);
        world.removeBlock(oldPos, false);
        peripheralsDirty = true;
        return true;
    }

    @Override
    public Direction getDirection() {
        return owner == null ? Direction.NORTH : owner.getBlockState().getValue(WasmTurtleBlock.FACING);
    }

    @Override
    public void setDirection(Direction dir) {
        if (owner == null || dir.getAxis() == Direction.Axis.Y || getLevel() == null) return;
        getLevel().setBlock(getPosition(), owner.getBlockState().setValue(WasmTurtleBlock.FACING, dir), 3);
        peripheralsDirty = true;
    }

    @Override
    public int getSelectedSlot() {
        return selected;
    }

    @Override
    public void setSelectedSlot(int slot) {
        if (slot < 0 || slot >= INVENTORY_SIZE) throw new IllegalArgumentException("Slot out of bounds");
        selected = slot;
        changed(false);
    }

    @Override
    public void setColour(int colour) {
        this.colour = colour;
    }

    @Override
    public int getColour() {
        return colour;
    }

    @Override
    public GameProfile getOwningPlayer() {
        return owningPlayer != null ? owningPlayer : DEFAULT_PROFILE;
    }

    @Override
    public Container getInventory() {
        return inventory;
    }

    @Override
    public boolean isFuelNeeded() {
        return dan200.computercraft.shared.config.Config.turtlesNeedFuel;
    }

    @Override
    public int getFuelLevel() {
        return Math.min(fuel, getFuelLimit());
    }

    @Override
    public void setFuelLevel(int level) {
        fuel = Math.max(0, Math.min(level, getFuelLimit()));
        changed(false);
    }

    @Override
    public int getFuelLimit() {
        return dan200.computercraft.shared.config.Config.advancedTurtleFuelLimit;
    }

    @Override
    public boolean consumeFuel(int amount) {
        if (!isFuelNeeded()) return true;
        if (fuel < amount) return false;
        setFuelLevel(fuel - amount);
        return true;
    }

    @Override
    public void addFuel(int amount) {
        setFuelLevel(fuel + amount);
    }

    @Override
    public MethodResult executeCommand(TurtleCommand command) {
        var result = getLevel() instanceof ServerLevel level && level.getServer().isSameThread() ? run(command) : submitUnchecked(command);
        return MethodResult.of(TurtleApi.toLua(result));
    }

    private TurtleCommandResult submitUnchecked(TurtleCommand command) {
        try {
            return submit(command);
        } catch (LuaException e) {
            return TurtleCommandResult.failure(e.getMessage());
        }
    }

    @Override
    public void playAnimation(TurtleAnimation animation) {
        switch (animation) {
            case MOVE_FORWARD, MOVE_BACK, MOVE_UP, MOVE_DOWN, TURN_LEFT, TURN_RIGHT -> busyTicks = MOVE_TICKS;
            case SWING_LEFT_TOOL, SWING_RIGHT_TOOL, WAIT -> busyTicks = SWING_TICKS;
            case SHORT_WAIT -> busyTicks = SWING_TICKS / 2;
            default -> {
            }
        }
        if (owner != null) owner.animate(animation);
    }

    @Override
    public ITurtleUpgrade getUpgrade(TurtleSide side) {
        var data = upgrades[side.ordinal()];
        return data == null ? null : data.upgrade();
    }

    @Override
    public UpgradeData<ITurtleUpgrade> getUpgradeWithData(TurtleSide side) {
        return upgrades[side.ordinal()];
    }

    @Override
    public void setUpgrade(TurtleSide side, UpgradeData<ITurtleUpgrade> upgrade) {
        setUpgradeItem(side, upgrade == null ? ItemStack.EMPTY : upgrade.getUpgradeItem());
    }

    @Override
    public IPeripheral getPeripheral(TurtleSide side) {
        return upgradePeripherals[side.ordinal()];
    }

    @Override
    public DataComponentPatch getUpgradeData(TurtleSide side) {
        var data = upgrades[side.ordinal()];
        return data == null ? DataComponentPatch.EMPTY : data.data();
    }

    @Override
    public void setUpgradeData(TurtleSide side, DataComponentPatch patch) {
        var i = side.ordinal();
        var data = upgrades[i];
        if (data == null || patch.equals(data.data())) return;
        upgrades[i] = UpgradeData.of(data.holder(), patch);
        upgradeItems[i] = upgrades[i].getUpgradeItem();
        changed(true);
    }

    // ---- ComputerCore.Owner ---------------------------------------------------------------------------------------

    @Override
    public ServerLevel level() {
        return getLevel() instanceof ServerLevel level ? level : null;
    }

    @Override
    public BlockPos position() {
        return getPosition();
    }

    @Override
    public void onIdAssigned(int id) {
        changed(false);
    }

    @Override
    public void onLabelChanged(String label) {
        changed(false);
    }

    @Override
    public void onStateChanged(boolean running) {
        on = running;
        changed(false);
    }

    @Override
    public void onBooted() {
        peripheralsDirty = true;
    }

    @Override
    public void onTermResized() {
        changed(false);
    }

    // ---- Tool use ------------------------------------------------------------------------------------------------------

    /**
     * Dig or attack with a tool upgrade ({@code side} null: left, then right). The upgrade's own useTool first; CC's
     * tools (and addon tools built with CC's tool builder) need CC's internal turtle and refuse ours, so then the
     * turtle uses the upgrade's tool item itself ({@link TurtleTools}).
     */
    TurtleCommandResult useTool(TurtleVerb verb, Direction dir, TurtleSide side) {
        TurtleSide[] sides = side == null ? TurtleSide.values() : new TurtleSide[]{side};
        TurtleCommandResult last = null;
        for (var s : sides) {
            var data = upgrades[s.ordinal()];
            if (data == null || !data.upgrade().getUpgradeType().isTool()) continue;
            TurtleCommandResult result;
            try {
                result = data.upgrade().useTool(this, s, verb, dir);
            } catch (IllegalStateException e) {
                result = TurtleTools.use(this, s, data, verb, dir);
            }
            if (result.isSuccess()) {
                playAnimation(s == TurtleSide.LEFT ? TurtleAnimation.SWING_LEFT_TOOL : TurtleAnimation.SWING_RIGHT_TOOL);
                return result;
            }
            last = result;
        }
        if (last != null) return last;
        return TurtleCommandResult.failure(verb == TurtleVerb.DIG ? "No tool to dig with" : "No tool to attack with");
    }
}
