package dev.ccbaremetal.turtle;

import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/** The WASM turtle as an item: keeps its computer, label, upgrades and fuel (shown in the tooltip). */
public class WasmTurtleItem extends BlockItem {
    public WasmTurtleItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        var upgrades = stack.get(ModRegistry.TURTLE_UPGRADES.get());
        if (upgrades != null) {
            var items = NonNullList.withSize(2, ItemStack.EMPTY);
            upgrades.copyInto(items);
            String[] names = {"left", "right"};
            for (var i = 0; i < 2; i++) {
                if (items.get(i).isEmpty()) continue;
                tooltip.add(Component.literal(names[i] + ": ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(items.get(i).getHoverName().copy().withStyle(ChatFormatting.GRAY)));
            }
        }
        var fuel = stack.get(ModRegistry.TURTLE_FUEL.get());
        if (fuel != null && fuel > 0) tooltip.add(Component.translatable("gui.ccbaremetal.fuel", fuel).withStyle(ChatFormatting.GRAY));
        var id = stack.get(ModRegistry.COMPUTER_ID.get());
        if (flag.isAdvanced() && id != null) tooltip.add(Component.translatable("tooltip.ccbaremetal.computer_id", id).withStyle(ChatFormatting.DARK_GRAY));
    }
}
