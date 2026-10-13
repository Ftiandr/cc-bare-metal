package dev.ccbaremetal.unicode.mixin;

import dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity;
import dev.ccbaremetal.unicode.CcText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** A computer's label as its block name and as the name of the item it drops. */
@Mixin(value = AbstractComputerBlockEntity.class, remap = false)
public abstract class AbstractComputerBlockEntityMixin {
    @ModifyArg(method = {"getName", "getCustomName", "collectSafeComponents"}, at = @At(value = "INVOKE", target = CcText.LITERAL), require = 0)
    private String ccbaremetal$label(String label) {
        return CcText.display(label);
    }
}
