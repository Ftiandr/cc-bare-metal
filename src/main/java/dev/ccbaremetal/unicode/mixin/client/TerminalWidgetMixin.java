package dev.ccbaremetal.unicode.mixin.client;

import dan200.computercraft.client.gui.widgets.TerminalWidget;
import dan200.computercraft.core.input.UserComputerInput;
import dan200.computercraft.core.terminal.Terminal;
import dev.ccbaremetal.unicode.Utf8Layout;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Mouse positions on lines with UTF-8 text: the column the player clicked to the cell the program wrote there. */
@Mixin(value = TerminalWidget.class, remap = false)
public abstract class TerminalWidgetMixin {
    @Shadow
    @Final
    private Terminal terminal;

    @Unique
    private int ccbaremetal$cell(int x, int y) {
        return Utf8Layout.logicalX(terminal, x - 1, y - 1) + 1;
    }

    @Redirect(method = "mouseClicked", at = @At(value = "INVOKE", target = "Ldan200/computercraft/core/input/UserComputerInput;mouseClick(III)V"), require = 0)
    private void ccbaremetal$click(UserComputerInput input, int button, int x, int y) {
        input.mouseClick(button, ccbaremetal$cell(x, y), y);
    }

    @Redirect(method = "mouseReleased", at = @At(value = "INVOKE", target = "Ldan200/computercraft/core/input/UserComputerInput;mouseUp(III)V"), require = 0)
    private void ccbaremetal$up(UserComputerInput input, int button, int x, int y) {
        input.mouseUp(button, ccbaremetal$cell(x, y), y);
    }

    @Redirect(method = "mouseDragged", at = @At(value = "INVOKE", target = "Ldan200/computercraft/core/input/UserComputerInput;mouseDrag(III)V"), require = 0)
    private void ccbaremetal$drag(UserComputerInput input, int button, int x, int y) {
        input.mouseDrag(button, ccbaremetal$cell(x, y), y);
    }

    @Redirect(method = "mouseScrolled", at = @At(value = "INVOKE", target = "Ldan200/computercraft/core/input/UserComputerInput;mouseScroll(III)V"), require = 0)
    private void ccbaremetal$scroll(UserComputerInput input, int direction, int x, int y) {
        input.mouseScroll(direction, ccbaremetal$cell(x, y), y);
    }
}
