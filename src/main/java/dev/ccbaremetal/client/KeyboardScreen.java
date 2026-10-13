package dev.ccbaremetal.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ccbaremetal.computer.KeyboardMenu;
import dev.ccbaremetal.network.Payloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;

/**
 * Typing on a computer with the keyboard: no window, the world stays in view, the player stands still but looks
 * around with the mouse as usual. Every key goes to the computer, Esc included (Shift+Esc leaves); mouse buttons, the
 * wheel and the movement (relative, at most 20 times a second) go to it too. Only a line at the top says what is
 * going on.
 */
public final class KeyboardScreen extends Screen implements MenuAccess<KeyboardMenu> {
    private final KeyboardMenu menu;
    private final Set<Integer> held = new HashSet<>();
    private double lastX, lastY, pendingX, pendingY;
    /** Where the cursor was at the last head turn (each frame). */
    private double turnX, turnY;

    public KeyboardScreen(KeyboardMenu menu, Inventory inventory, Component title) {
        super(title);
        this.menu = menu;
    }

    @Override
    public KeyboardMenu getMenu() {
        return menu;
    }

    @Override
    protected void init() {
        var window = minecraft.getWindow();
        InputConstants.grabOrReleaseMouse(window.getWindow(), GLFW.GLFW_CURSOR_DISABLED, window.getScreenWidth() / 2.0, window.getScreenHeight() / 2.0);
        lastX = turnX = minecraft.mouseHandler.xpos();
        lastY = turnY = minecraft.mouseHandler.ypos();
    }

    /** Turn the head as Minecraft does with no screen open (sensitivity, inverted mouse). */
    private void turnHead() {
        var x = minecraft.mouseHandler.xpos();
        var y = minecraft.mouseHandler.ypos();
        double dx = x - turnX, dy = y - turnY;
        turnX = x;
        turnY = y;
        if (minecraft.player == null || (dx == 0 && dy == 0)) return;
        var s = minecraft.options.sensitivity().get() * 0.6 + 0.2;
        var f = s * s * s * 8.0;
        var invert = minecraft.options.invertYMouse().get() ? -1 : 1;
        minecraft.player.turn(dx * f, dy * f * invert);
    }

    private void send(String event, int a, int b, int c, String text) {
        if (minecraft.getConnection() != null) PacketDistributor.sendToServer(new Payloads.Input(menu.containerId, event, a, b, c, text));
    }

    private static int modifiers(int glfwMods) {
        var mods = 0;
        if ((glfwMods & GLFW.GLFW_MOD_SHIFT) != 0) mods |= 1;
        if ((glfwMods & GLFW.GLFW_MOD_CONTROL) != 0) mods |= 2;
        if ((glfwMods & GLFW.GLFW_MOD_ALT) != 0) mods |= 4;
        return mods;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int mods) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && (mods & GLFW.GLFW_MOD_SHIFT) != 0) {
            onClose();
            return true;
        }
        var repeat = !held.add(keyCode);
        send("key", keyCode, repeat ? 1 : 0, modifiers(mods), "");
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int mods) {
        if (held.remove(keyCode)) send("key_up", keyCode, 0, 0, "");
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if ((mods & GLFW.GLFW_MOD_CONTROL) == 0 && c >= 32) send("char", 0, 0, 0, String.valueOf(c));
        return true;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        send("mouse_button", button, 1, 0, "");
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        send("mouse_button", button, 0, 0, "");
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (scrollY != 0) send("mouse_wheel", scrollY > 0 ? 1 : -1, 0, 0, "");
        return true;
    }

    @Override
    public void tick() {
        // the captured cursor moves freely: send its movement in window pixels, whole steps only
        var x = minecraft.mouseHandler.xpos();
        var y = minecraft.mouseHandler.ypos();
        pendingX += x - lastX;
        pendingY += y - lastY;
        lastX = x;
        lastY = y;
        int dx = (int) pendingX, dy = (int) pendingY;
        if (dx != 0 || dy != 0) {
            send("mouse_relative", dx, dy, 0, "");
            pendingX -= dx;
            pendingY -= dy;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        turnHead();
        var name = menu.label().isEmpty() ? "#" + menu.computerId() : menu.label();
        var text = Component.translatable("gui.ccbaremetal.keyboard_hint", name);
        graphics.fill(0, 0, width, 13, 0x90000000);
        graphics.drawCenteredString(font, text, width / 2, 3, 0xFFFFFF);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // the world stays visible as it is
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft.player != null) minecraft.player.closeContainer();
        else super.onClose();
    }

    @Override
    public void removed() {
        for (var key : held) send("key_up", key, 0, 0, "");
        held.clear();
        var window = minecraft.getWindow();
        InputConstants.grabOrReleaseMouse(window.getWindow(), GLFW.GLFW_CURSOR_NORMAL, window.getScreenWidth() / 2.0, window.getScreenHeight() / 2.0);
        super.removed();
    }
}
