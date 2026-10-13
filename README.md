<img src="docs/images/logo.png" alt="CC: Bare Metal logo" width="160" align="right">

# CC: Bare Metal

**English** | [Русский](README.ru.md)

*WebAssembly computers for CC: Tweaked: a Unix-like system, an in-game C/C++ compiler and Linux-style devices.*

An addon for [CC: Tweaked](https://tweaked.cc) (Minecraft 1.21.1, NeoForge). It adds a new tier of computers that
run **WebAssembly** programs instead of Lua. Inside is a Unix-like system with a shell and utilities, a real
**C/C++ compiler right in the game**, graphics up to 1280×800, Linux-style devices (`/dev/fb0`, `/dev/input`,
`/dev/periph`) and access to every peripheral of CC: Tweaked and its addons. There is also a WASM turtle and a pocket
computer, and CC's own computers, monitors and printers get Unicode, all without forking CC and without changing
what programs see.

Programs run in a sandbox: they can only reach their own computer's files.

![Computer window](docs/images/computer.png)

## Features

**Computers**

- **WASM computer**: one tier above the advanced computer. CC's normal computer is grey, the advanced one gold,
  the WASM one amethyst. The window looks like CC's advanced computer and pixels are always square. Programs run on
  their own threads and do not slow the server down.
- **WASM turtle**: moves, digs, builds. It has CC's turtle API with the same names and results, so CC turtle
  programs work as they are. Upgrades go into slots instead of being crafted in: any upgrade from CC or its addons.
- **WASM pocket computer**: 4 module slots instead of one crafted upgrade. Speaker, modems, player detector, geo
  scanner and any other pocket upgrade from CC and its addons can be plugged in and out on the fly.
- **Pixel monitor**: shows an image, forms walls of up to 8×6 blocks, touch-sensitive (`/dev/input`).

**System and development**

- `sh` shell: pipelines, redirections, variables, loops, functions, `case`, `$(...)` substitution.
- About 175 commands that behave like their GNU counterparts: coreutils, `sed`, `awk`, `diff`, `tar`, `grep -r`,
  `find -exec`, `xargs`, `sha256sum`...; the `edit` editor with selection, mouse and undo; the `pkg` package
  manager; `man` pages.
- **C and C++ in the game**: clang 22 and libc++ (`c++`, `g++`, `cc`, `gcc`), exceptions, the whole standard
  library, `ar`/`nm`/`objdump`. A POSIX layer (`termios`, `posix_spawn`, `waitpid`, `popen`, `ioctl`) lets Linux
  programs build unchanged. The compiler itself is WebAssembly, so it works on every platform.
- **Users and permissions** as on Linux: a setup on the first start (name, user, password), `login`, `passwd`,
  `sudo` with `/etc/sudoers`, `su`, `useradd`, `chmod`, `chown`, `lock`.
- **Network** over CC modems (wireless, ender, wired), as TCP/IP: computers by name, services by port; `nc`, `ss`,
  sockets in C++. **`ssh`** and `scp` on top of it, encrypted, with host keys.
- Drag files and folders from your desktop into the computer window.
- **Keyboard**: type on a computer from the world, in hand or placed on a desk (all keys captured, Esc too;
  Shift+Esc leaves; you still look around), as a `/dev/input` device with a relative mouse and Lua events.
- CraftOS and Lua with CC's real ROM: `craftos`, `lua file`.

**Graphics and input**

- Indexed framebuffer (2/4/16/256 colours; only changes go over the network): through the API, the `/dev/fb0`
  file or on pixel monitors.
- Linux-style input: `/dev/input/event0` (keyboard), `event1` (mouse), `event2…` (monitor touchscreens),
  `/dev/input/mice`. An xterm-style terminal: mouse, keys with modifiers, clipboard.

**CC: Tweaked and addons**

- Any peripheral from any mod, with no per-mod code: methods, objects returned by methods, addon global APIs,
  events.
- Peripherals as files: `/dev/periph/<name>/<method>`, `/dev/fbN`, `/dev/dspN` (speakers), `/dev/lpN` (printers),
  `/dev/monitorN` (CC monitors as terminals), `/sys/class/redstone`.
- `rednet` and `gps` compatible with Lua computers.
- **Internet**: `curl`, `wget`, `pkg install URL`, `cc::http` in C++, `http` in Lua, all through CC's own HTTP API
  with the same server settings and access rules.
- **Unicode for CC computers**: Cyrillic and other scripts on computers, monitors, pocket computers, printouts and
  labels. Programs still see bytes, as in CC.

![Turtle modules](docs/images/turtle-modules.png)

## Requirements

- Minecraft 1.21.1, NeoForge 21.1.249 or newer (within 21.1.x)
- CC: Tweaked 1.120.0 or newer
- Java 21

The mod is needed on both the server and the client.

## Installation

Put `ccbaremetal-<version>.jar` into the `mods` folder next to CC: Tweaked. The C/C++ compiler is already inside the
jar (about 41 MB); on first use it is unpacked into `<game folder>/ccbaremetal/toolchain`.

## Quick start

1. Craft a WASM computer (6 amethyst shards, a diamond, a CC advanced computer, a block of redstone) and place it.
2. Open it (right click) and press Enter or the power button on the sidebar.
3. The setup asks for the computer's name, your user name and a password; then log in.
4. Try:

```sh
ls /usr/share/examples
cd /tmp && c++ /usr/share/examples/hello.cpp -o hello && ./hello
man intro
```

## Documentation

Details are in [docs/en](docs/en/README.md):

1. [Quick start and the computer window](docs/en/01-quick-start.md)
2. [Blocks, recipes, monitors, turtle, pocket computer, keyboard](docs/en/02-blocks.md)
3. [System: files, shell, commands, packages, internet, Lua](docs/en/03-system.md)
4. [Programming in C++](docs/en/04-cpp.md)
5. [Graphics and input: /dev/fb0, /dev/input, pixel monitors](docs/en/05-graphics-input.md)
6. [Peripherals of CC: Tweaked and addons, /dev/periph, rednet](docs/en/06-peripherals.md)
7. [Unicode for CC: Tweaked computers](docs/en/07-unicode.md)
8. [Configuration](docs/en/08-config.md)
9. [Internals and building from source](docs/en/09-development.md)

The same information is in the in-game manual: `man intro`, `man cc`, `man baremetal`, `man gfx`, `man dev`,
`man keys`, `man turtle`, `man http`, `man users`, `man ssh`, `man net`.

## Building from source

```sh
./gradlew build                 # jar in build/libs
make -C userland                # rebuild the system (C++); needs wasi-sdk in tools/
```

The first build downloads clang (YoWASP) and translates it to JVM bytecode: about 4 minutes and 4 GB of memory.
More in [docs/en/09-development.md](docs/en/09-development.md).

## License

Code: [Mozilla Public License 2.0](LICENSE), the same as CC: Tweaked itself. Textures recoloured from CC: Tweaked
stay under its [ComputerCraft Public License](LICENSES/LicenseRef-CCPL.txt) (which allows non-commercial mods).
Components bundled in the jar (Chicory, LLVM/clang, wasi-libc, libc++) and their licenses are listed in
[THIRD_PARTY.md](THIRD_PARTY.md).

## Use of AI

An AI assistant, Claude (Anthropic), was used during development: a significant part of the code and documentation
was written with its help. The concept, architecture, decisions, review and in-game testing are the author's.

## Acknowledgements

- [CC: Tweaked](https://github.com/cc-tweaked/CC-Tweaked): the foundation: peripherals, the CraftOS ROM (taken from
  the installed CC, not included in the jar), HTTP. Block and window textures are CC: Tweaked textures recoloured
  to amethyst; they stay under CC: Tweaked's license.
- [Chicory](https://github.com/dylibso/chicory): WebAssembly runtime for the JVM.
- [LLVM/clang](https://llvm.org) as built by [YoWASP](https://yowasp.org), and
  [wasi-sdk](https://github.com/WebAssembly/wasi-sdk) (wasi-libc, libc++): the compiler and standard library.
- Project template: [NeoForged MDK](https://github.com/NeoForged/MDK) (`TEMPLATE_LICENSE.txt`).
