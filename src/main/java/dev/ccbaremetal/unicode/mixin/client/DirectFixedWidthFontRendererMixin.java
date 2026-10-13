package dev.ccbaremetal.unicode.mixin.client;

import dan200.computercraft.client.render.text.DirectFixedWidthFontRenderer;
import dan200.computercraft.client.render.text.FixedWidthFontRenderer;
import dan200.computercraft.core.terminal.Terminal;
import dev.ccbaremetal.unicode.Utf8Layout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static dan200.computercraft.client.render.text.FixedWidthFontRenderer.FONT_HEIGHT;
import static dan200.computercraft.client.render.text.FixedWidthFontRenderer.FONT_WIDTH;
import static dan200.computercraft.core.util.Colour.BLACK;

/**
 * UTF-8 text on monitors (CC's VBO monitor renderer), as {@link FixedWidthFontRendererMixin}. The monitor's vertex
 * buffer is sized by {@code getVertexCount}, so the rectangles of our glyphs are added to it; the cursor stays the
 * last quad, as CC's renderer expects.
 */
@Mixin(value = DirectFixedWidthFontRenderer.class, remap = false)
public abstract class DirectFixedWidthFontRendererMixin {
    // FixedWidthFontRenderer.BACKGROUND_START/END: a solid white area of CC's font texture.
    private static final float SOLID_START = (256.0f - 6.0f) / 256.0f;
    private static final float SOLID_END = (256.0f - 4.0f) / 256.0f;

    @Shadow
    private static void drawChar(DirectFixedWidthFontRenderer.QuadEmitter emitter, float x, float y, int index, int colour) {
    }

    @Inject(method = "drawTerminalForeground", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ccbaremetal$drawForeground(DirectFixedWidthFontRenderer.QuadEmitter emitter, float x, float y, Terminal terminal, CallbackInfo ci) {
        var anyUnicode = false;
        for (var i = 0; i < terminal.getHeight() && !anyUnicode; i++) anyUnicode = Utf8Layout.layout(terminal, i) != null;
        if (!anyUnicode) return;
        ci.cancel();
        var palette = terminal.getPalette();
        for (var i = 0; i < terminal.getHeight(); i++) {
            var rowY = y + FONT_HEIGHT * i;
            var line = Utf8Layout.layout(terminal, i);
            var textColour = terminal.getTextColourLine(i);
            if (line == null) {
                DirectFixedWidthFontRenderer.drawString(emitter, x, rowY, terminal.getLine(i), textColour, palette);
                continue;
            }
            for (var g = 0; g < line.glyphs(); g++) {
                var cp = line.codepoints()[g];
                var colour = palette.getRenderColours(FixedWidthFontRenderer.getColour(textColour.charAt(line.cells()[g]), BLACK));
                var gx = x + line.columns()[g] * FONT_WIDTH;
                var cc = Utf8Layout.ccGlyph(cp);
                if (cc >= 0) {
                    drawChar(emitter, gx, rowY, cc, colour);
                    continue;
                }
                var runs = Utf8Layout.runs(cp);
                for (var r = 0; r < runs.length; r += 3) {
                    var px = gx + runs[r];
                    var py = rowY + runs[r + 1];
                    emitter.quad(px, py, px + runs[r + 2], py + 1, 0, colour, SOLID_START, SOLID_START, SOLID_END, SOLID_END);
                }
            }
        }
    }

    @Inject(method = "drawCursor", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ccbaremetal$drawCursor(DirectFixedWidthFontRenderer.QuadEmitter emitter, float x, float y, Terminal terminal, CallbackInfo ci) {
        var line = Utf8Layout.layout(terminal, terminal.getCursorY());
        if (line == null) return;
        ci.cancel();
        if (FixedWidthFontRenderer.isCursorVisible(terminal)) {
            var colour = terminal.getPalette().getRenderColours(15 - terminal.getTextColour());
            drawChar(emitter, x + line.column(terminal.getCursorX()) * FONT_WIDTH, y + terminal.getCursorY() * FONT_HEIGHT, '_', colour);
        }
    }

    @Inject(method = "getVertexCount", at = @At("RETURN"), cancellable = true, require = 0)
    private static void ccbaremetal$vertexCount(Terminal terminal, CallbackInfoReturnable<Integer> cir) {
        var extra = Utf8Layout.extraQuads(terminal);
        if (extra > 0) cir.setReturnValue(cir.getReturnValueI() + extra * 4);
    }
}
