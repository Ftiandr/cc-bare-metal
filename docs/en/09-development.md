# 9. Internals and building

## Building

You need Java 21 and [wasi-sdk](https://github.com/WebAssembly/wasi-sdk) 34 in `tools/` (the system and the
in-game compiler's sysroot are built with it):

```sh
mkdir -p tools
curl -sSL https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-34/wasi-sdk-34.0-x86_64-linux.tar.gz | tar xz -C tools
```

```sh
./gradlew build                 # jar in build/libs
./gradlew compileJava           # quick Java check
./gradlew runClient             # a client for testing
make -C userland                # rebuild the system (C++) into src/main/rootfs
```

The first build downloads clang (YoWASP, npm) and translates it to JVM bytecode: ~4 minutes and 4 GB of memory;
after that the step is skipped until clang changes.

## Layout

| Where | What |
|---|---|
| `src/main/java/dev/ccbaremetal/kernel` | the WASM machine without Minecraft code (except `Machine.Host`): virtual FS, processes on their own threads (Chicory), WASI, the `ccbaremetal` system calls, terminal and TTY, framebuffer, `/dev` `/proc` `/sys`, the bridge to CC peripherals, CraftOS/Lua (Cobalt) |
| `src/main/java/dev/ccbaremetal/computer` | `ComputerCore` (machine, screens, input, power) and the computer block |
| `src/main/java/dev/ccbaremetal/pocket`, `turtle` | pocket computer and turtle: modules in slots, the turtle API, `ITurtleAccess`/`IPocketAccess` for CC and addon upgrades |
| `src/main/java/dev/ccbaremetal/monitor`, `client`, `network` | pixel monitor, windows, rendering, networking |
| `src/main/java/dev/ccbaremetal/unicode` | Unicode for CC computers (`Utf8Layout`, `CcText`, mixins in `ccbaremetal.mixins.json`) |
| `userland/` | the system in C++: `sh`, `coreutils`, `textutils`, `ccutils`, `userutils` (users, sudo, lock), `ssh`, `edit`, `pkg`, `periph`, `init`, the `cc` compiler driver, the `libbaremetal` library (`include/baremetal.hpp`, `baremetal.h`, `linux/input.h`) |
| `src/main/rootfs` | the built system (in git); Gradle packs it into the jar as `ccbaremetal/system.zip` |
| `userland/build-toolchain.sh`, `src/jittool` | the in-game compiler: clang 22 + wasi-sdk sysroot with libc++; at build time clang is translated to JVM bytecode and slimmed down (the full module would take ~2 GB of memory) |
| `scripts/` | texture and font generators |

Key decisions:

- Programs are WASI modules; Chicory translates them to JVM bytecode (cached in
  `<game folder>/ccbaremetal/jit-cache`); clang comes already translated inside the jar.
- Only current WebAssembly exception handling (`try_table`/`exnref`) is supported, so C++ is built with
  `-fwasm-exceptions -mllvm -wasm-use-legacy-eh=false`.
- WASI programs have no current folder; the system's programs take it from `$PWD` (`crt_cwd.o`), and for clang and
  binutils the `cc` driver makes paths absolute.
- Addon compatibility is generic: peripheral methods are built by CC's generator, objects in results become handles
  (`kernel/proc/ObjectTable`, the `obj_call` call), addon APIs are created per machine (`kernel/periph/AddonApis`).
- Devices follow Linux's model (`kernel/dev`): the `DeviceTree` holds the peripheral bus, the drivers (one file each
  in `kernel/dev/drivers`: pixel monitor, monitor, speaker, printer, modem; first match wins) and the devices by
  class; `/dev` and `/sys` only show the tree, and a new kind of device is one driver file. The computer's own devices
  (fb0, keyboard, mouse, redstone) are in `ComputerDevices`. Peripherals without a driver stay in `/dev/periph`.
- The network (`kernel/net/Net`) is a small TCP in the kernel, sending through the net-class devices (`NetDevice`:
  the modem driver's wlanN/ethN, `Loopback`): sockets are file descriptors; a computer listens on
  the modem channel hashed from its name, packets carry ports, sequence numbers and acknowledgements and are sent
  again when lost. Packets are handled on one shared thread, never inside `queueEvent` (a transmit reaches the other
  computers synchronously). `ssh`/`scp` are ordinary programs on top of it.
- Unicode for CC: CC stores bytes, drawing compacts UTF-8 per segment; every mixin is optional (`require = 0`),
  forking CC is ruled out.

## Render preview

`BAREMETAL_RENDER_PREVIEW=preview.png ./gradlew runClient`: the client draws a sample CC terminal with Unicode
through CC's real renderer, the computer, pocket and turtle windows and the item icons, saves PNGs and quits. It
can run headless inside `kwin_wayland --virtual --xwayland`.

## Textures and font

- `python3 scripts/gen_computer_textures.py`: WASM computer, turtle and pocket computer: CC's advanced versions with
  the gold recoloured to amethyst (`scripts/cc_textures.py`; the originals are read from the CC: Tweaked jar in the
  Gradle cache);
- `python3 scripts/gen_gui_textures.py`: the computer window: CC's border, sidebar and buttons, the module panel;
- `python3 scripts/gen_monitor_textures.py`: the pixel monitor: CC's advanced monitor in amethyst, connected
  textures, models and block states;
- 6×9 font: `src/main/resources/ccbaremetal/font/glyphs.txt` (hand-drawn); composed letters with diacritics are made
  by `scripts/gen_font_compose.py` → `compose.txt`.
