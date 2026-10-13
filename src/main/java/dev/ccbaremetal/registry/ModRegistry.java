package dev.ccbaremetal.registry;

import com.mojang.serialization.Codec;
import dev.ccbaremetal.BareMetal;
import dev.ccbaremetal.computer.ComputerMenu;
import dev.ccbaremetal.computer.WasmComputerBlock;
import dev.ccbaremetal.computer.WasmComputerBlockEntity;
import dev.ccbaremetal.monitor.PixelMonitorBlock;
import dev.ccbaremetal.monitor.PixelMonitorBlockEntity;
import dev.ccbaremetal.pocket.PocketComputerItem;
import dev.ccbaremetal.pocket.PocketComputerMenu;
import dev.ccbaremetal.turtle.TurtleMenu;
import dev.ccbaremetal.turtle.WasmTurtleBlock;
import dev.ccbaremetal.turtle.WasmTurtleBlockEntity;
import dev.ccbaremetal.turtle.WasmTurtleItem;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** All registered content. */
public final class ModRegistry {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(BareMetal.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BareMetal.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, BareMetal.MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, BareMetal.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BareMetal.MOD_ID);
    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, BareMetal.MOD_ID);

    /** The computer id stored on computer items, so a broken computer keeps its files. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> COMPUTER_ID = COMPONENTS.registerComponentType(
        "computer_id", b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    /** Pocket computer: whether it is on (so it boots again when its holder is loaded). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> POCKET_ON = COMPONENTS.registerComponentType(
        "pocket_on", b -> b.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL));
    /** Pocket computer: the upgrade items in its module slots (their state is kept in the items' components). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> POCKET_UPGRADES = COMPONENTS.registerComponentType(
        "pocket_upgrades", b -> b.persistent(ItemContainerContents.CODEC).networkSynchronized(ItemContainerContents.STREAM_CODEC).cacheEncoding());
    /** Pocket computer: colour of its light, set by upgrades (not saved). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> POCKET_LIGHT = COMPONENTS.registerComponentType(
        "pocket_light", b -> b.networkSynchronized(ByteBufCodecs.INT));

    /** Turtle item: its upgrades (left, right) and fuel. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> TURTLE_UPGRADES = COMPONENTS.registerComponentType(
        "turtle_upgrades", b -> b.persistent(ItemContainerContents.CODEC).networkSynchronized(ItemContainerContents.STREAM_CODEC).cacheEncoding());
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> TURTLE_FUEL = COMPONENTS.registerComponentType(
        "turtle_fuel", b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    public static final DeferredBlock<WasmComputerBlock> WASM_COMPUTER = BLOCKS.register("wasm_computer", () -> new WasmComputerBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(2.0f).sound(SoundType.METAL)
            .lightLevel(state -> state.getValue(WasmComputerBlock.ON) ? 7 : 0)));
    public static final DeferredBlock<PixelMonitorBlock> PIXEL_MONITOR = BLOCKS.register("pixel_monitor", () -> new PixelMonitorBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(2.0f).sound(SoundType.METAL)));

    public static final DeferredBlock<WasmTurtleBlock> WASM_TURTLE = BLOCKS.register("wasm_turtle", () -> new WasmTurtleBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(2.5f).sound(SoundType.METAL).noOcclusion()));

    public static final DeferredBlock<dev.ccbaremetal.computer.KeyboardBlock> KEYBOARD_BLOCK = BLOCKS.register("keyboard", () -> new dev.ccbaremetal.computer.KeyboardBlock(
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(0.8f).sound(SoundType.STONE).noOcclusion()));

    public static final DeferredItem<BlockItem> WASM_COMPUTER_ITEM = ITEMS.registerSimpleBlockItem(WASM_COMPUTER);
    public static final DeferredItem<BlockItem> PIXEL_MONITOR_ITEM = ITEMS.registerSimpleBlockItem(PIXEL_MONITOR);
    public static final DeferredItem<WasmTurtleItem> WASM_TURTLE_ITEM = ITEMS.register("wasm_turtle",
        () -> new WasmTurtleItem(WASM_TURTLE.get(), new Item.Properties().stacksTo(1)));
    public static final DeferredItem<PocketComputerItem> WASM_POCKET_COMPUTER = ITEMS.register("wasm_pocket_computer",
        () -> new PocketComputerItem(new Item.Properties().stacksTo(1)));
    /** Linked to a computer (COMPUTER_ID): typing on it from the world, every key captured. */
    public static final DeferredItem<dev.ccbaremetal.computer.KeyboardItem> KEYBOARD = ITEMS.register("keyboard",
        () -> new dev.ccbaremetal.computer.KeyboardItem(KEYBOARD_BLOCK.get(), new Item.Properties().stacksTo(1)));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WasmComputerBlockEntity>> WASM_COMPUTER_BE = BLOCK_ENTITIES.register(
        "wasm_computer", () -> BlockEntityType.Builder.of(WasmComputerBlockEntity::new, WASM_COMPUTER.get()).build(null));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WasmTurtleBlockEntity>> WASM_TURTLE_BE = BLOCK_ENTITIES.register(
        "wasm_turtle", () -> BlockEntityType.Builder.of(WasmTurtleBlockEntity::new, WASM_TURTLE.get()).build(null));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<dev.ccbaremetal.computer.KeyboardBlockEntity>> KEYBOARD_BE = BLOCK_ENTITIES.register(
        "keyboard", () -> BlockEntityType.Builder.of(dev.ccbaremetal.computer.KeyboardBlockEntity::new, KEYBOARD_BLOCK.get()).build(null));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PixelMonitorBlockEntity>> PIXEL_MONITOR_BE = BLOCK_ENTITIES.register(
        "pixel_monitor", () -> BlockEntityType.Builder.of(PixelMonitorBlockEntity::new, PIXEL_MONITOR.get()).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<ComputerMenu>> COMPUTER_MENU = MENUS.register(
        "computer", () -> IMenuTypeExtension.create(ComputerMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<PocketComputerMenu>> POCKET_MENU = MENUS.register(
        "pocket_computer", () -> IMenuTypeExtension.create(PocketComputerMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<TurtleMenu>> TURTLE_MENU = MENUS.register(
        "turtle", () -> IMenuTypeExtension.create(TurtleMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<dev.ccbaremetal.computer.KeyboardMenu>> KEYBOARD_MENU = MENUS.register(
        "keyboard", () -> IMenuTypeExtension.create(dev.ccbaremetal.computer.KeyboardMenu::new));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
        .title(Component.translatable("itemGroup.ccbaremetal"))
        .icon(() -> WASM_COMPUTER_ITEM.get().getDefaultInstance())
        .displayItems((params, output) -> {
            output.accept(WASM_COMPUTER_ITEM.get());
            output.accept(PIXEL_MONITOR_ITEM.get());
            output.accept(WASM_POCKET_COMPUTER.get());
            output.accept(WASM_TURTLE_ITEM.get());
            output.accept(KEYBOARD.get());
        })
        .build());

    private ModRegistry() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MENUS.register(bus);
        TABS.register(bus);
        COMPONENTS.register(bus);
    }
}
