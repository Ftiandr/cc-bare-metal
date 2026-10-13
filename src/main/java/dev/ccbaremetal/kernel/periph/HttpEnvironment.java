package dev.ccbaremetal.kernel.periph;

import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.WorkMonitor;
import dan200.computercraft.core.apis.IAPIEnvironment;
import dan200.computercraft.core.computer.ComputerEnvironment;
import dan200.computercraft.core.computer.ComputerSide;
import dan200.computercraft.core.computer.GlobalEnvironment;
import dan200.computercraft.core.filesystem.FileSystem;
import dan200.computercraft.core.metrics.MetricsObserver;
import dan200.computercraft.core.terminal.Terminal;
import dev.ccbaremetal.kernel.Machine;
import dev.ccbaremetal.kernel.event.Event;
import dev.ccbaremetal.kernel.proc.Process;

import java.io.InputStream;

/**
 * What CC: Tweaked's own HTTP API ({@code HTTPAPI}: requests, websockets, URL checks) needs of a computer: events,
 * timers, an id and a user agent. With this, the WASM computer uses CC's HTTP implementation itself, so it behaves
 * exactly like CC's (redirects, size and bandwidth limits, proxy, compression) and follows the same server settings:
 * {@code http.enabled}, the address rules (private networks are denied by default), the request and websocket limits.
 */
final class HttpEnvironment implements IAPIEnvironment {
    private static final GlobalEnvironment GLOBAL = new GlobalEnvironment() {
        @Override
        public String getHostString() {
            return "ComputerCraft " + version() + " (CC: Bare Metal)";
        }

        @Override
        public String getUserAgent() {
            return "computercraft/" + version();
        }

        @Override
        public Mount createResourceMount(String domain, String subPath) {
            return null;
        }

        @Override
        public InputStream createResourceFile(String domain, String subPath) {
            return null;
        }
    };

    private final Machine machine;

    HttpEnvironment(Machine machine) {
        this.machine = machine;
    }

    private static String version() {
        return net.neoforged.fml.ModList.get().getModContainerById("computercraft")
            .map(c -> c.getModInfo().getVersion().toString()).orElse("1.120.0");
    }

    @Override
    public int getComputerID() {
        return machine.id();
    }

    @Override
    public ComputerEnvironment getComputerEnvironment() {
        return null;
    }

    @Override
    public GlobalEnvironment getGlobalEnvironment() {
        return GLOBAL;
    }

    @Override
    public WorkMonitor getMainThreadMonitor() {
        return null;
    }

    @Override
    public Terminal getTerminal() {
        return null;
    }

    @Override
    public FileSystem getFileSystem() {
        return null;
    }

    @Override
    public void shutdown() {
        machine.shutdownAsync();
    }

    @Override
    public void reboot() {
        machine.reboot();
    }

    /** http_success, http_failure, websocket_message ...: to every program that listens, like any machine event. */
    @Override
    public void queueEvent(String event, Object... args) {
        machine.queueEvent(new Event(event, args == null ? new Object[0] : args));
    }

    @Override
    public void setPeripheralChangeListener(IPeripheralChangeListener listener) {
    }

    @Override
    public IPeripheral getPeripheral(ComputerSide side) {
        return null;
    }

    @Override
    public String getLabel() {
        return machine.label();
    }

    @Override
    public void setLabel(String label) {
    }

    /** A timer for the program that is calling (websocket receive with a timeout). */
    @Override
    public int startTimer(long ticks) {
        var caller = Process.CURRENT.get();
        if (caller == null) return -1;
        return machine.startTimer(caller, ticks * 0.05);
    }

    @Override
    public void cancelTimer(int id) {
        machine.cancelTimer(id);
    }

    @Override
    public MetricsObserver metrics() {
        return MetricsObserver.discard();
    }
}
