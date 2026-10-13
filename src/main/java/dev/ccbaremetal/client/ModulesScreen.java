package dev.ccbaremetal.client;

import dev.ccbaremetal.computer.ComputerMenu;
import dev.ccbaremetal.computer.ModulePanel;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The module panel of a pocket computer or turtle: its module slots (upgrades), other slots and the player's
 * inventory, in a small CC-style amethyst panel over the computer's window (which keeps being drawn behind it). A
 * real container screen, so inventory mods (JEI/EMI, Inventory Profiles Next) help here, and only here. The module
 * button, Esc or the inventory key go back to the computer.
 */
public class ModulesScreen<T extends ComputerMenu & ModulePanel> extends AbstractContainerScreen<T> {
    private static final ResourceLocation PANEL_SPRITE = ResourceLocation.fromNamespaceAndPath("ccbaremetal", "panel");
    private static final ResourceLocation SLOT_SPRITE = ResourceLocation.fromNamespaceAndPath("ccbaremetal", "slot");
    private static final ResourceLocation MODULE_SLOT_SPRITE = ResourceLocation.fromNamespaceAndPath("ccbaremetal", "module_slot");
    private static final int LABEL_COLOUR = 0xFF2B2633;

    private final ComputerScreen<T> parent;
    /** Going back to the computer's window (rather than closing everything). */
    private boolean returning;

    public ModulesScreen(ComputerScreen<T> parent, T menu, Inventory inventory) {
        super(menu, inventory, Component.translatable("gui.ccbaremetal.modules"));
        this.parent = parent;
        imageWidth = menu.panelWidth();
        imageHeight = menu.panelHeight();
    }

    public ComputerScreen<T> parent() {
        return parent;
    }

    @Override
    protected void init() {
        // The computer's window behind keeps its layout for this window size.
        parent.init(minecraft, width, height);
        super.init();
        var centre = parent.screenCentre();
        leftPos = Math.max(0, Math.min(width - imageWidth, (int) Math.round(centre[0] - imageWidth / 2.0)));
        topPos = Math.max(0, Math.min(height - imageHeight, (int) Math.round(centre[1] - imageHeight / 2.0)));
        titleLabelX = (imageWidth - font.width(title)) / 2;
        titleLabelY = 6;
    }

    private void back() {
        returning = true;
        minecraft.setScreen(parent);
    }

    // ---- Rendering -------------------------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        parent.renderBehind(g, partialTick);
        renderBg(g, partialTick, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 100);
        g.blitSprite(PANEL_SPRITE, leftPos, topPos, imageWidth, imageHeight);
        var locked = menu.lockedInventorySlot();
        var highlighted = menu.highlightedSlot();
        for (var i = 0; i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            int x = leftPos + slot.x - 1, y = topPos + slot.y - 1;
            g.blitSprite(i < menu.moduleSlots() ? MODULE_SLOT_SPRITE : SLOT_SPRITE, x, y, 18, 18);
            // The pocket computer itself: locked while its window is open.
            if (locked >= 0 && slot.container instanceof Inventory && slot.getContainerSlot() == locked) g.fill(x, y, x + 18, y + 18, 0x80202020);
            // The turtle's selected slot.
            if (i == highlighted) g.renderOutline(x - 1, y - 1, 20, 20, 0xFFF0E0FF);
        }
        g.pose().popPose();
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Above the panel, which is drawn above the computer's screen.
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        g.drawString(font, title, titleLabelX, titleLabelY, LABEL_COLOUR, false);
        for (var label : menu.labels()) g.drawString(font, label.text(), label.x(), label.y(), LABEL_COLOUR, false);
        g.pose().popPose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        var module = parent.modulesButton();
        if (parent.buttonAt(mouseX, mouseY) == module) {
            g.renderTooltip(font, font.split(parent.buttonTooltip(module), 240), mouseX, mouseY);
        } else if (hoveredSlot != null && !hoveredSlot.hasItem() && menu.getCarried().isEmpty() && hoveredSlot.index < menu.moduleSlots()) {
            g.renderComponentTooltip(font, moduleSlotTooltip(hoveredSlot.index), mouseX, mouseY);
        } else {
            renderTooltip(g, mouseX, mouseY);
        }
    }

    /** Tooltip of an empty module slot: its peripheral name and the items that fit (all of them with Shift). */
    private List<Component> moduleSlotTooltip(int index) {
        var lines = new ArrayList<Component>();
        lines.add(Component.translatable("gui.ccbaremetal.module_slot", index + 1, menu.moduleSlotName(index)));
        var all = menu.supportedModules(minecraft.level.registryAccess());
        if (all.isEmpty()) {
            lines.add(Component.translatable("gui.ccbaremetal.no_modules_known").withStyle(ChatFormatting.GRAY));
            return lines;
        }
        lines.add(Component.translatable("gui.ccbaremetal.modules_fit").withStyle(ChatFormatting.GRAY));
        var limit = hasShiftDown() ? all.size() : Math.min(all.size(), 8);
        for (var i = 0; i < limit; i++) {
            lines.add(Component.literal("  ").append(all.get(i).value().getCraftingItem().getHoverName()).withStyle(ChatFormatting.GRAY));
        }
        if (limit < all.size()) lines.add(Component.translatable("gui.ccbaremetal.modules_more", all.size() - limit).withStyle(ChatFormatting.DARK_GRAY));
        if (!menu.allowsDuplicates()) lines.add(Component.translatable("gui.ccbaremetal.modules_unique").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    // ---- Input -----------------------------------------------------------------------------------------------------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int mods) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            back();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, mods);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && parent.buttonAt(mouseX, mouseY) == parent.modulesButton()) {
            back();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Clicks next to the panel never throw the carried item into the world. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top, int button) {
        return false;
    }

    @Override
    public void removed() {
        if (returning) {
            // The menu stays open: back to the computer's window.
            returning = false;
            return;
        }
        super.removed();
        parent.dispose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
