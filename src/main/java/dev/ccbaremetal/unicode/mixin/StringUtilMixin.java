package dev.ccbaremetal.unicode.mixin;

import dan200.computercraft.core.util.StringUtil;
import dev.ccbaremetal.unicode.UnicodeConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Pasting: characters outside CC's charset are kept as UTF-8 instead of being removed. Everything else is CC's
 * behaviour: its own characters as one byte, the first line only, at most MAX_PASTE_LENGTH bytes (never cutting a
 * character in half).
 */
@Mixin(value = StringUtil.class, remap = false)
public abstract class StringUtilMixin {
    /** Labels and titles (os.setComputerLabel, disk labels, printer page titles) keep UTF-8 characters. */
    @Inject(method = "normaliseLabel", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ccbaremetal$label(String text, CallbackInfoReturnable<String> cir) {
        if (!UnicodeConfig.enabled()) return;
        cir.setReturnValue(dev.ccbaremetal.unicode.CcText.normaliseLabel(text));
    }

    @Inject(method = "getClipboardString", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ccbaremetal$clipboard(String clipboard, CallbackInfoReturnable<ByteBuffer> cir) {
        if (!UnicodeConfig.enabled()) return;
        var output = new byte[StringUtil.MAX_PASTE_LENGTH];
        var idx = 0;
        var iterator = clipboard.codePoints().iterator();
        while (iterator.hasNext()) {
            var cp = iterator.next();
            var chr = StringUtil.unicodeToTerminal(cp);
            byte[] encoded;
            if (chr >= 0) {
                if (!StringUtil.isTypableChar(chr)) break; // stop at the end of the first line, like CC
                encoded = new byte[]{(byte) chr};
            } else if (cp >= 0x80 && !Character.isISOControl(cp)) {
                encoded = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            } else {
                continue;
            }
            if (idx + encoded.length > output.length) break;
            System.arraycopy(encoded, 0, output, idx, encoded.length);
            idx += encoded.length;
        }
        cir.setReturnValue(ByteBuffer.wrap(output, 0, idx).asReadOnlyBuffer());
    }
}
