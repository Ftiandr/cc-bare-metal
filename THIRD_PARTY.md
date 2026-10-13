# Third-party components

CC: Bare Metal is licensed under the [Mozilla Public License 2.0](LICENSE). The mod jar also contains the
components below, under their own licenses (full texts in [LICENSES](LICENSES)).

| Component | Where | License |
|---|---|---|
| [Chicory](https://github.com/dylibso/chicory) — WebAssembly runtime for the JVM | bundled in the jar (jar-in-jar) | Apache-2.0 |
| [LLVM](https://llvm.org): clang, lld, binutils, compiler-rt builtins and clang's resource headers | `ccbaremetal/toolchain.zip`, and clang translated to JVM bytecode in `ccbaremetal/jit/` | Apache-2.0 WITH LLVM-exception |
| [YoWASP clang](https://github.com/YoWASP/clang) — the WebAssembly build of LLVM used above | `ccbaremetal/toolchain.zip` | ISC (packaging), LLVM's license for LLVM |
| [wasi-sdk](https://github.com/WebAssembly/wasi-sdk) sysroot: [wasi-libc](https://github.com/WebAssembly/wasi-libc) (with parts of musl) | `ccbaremetal/toolchain.zip`; linked into the programs in `ccbaremetal/system.zip` | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT; musl parts MIT (Copyright © 2005-2020 Rich Felker, et al.) |
| wasi-sdk sysroot: libc++, libc++abi, libunwind (LLVM) | `ccbaremetal/toolchain.zip`; linked into the programs in `ccbaremetal/system.zip` | Apache-2.0 WITH LLVM-exception |
| [SDL 2.32](https://www.libsdl.org) — media library for ported programs (built with our video driver, `userland/sdl/`) | `ccbaremetal/system.zip`: `/usr/include/SDL2`, `/usr/lib/wasm32-wasip1-threads/libSDL2.a` | Zlib (Copyright © 1997-2025 Sam Lantinga) |
| [NeoForged MDK](https://github.com/NeoForged/MDK) — project template (Gradle files) | source only | MIT ([TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt)) |

## Textures derived from CC: Tweaked

The textures of the WASM computer, turtle, pocket computer and pixel monitor, and the sprites of the computer window
(border, sidebar, power and terminate buttons) are [CC: Tweaked](https://github.com/cc-tweaked/CC-Tweaked)'s
textures, recoloured from gold to amethyst by `scripts/gen_*_textures.py` (the reboot, zoom and module icons are
drawn in the same style). CC: Tweaked's textures are Copyright 2011 Daniel Ratcliffe and licensed under the
[ComputerCraft Public License](LICENSES/LicenseRef-CCPL.txt); these derived textures stay under that license. It
allows their use in non-commercial Minecraft mods; they may not be used in a commercial mod.

Files: `src/main/resources/assets/ccbaremetal/textures/block/wasm_computer_*`, `wasm_turtle_*`, `pixel_monitor_*`,
`textures/item/wasm_pocket_computer*`, `textures/gui/sprites/**`.

The file-by-file licensing is also in [REUSE.toml](REUSE.toml) (the same format CC: Tweaked uses).

## Not bundled

CraftOS's ROM, Cobalt (Lua) and CC: Tweaked's HTTP implementation are used from the installed CC: Tweaked at runtime
and are not part of this mod.
