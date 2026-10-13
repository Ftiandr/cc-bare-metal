package dev.ccbaremetal.unicode.mixin;

import dan200.computercraft.api.lua.Coerced;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.shared.peripheral.printer.PrinterPeripheral;
import dev.ccbaremetal.unicode.CcText;
import dev.ccbaremetal.unicode.UnicodeConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Printers store characters, not bytes: printer.write("Привет") puts 6 letters on the page and moves the cursor by
 * 6, so a line holds 25 letters in any language. Text that is not UTF-8 (CC's own charset) is stored as before.
 * Programs that print line by line (write, then setCursorPos to the next line), which is how printers are used, see
 * no difference; getCursorPos reports where the text really ended.
 */
@Mixin(value = PrinterPeripheral.class, remap = false)
public abstract class PrinterPeripheralMixin {
    @Shadow
    private Terminal getCurrentPage() throws LuaException {
        throw new AssertionError();
    }

    @Inject(method = "write", at = @At("HEAD"), cancellable = true, require = 0)
    private void ccbaremetal$write(Coerced<String> textA, CallbackInfo ci) throws LuaException {
        if (!UnicodeConfig.enabled()) return;
        var text = CcText.display(textA.value());
        if (text == textA.value()) return; // no UTF-8: CC's own code
        var page = getCurrentPage();
        page.write(text);
        page.setCursorPos(page.getCursorX() + text.length(), page.getCursorY());
        ci.cancel();
    }
}
