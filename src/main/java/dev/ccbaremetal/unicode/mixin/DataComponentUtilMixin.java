package dev.ccbaremetal.unicode.mixin;

import dan200.computercraft.shared.util.DataComponentUtil;
import dev.ccbaremetal.unicode.CcText;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Item names from CC labels (computers, pocket computers, disks): the name shows the text, CC reads back the same
 * bytes it stored.
 */
@Mixin(value = DataComponentUtil.class, remap = false)
public abstract class DataComponentUtilMixin {
    @ModifyArg(method = "setCustomName", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/Component;literal(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;"), require = 0)
    private static String ccbaremetal$name(String label) {
        return CcText.display(label);
    }

    @Inject(method = "getCustomName(Lnet/minecraft/network/chat/Component;)Ljava/lang/String;", at = @At("RETURN"), cancellable = true, require = 0)
    private static void ccbaremetal$label(Component name, CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(CcText.toBytes(cir.getReturnValue()));
    }
}
