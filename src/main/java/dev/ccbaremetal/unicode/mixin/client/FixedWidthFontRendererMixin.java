package dev.ccbaremetal.unicode.mixin.client;

import dan200.computercraft.client.FrameInfo;
import dan200.computercraft.client.render.text.FixedWidthFontRenderer;
import dan200.computercraft.core.terminal.Palette;
import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.core.terminal.TextBuffer;
import dev.ccbaremetal.unicode.Utf8Layout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dan200.computercraft.client.render.text.FixedWidthFontRenderer.FONT_HEIGHT;
import static dan200.computercraft.client.render.text.FixedWidthFontRenderer.FONT_WIDTH;
import static dan200.computercraft.core.util.Colour.BLACK;

/**
 * UTF-8 text in CC's terminal renderer (computer GUI, pocket computers, printouts): lines with multi-byte
 * characters are drawn through {@link Utf8Layout}; everything else is left to CC. Characters CC has are drawn
 * with CC's glyphs, the rest with our font as solid rectangles (CC's own background quads), so no new render
 * type or texture is involved.
 */
@Mixin(value = FixedWidthFontRenderer.class, remap = false)
public abstract class FixedWidthFontRendererMixin {
    @Shadow
    private static void drawChar(FixedWidthFontRenderer.QuadEmitter emitter, float x, float y, int index, int colour, int light) {
    }

    @Unique
    private static void ccbaremetal$drawLine(FixedWidthFontRenderer.QuadEmitter emitter, float x, float y, Utf8Layout.Line line,
                                        TextBuffer textColour, Palette palette, int light) {
        for (var g = 0; g < line.glyphs(); g++) {
            var cp = line.codepoints()[g];
            var colour = palette.getRenderColours(FixedWidthFontRenderer.getColour(textColour.charAt(line.cells()[g]), BLACK));
            var gx = x + line.columns()[g] * FONT_WIDTH;
            var cc = Utf8Layout.ccGlyph(cp);
            if (cc >= 0) {
                drawChar(emitter, gx, y, cc, colour, light);
                continue;
            }
            var runs = Utf8Layout.runs(cp);
            for (var i = 0; i < runs.length; i += 3) {
                FixedWidthFontRenderer.drawQuad(emitter, gx + runs[i], y + runs[i + 1], 0, runs[i + 2], 1, colour, light);
            }
        }
    }

    @Inject(method = "drawString", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ccbaremetal$drawString(FixedWidthFontRenderer.QuadEmitter emitter, float x, float y, TextBuffer text, TextBuffer textColour,
                                          Palette palette, int light, CallbackInfo ci) {
        var line = Utf8Layout.layout(text, null);
        if (line == null) return;
        ccbaremetal$drawLine(emitter, x, y, line, textColour, palette, light);
        ci.cancel();
    }

    @Inject(method = "drawTerminalForeground", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ccbaremetal$drawForeground(FixedWidthFontRenderer.QuadEmitter emitter, float x, float y, Terminal terminal, CallbackInfo ci) {
        var palette = terminal.getPalette();
        for (var i = 0; i < terminal.getHeight(); i++) {
            var rowY = y + FONT_HEIGHT * i;
            var line = Utf8Layout.layout(terminal, i);
            if (line == null) {
                FixedWidthFontRenderer.drawString(emitter, x, rowY, terminal.getLine(i), terminal.getTextColourLine(i), palette,
                    dan200.computercraft.client.render.RenderTypes.FULL_BRIGHT_LIGHTMAP);
            } else {
                ccbaremetal$drawLine(emitter, x, rowY, line, terminal.getTextColourLine(i), palette,
                    dan200.computercraft.client.render.RenderTypes.FULL_BRIGHT_LIGHTMAP);
            }
        }
        ci.cancel();
    }

    @Inject(method = "drawCursor", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ccbaremetal$drawCursor(FixedWidthFontRenderer.QuadEmitter emitter, float x, float y, Terminal terminal, CallbackInfo ci) {
        var line = Utf8Layout.layout(terminal, terminal.getCursorY());
        if (line == null) return;
        ci.cancel();
        if (FixedWidthFontRenderer.isCursorVisible(terminal) && FrameInfo.getGlobalCursorBlink()) {
            var colour = terminal.getPalette().getRenderColours(15 - terminal.getTextColour());
            drawChar(emitter, x + line.column(terminal.getCursorX()) * FONT_WIDTH, y + terminal.getCursorY() * FONT_HEIGHT, '_', colour,
                dan200.computercraft.client.render.RenderTypes.FULL_BRIGHT_LIGHTMAP);
        }
    }
}
