package dev.ccbaremetal.unicode.mixin;

import dan200.computercraft.core.input.UserComputerInput;
import dan200.computercraft.core.util.StringUtil;
import dev.ccbaremetal.unicode.UnicodeConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Typing characters CC's charset does not have (Cyrillic, Greek, CJK...): CC drops them; they are sent as UTF-8
 * instead, as a "paste" event (which read(), edit and other programs already handle). A "char" event stays one byte.
 */
@Mixin(value = UserComputerInput.class, remap = false)
public abstract class UserComputerInputMixin {
    @Inject(method = "codepointTyped", at = @At("HEAD"), cancellable = true, require = 0)
    private void ccbaremetal$typed(int codepoint, CallbackInfo ci) {
        if (!UnicodeConfig.enabled() || codepoint < 0x80 || StringUtil.unicodeToTerminal(codepoint) >= 0) return;
        if (Character.isSurrogate((char) codepoint) || Character.isISOControl(codepoint)) return;
        var bytes = new String(Character.toChars(codepoint)).getBytes(StandardCharsets.UTF_8);
        ((UserComputerInput) (Object) this).paste(ByteBuffer.wrap(bytes).asReadOnlyBuffer());
        ci.cancel();
    }
}
