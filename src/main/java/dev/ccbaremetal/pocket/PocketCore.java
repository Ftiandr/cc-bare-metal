package dev.ccbaremetal.pocket;

import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.pocket.IPocketAccess;
import dan200.computercraft.api.pocket.IPocketUpgrade;
import dan200.computercraft.api.upgrades.UpgradeData;
import dev.ccbaremetal.computer.ComputerCore;
import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.phys.Vec3;

/**
 * A running WASM pocket computer: its {@link ComputerCore} and its module slots. Every slot gets its own view of the
 * pocket computer ({@link IPocketAccess}), so CC's pocket upgrades - one per pocket computer in CC - work side by side
 * without knowing about each other. Slot 1 is the peripheral "back" (where CC's pocket computers have their upgrade),
 * the others "slot2".."slot4".
 *
 * <p>The item is the truth for what is installed; what upgrades change (their data, the light, the power state) is
 * written back to it from {@link #update}, upgrade data at most every {@link #WRITE_INTERVAL} ticks.
 */
public final class PocketCore implements ComputerCore.Owner {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PocketCore.class);
    public static final int SLOTS = 4;
    private static final String[] NAMES = {"back", "slot2", "slot3", "slot4"};
    private static final int WRITE_INTERVAL = 10;

    private final ComputerCore core;
    private final Slot[] slots = new Slot[SLOTS];
    private ServerLevel level;
    private Entity holder;
    private Vec3 position = Vec3.ZERO;
    long lastTick;

    private boolean wantOn;
    private boolean bootRequested;
    private boolean stateDirty;
    private String label;
    private boolean labelDirty;
    private int light = -1;
    private boolean lightDirty;
    private boolean dataDirty;
    private int writeCooldown;

    PocketCore(int computerId, ItemStack stack) {
        var name = stack.get(DataComponents.CUSTOM_NAME);
        label = name == null ? null : name.getString();
        core = new ComputerCore(this, computerId, label, 80, 30);
        wantOn = stack.getOrDefault(ModRegistry.POCKET_ON.get(), false);
        for (var i = 0; i < SLOTS; i++) slots[i] = new Slot(i);
    }

    public ComputerCore core() {
        return core;
    }

    public Entity holder() {
        return holder;
    }

    /** Peripheral name of a slot (0-based). */
    public static String slotName(int index) {
        return NAMES[index];
    }

    // ---- Ticking (from the item in a player's inventory) -----------------------------------------------------------

    void update(ServerLevel level, ItemStack stack, Entity holder) {
        this.level = level;
        this.holder = holder;
        position = holder.position();
        lastTick = level.getServer().getTickCount();
        if (!dataDirty) syncSlots(stack);
        if (wantOn && !bootRequested) {
            bootRequested = true;
            core.turnOn();
        }
        core.tick();
        var machine = core.machine();
        if (machine != null && machine.isOn()) {
            for (var slot : slots) {
                if (slot.data == null || slot.peripheral == null) continue;
                try {
                    slot.data.upgrade().update(slot, slot.peripheral);
                } catch (RuntimeException e) {
                    // An upgrade's bug must not stop the pocket computer (or the server tick).
                    LOG.warn("Pocket upgrade {} failed to update", slot.data.holder().key().location(), e);
                }
            }
        }
        writeBack(stack);
    }

    void stop() {
        core.stop();
        for (var slot : slots) slot.peripheral = null;
        bootRequested = false;
    }

    /** Install what the item says is in the slots (only slots that changed). */
    private void syncSlots(ItemStack stack) {
        var items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        stack.getOrDefault(ModRegistry.POCKET_UPGRADES.get(), ItemContainerContents.EMPTY).copyInto(items);
        for (var i = 0; i < SLOTS; i++) {
            if (!ItemStack.isSameItemSameComponents(slots[i].stack, items.get(i))) slots[i].install(items.get(i));
        }
    }

    private void writeBack(ItemStack stack) {
        if (stateDirty) {
            stateDirty = false;
            stack.set(ModRegistry.POCKET_ON.get(), wantOn);
        }
        if (labelDirty) {
            labelDirty = false;
            if (label == null) stack.remove(DataComponents.CUSTOM_NAME);
            else stack.set(DataComponents.CUSTOM_NAME, Component.literal(label));
        }
        if (lightDirty) {
            lightDirty = false;
            if (light < 0) stack.remove(ModRegistry.POCKET_LIGHT.get());
            else stack.set(ModRegistry.POCKET_LIGHT.get(), light);
        }
        if (writeCooldown > 0) writeCooldown--;
        if (dataDirty && writeCooldown == 0) {
            dataDirty = false;
            writeCooldown = WRITE_INTERVAL;
            // Keep what a player changed meanwhile: only slots still holding the same item get the upgrade's state.
            var items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
            stack.getOrDefault(ModRegistry.POCKET_UPGRADES.get(), ItemContainerContents.EMPTY).copyInto(items);
            for (var i = 0; i < SLOTS; i++) {
                if (ItemStack.isSameItem(items.get(i), slots[i].stack) || items.get(i).isEmpty()) items.set(i, slots[i].stack.copy());
            }
            stack.set(ModRegistry.POCKET_UPGRADES.get(), ItemContainerContents.fromItems(items));
        }
    }

    // ---- ComputerCore.Owner ---------------------------------------------------------------------------------------

    @Override
    public ServerLevel level() {
        return level;
    }

    @Override
    public BlockPos position() {
        return BlockPos.containing(position);
    }

    @Override
    public void onIdAssigned(int id) {
        // The id is given to the item before the core exists (PocketCores).
    }

    @Override
    public void onLabelChanged(String newLabel) {
        label = newLabel;
        labelDirty = true;
    }

    @Override
    public void onStateChanged(boolean on) {
        if (wantOn != on) {
            wantOn = on;
            stateDirty = true;
        }
    }

    @Override
    public void onBooted() {
        for (var slot : slots) slot.attach();
    }

    // ---- Slots -----------------------------------------------------------------------------------------------------

    /** One module slot, and the pocket computer as its upgrade sees it. */
    private final class Slot implements IPocketAccess {
        private final int index;
        private ItemStack stack = ItemStack.EMPTY;
        private UpgradeData<IPocketUpgrade> data;
        private IPeripheral peripheral;

        Slot(int index) {
            this.index = index;
        }

        void install(ItemStack item) {
            detach();
            stack = item.isEmpty() ? ItemStack.EMPTY : item.copyWithCount(1);
            data = null;
            if (stack.isEmpty() || level == null) return;
            var holder = PocketUpgrades.find(level.registryAccess(), stack);
            if (holder == null) return;
            data = UpgradeData.of(holder, holder.value().getUpgradeData(stack));
            try {
                peripheral = holder.value().createPeripheral(this);
            } catch (RuntimeException e) {
                LOG.warn("Pocket upgrade {} failed to create its peripheral", holder.key().location(), e);
                peripheral = null;
            }
            attach();
        }

        void attach() {
            var machine = core.machine();
            if (machine != null && peripheral != null) machine.peripherals().set(NAMES[index], peripheral);
        }

        void detach() {
            var machine = core.machine();
            if (machine != null && peripheral != null) machine.peripherals().set(NAMES[index], null);
            peripheral = null;
        }

        @Override
        public ServerLevel getLevel() {
            return level;
        }

        @Override
        public Vec3 getPosition() {
            return position;
        }

        @Override
        public Entity getEntity() {
            return holder;
        }

        @Override
        public int getColour() {
            return -1;
        }

        @Override
        public void setColour(int colour) {
        }

        @Override
        public int getLight() {
            return light;
        }

        @Override
        public void setLight(int colour) {
            colour = colour < 0 ? -1 : colour & 0xFFFFFF;
            if (colour != light) {
                light = colour;
                lightDirty = true;
            }
        }

        @Override
        public UpgradeData<IPocketUpgrade> getUpgrade() {
            return data;
        }

        @Override
        public void setUpgrade(UpgradeData<IPocketUpgrade> upgrade) {
            install(upgrade == null ? ItemStack.EMPTY : upgrade.getUpgradeItem());
            dataDirty = true;
        }

        @Override
        public DataComponentPatch getUpgradeData() {
            return data == null ? DataComponentPatch.EMPTY : data.data();
        }

        @Override
        public void setUpgradeData(DataComponentPatch patch) {
            if (data == null || patch.equals(data.data())) return;
            data = UpgradeData.of(data.holder(), patch);
            stack = data.getUpgradeItem();
            dataDirty = true;
        }

        @Override
        public void invalidatePeripheral() {
            if (data == null) return;
            detach();
            peripheral = data.upgrade().createPeripheral(this);
            attach();
        }
    }
}
