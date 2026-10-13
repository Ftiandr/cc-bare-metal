package dev.ccbaremetal.unicode.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.shared.peripheral.monitor.MonitorBlockEntity;
import dev.ccbaremetal.unicode.Utf8Layout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Touching a monitor that shows UTF-8 text: the column the player hit to the cell the program wrote there (the
 * same layout the clients draw with).
 */
@Mixin(value = MonitorBlockEntity.class, remap = false)
public abstract class MonitorBlockEntityMixin {
    @ModifyVariable(method = "monitorTouched", at = @At("LOAD"), name = "xCharPos", require = 0)
    private int ccbaremetal$touch(int xCharPos, @Local(name = "yCharPos") int yCharPos, @Local(name = "originTerminal") Terminal terminal) {
        return Utf8Layout.logicalX(terminal, xCharPos - 1, yCharPos - 1) + 1;
    }
}
