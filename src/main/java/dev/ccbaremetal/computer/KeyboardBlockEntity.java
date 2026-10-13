package dev.ccbaremetal.computer;

import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** A placed keyboard's link: the id of its computer (from the item's computer_id, back into it when broken). */
public final class KeyboardBlockEntity extends BlockEntity {
    private int computerId = -1;

    public KeyboardBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.KEYBOARD_BE.get(), pos, state);
    }

    public int computerId() {
        return computerId;
    }

    @Override
    protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        var id = input.get(ModRegistry.COMPUTER_ID.get());
        if (id != null) computerId = id;
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        if (computerId >= 0) builder.set(ModRegistry.COMPUTER_ID.get(), computerId);
    }

    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        super.removeComponentsFromTag(tag);
        tag.remove("ComputerId");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (computerId >= 0) tag.putInt("ComputerId", computerId);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        computerId = tag.contains("ComputerId") ? tag.getInt("ComputerId") : -1;
    }
}
