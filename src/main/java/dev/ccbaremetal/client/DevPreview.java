package dev.ccbaremetal.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import dan200.computercraft.client.render.RenderTypes;
import dan200.computercraft.client.render.text.DirectFixedWidthFontRenderer;
import dan200.computercraft.client.render.text.FixedWidthFontRenderer;
import dan200.computercraft.core.terminal.Terminal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Development check of the Unicode renderer for CC's terminals: with the environment variable BAREMETAL_RENDER_PREVIEW
 * set to a file name, the client draws a CC terminal with UTF-8 text through CC's own renderer, also fills a
 * monitor (VBO) buffer sized by CC to check it is large enough, saves a PNG and quits. Does nothing otherwise.
 */
public final class DevPreview {
    private static boolean done;
    private static File out;

    private DevPreview() {
    }

    public static void register() {
        if (System.getenv("BAREMETAL_RENDER_PREVIEW") == null) return;
        NeoForge.EVENT_BUS.register(DevPreview.class);
    }

    private static String bytes(String text) {
        return new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    private static Terminal sample() {
        var term = new Terminal(40, 9, true);
        String[] lines = {
            "CraftOS 1.9 (Unicode)",
            "> print(\"Привет, мир!\")",
            "Привет, мир!",
            "|Имя: Ёжик         | 42 |",
            "Ελληνικά, Français, Español: ñ",
            "Box: ┌──┐ arrows: ← → ↑ ↓ ♥ ☺",
            "Lone byte: Ð Latin-1: café",
            "PRINTOUT",
            "> ",
        };
        for (var y = 0; y < lines.length; y++) {
            term.setCursorPos(0, y);
            term.setTextColour(y == 1 || y == 8 ? 4 : 0);
            if (lines[y].equals("PRINTOUT")) {
                term.write("абвгдеёжзийклмнопрстуфхцч"); // characters stored directly, as on printed pages
                continue;
            }
            term.write(lines[y].contains("Latin-1") ? lines[y].replace("Lone byte: Ð", bytes("Lone byte: ") + "Ð") : bytes(lines[y]));
        }
        // a coloured button after Cyrillic text keeps its column
        term.setCursorPos(27, 3);
        term.setBackgroundColour(14);
        term.write(" OK ");
        term.setBackgroundColour(15);
        term.setCursorPos(2, 8);
        term.write(bytes("Ввод"));
        term.setCursorPos(2 + bytes("Ввод").length(), 8);
        term.setCursorBlink(true);
        return term;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (done || !(mc.screen instanceof TitleScreen)) return;
        done = true;
        out = new File(System.getenv("BAREMETAL_RENDER_PREVIEW"));
        try {
            var term = sample();

            // Tooltip of a printout titled in UTF-8 bytes (as CC stores it), on the real client.
            var page = new net.minecraft.world.item.ItemStack(dan200.computercraft.shared.ModRegistry.Items.PRINTED_PAGE.get());
            page.set(dan200.computercraft.shared.ModRegistry.DataComponents.PRINTOUT.get(),
                new dan200.computercraft.shared.media.items.PrintoutData(bytes("Привет мир"), java.util.Collections.nCopies(dan200.computercraft.shared.media.items.PrintoutData.LINES_PER_PAGE, new dan200.computercraft.shared.media.items.PrintoutData.Line(" ".repeat(25), "f".repeat(25)))));
            for (var line : page.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.EMPTY, null, net.minecraft.world.item.TooltipFlag.NORMAL)) {
                System.out.println("[ccbaremetal preview] tooltip: " + line.getString());
            }

            // Monitor path: CC sizes the buffer with getVertexCount; our glyph quads must fit.
            var vertices = DirectFixedWidthFontRenderer.getVertexCount(term);
            var buffer = MemoryUtil.memAlloc(vertices * RenderTypes.TERMINAL.format().getVertexSize());
            try {
                var emitter = new DirectFixedWidthFontRenderer.ByteBufferEmitter(buffer);
                DirectFixedWidthFontRenderer.drawTerminalForeground(emitter, 0, 0, term);
                DirectFixedWidthFontRenderer.drawCursor(emitter, 0, 0, term);
                System.out.println("[ccbaremetal preview] monitor buffer: " + buffer.position() + " of " + buffer.capacity() + " bytes used");
            } finally {
                MemoryUtil.memFree(buffer);
            }

            // GUI path: draw into an offscreen target at 4x.
            var scale = 4;
            int w = (term.getWidth() * FixedWidthFontRenderer.FONT_WIDTH + 4) * scale, h = (term.getHeight() * FixedWidthFontRenderer.FONT_HEIGHT + 4) * scale;
            var target = new TextureTarget(w, h, true, Minecraft.ON_OSX);
            target.setClearColor(0, 0, 0, 1);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            var oldProjection = RenderSystem.getProjectionMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, w, h, 0, -1000, 1000), VertexSorting.ORTHOGRAPHIC_Z);
            var modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            modelView.identity();
            RenderSystem.applyModelViewMatrix();
            var pose = new PoseStack();
            pose.scale(scale, scale, 1);
            var buffers = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 20));
            FixedWidthFontRenderer.drawTerminal(FixedWidthFontRenderer.toVertexConsumer(pose, buffers.getBuffer(RenderTypes.TERMINAL)), 2, 2, term, 2, 2, 2, 2);
            buffers.endBatch();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(oldProjection, VertexSorting.ORTHOGRAPHIC_Z);
            try (var image = Screenshot.takeScreenshot(target)) {
                image.writeToFile(out);
            }
            target.destroyBuffers();

            // The WASM computer item as in the creative tab (blinking cursor model), at 8x.
            var itemTarget = new TextureTarget(48 * 8, 16 * 8, true, Minecraft.ON_OSX);
            itemTarget.setClearColor(0.55f, 0.55f, 0.55f, 1);
            itemTarget.clear(Minecraft.ON_OSX);
            itemTarget.bindWrite(true);
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, 48, 16, 0, -1000, 3000), VertexSorting.ORTHOGRAPHIC_Z);
            var graphics = new net.minecraft.client.gui.GuiGraphics(mc, mc.renderBuffers().bufferSource());
            graphics.renderItem(new net.minecraft.world.item.ItemStack(dev.ccbaremetal.registry.ModRegistry.WASM_COMPUTER_ITEM.get()), 0, 0);
            graphics.renderItem(new net.minecraft.world.item.ItemStack(dev.ccbaremetal.registry.ModRegistry.WASM_TURTLE_ITEM.get()), 16, 0);
            var pocketOn = new net.minecraft.world.item.ItemStack(dev.ccbaremetal.registry.ModRegistry.WASM_POCKET_COMPUTER.get());
            pocketOn.set(dev.ccbaremetal.registry.ModRegistry.POCKET_ON.get(), true);
            graphics.renderItem(pocketOn, 32, 0);
            graphics.flush();
            RenderSystem.setProjectionMatrix(oldProjection, VertexSorting.ORTHOGRAPHIC_Z);
            try (var image = Screenshot.takeScreenshot(itemTarget)) {
                image.writeToFile(new File(out.getPath().replace(".png", "-item.png")));
            }
            itemTarget.destroyBuffers();
            mc.getMainRenderTarget().bindWrite(true);
            System.out.println("[ccbaremetal preview] wrote " + out);
            openComputerScreen(mc);
        } catch (Throwable t) {
            System.out.println("[ccbaremetal preview] failed: " + t);
            t.printStackTrace(System.out);
        }
        mc.stop();
    }

    /**
     * Our computer window with a sample terminal, drawn straight into an offscreen target of the window's size (there
     * is no world or player here, so the screen is not opened for real and sends nothing).
     */
    private static void openComputerScreen(Minecraft mc) {
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        buf.writeVarInt(7);
        var menu = new dev.ccbaremetal.computer.ComputerMenu(1, null, buf);
        var shown = new ComputerScreen<>(menu, new net.minecraft.world.entity.player.Inventory(null), net.minecraft.network.chat.Component.literal("WASM Computer")) {
            @Override
            public void renderBackground(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                g.fill(0, 0, width, height, 0xFF3A4048);
            }
        };
        var window = mc.getWindow();
        shown.init(mc, window.getGuiScaledWidth(), window.getGuiScaledHeight());
        var size = shown.wantedSize();
        var term = new dev.ccbaremetal.kernel.tty.Terminal(size[0], size[1]);
        var writer = new dev.ccbaremetal.kernel.tty.AnsiWriter(term);
        var text = "CC: Bare Metal 0.1.0 - WebAssembly computer #7\r\n"
            + "user@computer-7:~$ ls /usr/share/examples\r\n"
            + "\u001b[34mcircles.cpp\u001b[0m  exceptions.cpp  framebuffer.cpp  hello.c  paint.cpp  touchpaint.cpp\r\n"
            + "user@computer-7:~$ echo 'Привет, мир!'\r\nПривет, мир!\r\n"
            + "\u001b[41m red \u001b[42m green \u001b[44m blue \u001b[0m\r\n"
            + "user@computer-7:~$ ";
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        writer.write(bytes, 0, bytes.length);
        shown.setState(true, false, false, false);
        shown.onTerminal(dev.ccbaremetal.network.Compression.deflate(term.snapshot().data()));
        var reboot = shown.buttonCentre(1);
        renderShot(mc, shown, (int) reboot[0], (int) reboot[1], "-gui.png");
        shown.setState(false, false, false, false);
        renderShot(mc, shown, -1, -1, "-gui-off.png");
        shown.removed();

        // The pocket computer with its module panel open (two modules installed).
        var pocketBuf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        pocketBuf.writeVarInt(8);
        pocketBuf.writeVarInt(0);
        var pocketMenu = new dev.ccbaremetal.pocket.PocketComputerMenu(2, new net.minecraft.world.entity.player.Inventory(null), pocketBuf);
        pocketMenu.slots.get(0).set(new net.minecraft.world.item.ItemStack(dan200.computercraft.shared.ModRegistry.Items.SPEAKER.get()));
        pocketMenu.slots.get(1).set(new net.minecraft.world.item.ItemStack(dan200.computercraft.shared.ModRegistry.Items.WIRELESS_MODEM_ADVANCED.get()));
        var inventory = new net.minecraft.world.entity.player.Inventory(null);
        var pocketScreen = new ComputerScreen<>(pocketMenu, inventory, net.minecraft.network.chat.Component.literal("WASM Pocket Computer")) {
            @Override
            public void renderBackground(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                g.fill(0, 0, width, height, 0xFF3A4048);
            }
        };
        pocketScreen.init(mc, window.getGuiScaledWidth(), window.getGuiScaledHeight());
        pocketScreen.setState(true, false, false, false);
        pocketScreen.onTerminal(dev.ccbaremetal.network.Compression.deflate(term.snapshot().data()));
        var modulesScreen = new ModulesScreen<>(pocketScreen, pocketMenu, inventory);
        modulesScreen.init(mc, window.getGuiScaledWidth(), window.getGuiScaledHeight());
        var modules = pocketScreen.buttonCentre(5);
        renderShot(mc, modulesScreen, (int) modules[0], (int) modules[1], "-pocket.png");
        pocketScreen.dispose();

        // The turtle's module panel: two upgrades, its inventory (selected slot 1), fuel.
        var turtleBuf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        turtleBuf.writeVarInt(9);
        var turtleMenu = new dev.ccbaremetal.turtle.TurtleMenu(3, inventory, turtleBuf);
        turtleMenu.slots.get(0).set(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
        turtleMenu.slots.get(1).set(new net.minecraft.world.item.ItemStack(dan200.computercraft.shared.ModRegistry.Items.WIRELESS_MODEM_NORMAL.get()));
        turtleMenu.slots.get(2).set(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 37));
        turtleMenu.setData(1, 160);
        var turtleScreen = new ComputerScreen<>(turtleMenu, inventory, net.minecraft.network.chat.Component.literal("WASM Turtle")) {
            @Override
            public void renderBackground(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                g.fill(0, 0, width, height, 0xFF3A4048);
            }
        };
        turtleScreen.init(mc, window.getGuiScaledWidth(), window.getGuiScaledHeight());
        turtleScreen.setState(true, false, false, false);
        turtleScreen.onTerminal(dev.ccbaremetal.network.Compression.deflate(term.snapshot().data()));
        var turtleModules = new ModulesScreen<>(turtleScreen, turtleMenu, inventory);
        turtleModules.init(mc, window.getGuiScaledWidth(), window.getGuiScaledHeight());
        renderShot(mc, turtleModules, -1, -1, "-turtle.png");
        turtleScreen.dispose();
    }

    private static void renderShot(Minecraft mc, net.minecraft.client.gui.screens.Screen shown, int mouseX, int mouseY, String suffix) {
        var window = mc.getWindow();
        int w = window.getWidth(), h = window.getHeight();
        var target = new TextureTarget(w, h, true, Minecraft.ON_OSX);
        target.setClearColor(0, 0, 0, 1);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        var oldProjection = RenderSystem.getProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, (float) (w / window.getGuiScale()), (float) (h / window.getGuiScale()), 0, 1000, 21000),
            VertexSorting.ORTHOGRAPHIC_Z);
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        modelView.translate(0, 0, -11000);
        RenderSystem.applyModelViewMatrix();
        var graphics = new net.minecraft.client.gui.GuiGraphics(mc, mc.renderBuffers().bufferSource());
        shown.render(graphics, mouseX, mouseY, 0);
        graphics.flush();
        modelView.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(oldProjection, VertexSorting.ORTHOGRAPHIC_Z);
        var file = new File(out.getPath().replace(".png", suffix));
        try (var image = Screenshot.takeScreenshot(target)) {
            image.writeToFile(file);
            System.out.println("[ccbaremetal preview] wrote " + file + " (" + w + "x" + h + ", gui scale " + window.getGuiScale() + ")");
        } catch (Exception e) {
            System.out.println("[ccbaremetal preview] screenshot failed: " + e);
        }
        target.destroyBuffers();
        mc.getMainRenderTarget().bindWrite(true);
    }

}
