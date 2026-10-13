package dev.ccbaremetal.client;

import dev.ccbaremetal.BareMetalClientConfig;
import dev.ccbaremetal.computer.ComputerMenu;
import dev.ccbaremetal.kernel.gfx.TermFont;
import dev.ccbaremetal.kernel.tty.Terminal;
import dev.ccbaremetal.network.Payloads;
import dev.ccbaremetal.pocket.PocketComputerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;

/**
 * The computer GUI, in the style of CC: Tweaked's advanced computer (amethyst instead of gold): the terminal in CC's
 * border, with CC's sidebar on the left (power, reboot, terminate, font size). The frame and sidebar are drawn at the
 * GUI scale and fill most of the window; the zoom only changes the font: a font pixel is {@code zoom} window pixels,
 * and the terminal gets as many columns and rows as fit. Both are whole numbers of window pixels, so pixels stay
 * square. In graphics mode the framebuffer is shown at the largest whole scale that fits the same area.
 *
 * <p>It is a plain screen, not a container screen, so inventory overlays of other mods (JEI/EMI item lists,
 * Inventory Profiles Next buttons) stay away from the terminal. The pocket computer's module slots are a separate
 * container screen drawn over it ({@link ModulesScreen}).
 *
 * <ul>
 *   <li>Mouse drag selects text (Shift+drag when the program uses the mouse), double click selects a word, triple
 *       click a line. Ctrl+Shift+C copies and Ctrl+Shift+V pastes, as in Linux terminals. Also: Ctrl+C copies when
 *       there is a selection (otherwise the program gets it), Ctrl+V / Shift+Insert / middle click paste, right
 *       click copies or pastes. Shortcuts are matched by key position too, so they work in any keyboard layout.</li>
 *   <li>Mouse wheel and Shift+PageUp/PageDown scroll back through the history.</li>
 *   <li>Ctrl+'+' / Ctrl+'-' / Ctrl+0 or Ctrl+wheel change the font size.</li>
 *   <li>Hold Ctrl+T / Ctrl+R / Ctrl+S for a second to terminate / reboot / shut down, like CC.</li>
 * </ul>
 */
public class ComputerScreen<T extends ComputerMenu> extends Screen implements MenuAccess<T> {
    private static final int MAX_ZOOM = 8;
    /** Part of the window the frame may use. */
    private static final double WINDOW_FRACTION = 0.96;
    // CC's layout, in art pixels (frame pixels): sidebar, border, margin around the text, sidebar buttons.
    private static final int SIDEBAR_WIDTH = 17;
    private static final int BORDER = 12;
    private static final int PAD = 2;
    private static final int ICON = 12;
    private static final int ICON_STEP = 16;
    /** Room above the frame for the title line, in GUI units. */
    private static final int LABEL = 11;

    private static final ResourceLocation BORDER_SPRITE = ResourceLocation.fromNamespaceAndPath("ccbaremetal", "border");
    private static final ResourceLocation SIDEBAR_SPRITE = ResourceLocation.fromNamespaceAndPath("ccbaremetal", "sidebar");

    private final ScreenImage image = new ScreenImage("computer");
    private final Set<Integer> held = new HashSet<>();
    private boolean on;
    private boolean programMouse;
    private boolean programMotion;
    private long lastMotionSent;
    /** Motion held back by the rate limit: sent from the tick, so the program still learns where the mouse stopped. */
    private boolean motionPending;
    private double pendingMouseX;
    private double pendingMouseY;
    private int heldShortcut = -1;
    private long heldSince;

    // Layout, in physical (window) pixels unless noted.
    private double guiScale;
    /** Art pixel (frame and sidebar pixel) size: the GUI scale, made smaller if the frame does not fit. */
    private int art;
    /** Biggest content area for the terminal, in art pixels. */
    private int maxInnerW;
    private int maxInnerH;
    private int frameX;
    private int frameY;
    /** Content area inside the border and margin, in art pixels. */
    private int innerW;
    private int innerH;
    /** Font pixel size in window pixels (the font size buttons and Ctrl+'+'/'-'). */
    private int zoom;
    private int sentCols = -1;
    private int sentRows = -1;
    private double dispX;
    private double dispY;
    private double cellW;
    private double cellH;
    private double pixelScale;

    /** A sidebar button: sprites ccbaremetal:buttons/NAME and NAME_hover, a tooltip, and what it does. */
    private record SideButton(java.util.function.Supplier<String> sprite, java.util.function.Supplier<Component> tooltip, Runnable action) {
    }

    private final java.util.List<SideButton> buttons = new java.util.ArrayList<>(java.util.List.of(
        new SideButton(() -> on ? "turned_on" : "turned_off",
            () -> on ? hinted("gui.ccbaremetal.power_off", "gui.baremetal.hold_ctrl_s") : Component.translatable("gui.ccbaremetal.power_on"),
            () -> power(on ? Payloads.POWER_SHUTDOWN : Payloads.POWER_ON)),
        new SideButton(() -> "reboot", () -> hinted("gui.ccbaremetal.reboot", "gui.baremetal.hold_ctrl_r"), () -> {
            if (on) power(Payloads.POWER_REBOOT);
        }),
        new SideButton(() -> "terminate", () -> hinted("gui.ccbaremetal.terminate", "gui.baremetal.hold_ctrl_t"), () -> {
            if (on) power(Payloads.POWER_TERMINATE);
        }),
        new SideButton(() -> "zoom_in", () -> Component.translatable("gui.ccbaremetal.zoom_in", zoom), () -> setZoom(zoom + 1)),
        new SideButton(() -> "zoom_out", () -> Component.translatable("gui.ccbaremetal.zoom_out", zoom), () -> setZoom(zoom - 1))));


    // Mouse state.
    private boolean selecting;
    private long anchorLine;
    private int anchorCol;
    private long lastClickTime;
    private long lastClickLine;
    private int lastClickCol;
    private int clickCount;
    private int lastCellX = -1;
    private int lastCellY = -1;

    // Short message in the title bar ("Copied 12 characters").
    private String notice = "";
    private long noticeUntil;

    protected final T menu;
    private final Inventory inventory;
    /** Switching to the module screen or a dialog: keep the screen image (this screen comes back). */
    private boolean openingModules;

    /** Files dropped on the window, waiting for the server's go-ahead or being sent. */
    private record Upload(int id, java.util.List<String> paths, java.util.List<Long> sizes, byte[] data) {
    }

    private static int nextUploadId;
    private Upload upload;
    private int uploadSent = -1;
    private boolean uploadOverwrite;

    public ComputerScreen(T menu, Inventory inventory, Component title) {
        super(title);
        this.menu = menu;
        this.inventory = inventory;
        if (menu instanceof dev.ccbaremetal.computer.ModulePanel) {
            buttons.add(new SideButton(() -> "modules", () -> hinted("gui.ccbaremetal.modules",
                menu instanceof PocketComputerMenu ? "gui.ccbaremetal.modules_hint" : "gui.ccbaremetal.turtle_modules_hint"), this::openModules));
        }
    }

    @Override
    public T getMenu() {
        return menu;
    }

    /** The module slots (pocket computer, turtle), as a container screen over this one. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void openModules() {
        if (!(menu instanceof dev.ccbaremetal.computer.ModulePanel)) return;
        selecting = false;
        openingModules = true;
        minecraft.setScreen(new ModulesScreen(this, menu, inventory));
    }

    /** Index of the module button, or -1 (no module slots). */
    int modulesButton() {
        return menu instanceof dev.ccbaremetal.computer.ModulePanel ? buttons.size() - 1 : -1;
    }

    public ScreenImage image() {
        return image;
    }

    public void setState(boolean on, boolean graphics, boolean mouse, boolean motion) {
        if (!on) sentCols = sentRows = -1;
        this.on = on;
        this.programMouse = mouse;
        this.programMotion = motion;
        image.setGraphics(graphics);
    }

    public void onTerminal(byte[] data) {
        var added = image.applyTerminal(data);
        // Keep looking at the same text when scrolled back.
        if (image.scrollOffset() > 0 && added > 0) image.setScrollOffset(image.scrollOffset() + added);
        if (image.alternate()) image.setScrollOffset(0);
        sendSize();
    }

    // ---- Layout ----------------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        guiScale = minecraft.getWindow().getGuiScale();
        var configured = BareMetalClientConfig.textZoom();
        zoom = configured > 0 ? configured : autoZoom();
        layoutFrame();
        super.init();
        sendSize();
    }

    private int windowW() {
        return minecraft.getWindow().getWidth();
    }

    private int windowH() {
        return minecraft.getWindow().getHeight();
    }

    private int autoZoom() {
        return Math.max(1, Math.min(MAX_ZOOM, (int) Math.round(windowH() * WINDOW_FRACTION / 360.0)));
    }

    /** Art pixel size and the biggest content area: the GUI scale, smaller if not even a small terminal would fit. */
    private void layoutFrame() {
        var availW = windowW() * WINDOW_FRACTION;
        var availH = windowH() * WINDOW_FRACTION - LABEL * guiScale;
        art = Math.max(1, (int) Math.round(guiScale));
        while (true) {
            maxInnerW = (int) (availW / art) - SIDEBAR_WIDTH - 2 * BORDER - 2 * PAD;
            maxInnerH = (int) (availH / art) - 2 * BORDER - 2 * PAD;
            if (art == 1 || (maxInnerW >= Terminal.MIN_WIDTH * TermFont.WIDTH && maxInnerH >= Terminal.MIN_HEIGHT * TermFont.HEIGHT)) break;
            art--;
        }
        maxInnerW = Math.max(TermFont.WIDTH, maxInnerW);
        maxInnerH = Math.max(TermFont.HEIGHT, maxInnerH);
    }

    private void setZoom(int newZoom) {
        newZoom = Math.max(1, Math.min(MAX_ZOOM, newZoom));
        BareMetalClientConfig.setTextZoom(newZoom);
        zoom = newZoom;
        sendSize();
    }

    /** Columns that fit the content area at the zoom. */
    private int fitCols() {
        return Math.max(Terminal.MIN_WIDTH, Math.min(Terminal.MAX_WIDTH, maxInnerW * art / (TermFont.WIDTH * zoom)));
    }

    /** Rows that fit the content area at the zoom. */
    private int fitRows() {
        return Math.max(Terminal.MIN_HEIGHT, Math.min(Terminal.MAX_HEIGHT, maxInnerH * art / (TermFont.HEIGHT * zoom)));
    }

    private void power(int action) {
        if (minecraft.getConnection() != null) PacketDistributor.sendToServer(new Payloads.Power(menu.containerId, action));
    }

    private static Component hinted(String key, String hint) {
        return Component.translatable(key).append("\n").append(Component.translatable(hint).withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    /** The terminal size this window wants (columns, rows). */
    int[] wantedSize() {
        return new int[]{fitCols(), fitRows()};
    }

    /** Ask the server for a terminal that fills the content area at the zoom. */
    private void sendSize() {
        var cols = fitCols();
        var rows = fitRows();
        if (!on || (cols == sentCols && rows == sentRows)) return;
        sentCols = cols;
        sentRows = rows;
        input("resize", cols, rows, 0, "");
    }

    /**
     * Where everything is drawn. Text: the terminal at the zoom (smaller whole scale if another viewer made it
     * bigger than this window). Graphics: the framebuffer at the largest whole scale fitting the same area. Only a
     * framebuffer bigger than the whole area is scaled down by a fraction.
     */
    private void layoutDisplay() {
        int cols = on && image.termWidth() > 0 ? image.termWidth() : fitCols();
        int rows = on && image.termHeight() > 0 ? image.termHeight() : fitRows();
        int textW = cols * TermFont.WIDTH;
        int textH = rows * TermFont.HEIGHT;
        // The frame always has the biggest size, so it never moves when the font size changes; the text is centred.
        innerW = maxInnerW;
        innerH = maxInnerH;
        // The zoom once the terminal has the size asked for; until then (resize on its way, or another viewer's size)
        // the largest whole scale that fits, so the text does not jump to a wrong size in between.
        int textScale = cols == fitCols() && rows == fitRows() ? zoom : MAX_ZOOM;
        while (textScale > 1 && (textW * textScale > maxInnerW * art || textH * textScale > maxInnerH * art)) textScale--;
        int frameW = SIDEBAR_WIDTH + 2 * BORDER + 2 * PAD + innerW;
        int frameH = 2 * BORDER + 2 * PAD + innerH;
        frameX = (windowW() - frameW * art) / 2;
        frameY = (int) ((windowH() + LABEL * guiScale - frameH * art) / 2);
        double boxX = frameX + (SIDEBAR_WIDTH + BORDER + PAD) * art;
        double boxY = frameY + (BORDER + PAD) * art;
        double boxW = innerW * art;
        double boxH = innerH * art;
        int w;
        int h;
        double scale;
        if (image.graphics()) {
            w = Math.max(1, image.fbWidth());
            h = Math.max(1, image.fbHeight());
            scale = Math.floor(Math.min(boxW / w, boxH / h));
            if (scale < 1) scale = Math.min(boxW / w, boxH / h);
        } else {
            w = textW;
            h = textH;
            scale = textScale;
            if (textW * scale > boxW || textH * scale > boxH) scale = Math.min(boxW / w, boxH / h);
        }
        pixelScale = scale;
        dispX = boxX + Math.floor((boxW - w * scale) / 2);
        dispY = boxY + Math.floor((boxH - h * scale) / 2);
        cellW = TermFont.WIDTH * scale;
        cellH = TermFont.HEIGHT * scale;
        screenCentre[0] = (boxX + boxW / 2) / guiScale;
        screenCentre[1] = (boxY + boxH / 2) / guiScale;
    }

    /** Middle of the screen area (GUI coordinates), where the module panel goes. */
    private final double[] screenCentre = new double[2];

    double[] screenCentre() {
        layoutDisplay();
        return screenCentre;
    }

    /** Middle of sidebar button {@code i}, in GUI coordinates (for the render preview). */
    double[] buttonCentre(int i) {
        layoutDisplay();
        return new double[]{(frameX + (4 + ICON / 2.0) * art) / guiScale, (frameY + (BORDER + 5 + i * ICON_STEP + ICON / 2.0) * art) / guiScale};
    }

    /** Index of the sidebar button under the mouse (GUI coordinates), or -1. */
    int buttonAt(double mouseX, double mouseY) {
        double px = (mouseX * guiScale - frameX) / art;
        double py = (mouseY * guiScale - frameY) / art;
        for (var i = 0; i < buttons.size(); i++) {
            int bx = 4, by = BORDER + 5 + i * ICON_STEP;
            if (px >= bx && px < bx + ICON && py >= by && py < by + ICON) return i;
        }
        return -1;
    }

    // ---- Rendering -------------------------------------------------------------------------------------------------

    /** Frame, label and screen, on the art pixel grid. */
    void renderComputer(GuiGraphics g, int mouseX, int mouseY) {
        renderFrame(g, mouseX, mouseY);
        renderLabel(g);
        renderScreen(g);
    }

    /** Drawn behind the module screen: the (blurred) world and this computer, as it is now. */
    void renderBehind(GuiGraphics g, float partialTick) {
        image.updateScreen();
        layoutDisplay();
        renderBackground(g, -1, -1, partialTick);
        renderComputer(g, -1, -1);
    }

    /** CC's frame: border around the content area, sidebar with its buttons; units are art pixels. */
    private void renderFrame(GuiGraphics g, int mouseX, int mouseY) {
        var pose = g.pose();
        pose.pushPose();
        pose.scale((float) (1 / guiScale), (float) (1 / guiScale), 1);
        pose.translate(frameX, frameY, 0);
        pose.scale(art, art, 1);
        var background = on && !image.graphics() ? 0xFF000000 | image.termPalette(15) : 0xFF000000;
        int x = SIDEBAR_WIDTH + BORDER;
        g.fill(x, BORDER, x + innerW + 2 * PAD, BORDER + innerH + 2 * PAD, background);
        g.blitSprite(BORDER_SPRITE, SIDEBAR_WIDTH, 0, innerW + 2 * PAD + 2 * BORDER, innerH + 2 * PAD + 2 * BORDER);
        g.blitSprite(SIDEBAR_SPRITE, 0, BORDER, SIDEBAR_WIDTH, buttons.size() * ICON_STEP + 6);
        var hover = buttonAt(mouseX, mouseY);
        for (var i = 0; i < buttons.size(); i++) {
            var sprite = "buttons/" + buttons.get(i).sprite().get() + (i == hover ? "_hover" : "");
            g.blitSprite(ResourceLocation.fromNamespaceAndPath("ccbaremetal", sprite), 4, BORDER + 5 + i * ICON_STEP, ICON, ICON);
        }
        pose.popPose();
    }

    private static String formatRate(double bytesPerSecond) {
        if (bytesPerSecond < 1024) return Math.round(bytesPerSecond) + " B/s";
        return String.format("%.1f KiB/s", bytesPerSecond / 1024);
    }

    /** Title line above the frame: name, id, size, notices. */
    private void renderLabel(GuiGraphics g) {
        var title = this.title.getString() + " #" + menu.computerId();
        if (on && !image.graphics()) title += "   " + image.termWidth() + "\u00d7" + image.termHeight();
        if (on && image.graphics()) {
            // Graphics mode: resolution, colours and what the screen currently costs on the network.
            var depth = image.fbBpp() > 8 ? image.fbBpp() + "-bit" // true colour: no palette to count
                : (1 << image.fbBpp()) + " " + Component.translatable("gui.ccbaremetal.colours").getString();
            title += "   " + image.fbWidth() + "\u00d7" + image.fbHeight() + " " + depth + "   " + formatRate(image.bytesPerSecond());
        }
        if (image.scrollOffset() > 0) title += "   \u2191" + image.scrollOffset();
        if (System.currentTimeMillis() < noticeUntil) title += "   " + notice;
        int x = (int) Math.ceil((frameX + SIDEBAR_WIDTH * art) / guiScale);
        int y = (int) Math.floor(frameY / guiScale) - LABEL + 1;
        int maxWidth = (int) ((windowW() - frameX - SIDEBAR_WIDTH * art) / guiScale);
        if (y >= 0) g.drawString(font, font.plainSubstrByWidth(title, Math.max(0, maxWidth)), x, y, on ? 0xFFD8C4FF : 0xFFB0B0B0, true);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        image.updateScreen();
        layoutDisplay();
        super.render(g, mouseX, mouseY, partialTick);
        renderComputer(g, mouseX, mouseY);
        var hover = buttonAt(mouseX, mouseY);
        if (hover >= 0) g.renderTooltip(font, font.split(buttons.get(hover).tooltip().get(), 240), mouseX, mouseY);
    }

    /** Tooltip of sidebar button {@code i} (also shown by the module screen). */
    Component buttonTooltip(int i) {
        return buttons.get(i).tooltip().get();
    }

    /** The terminal or framebuffer, cursor, scrollbar; the hint when off. */
    private void renderScreen(GuiGraphics g) {
        var pose = g.pose();
        pose.pushPose();
        pose.scale((float) (1 / guiScale), (float) (1 / guiScale), 1);
        var tw = image.textureWidth();
        var th = image.textureHeight();
        var x0 = (int) dispX;
        var y0 = (int) dispY;
        // Off: an empty screen with the hint, as in CC (not what was on it before).
        if (on) g.blit(image.texture(), x0, y0, (int) Math.round(tw * pixelScale), (int) Math.round(th * pixelScale), 0, 0, tw, th, tw, th);
        if (on && !image.graphics() && image.scrollOffset() == 0 && image.cursorBlink && (System.currentTimeMillis() / 400) % 2 == 0
            && image.cursorX >= 0 && image.cursorY >= 0 && image.cursorX < image.termWidth() && image.cursorY < image.termHeight()) {
            var cx = dispX + image.cursorX * cellW;
            var cy = dispY + (image.cursorY + 1) * cellH - 2 * pixelScale;
            var colour = 0xFF000000 | image.termPalette(image.cursorColour);
            g.fill((int) cx, (int) cy, (int) Math.ceil(cx + cellW), (int) Math.ceil(cy + Math.max(1, pixelScale)), colour);
        }
        if (!image.graphics() && image.scrollOffset() > 0 && image.historySize() > 0) {
            // Scrollbar while looking at the history.
            var total = image.historySize() + image.termHeight();
            var barH = th * pixelScale;
            var thumbH = Math.max(8 * pixelScale, barH * image.termHeight() / total);
            var thumbY = dispY + (barH - thumbH) * (image.historySize() - image.scrollOffset()) / image.historySize();
            var bx = (int) (dispX + tw * pixelScale - 3 * pixelScale);
            g.fill(bx, (int) dispY, (int) (bx + 3 * pixelScale), (int) (dispY + barH), 0x40FFFFFF);
            g.fill(bx, (int) thumbY, (int) (bx + 3 * pixelScale), (int) (thumbY + thumbH), 0xC0FFFFFF);
        }
        pose.popPose();
        if (!on) {
            int cx = (int) ((frameX + (SIDEBAR_WIDTH + BORDER + PAD + innerW / 2.0) * art) / guiScale);
            int cy = (int) ((frameY + (BORDER + PAD + innerH / 2.0) * art) / guiScale) - 4;
            g.drawCenteredString(font, Component.translatable("gui.baremetal.off"), cx, cy, 0xFFFFFFFF);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft.player != null && (!minecraft.player.isAlive() || minecraft.player.isRemoved())) {
            minecraft.player.closeContainer();
            return;
        }
        if (heldShortcut >= 0 && System.currentTimeMillis() - heldSince > 1000) {
            var action = switch (heldShortcut) {
                case GLFW.GLFW_KEY_R -> Payloads.POWER_REBOOT;
                case GLFW.GLFW_KEY_S -> Payloads.POWER_SHUTDOWN;
                default -> Payloads.POWER_TERMINATE;
            };
            power(action);
            heldShortcut = -1;
        }
        if (motionPending && System.currentTimeMillis() - lastMotionSent >= 50) {
            motionPending = false;
            mouseMoved(pendingMouseX, pendingMouseY);
        }
        // The computer was just turned on: size its terminal for this window.
        if (on && sentCols < 0) sendSize();
        sendUploadChunks();
    }

    // ---- Keyboard --------------------------------------------------------------------------------------------------

    private void input(String event, int a, int b, int c, String text) {
        // No connection: the screen is shown by the render preview (DevPreview).
        if (minecraft.getConnection() != null) PacketDistributor.sendToServer(new Payloads.Input(menu.containerId, event, a, b, c, text));
    }

    private static int modifiers(int glfwMods) {
        var mods = 0;
        if ((glfwMods & GLFW.GLFW_MOD_SHIFT) != 0) mods |= 1;
        if ((glfwMods & GLFW.GLFW_MOD_CONTROL) != 0) mods |= 2;
        if ((glfwMods & GLFW.GLFW_MOD_ALT) != 0) mods |= 4;
        return mods;
    }

    private void notice(String key, Object... args) {
        notice = Component.translatable(key, args).getString();
        noticeUntil = System.currentTimeMillis() + 2000;
    }

    // ---- Files dropped from the desktop -----------------------------------------------------------------------------

    /** Files or folders dragged from the system's file manager: uploaded into the shell's current folder. */
    @Override
    public void onFilesDrop(java.util.List<java.nio.file.Path> dropped) {
        if (!on) {
            notice("gui.ccbaremetal.upload_off");
            return;
        }
        if (upload != null) return;
        var paths = new java.util.ArrayList<String>();
        var sizes = new java.util.ArrayList<Long>();
        var data = new java.io.ByteArrayOutputStream();
        try {
            for (var root : dropped) {
                var base = root.getFileName() == null ? "upload" : root.getFileName().toString();
                if (java.nio.file.Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    try (var walk = java.nio.file.Files.walk(root)) {
                        for (var f : (Iterable<java.nio.file.Path>) walk.sorted()::iterator) {
                            if (java.nio.file.Files.isSymbolicLink(f)) continue;
                            var rel = base + (f.equals(root) ? "" : "/" + root.relativize(f).toString().replace('\\', '/'));
                            if (java.nio.file.Files.isDirectory(f)) {
                                paths.add(rel + "/");
                                sizes.add(0L);
                            } else {
                                addFile(f, rel, paths, sizes, data);
                            }
                        }
                    }
                } else if (java.nio.file.Files.isRegularFile(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    addFile(root, base, paths, sizes, data);
                }
                if (paths.size() > Payloads.UploadStart.MAX_FILES) throw new java.io.IOException("too many files");
            }
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            notice("gui.ccbaremetal.upload_failed", e.getMessage());
            return;
        }
        if (paths.isEmpty()) return;
        upload = new Upload(++nextUploadId, paths, sizes, data.toByteArray());
        uploadSent = -1;
        notice("gui.ccbaremetal.upload_sending", paths.size());
        if (minecraft.getConnection() != null) PacketDistributor.sendToServer(new Payloads.UploadStart(menu.containerId, upload.id(), paths, sizes));
    }

    private static final long UPLOAD_CLIENT_LIMIT = 64L << 20;

    private static void addFile(java.nio.file.Path f, String rel, java.util.List<String> paths, java.util.List<Long> sizes,
                                java.io.ByteArrayOutputStream data) throws java.io.IOException {
        if (data.size() + java.nio.file.Files.size(f) > UPLOAD_CLIENT_LIMIT) throw new java.io.IOException("more than 64 MiB");
        var bytes = java.nio.file.Files.readAllBytes(f);
        paths.add(rel);
        sizes.add((long) bytes.length);
        data.writeBytes(bytes);
    }

    /** The server's answer: go ahead (asking first if files would be overwritten), done, or failed. */
    void onUploadReply(Payloads.UploadReply reply) {
        if (upload == null || reply.uploadId() != upload.id()) return;
        switch (reply.status()) {
            case Payloads.UPLOAD_READY -> {
                if (reply.conflicts().isEmpty()) {
                    startSending(false);
                    return;
                }
                var shown = String.join(", ", reply.conflicts().subList(0, Math.min(5, reply.conflicts().size())))
                    + (reply.conflicts().size() > 5 ? ", ..." : "");
                openingModules = true;
                minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes -> {
                    minecraft.setScreen(this);
                    if (yes) startSending(true);
                    else upload = null;
                }, Component.translatable("gui.ccbaremetal.upload_overwrite_title"),
                    Component.translatable("gui.ccbaremetal.upload_overwrite", reply.conflicts().size(), reply.dir(), shown)));
            }
            case Payloads.UPLOAD_DONE -> {
                upload = null;
                notice("gui.ccbaremetal.upload_done", reply.count(), reply.dir());
                noticeUntil = System.currentTimeMillis() + 4000;
            }
            default -> {
                upload = null;
                notice("gui.ccbaremetal.upload_failed", reply.message());
                noticeUntil = System.currentTimeMillis() + 5000;
            }
        }
    }

    private void startSending(boolean overwrite) {
        uploadOverwrite = overwrite;
        uploadSent = 0;
    }

    /** A few chunks per tick, so a big upload does not stall the connection. */
    private void sendUploadChunks() {
        if (upload == null || uploadSent < 0 || minecraft.getConnection() == null) return;
        var data = upload.data();
        for (var i = 0; i < 8; i++) {
            var end = Math.min(data.length, uploadSent + Payloads.UploadData.CHUNK);
            var last = end == data.length;
            PacketDistributor.sendToServer(new Payloads.UploadData(menu.containerId, upload.id(),
                java.util.Arrays.copyOfRange(data, uploadSent, end), last, uploadOverwrite));
            uploadSent = end;
            if (last) {
                uploadSent = -1;
                return;
            }
        }
    }

    /** A program copied text (OSC 52): only while its screen is open in front of the player. */
    public void programCopied(String text) {
        minecraft.keyboardHandler.setClipboard(text);
        notice("gui.ccbaremetal.copied", text.codePointCount(0, text.length()));
    }

    private void copySelection() {
        var text = image.selectedText();
        if (text.isEmpty()) {
            notice("gui.ccbaremetal.nothing_selected");
            return;
        }
        minecraft.keyboardHandler.setClipboard(text);
        notice("gui.ccbaremetal.copied", text.codePointCount(0, text.length()));
        image.clearSelection();
    }

    private void paste() {
        var clip = minecraft.keyboardHandler.getClipboard();
        if (clip.isEmpty()) {
            notice("gui.ccbaremetal.clipboard_empty");
            return;
        }
        clip = clip.replace("\r\n", "\n");
        if (clip.length() > 1 << 16) clip = clip.substring(0, 1 << 16);
        for (var i = 0; i < clip.length(); i += Payloads.Input.MAX_TEXT) {
            input("paste", 0, 0, 0, clip.substring(i, Math.min(clip.length(), i + Payloads.Input.MAX_TEXT)));
        }
        image.setScrollOffset(0);
    }

    private void scrollHistory(int lines) {
        if (image.alternate()) return;
        image.setScrollOffset(image.scrollOffset() + lines);
    }

    /** Whether a key event is the given key: by GLFW key code or, for non-US layouts, by its physical position. */
    private static boolean is(int keyCode, int scanCode, int glfwKey) {
        if (keyCode == glfwKey) return true;
        if (scanCode <= 0) return false;
        try {
            return GLFW.glfwGetKeyScancode(glfwKey) == scanCode;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isModifier(int key) {
        return key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT || key == GLFW.GLFW_KEY_LEFT_CONTROL
            || key == GLFW.GLFW_KEY_RIGHT_CONTROL || key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_RIGHT_ALT
            || key == GLFW.GLFW_KEY_LEFT_SUPER || key == GLFW.GLFW_KEY_RIGHT_SUPER;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int mods) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (!on) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
                power(Payloads.POWER_ON);
            }
            return true;
        }
        var ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0;
        var shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;

        // Clipboard: Ctrl+Shift+C / Ctrl+Shift+V always; Ctrl+C only with a selection (else it is the program's ^C).
        var keyC = is(keyCode, scanCode, GLFW.GLFW_KEY_C);
        var keyV = is(keyCode, scanCode, GLFW.GLFW_KEY_V);
        if ((ctrl && keyC && (shift || image.hasSelection())) || (ctrl && keyCode == GLFW.GLFW_KEY_INSERT)) {
            copySelection();
            return true;
        }
        if ((ctrl && keyV) || (shift && keyCode == GLFW.GLFW_KEY_INSERT)) {
            paste();
            return true;
        }
        if (ctrl && shift && is(keyCode, scanCode, GLFW.GLFW_KEY_A) && !image.graphics()) {
            image.selectAll();
            return true;
        }

        // Font size
        if (ctrl && (keyCode == GLFW.GLFW_KEY_EQUAL || keyCode == GLFW.GLFW_KEY_KP_ADD)) {
            setZoom(zoom + 1);
            return true;
        }
        if (ctrl && (keyCode == GLFW.GLFW_KEY_MINUS || keyCode == GLFW.GLFW_KEY_KP_SUBTRACT)) {
            setZoom(zoom - 1);
            return true;
        }
        if (ctrl && (keyCode == GLFW.GLFW_KEY_0 || keyCode == GLFW.GLFW_KEY_KP_0)) {
            BareMetalClientConfig.setTextZoom(0);
            zoom = autoZoom();
            layoutFrame();
            sendSize();
            return true;
        }

        // Scrollback
        if (shift && !ctrl && !image.alternate() && !image.graphics()) {
            var page = Math.max(1, image.termHeight() - 1);
            switch (keyCode) {
                case GLFW.GLFW_KEY_PAGE_UP -> {
                    scrollHistory(page);
                    return true;
                }
                case GLFW.GLFW_KEY_PAGE_DOWN -> {
                    scrollHistory(-page);
                    return true;
                }
                case GLFW.GLFW_KEY_HOME -> {
                    if (image.scrollOffset() > 0 || image.historySize() > 0) {
                        image.setScrollOffset(image.historySize());
                        return true;
                    }
                }
                case GLFW.GLFW_KEY_END -> {
                    if (image.scrollOffset() > 0) {
                        image.setScrollOffset(0);
                        return true;
                    }
                }
                default -> {
                }
            }
        }

        if (ctrl && (keyCode == GLFW.GLFW_KEY_R || keyCode == GLFW.GLFW_KEY_S || keyCode == GLFW.GLFW_KEY_T) && heldShortcut != keyCode) {
            heldShortcut = keyCode;
            heldSince = System.currentTimeMillis();
        }
        if (!isModifier(keyCode)) {
            image.setScrollOffset(0);
            image.clearSelection();
        }
        var repeat = !held.add(keyCode);
        input("key", keyCode, repeat ? 1 : 0, modifiers(mods), "");
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int mods) {
        if (keyCode == heldShortcut) heldShortcut = -1;
        if (held.remove(keyCode)) input("key_up", keyCode, 0, 0, "");
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (!on || (mods & GLFW.GLFW_MOD_CONTROL) != 0 || c < 32) return true;
        input("char", 0, 0, 0, String.valueOf(c));
        return true;
    }

    // ---- Mouse -----------------------------------------------------------------------------------------------------

    /** Mouse position in physical pixels relative to the displayed screen. */
    private double[] physical(double mouseX, double mouseY) {
        return new double[]{mouseX * guiScale - dispX, mouseY * guiScale - dispY};
    }

    private boolean overScreen(double mouseX, double mouseY) {
        var p = physical(mouseX, mouseY);
        return p[0] >= 0 && p[1] >= 0 && p[0] < image.textureWidth() * pixelScale && p[1] < image.textureHeight() * pixelScale;
    }

    /** Graphics mode: framebuffer pixel, clamped. */
    private int[] pixel(double mouseX, double mouseY) {
        var p = physical(mouseX, mouseY);
        var x = (int) Math.floor(p[0] / pixelScale);
        var y = (int) Math.floor(p[1] / pixelScale);
        return new int[]{Math.max(0, Math.min(image.fbWidth() - 1, x)), Math.max(0, Math.min(image.fbHeight() - 1, y))};
    }

    /** Text mode: 0-based cell, clamped to the terminal. */
    private int[] cell(double mouseX, double mouseY) {
        var p = physical(mouseX, mouseY);
        var x = (int) Math.floor(p[0] / cellW);
        var y = (int) Math.floor(p[1] / cellH);
        return new int[]{Math.max(0, Math.min(image.termWidth() - 1, x)), Math.max(0, Math.min(image.termHeight() - 1, y))};
    }

    /** Whether mouse events go to the program rather than to selection/scrolling. */
    private boolean programGetsMouse(int mods) {
        if (image.graphics()) return true;
        return programMouse && (mods & GLFW.GLFW_MOD_SHIFT) == 0;
    }

    private static boolean shiftDown() {
        return Screen.hasShiftDown();
    }

    private static int ccButton(int button) {
        return switch (button) {
            case 1 -> 2;
            case 2 -> 3;
            default -> 1;
        };
    }

    private void sendMouse(String event, int button, double mouseX, double mouseY) {
        if (image.graphics()) {
            var p = pixel(mouseX, mouseY);
            input(event, button, p[0], p[1], "");
        } else {
            var c = cell(mouseX, mouseY);
            input(event, button, c[0] + 1, c[1] + 1, "");
        }
    }

    /**
     * Plain mouse movement, only while a program wants it (reads /dev/input or enabled xterm mode 1003): sent when
     * the cell (pixel in graphics mode) changes, at most 20 times a second.
     */
    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX, mouseY);
        motionPending = false;
        if (!on || !programMotion || selecting || !overScreen(mouseX, mouseY) || !programGetsMouse(shiftDown() ? GLFW.GLFW_MOD_SHIFT : 0)) return;
        var c = image.graphics() ? pixel(mouseX, mouseY) : cell(mouseX, mouseY);
        var now = System.currentTimeMillis();
        if (c[0] == lastCellX && c[1] == lastCellY) return;
        if (now - lastMotionSent < 50) {
            motionPending = true;
            pendingMouseX = mouseX;
            pendingMouseY = mouseY;
            return;
        }
        lastMotionSent = now;
        lastCellX = c[0];
        lastCellY = c[1];
        sendMouse("mouse_move", 0, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            var b = buttonAt(mouseX, mouseY);
            if (b >= 0) {
                buttons.get(b).action().run();
                return true;
            }
        }
        if (!on || !overScreen(mouseX, mouseY)) return super.mouseClicked(mouseX, mouseY, button);
        if (programGetsMouse(shiftDown() ? GLFW.GLFW_MOD_SHIFT : 0)) {
            sendMouse("mouse_click", ccButton(button), mouseX, mouseY);
            var c = image.graphics() ? pixel(mouseX, mouseY) : cell(mouseX, mouseY);
            lastCellX = c[0];
            lastCellY = c[1];
            return true;
        }
        var c = cell(mouseX, mouseY);
        var line = image.viewLine(c[1]);
        switch (button) {
            case 0 -> {
                var now = System.currentTimeMillis();
                clickCount = now - lastClickTime < 300 && line == lastClickLine && Math.abs(c[0] - lastClickCol) <= 1 ? clickCount + 1 : 1;
                lastClickTime = now;
                lastClickLine = line;
                lastClickCol = c[0];
                anchorLine = line;
                anchorCol = c[0];
                if (clickCount == 2) {
                    image.selectWord(line, c[0]);
                } else if (clickCount >= 3) {
                    image.selectLines(line, line);
                    clickCount = 3;
                } else {
                    image.clearSelection();
                    selecting = true;
                }
            }
            case 1 -> {
                if (image.hasSelection()) copySelection();
                else paste();
            }
            case 2 -> paste();
            default -> {
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (!on) return true;
        if (selecting && button == 0) {
            // Drag past the top/bottom edge to scroll while selecting.
            var p = physical(mouseX, mouseY);
            if (p[1] < 0) scrollHistory(1);
            else if (p[1] >= image.textureHeight() * pixelScale) scrollHistory(-1);
            var c = cell(mouseX, mouseY);
            var line = image.viewLine(c[1]);
            if (line != anchorLine || c[0] != anchorCol || image.hasSelection()) image.select(anchorLine, anchorCol, line, c[0]);
            return true;
        }
        if (programGetsMouse(shiftDown() ? GLFW.GLFW_MOD_SHIFT : 0) && !selecting) {
            var c = image.graphics() ? pixel(mouseX, mouseY) : cell(mouseX, mouseY);
            if (c[0] != lastCellX || c[1] != lastCellY) {
                sendMouse("mouse_drag", ccButton(button), mouseX, mouseY);
                lastCellX = c[0];
                lastCellY = c[1];
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (selecting && button == 0) {
            selecting = false;
            return true;
        }
        if (on && programGetsMouse(shiftDown() ? GLFW.GLFW_MOD_SHIFT : 0) && overScreen(mouseX, mouseY)) {
            sendMouse("mouse_up", ccButton(button), mouseX, mouseY);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return true;
        if (Screen.hasControlDown()) {
            setZoom(zoom + (scrollY > 0 ? 1 : -1));
            return true;
        }
        if (!on) return true;
        var shift = shiftDown();
        if (image.graphics() || (programMouse && !shift)) {
            if (overScreen(mouseX, mouseY)) sendMouse("mouse_scroll", scrollY > 0 ? -1 : 1, mouseX, mouseY);
            return true;
        }
        if (image.alternate()) {
            // Full-screen text programs without mouse support (edit, more): the wheel acts as arrow keys.
            var key = scrollY > 0 ? GLFW.GLFW_KEY_UP : GLFW.GLFW_KEY_DOWN;
            for (var i = 0; i < 3; i++) {
                input("key", key, 0, 0, "");
                input("key_up", key, 0, 0, "");
            }
            return true;
        }
        scrollHistory(scrollY > 0 ? 3 : -3);
        return true;
    }

    /** Esc: close the computer (and its menu on the server). */
    @Override
    public void onClose() {
        if (minecraft.player != null) minecraft.player.closeContainer();
        else super.onClose();
    }

    @Override
    public void removed() {
        super.removed();
        if (openingModules) {
            openingModules = false;
            return;
        }
        image.close();
    }

    /** The module screen closed for good (not back to this screen). */
    void dispose() {
        image.close();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
