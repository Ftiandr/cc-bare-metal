package dev.ccbaremetal.computer;

import com.mojang.serialization.MapCodec;
import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/** The WASM computer block. Right-click turns it on and opens the screen. */
public class WasmComputerBlock extends BaseEntityBlock {
    public static final MapCodec<WasmComputerBlock> CODEC = simpleCodec(WasmComputerBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty ON = BooleanProperty.create("on");

    public WasmComputerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ON, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ON);
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
        return new WasmComputerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModRegistry.WASM_COMPUTER_BE.get(), (l, p, s, be) -> be.serverTick());
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
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof WasmComputerBlockEntity be) {
            be.turnOn();
            be.openScreen(serverPlayer);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** In creative mode blocks drop nothing; like CC, a computer that has files or a label still drops itself. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && level.getBlockEntity(pos) instanceof WasmComputerBlockEntity be
            && (be.computerId() >= 0 || be.label() != null)) {
            var stack = new ItemStack(this);
            stack.applyComponents(be.collectComponents());
            popResource(level, pos, stack);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof WasmComputerBlockEntity be) be.markPeripheralsDirty();
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean moving) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof WasmComputerBlockEntity be) be.markPeripheralsDirty();
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return level.getBlockEntity(pos) instanceof WasmComputerBlockEntity be ? be.redstoneOutput(direction.getOpposite()) : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return getSignal(state, level, pos, direction);
    }
}
