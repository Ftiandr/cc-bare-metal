package dev.ccbaremetal.kernel.periph;

/**
 * Marks a peripheral that is an API of the computer itself rather than a block next to it, such as a turtle's
 * movement API: CraftOS gets it as a global of the peripheral's name ({@code turtle}), as on CC, and it is not listed
 * by Lua's peripheral API. WASM programs still find it in /dev/periph and through the C++ API.
 */
public interface HostApi {
}
