package dev.ccbaremetal.computer;

import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.shared.computer.core.ServerContext;
import dev.ccbaremetal.BareMetalConfig;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.SystemImage;
import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.kernel.tty.Keys;
import dev.ccbaremetal.network.Compression;
import dev.ccbaremetal.network.FbData;
import dev.ccbaremetal.network.Payloads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The server side of one WASM computer, whatever holds it (a block, a pocket computer): its {@link Machine}, the
 * players viewing its screen, screen sync, input and power. The holder ({@link Owner}) supplies where it is and
 * stores what must be saved.
 */
public final class ComputerCore implements Machine.Host {
    /** Every running machine, so they can be stopped with the server. */
    private static final Set<Machine> RUNNING = Collections.newSetFromMap(new WeakHashMap<>());
    /** Every core that exists (weakly): keyboards find their computer by id. */
    private static final Set<ComputerCore> CORES = Collections.newSetFromMap(new WeakHashMap<>());

    /** What holds the computer. Called on the server thread. */
    public interface Owner {
        ServerLevel level();

        /** Block position of the computer (for a pocket computer: of its holder). */
        BlockPos position();

        /** The computer got an id: store it with the holder. */
        void onIdAssigned(int id);

        default void onLabelChanged(String label) {
        }

        /** The computer was turned on or off: remember it, so it comes back on after a reload. */
        default void onStateChanged(boolean on) {
        }

        default void onRedstoneOutputChanged() {
        }

        /** Peripherals must be attached again (the machine just booted). */
        default void onBooted() {
        }

        /** A viewer resized the terminal: worth saving, so it comes back at that size. */
        default void onTermResized() {
        }
    }

    private Owner owner;
    private int computerId;
    private String label;
    private Machine machine;
    private final Set<ComputerMenu> viewers = new CopyOnWriteArraySet<>();
    private final FileUpload uploads = new FileUpload(this);
    /** Viewers that still need the whole screen; sent once the client has actually opened the menu. */
    private final Set<ComputerMenu> pendingViewers = new CopyOnWriteArraySet<>();

    private long sentTermVersion = -1;
    private boolean sentOn;
    private boolean sentGraphics;
    private boolean sentMouse;
    private boolean sentMotion;
    private int termCols;
    private int termRows;
    private int syncCooldown;
    private boolean wasOn;
    private boolean sentLocked;

    public ComputerCore(Owner owner, int computerId, String label, int termCols, int termRows) {
        this.owner = owner;
        this.computerId = computerId;
        this.label = label;
        this.termCols = termCols;
        this.termRows = termRows;
        synchronized (CORES) {
            CORES.add(this);
        }
    }

    /** The loaded, running computer with this id in {@code level}, or null. */
    public static ComputerCore find(int computerId, ServerLevel level) {
        synchronized (CORES) {
            for (var core : CORES) {
                if (core.computerId == computerId && core.isOn() && core.owner.level() == level) return core;
            }
        }
        return null;
    }

    // ---- Accessors -------------------------------------------------------------------------------------------------

    /** A new holder (a turtle that moved into another block): the machine keeps running. */
    public void setOwner(Owner owner) {
        this.owner = owner;
    }

    public int computerId() {
        return computerId;
    }

    public void setComputerId(int id) {
        computerId = id;
    }

    public String label() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
        if (machine != null) machine.loadLabel(label);
    }

    public int termCols() {
        return termCols;
    }

    public int termRows() {
        return termRows;
    }

    public void setTermSize(int cols, int rows) {
        termCols = cols;
        termRows = rows;
    }

    public Machine machine() {
        return machine;
    }

    public boolean isOn() {
        return machine != null && machine.isOn();
    }

    public boolean hasViewers() {
        return !viewers.isEmpty();
    }

    // ---- Machine lifecycle ---------------------------------------------------------------------------------------

    /** The machine, created (with a new computer id if needed) when there is a world. */
    public Machine ensureMachine() {
        if (machine != null) return machine;
        var level = owner.level();
        if (level == null) return null;
        var server = level.getServer();
        if (computerId < 0) {
            computerId = ComputerCraftAPI.createUniqueNumberedSaveDir(server, "ccbaremetal/computer");
            owner.onIdAssigned(computerId);
        }
        var storage = ServerContext.get(server).storageDir().resolve("ccbaremetal").resolve("computer").resolve(Integer.toString(computerId));
        var config = new Machine.Config(
            BareMetalConfig.get(BareMetalConfig.MEMORY_PER_PROCESS_MB) * 16,
            BareMetalConfig.get(BareMetalConfig.COMPILER_MEMORY_MB) * 16,
            BareMetalConfig.get(BareMetalConfig.MAX_PROCESSES),
            BareMetalConfig.get(BareMetalConfig.DISK_SPACE_MB) * 1024L * 1024L,
            BareMetalConfig.get(BareMetalConfig.TMP_SPACE_MB) * 1024L * 1024L,
            termCols, termRows, 1280, 800, BareMetalConfig.get(BareMetalConfig.THREADS_PER_PROCESS),
            BareMetalConfig.get(BareMetalConfig.CPUS_PER_PROCESS));
        machine = new Machine(computerId, storage, SystemImage.get(), config, this);
        machine.loadLabel(label);
        synchronized (RUNNING) {
            RUNNING.add(machine);
        }
        return machine;
    }

    public void turnOn() {
        var m = ensureMachine();
        if (m == null || m.isOn()) return;
        m.boot();
    }

    public void shutdown() {
        if (machine != null) machine.shutdownAsync();
    }

    public void reboot() {
        if (machine == null || !machine.isOn()) turnOn();
        else machine.reboot();
    }

    /** Stop the machine without changing the saved power state (unloading). */
    public void stop() {
        var m = machine;
        machine = null;
        wasOn = false;
        if (m != null) {
            synchronized (RUNNING) {
                RUNNING.remove(m);
            }
            m.haltAsync();
        }
    }

    public static void shutdownAll() {
        Machine[] machines;
        synchronized (RUNNING) {
            machines = RUNNING.toArray(new Machine[0]);
            RUNNING.clear();
        }
        for (var m : machines) m.halt();
    }

    /** Server tick: run the machine's timers and send the screen to viewers. */
    public void tick() {
        if (machine == null && !pendingViewers.isEmpty()) ensureMachine();
        if (machine == null) return;
        machine.tick();
        var running = machine.isOn();
        if (running && !wasOn) owner.onBooted();
        wasOn = running;
        syncScreens();
    }

    // ---- Machine.Host ---------------------------------------------------------------------------------------------

    @Override
    public MinecraftServer server() {
        var level = owner.level();
        return level == null ? null : level.getServer();
    }

    private static final net.minecraft.resources.ResourceLocation SPEAKER_STOP =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("computercraft", "speaker_stop");

    @Override
    public boolean canStopSpeakers() {
        var server = server();
        if (server == null) return false;
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return false; // nobody hears it: no lag to drop
        // CC sends the stop message to every player; a connection without CC's channel (a test's mock player) throws
        for (var player : players) if (player.connection == null || !player.connection.hasChannel(SPEAKER_STOP)) return false;
        return true;
    }

    @Override
    public void onLabelChanged(String newLabel) {
        dev.ccbaremetal.kernel.periph.MainThread.submit(() -> {
            label = newLabel;
            owner.onLabelChanged(newLabel);
        });
    }

    @Override
    public void onRedstoneOutputChanged() {
        owner.onRedstoneOutputChanged();
    }

    @Override
    public void onStateChanged(boolean running) {
        dev.ccbaremetal.kernel.periph.MainThread.submit(() -> owner.onStateChanged(running));
    }

    @Override
    public long dayTime() {
        var l = owner.level();
        return l == null ? 0 : l.getDayTime();
    }

    @Override
    public ServerLevel level() {
        return owner.level();
    }

    @Override
    public BlockPos position() {
        return owner.position();
    }

    // ---- Screens ---------------------------------------------------------------------------------------------------

    void addViewer(ComputerMenu menu) {
        // The menu is created before the client is told to open it, so the screen is sent on the next tick.
        viewers.add(menu);
        pendingViewers.add(menu);
    }

    void removeViewer(ComputerMenu menu) {
        viewers.remove(menu);
        pendingViewers.remove(menu);
        if (menu.player() != null) uploads.forget(menu.player().getUUID());
    }

    /** Files dropped on the window by a player. */
    public FileUpload uploads() {
        return uploads;
    }

    private void send(ComputerMenu menu, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        var player = menu.player();
        if (player != null && player.containerMenu == menu) PacketDistributor.sendToPlayer(player, payload);
    }

    private void sendFull(ComputerMenu menu) {
        var m = ensureMachine();
        if (m == null) return;
        var graphics = m.shownDisplay() == Machine.DisplayMode.GRAPHICS;
        send(menu, new Payloads.ScreenState(menu.containerId, m.isOn(), graphics, m.wantsMouse(), m.wantsMouseMotion()));
        menu.historySent = -1;
        menu.historyEpochSent = -1;
        sendTerminal(menu, m);
        send(menu, new Payloads.Framebuffer(menu.containerId, FbData.of(m.framebuffer().full())));
    }

    /** Send the terminal plus the history lines this viewer has not seen yet. */
    private void sendTerminal(ComputerMenu menu, Machine m) {
        var snapshot = m.shownTerminal().snapshot(menu.historySent, menu.historyEpochSent);
        menu.historySent = snapshot.historyEnd();
        menu.historyEpochSent = snapshot.historyEpoch();
        send(menu, new Payloads.Terminal(menu.containerId, Compression.deflate(snapshot.data())));
    }

    private void syncScreens() {
        if (viewers.isEmpty()) return;
        var m = machine;
        for (var v : pendingViewers) {
            if (v.player() != null && v.player().containerMenu == v) {
                pendingViewers.remove(v);
                sendFull(v);
            }
        }
        var locked = m.screenLocked();
        if (locked != sentLocked) {
            // another terminal is shown now (the lock prompt, or the console again): send it whole
            sentLocked = locked;
            sentTermVersion = -1;
            for (var v : viewers) {
                v.historySent = -1;
                v.historyEpochSent = -1;
            }
        }
        var graphics = m.shownDisplay() == Machine.DisplayMode.GRAPHICS;
        var running = m.isOn();
        var mouse = m.wantsMouse();
        var motion = m.wantsMouseMotion();
        if (running != sentOn || graphics != sentGraphics || mouse != sentMouse || motion != sentMotion) {
            sentOn = running;
            sentGraphics = graphics;
            sentMouse = mouse;
            sentMotion = motion;
            for (var v : viewers) {
                if (!pendingViewers.contains(v)) send(v, new Payloads.ScreenState(v.containerId, running, graphics, mouse, motion));
            }
        }
        var copied = m.shownTerminal().takeClipboardRequest();
        if (copied != null) {
            for (var v : viewers) {
                if (!pendingViewers.contains(v)) send(v, new Payloads.Clipboard(v.containerId, copied));
            }
        }
        var version = m.shownTerminal().version();
        if (version != sentTermVersion) {
            sentTermVersion = version;
            for (var v : viewers) {
                if (!pendingViewers.contains(v)) sendTerminal(v, m);
            }
        }
        if (syncCooldown > 0) {
            syncCooldown--;
            return;
        }
        if (graphics) {
            var update = m.framebuffer().takeDirty();
            if (update != null) {
                var data = FbData.of(update);
                var limit = BareMetalConfig.get(BareMetalConfig.MAX_SYNC_KB_PER_TICK) * 1024;
                syncCooldown = data.packed().length / Math.max(1, limit);
                for (var v : viewers) send(v, new Payloads.Framebuffer(v.containerId, data));
            }
        }
    }

    /** Input from a player's screen (server thread). */
    public void handleInput(Payloads.Input input) {
        var m = machine;
        if (m == null || !m.isOn()) return;
        switch (input.event()) {
            case "key" -> m.input(Event.of("key", input.a(), input.b() != 0), input.c());
            case "key_up" -> m.input(Event.of("key_up", input.a()), 0);
            case "char" -> {
                if (!input.text().isEmpty()) m.input(Event.of("char", input.text()), 0);
            }
            case "paste" -> m.input(Event.of("paste", input.text()), 0);
            case "mouse_click", "mouse_up", "mouse_drag", "mouse_scroll", "mouse_move" -> {
                // The client sends framebuffer pixels in graphics mode and 1-based character cells in text mode.
                int x;
                int y;
                if (m.shownDisplay() == Machine.DisplayMode.GRAPHICS) {
                    var fb = m.framebuffer();
                    x = Math.max(0, Math.min(fb.width() - 1, input.b()));
                    y = Math.max(0, Math.min(fb.height() - 1, input.c()));
                } else {
                    var t = m.shownTerminal();
                    y = Math.max(1, Math.min(t.height(), input.c()));
                    // The screen shows rows written by Lua programs compacted; they count in cells (bytes).
                    x = Math.max(1, Math.min(t.width(), t.cellAt(input.b() - 1, y - 1) + 1));
                }
                m.input(Event.of(input.event(), input.a(), x, y), 0);
            }
            case "resize" -> {
                m.resizeTerminal(input.a(), input.b());
                if (termCols != m.terminal().width() || termRows != m.terminal().height()) {
                    termCols = m.terminal().width();
                    termRows = m.terminal().height();
                    owner.onTermResized();
                }
            }
            default -> {
            }
        }
    }

    public void handlePower(int action) {
        switch (action) {
            case Payloads.POWER_ON -> turnOn();
            case Payloads.POWER_SHUTDOWN -> shutdown();
            case Payloads.POWER_REBOOT -> reboot();
            case Payloads.POWER_TERMINATE -> {
                if (machine != null) machine.input(Event.of("key", Keys.A + ('t' - 'a'), false), Keys.MOD_CONTROL);
            }
            default -> {
            }
        }
    }
}
