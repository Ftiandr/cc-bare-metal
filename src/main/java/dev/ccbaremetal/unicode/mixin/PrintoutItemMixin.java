package dev.ccbaremetal.unicode.mixin;

import dan200.computercraft.shared.media.items.PrintoutItem;
import dev.ccbaremetal.unicode.CcText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The printout title as the title of its screen. */
@Mixin(value = PrintoutItem.class, remap = false)
public abstract class PrintoutItemMixin {
    @ModifyArg(method = "use", at = @At(value = "INVOKE", target = CcText.LITERAL), require = 0)
    private String ccbaremetal$title(String title) {
        return CcText.display(title);
    }
}
