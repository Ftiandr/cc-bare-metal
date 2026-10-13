package dev.ccbaremetal.monitor;

import com.mojang.serialization.MapCodec;
import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/** A monitor block that shows a framebuffer. Adjacent monitors facing the same way form one big screen. */
public class PixelMonitorBlock extends BaseEntityBlock {
    public static final MapCodec<PixelMonitorBlock> CODEC = simpleCodec(PixelMonitorBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    /** Neighbours that are part of the same screen, as seen by the viewer (connected textures, like CC's monitors). */
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty LEFT =
        net.minecraft.world.level.block.state.properties.BooleanProperty.create("left");
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty RIGHT =
        net.minecraft.world.level.block.state.properties.BooleanProperty.create("right");
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty UP =
        net.minecraft.world.level.block.state.properties.BooleanProperty.create("up");
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty DOWN =
        net.minecraft.world.level.block.state.properties.BooleanProperty.create("down");

    public PixelMonitorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)
            .setValue(LEFT, false).setValue(RIGHT, false).setValue(UP, false).setValue(DOWN, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LEFT, RIGHT, UP, DOWN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PixelMonitorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModRegistry.PIXEL_MONITOR_BE.get(), (l, p, s, be) -> be.serverTick());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide) MonitorGroups.rebuild(level, pos, state.getValue(FACING));
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        var wasMonitor = state.is(newState.getBlock());
        super.onRemove(state, level, pos, newState, moved);
        if (!level.isClientSide && !wasMonitor) {
            var facing = state.getValue(FACING);
            for (var dir : Direction.values()) {
                var neighbour = pos.relative(dir);
                if (level.getBlockState(neighbour).is(this)) MonitorGroups.rebuild(level, neighbour, facing);
            }
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (hit.getDirection() != state.getValue(FACING)) return InteractionResult.PASS;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof PixelMonitorBlockEntity be) be.touch(hit.getLocation());
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
