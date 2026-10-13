package dev.ccbaremetal.computer;

import dev.ccbaremetal.kernel.dev.KeyboardDevice;
import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.network.Payloads;
import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * A player typing on a computer with the keyboard (in hand, or placed as a block): every key goes to the computer
 * (Esc too; Shift+Esc leaves), the player stands still, looks around and sees the world, not the screen. Server side
 * it holds the keyboard's input device (/dev/input/eventN) for as long as it is open, and stays open while
 * {@code valid} holds (see {@link #holding} and {@link KeyboardBlock}).
 */
public final class KeyboardMenu extends AbstractContainerMenu {
    public static final double RANGE = 64;

    private final int computerId;
    private final String label;
    private final ComputerCore core;
    private final KeyboardDevice device;
    private final java.util.function.Predicate<Player> valid;

    /** Client constructor. */
    public KeyboardMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModRegistry.KEYBOARD_MENU.get(), containerId);
        computerId = buf.readVarInt();
        label = buf.readUtf();
        core = null;
        device = null;
        valid = p -> true;
    }

    /** Server constructor: plugs the keyboard in; open while {@code valid} holds (and the computer runs). */
    public KeyboardMenu(int containerId, ComputerCore core, ServerPlayer player, java.util.function.Predicate<Player> valid) {
        super(ModRegistry.KEYBOARD_MENU.get(), containerId);
        this.valid = valid;
        this.computerId = core.computerId();
        this.label = core.label() == null ? "" : core.label();
        this.core = core;
        this.device = core.machine().attachKeyboard(player.getGameProfile().getName());
    }

    public int computerId() {
        return computerId;
    }

    public String label() {
        return label;
    }

    /** A key, character or mouse event from the player's keyboard screen. */
    public void input(Payloads.Input p) {
        var machine = core.machine();
        if (machine == null || device == null) return;
        var event = switch (p.event()) {
            case "key" -> Event.of("key", p.a(), p.b() != 0);
            case "key_up" -> Event.of("key_up", p.a());
            case "char" -> p.text().isEmpty() ? null : Event.of("char", p.text());
            case "paste" -> Event.of("paste", p.text());
            case "mouse_relative" -> Event.of("mouse_relative", p.a(), p.b());
            case "mouse_button" -> Event.of("mouse_button", p.a(), p.b() != 0);
            case "mouse_wheel" -> Event.of("mouse_wheel", p.a());
            default -> null;
        };
        if (event != null) machine.keyboardInput(device, event, p.c());
    }

    /** Open the keyboard screen for {@code player} on {@code core}, open while {@code valid} holds. */
    public static void open(ServerPlayer player, ComputerCore core, java.util.function.Predicate<Player> valid) {
        var title = net.minecraft.network.chat.Component.translatable("gui.ccbaremetal.keyboard");
        player.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inventory, p) -> new KeyboardMenu(id, core, player, valid), title), buf -> {
            buf.writeVarInt(core.computerId());
            buf.writeUtf(core.label() == null ? "" : core.label());
        });
    }

    /** Typing with the keyboard in hand: it is linked to the computer and the player is within {@link #RANGE} of it. */
    public static java.util.function.Predicate<Player> holding(ComputerCore core) {
        var id = core.computerId();
        return player -> {
            var holds = false;
            for (var stack : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()}) {
                holds |= stack.is(ModRegistry.KEYBOARD.get()) && Integer.valueOf(id).equals(stack.get(ModRegistry.COMPUTER_ID.get()));
            }
            return holds && core.level() == player.level() && player.distanceToSqr(core.position().getCenter()) <= RANGE * RANGE;
        };
    }

    @Override
    public boolean stillValid(Player player) {
        if (core == null) return true;
        return core.isOn() && valid.test(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        var machine = core == null ? null : core.machine();
        if (machine != null && device != null) machine.detachKeyboard(device);
    }
}
