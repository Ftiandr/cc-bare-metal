package dev.ccbaremetal.computer;

import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;

/** A computer block: holds a {@link ComputerCore} and connects it to the world (peripherals on its sides, redstone). */
public class WasmComputerBlockEntity extends BlockEntity implements ComputerCore.Owner, MenuProvider {
    private boolean on;

    private final ComputerCore core = new ComputerCore(this, -1, null, 80, 30);
    private final ComputerPeripheral peripheral = new ComputerPeripheral(this);
    @SuppressWarnings("unchecked")
    private final BlockCapabilityCache<IPeripheral, Direction>[] caches = new BlockCapabilityCache[6];
    private volatile boolean peripheralsDirty = true;
    private final int[] redstone = new int[6];

    public WasmComputerBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.WASM_COMPUTER_BE.get(), pos, state);
    }

    // ---- Accessors -------------------------------------------------------------------------------------------------

    public ComputerCore core() {
        return core;
    }

    public int computerId() {
        return core.computerId();
    }

    /** The power state that is saved with the block (and restored on load). */
    public boolean isOnSaved() {
        return on;
    }

    public String label() {
        return core.label();
    }

    public boolean isOn() {
        return core.isOn();
    }

    public Machine machine() {
        return core.machine();
    }

    public IPeripheral peripheral() {
        return peripheral;
    }

    private Direction facing() {
        return getBlockState().getValue(WasmComputerBlock.FACING);
    }

    /**
     * CC side name of a world direction. Left and right are as seen by a player looking at the screen, exactly like
     * CC: Tweaked's computers (its ComputerBlockEntity swaps them after DirectionUtil.toLocal).
     */
    private String sideName(Direction dir) {
        var front = facing();
        if (dir == front) return "front";
        if (dir == front.getOpposite()) return "back";
        if (dir == front.getCounterClockWise()) return "right";
        if (dir == front.getClockWise()) return "left";
        return dir == Direction.UP ? "top" : "bottom";
    }

    public void turnOn() {
        core.turnOn();
        peripheralsDirty = true;
    }

    public void shutdown() {
        core.shutdown();
    }

    public void reboot() {
        core.reboot();
    }

    public static void shutdownAll() {
        ComputerCore.shutdownAll();
    }

    // ---- ComputerCore.Owner ---------------------------------------------------------------------------------------

    @Override
    public ServerLevel level() {
        return getLevel() instanceof ServerLevel server ? server : null;
    }

    @Override
    public BlockPos position() {
        return getBlockPos();
    }

    @Override
    public void onIdAssigned(int id) {
        setChanged();
    }

    @Override
    public void onLabelChanged(String newLabel) {
        setChanged();
    }

    @Override
    public void onBooted() {
        peripheralsDirty = true;
    }

    @Override
    public void onTermResized() {
        setChanged();
    }

    @Override
    public void onRedstoneOutputChanged() {
        if (level == null || isRemoved()) return;
        var block = getBlockState().getBlock();
        level.updateNeighborsAt(worldPosition, block);
        for (var dir : Direction.values()) level.updateNeighborsAtExceptFromFacing(worldPosition.relative(dir), block, dir.getOpposite());
    }

    @Override
    public void onStateChanged(boolean running) {
        if (isRemoved() || level == null) return;
        on = running;
        var state = getBlockState();
        if (state.hasProperty(WasmComputerBlock.ON) && state.getValue(WasmComputerBlock.ON) != running) {
            level.setBlock(worldPosition, state.setValue(WasmComputerBlock.ON, running), 3);
        }
        setChanged();
    }

    public int redstoneOutput(Direction side) {
        var machine = core.machine();
        if (machine == null) return 0;
        return machine.redstoneOutput(Machine.SIDES.indexOf(sideName(side)));
    }

    // ---- Ticking ---------------------------------------------------------------------------------------------------

    public void markPeripheralsDirty() {
        peripheralsDirty = true;
    }

    public void serverTick() {
        if (core.machine() == null && on) turnOn();
        core.tick();
        var machine = core.machine();
        if (machine != null && machine.isOn()) {
            if (peripheralsDirty) refreshPeripherals(machine);
            updateRedstone(machine);
        }
    }

    private void refreshPeripherals(Machine machine) {
        peripheralsDirty = false;
        if (!(level instanceof ServerLevel serverLevel)) return;
        for (var dir : Direction.values()) {
            var cache = caches[dir.ordinal()];
            if (cache == null) {
                cache = caches[dir.ordinal()] = BlockCapabilityCache.create(PeripheralCapability.get(), serverLevel,
                    worldPosition.relative(dir), dir.getOpposite(), () -> !isRemoved(), () -> peripheralsDirty = true);
            }
            var found = cache.getCapability();
            if (found == null) {
                var neighbour = worldPosition.relative(dir);
                found = dan200.computercraft.impl.Peripherals.getGenericPeripheral(serverLevel, neighbour, dir.getOpposite(),
                    serverLevel.getBlockEntity(neighbour));
            }
            machine.peripherals().set(sideName(dir), found);
        }
    }

    private void updateRedstone(Machine machine) {
        if (level == null) return;
        var levels = new int[6];
        for (var dir : Direction.values()) {
            var index = Machine.SIDES.indexOf(sideName(dir));
            levels[index] = level.getSignal(worldPosition.relative(dir), dir);
        }
        if (!java.util.Arrays.equals(levels, redstone)) {
            System.arraycopy(levels, 0, redstone, 0, 6);
            machine.updateRedstoneInputs(levels);
        }
    }

    // ---- Screens ---------------------------------------------------------------------------------------------------

    public void openScreen(ServerPlayer player) {
        core.ensureMachine();
        player.openMenu(this, buf -> buf.writeVarInt(core.computerId()));
    }

    @Override
    public Component getDisplayName() {
        return core.label() != null ? Component.literal(core.label()) : Component.translatable("block.ccbaremetal.wasm_computer");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        var pos = worldPosition;
        return new ComputerMenu(ModRegistry.COMPUTER_MENU.get(), containerId, core, (ServerPlayer) player,
            p -> !isRemoved() && p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64);
    }

    // ---- Persistence -----------------------------------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (core.computerId() >= 0) tag.putInt("ComputerId", core.computerId());
        if (core.label() != null) tag.putString("Label", core.label());
        tag.putBoolean("On", on);
        tag.putInt("TermCols", core.termCols());
        tag.putInt("TermRows", core.termRows());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        core.setComputerId(tag.contains("ComputerId") ? tag.getInt("ComputerId") : -1);
        core.setLabel(tag.contains("Label") ? tag.getString("Label") : null);
        on = tag.getBoolean("On");
        if (tag.contains("TermCols")) core.setTermSize(tag.getInt("TermCols"), tag.getInt("TermRows"));
    }

    @Override
    protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        var id = input.get(ModRegistry.COMPUTER_ID.get());
        if (id != null) core.setComputerId(id);
        var name = input.get(DataComponents.CUSTOM_NAME);
        if (name != null) core.setLabel(name.getString());
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        if (core.computerId() >= 0) builder.set(ModRegistry.COMPUTER_ID.get(), core.computerId());
        if (core.label() != null) builder.set(DataComponents.CUSTOM_NAME, Component.literal(core.label()));
    }

    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        super.removeComponentsFromTag(tag);
        tag.remove("ComputerId");
        tag.remove("Label");
    }

    // ---- Unloading -------------------------------------------------------------------------------------------------

    @Override
    public void setRemoved() {
        super.setRemoved();
        core.stop();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        core.stop();
    }
}
