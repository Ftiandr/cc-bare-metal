package dev.ccbaremetal.turtle;

import com.mojang.serialization.MapCodec;
import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The WASM turtle block. Drawn by its block entity renderer (it moves and turns smoothly, with its upgrades). */
public class WasmTurtleBlock extends BaseEntityBlock {
    public static final MapCodec<WasmTurtleBlock> CODEC = simpleCodec(WasmTurtleBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private static final VoxelShape SHAPE = box(2, 2, 2, 14, 14, 14);

    public WasmTurtleBlock(Properties properties) {
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
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WasmTurtleBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModRegistry.WASM_TURTLE_BE.get(), (l, p, s, be) -> be.serverTick());
    }

    /** The keyboard item links itself and starts typing (KeyboardItem.useOn) instead of opening the window. */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state, Level level,
                                                                  BlockPos pos, Player player, net.minecraft.world.InteractionHand hand,
                                                                  BlockHitResult hit) {
        if (stack.is(dev.ccbaremetal.registry.ModRegistry.KEYBOARD.get())) return net.minecraft.world.ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof WasmTurtleBlockEntity be) {
            be.turtle().core().turnOn();
            be.openScreen(serverPlayer);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof WasmTurtleBlockEntity be) {
            if (placer instanceof Player player) be.turtle().setOwningPlayer(player.getGameProfile());
            be.turtle().markPeripheralsDirty();
        }
    }

    /** In creative mode blocks drop nothing; like CC, a turtle that has files, a label or upgrades still drops itself. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && level.getBlockEntity(pos) instanceof WasmTurtleBlockEntity be
            && (be.turtle().core().computerId() >= 0 || be.turtle().core().label() != null)) {
            var stack = new ItemStack(this);
            stack.applyComponents(be.collectComponents());
            popResource(level, pos, stack);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** The inventory spills when the turtle is broken (not when it just moved on). */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof WasmTurtleBlockEntity be && be.holdsTurtle()) {
            Containers.dropContents(level, pos, be.turtle().getInventory());
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean moving) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof WasmTurtleBlockEntity be) be.turtle().markPeripheralsDirty();
    }
}
