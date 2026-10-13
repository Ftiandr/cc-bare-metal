package dev.ccbaremetal.turtle;

import com.mojang.authlib.GameProfile;
import dan200.computercraft.api.turtle.TurtleAnimation;
import dan200.computercraft.api.turtle.TurtleSide;
import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block of a WASM turtle. It holds a {@link WasmTurtle} (which moves on to the next block entity when the turtle
 * moves), saves it, and tells clients what to draw: the upgrades and the current animation.
 */
public class WasmTurtleBlockEntity extends BlockEntity implements MenuProvider {
    private WasmTurtle turtle = new WasmTurtle(this);

    // Client side: upgrades to draw and the animation in progress.
    public ItemStack clientLeft = ItemStack.EMPTY;
    public ItemStack clientRight = ItemStack.EMPTY;
    /** Animation (TurtleAnimation ordinal) and the game time it started at. */
    public int animation;
    public long animationStart;

    public WasmTurtleBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.WASM_TURTLE_BE.get(), pos, state);
    }

    public WasmTurtle turtle() {
        return turtle;
    }

    /** The turtle moved here: take over its state (the old block entity is removed right after). */
    void adopt(WasmTurtle moved) {
        turtle = moved;
        moved.setOwner(this);
        setChanged();
    }

    /** Whether this block still holds its turtle (false for the old block of a turtle that moved away). */
    boolean holdsTurtle() {
        return turtle.owner() == this;
    }

    public Direction facing() {
        return getBlockState().getValue(WasmTurtleBlock.FACING);
    }

    public void serverTick() {
        if (holdsTurtle()) turtle.tick();
    }

    // ---- Client sync -------------------------------------------------------------------------------------------------

    void sync() {
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    void animate(TurtleAnimation animation) {
        this.animation = animation.ordinal();
        animationStart = level == null ? 0 : level.getGameTime();
        sync();
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        saveUpgrades(tag, registries);
        tag.putInt("Animation", animation);
        tag.putLong("AnimationStart", animationStart);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadClient(tag, registries);
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        loadClient(packet.getTag(), registries);
    }

    private void loadClient(CompoundTag tag, HolderLookup.Provider registries) {
        clientLeft = ItemStack.parseOptional(registries, tag.getCompound("Left"));
        clientRight = ItemStack.parseOptional(registries, tag.getCompound("Right"));
        animation = tag.getInt("Animation");
        animationStart = tag.getLong("AnimationStart");
    }

    // ---- Screen ------------------------------------------------------------------------------------------------------

    public void openScreen(ServerPlayer player) {
        turtle.core().ensureMachine();
        player.openMenu(this, buf -> buf.writeVarInt(turtle.core().computerId()));
    }

    @Override
    public Component getDisplayName() {
        var label = turtle.core().label();
        return label != null ? Component.literal(label) : Component.translatable("block.ccbaremetal.wasm_turtle");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new TurtleMenu(containerId, inventory, turtle, (ServerPlayer) player);
    }

    // ---- Persistence ---------------------------------------------------------------------------------------------------

    private void saveUpgrades(CompoundTag tag, HolderLookup.Provider registries) {
        var left = turtle.upgradeItem(TurtleSide.LEFT);
        var right = turtle.upgradeItem(TurtleSide.RIGHT);
        if (!left.isEmpty()) tag.put("Left", left.save(registries));
        if (!right.isEmpty()) tag.put("Right", right.save(registries));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        var core = turtle.core();
        if (core.computerId() >= 0) tag.putInt("ComputerId", core.computerId());
        if (core.label() != null) tag.putString("Label", core.label());
        tag.putBoolean("On", turtle.isOnSaved());
        tag.putInt("TermCols", core.termCols());
        tag.putInt("TermRows", core.termRows());
        tag.putInt("Fuel", turtle.getFuelLevel());
        tag.putInt("Selected", turtle.getSelectedSlot());
        var owner = turtle.getOwningPlayer();
        if (owner != WasmTurtle.DEFAULT_PROFILE) {
            tag.putUUID("OwnerId", owner.getId());
            tag.putString("OwnerName", owner.getName());
        }
        var items = NonNullList.withSize(WasmTurtle.INVENTORY_SIZE, ItemStack.EMPTY);
        for (var i = 0; i < items.size(); i++) items.set(i, turtle.getInventory().getItem(i));
        ContainerHelper.saveAllItems(tag, items, registries);
        saveUpgrades(tag, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        var core = turtle.core();
        core.setComputerId(tag.contains("ComputerId") ? tag.getInt("ComputerId") : -1);
        core.setLabel(tag.contains("Label") ? tag.getString("Label") : null);
        turtle.setOnSaved(tag.getBoolean("On"));
        if (tag.contains("TermCols")) core.setTermSize(tag.getInt("TermCols"), tag.getInt("TermRows"));
        turtle.setFuel(tag.getInt("Fuel"));
        turtle.setSelectedSlot(Math.max(0, Math.min(WasmTurtle.INVENTORY_SIZE - 1, tag.getInt("Selected"))));
        if (tag.hasUUID("OwnerId")) turtle.setOwningPlayer(new GameProfile(tag.getUUID("OwnerId"), tag.getString("OwnerName")));
        var items = NonNullList.withSize(WasmTurtle.INVENTORY_SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
        for (var i = 0; i < items.size(); i++) turtle.getInventory().setItem(i, items.get(i));
        turtle.loadUpgradeItems(ItemStack.parseOptional(registries, tag.getCompound("Left")), ItemStack.parseOptional(registries, tag.getCompound("Right")));
    }

    @Override
    protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        var id = input.get(ModRegistry.COMPUTER_ID.get());
        if (id != null) turtle.core().setComputerId(id);
        var name = input.get(DataComponents.CUSTOM_NAME);
        if (name != null) turtle.core().setLabel(name.getString());
        var fuel = input.get(ModRegistry.TURTLE_FUEL.get());
        if (fuel != null) turtle.setFuel(fuel);
        var upgrades = input.get(ModRegistry.TURTLE_UPGRADES.get());
        if (upgrades != null) {
            var items = NonNullList.withSize(2, ItemStack.EMPTY);
            upgrades.copyInto(items);
            turtle.loadUpgradeItems(items.get(0), items.get(1));
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        var core = turtle.core();
        if (core.computerId() >= 0) builder.set(ModRegistry.COMPUTER_ID.get(), core.computerId());
        if (core.label() != null) builder.set(DataComponents.CUSTOM_NAME, Component.literal(core.label()));
        if (turtle.getFuelLevel() > 0) builder.set(ModRegistry.TURTLE_FUEL.get(), turtle.getFuelLevel());
        var left = turtle.upgradeItem(TurtleSide.LEFT);
        var right = turtle.upgradeItem(TurtleSide.RIGHT);
        if (!left.isEmpty() || !right.isEmpty()) {
            builder.set(ModRegistry.TURTLE_UPGRADES.get(), ItemContainerContents.fromItems(java.util.List.of(left, right)));
        }
    }

    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        super.removeComponentsFromTag(tag);
        tag.remove("ComputerId");
        tag.remove("Label");
        tag.remove("Fuel");
        tag.remove("Left");
        tag.remove("Right");
    }

    // ---- Unloading -------------------------------------------------------------------------------------------------

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (holdsTurtle()) turtle.stop();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (holdsTurtle()) turtle.stop();
    }
}
