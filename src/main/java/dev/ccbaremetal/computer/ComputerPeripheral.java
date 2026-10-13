package dev.ccbaremetal.computer;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;

/**
 * Lets normal CC computers control a WASM computer next to them, like CC's own "computer" peripheral
 * (peripheral.wrap("left").turnOn() etc).
 */
public final class ComputerPeripheral implements IPeripheral {
    private final WasmComputerBlockEntity owner;

    ComputerPeripheral(WasmComputerBlockEntity owner) {
        this.owner = owner;
    }

    @Override
    public String getType() {
        return "computer";
    }

    @Override
    public java.util.Set<String> getAdditionalTypes() {
        return java.util.Set.of("wasm_computer");
    }

    @LuaFunction(mainThread = true)
    public void turnOn() {
        owner.turnOn();
    }

    @LuaFunction(mainThread = true)
    public void shutdown() {
        owner.shutdown();
    }

    @LuaFunction(mainThread = true)
    public void reboot() {
        owner.reboot();
    }

    @LuaFunction
    public int getID() {
        return owner.computerId();
    }

    @LuaFunction
    public boolean isOn() {
        return owner.isOn();
    }

    @LuaFunction
    public String getLabel() {
        var label = owner.label();
        return label == null ? null : dev.ccbaremetal.kernel.periph.CcStrings.bytes(label); // UTF-8 bytes, as Lua expects
    }

    @Override
    public boolean equals(IPeripheral other) {
        return other instanceof ComputerPeripheral p && p.owner == owner;
    }
}
