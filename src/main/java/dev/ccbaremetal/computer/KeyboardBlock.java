package dev.ccbaremetal.computer;

import com.mojang.serialization.MapCodec;
import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A placed keyboard (linked before placing): right-click it to type on its computer, which must be within
 * {@link KeyboardMenu#RANGE} blocks of it. Typing stops when the player walks away ({@link #REACH} blocks) or the
 * keyboard is broken (it drops with its link).
 */
public final class KeyboardBlock extends BaseEntityBlock {
    public static final MapCodec<KeyboardBlock> CODEC = simpleCodec(KeyboardBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final double REACH = 8;

    private static final VoxelShape NORTH_SOUTH = Block.box(1, 0, 4, 15, 2, 12);
    private static final VoxelShape EAST_WEST = Block.box(4, 0, 1, 12, 2, 15);

    public KeyboardBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? NORTH_SOUTH : EAST_WEST;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new KeyboardBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(player instanceof ServerPlayer server) || !(level instanceof ServerLevel serverLevel)) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof KeyboardBlockEntity keyboard) || keyboard.computerId() < 0) return InteractionResult.PASS;
        var id = keyboard.computerId();
        var core = ComputerCore.find(id, serverLevel);
        if (core == null || core.position().distSqr(pos) > KeyboardMenu.RANGE * KeyboardMenu.RANGE) {
            server.displayClientMessage(Component.translatable("message.ccbaremetal.keyboard_far", id), true);
            return InteractionResult.CONSUME;
        }
        var center = pos.getCenter();
        KeyboardMenu.open(server, core, p -> p.level() == level && p.distanceToSqr(center) <= REACH * REACH
            && level.getBlockEntity(pos) instanceof KeyboardBlockEntity k && k.computerId() == id);
        return InteractionResult.CONSUME;
    }
}
