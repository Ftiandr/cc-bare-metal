# 4. Programming in C++

## The compiler

Every WASM computer has a real **clang 22** with **lld** and **libc++**. The compiler itself is compiled to
WebAssembly and runs inside the mod, so it works on every platform. Programs are ordinary WASI modules
(`wasm32-wasip1`) and run like any other command.

```sh
c++ hello.cpp -o hello && ./hello                  # C++ (also g++, clang++)
cc tool.c -o tool                                  # C (also gcc, clang, c99)
c++ -std=c++20 -O2 -Wall game.cpp util.cpp -o game
c++ -c a.cpp b.cpp && ar rcs libab.a a.o b.o && c++ main.cpp -L. -lab -o prog
c++ -v ...                                         # show the steps
```

- Every driver (`cc`, `gcc`, `c++`...) handles both C and C++ by file extension and links libc++, like gcc.
- Exceptions are on (`-fno-exceptions` turns them off); RTTI, templates and the whole standard library: containers,
  `<algorithm>`, `<string>`, `<iostream>`, `<fstream>`, `<filesystem>`, `<chrono>`, `<regex>`, `<format>`...
- Threads: `-pthread` (POSIX threads, `std::thread`, atomics), see below.
- Speed: a C++ file with `<iostream>` compiles in 10–20 s, a C file in about a second; the first run after the
  server starts takes another ~3 s to load the compiler.
- Binutils: `ar ranlib nm objdump objcopy strip size c++filt addr2line ld`.
- Paths: `/usr/include` and `/usr/local/include` (headers), `/usr/lib` and `/usr/local/lib` (libraries).
- The compiler's memory is limited by the `compiler_memory_mb` setting (1024 MB by default), ordinary programs by
  `memory_per_process_mb` (256 MB).

Programs can also be built outside the game with wasi-sdk:
`clang++ --target=wasm32-wasip1 -fwasm-exceptions -mllvm -wasm-use-legacy-eh=false` (the runtime only supports
current WebAssembly exception handling).

## Threads

```sh
cc -pthread -O2 worker.c -o worker        # pthread_create, mutexes, condition variables, _Thread_local, atomics
c++ -pthread game.cpp -o game             # std::thread, std::mutex, std::async...
```

`-pthread` builds for `wasm32-wasip1-threads`: the program's memory is shared, and every thread runs on its own
thread of the server, in parallel (WASI's thread-spawn). `std::thread::hardware_concurrency()` and
`sysconf(_SC_NPROCESSORS_ONLN)` give the server's processors, at most `threads_per_process` (8 by default):
the limit of threads one program may have. `exit()` from any thread, or the end of `main`, ends them all; `kill`
and Ctrl+C too. Scheduling priorities are accepted and have no effect. Outside the game:
`--target=wasm32-wasip1-threads -pthread -Wl,--import-memory,--export-memory,--max-memory=4294967296`.

## SDL2

The system has [SDL 2.32](https://www.libsdl.org) (zlib license), so games and players written for SDL build as they
are:

```sh
cc game.c $(sdl2-config --cflags --libs) -o game      # = -I/usr/include/SDL2 -pthread -lSDL2
```

- Video: a window is the whole screen in 32-bit true colour (or a pixel monitor: `SDL_CCBAREMETAL_FB=fb1`), at most
  the screen's size; SDL's software renderer (`SDL_Renderer`, textures, YUV) and window surfaces work.
- Input: keys from `/dev/input/event0`, the mouse from `event1`, text (`SDL_TEXTINPUT`) from the terminal in any
  keyboard layout; Ctrl+C becomes `SDL_QUIT`.
- Audio: SDL's OSS driver on `/dev/dsp` (the first speaker; `AUDIODEV=/dev/dsp1` for another), any format and rate
  (the kernel converts).
- Threads, timers, `SDL_GetTicks`, `SDL_Delay`: yes. No joysticks, haptics, OpenGL or shared objects.

SDL needs threads (audio and timers run on their own), so `-lSDL2` turns `-pthread` on by itself.

## System headers

- `<baremetal.hpp>`: the system's C++ API (namespace `cc`); libbaremetal is linked automatically;
- `<baremetal.h>`: raw system calls for C (`cc_gfx_*`, `cc_tty_*`, `cc_wait`...) and helpers returning JSON strings
  (`cc_spawn`, `cc_run`, `cc_event_wait`, `cc_periph_list`, `cc_periph_call`, `cc_lua`...);
- `<linux/input.h>`: `/dev/input` records (see [section 5](05-graphics-input.md)).

Plain POSIX works too: files, folders, `stdio`, `poll`, time, environment variables. What WASI lacks, libbaremetal
adds, so Linux programs build unchanged:

| Header | What is there |
|---|---|
| `<termios.h>` | `tcgetattr`, `tcsetattr`, `cfmakeraw`, `tcgetwinsize`: ICANON, ECHO, ISIG, ICRNL (Enter arrives as `\n`, after `cfmakeraw` as `\r`), VMIN/VTIME (reads with a timeout and polling) |
| `<sys/ioctl.h>` | `ioctl(fd, TIOCGWINSZ, &ws)`: terminal size; device controls such as OSS on `/dev/dspN` |
| `<sys/soundcard.h>` | OSS: `SNDCTL_DSP_SETFMT`, `SPEED`, `CHANNELS`, `GETODELAY`, `RESET`, `SYNC`... (see [section 6](06-peripherals.md)) |
| `<sys/wait.h>` | `waitpid`, `wait`, `WEXITSTATUS`... |
| `<spawn.h>` | `posix_spawn`, `posix_spawnp` (redirecting 0–2 with file actions) |
| `<stdlib.h>`, `<stdio.h>` | `system()`, `popen()` / `pclose()` (through `sh -c`); `mkstemp`, `mkdtemp`, `mkostemp`, `tmpfile`, `tempnam`, `tmpnam` |
| `<unistd.h>` | `pipe`, `pipe2`, `dup`, `dup2`, `getpid`, `getppid`, `setsid`, process groups, `execv`/`execvp`/`execl`... (run the program, wait, exit with its status), `ttyname`, `getlogin`, `getpass`; `sysconf(_SC_NPROCESSORS_ONLN)` |
| `<signal.h>` | `sigaction`, `sigprocmask`, signal sets, `kill`, `killpg`: dispositions are kept, SIGINT/SIGTERM end the process or are ignored with `SIG_IGN` (no asynchronous handlers) |
| `<sys/statvfs.h>`, `<sys/resource.h>` | `statvfs` (free space of a disk), `getrlimit` / `setrlimit` |
| `<pthread.h>`, `<sched.h>` | with `-pthread`: POSIX threads, plus scheduling parameters and `pthread_sigmask` |

`fork()` is impossible in WebAssembly (it fails with `ENOSYS`): programs are started with `posix_spawn`, `system`,
`exec*` or `cc::spawn`.

## The `<baremetal.hpp>` API

### Processes

```cpp
int pid = cc::spawn({"ls", "-l"});                  // start (searching PATH), returns the pid
int status; cc::wait(pid, &status);                 // wait for it (nohang = true: don't wait)
int rc = cc::run({"sh", "-c", "echo hi | wc -c"});  // start and wait
cc::SpawnOptions o; o.out = fd; o.flags = CC_SPAWN_NEW_GROUP;  // redirections, environment, groups
cc::chdir("/tmp"); cc::cwd(); cc::pid(); cc::sleep(0.5);
```

### Terminal

```cpp
auto size = cc::tty::size();                        // size.cols, size.rows
cc::tty::Mode raw(CC_TTY_RAW | CC_TTY_NOECHO);      // RAII: the mode lasts until the end of the scope
```

The terminal understands ANSI/VT100 and the main parts of xterm:
- colours (16, 256 and truecolor are mapped to the palette), cursor movement, clearing, the alternate screen
  (`ESC[?1049h`), bracketed paste (`ESC[?2004h`);
- what ncurses-style full-screen programs need: scroll regions (`ESC[2;20r`), `ESC M` / `ESC D`, inserting and
  deleting lines (`ESC[L`, `ESC[M`) and characters (`ESC[@`, `ESC[P`, `ESC[X`), `ESC 7` / `ESC 8`, charset
  selection `ESC ( B` (ignored), soft reset `ESC[!p`;
- replies to queries: cursor position `ESC[6n` → `ESC[row;columnR`, `ESC[5n`, `ESC[c`; they arrive on the
  program's input;
- mouse: `ESC[?1000h` clicks, `1002` also drags, `1003` any motion (`ESC[<35;x;yM`, only when the cell changes and
  at most 20 times a second; nothing is sent while the mode is off), `1006` SGR format (`ESC[<b;x;yM`), wheel as
  buttons 64/65;
- keys with modifiers as in xterm: Shift+↑ is `ESC[1;2A`, Ctrl+→ `ESC[1;5C`, Shift+Tab `ESC[Z`, Ctrl+Backspace
  `^W`; after `ESC[?1h` (application cursor keys) arrows, Home and End arrive as `ESC O A`;
- F1–F12 are not passed to programs: Minecraft takes them;
- OSC 52 (`ESC]52;c;BASE64 BEL`) puts text into the clipboard of the player looking at the screen.

Text is UTF-8.

### Events (as in CC)

```cpp
cc::setProcessFlags(CC_PROC_INPUT);                 // keyboard and mouse as events (CC_PROC_EVENTS: machine events)
int timer = cc::startTimer(1.0);
while (auto e = cc::pullEvent()) {                  // pullEvent("key"), pullEvent("", timeoutMs)
    if (e->name == "key") ...                       // e->args is a JSON array of arguments
    if (e->name == "mouse_click") ...               // button, x, y (pixels in graphics mode)
    if (e->name == "timer" && e->args[0].asInt() == timer) ...
}
cc::queueEvent("my_event", cc::Json::array({1, "two"}));
```

### JSON

Peripheral and event data is `cc::Json`:
`Json::parse(text)`, `.dump()`, `j["key"]`, `j[0]`, `.asInt() .asNumber() .asString() .asBool()`,
`Json::array({...})`, `Json::object({{"k", v}})`, `.push(v)`, `.set(k, v)`, `.isArray() .isObject()...`

### Graphics

`cc::gfx::mode(w, h, bpp)`, `clear`, `pixel`, `fill`, `rect`, `line`, `circle`, `text`, `blit`, `palette`,
`present`, `target("top")`: see [section 5](05-graphics-input.md).

### Peripherals, rednet, gps, Lua

`cc::Peripheral`, `cc::Object`, `cc::api`, `cc::Modem`, `cc::Monitor`, `cc::Speaker`, `cc::Printer`,
`cc::Drive`, `cc::Inventory`, `cc::EnergyStorage`, `cc::FluidStorage`, `cc::rednet`, `cc::gps`: see
[section 6](06-peripherals.md).

```cpp
auto r = cc::lua("local a, b = ... return a + b", cc::Json::array({1, 2}));  // r.value[0] == 3
```

### Network

Connections to other computers by name and port, as TCP sockets (see [Network](03-system.md#network)). A socket is
an ordinary file descriptor: `read`, `write`, `close`, `poll`, `cc::waitAny`.

```cpp
int fd = cc::net::connect("base", 1234);       // or -ECONNREFUSED, -ETIMEDOUT, -ENETDOWN, -EADDRINUSE
cc::net::sendAll(fd, "hello\n", 6);
char buf[256];
ssize_t n = read(fd, buf, sizeof buf);          // 0: the other side closed
close(fd);

int l = cc::net::listen(1234);                  // a server (ports below 1024: root only)
while (true) {
    int c = cc::net::accept(l);                 // the next connection; cc::net::info(c)->remote is who
    cc::net::sendAll(c, "hi\n", 3);
    close(c);
}
```

`cc::net::shutdown(fd)` ends only the writing direction (the peer reads end of file), `cc::net::status()` lists the
computer's sockets (what `ss` shows).

### System

`cc::systemInfo()` (id, label, version, time, screen sizes), `cc::computerId()`, `cc::setLabel(s)`,
`cc::redstoneInput(side)`, `cc::setRedstone(side, level)`, `cc::side("left")`.

## Example

```cpp
#include <baremetal.hpp>
#include <cstdio>

int main() {
    // Blink a redstone lamp at the back until a key is pressed
    cc::setProcessFlags(CC_PROC_INPUT);
    bool on = false;
    int timer = cc::startTimer(0.5);
    while (auto e = cc::pullEvent()) {
        if (e->name == "key") break;
        if (e->name == "timer" && e->args[0].asInt() == timer) {
            on = !on;
            cc::setRedstone(cc::side("back"), on ? 15 : 0);
            timer = cc::startTimer(0.5);
        }
    }
    cc::setRedstone(cc::side("back"), 0);
}
```

More examples in `/usr/share/examples`: `hello.cpp`, `exceptions.cpp`, `circles.cpp` (graphics),
`peripherals.cpp`, `lua.cpp`, `framebuffer.cpp`, `paint.cpp` (only `/dev/fb0` and `/dev/input`).
