-- Run with:  lua hello.lua
term.setTextColor(colors.yellow)
print("Hello from CraftOS inside WebAssembly computer #" .. os.getComputerID())
term.setTextColor(colors.white)
print("Peripherals: " .. textutils.serialise(peripheral.getNames()))
