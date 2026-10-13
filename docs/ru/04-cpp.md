# 4. Программирование на C++

## Компилятор

В каждом WASM-компьютере есть настоящий **clang 22** с **lld** и **libc++**. Компилятор сам скомпилирован в
WebAssembly и выполняется внутри мода, поэтому работает на любой платформе. Программы — обычные модули WASI
(`wasm32-wasip1`), которые запускаются как любая команда.

```sh
c++ hello.cpp -o hello && ./hello                  # C++ (также g++, clang++)
cc tool.c -o tool                                  # C (также gcc, clang, c99)
c++ -std=c++20 -O2 -Wall game.cpp util.cpp -o game
c++ -c a.cpp b.cpp && ar rcs libab.a a.o b.o && c++ main.cpp -L. -lab -o prog
c++ -v ...                                         # показать шаги
```

- Все команды (`cc`, `gcc`, `c++`...) понимают и C, и C++ по расширению файла и линкуют libc++, как gcc.
- Исключения включены (`-fno-exceptions` выключает); RTTI, шаблоны, вся стандартная библиотека: контейнеры,
  `<algorithm>`, `<string>`, `<iostream>`, `<fstream>`, `<filesystem>`, `<chrono>`, `<regex>`, `<format>`...
- Потоков нет.
- Скорость: файл на C++ с `<iostream>` компилируется 10–20 с, на C — около секунды; первый вызов после запуска
  сервера — ещё ~3 с на загрузку компилятора.
- Binutils: `ar ranlib nm objdump objcopy strip size c++filt addr2line ld`.
- Пути: `/usr/include` и `/usr/local/include` (заголовки), `/usr/lib` и `/usr/local/lib` (библиотеки).
- Память компилятора ограничена настройкой `compiler_memory_mb` (по умолчанию 1024 МБ), обычных программ —
  `memory_per_process_mb` (64 МБ).

Программы можно собирать и вне игры с wasi-sdk:
`clang++ --target=wasm32-wasip1 -fwasm-exceptions -mllvm -wasm-use-legacy-eh=false` (среда выполнения
поддерживает только современную обработку исключений WebAssembly).

## Заголовки системы

- `<baremetal.hpp>` — C++ API системы (пространство имён `cc`), libbaremetal подключается автоматически;
- `<baremetal.h>` — сырые системные вызовы для C (`cc_gfx_*`, `cc_tty_*`, `cc_wait`...) и помощники, возвращающие
  JSON-строки (`cc_spawn`, `cc_run`, `cc_event_wait`, `cc_periph_list`, `cc_periph_call`, `cc_lua`...);
- `<linux/input.h>` — записи `/dev/input` (см. [раздел 5](05-graphics-input.md)).

Обычный POSIX тоже работает: файлы, папки, `stdio`, `poll`, время, переменные окружения. Чего в WASI нет,
добавляет libbaremetal, так что программы под Linux собираются без правок:

| Заголовок | Что есть |
|---|---|
| `<termios.h>` | `tcgetattr`, `tcsetattr`, `cfmakeraw`, `tcgetwinsize`: работают ICANON, ECHO, ISIG, ICRNL (Enter приходит как `\n`, после `cfmakeraw` — как `\r`), VMIN/VTIME (чтение с таймаутом и опрос) |
| `<sys/ioctl.h>` | `ioctl(fd, TIOCGWINSZ, &ws)` — размер терминала |
| `<sys/wait.h>` | `waitpid`, `wait`, `WEXITSTATUS`... |
| `<spawn.h>` | `posix_spawn`, `posix_spawnp` (перенаправление 0–2 через file actions) |
| `<stdlib.h>`, `<stdio.h>` | `system()`, `popen()` / `pclose()` (через `sh -c`) |

`fork()` в WebAssembly невозможен: программы запускаются через `posix_spawn`, `system` или `cc::spawn`.

## API `<baremetal.hpp>`

### Процессы

```cpp
int pid = cc::spawn({"ls", "-l"});                  // запустить (поиск по PATH), вернёт pid
int status; cc::wait(pid, &status);                 // дождаться (nohang = true — не ждать)
int rc = cc::run({"sh", "-c", "echo hi | wc -c"});  // запустить и дождаться
cc::SpawnOptions o; o.out = fd; o.flags = CC_SPAWN_NEW_GROUP;  // перенаправления, окружение, группы
cc::chdir("/tmp"); cc::cwd(); cc::pid(); cc::sleep(0.5);
```

### Терминал

```cpp
auto size = cc::tty::size();                        // size.cols, size.rows
cc::tty::Mode raw(CC_TTY_RAW | CC_TTY_NOECHO);      // RAII: режим до конца области
```

Терминал понимает ANSI/VT100 и основное из xterm:
- цвета (16, 256 и truecolor сводятся к палитре), перемещение курсора, очистка, альтернативный экран
  (`ESC[?1049h`), bracketed paste (`ESC[?2004h`);
- то, что нужно полноэкранным программам в духе ncurses: область прокрутки (`ESC[2;20r`), `ESC M` / `ESC D`,
  вставка и удаление строк (`ESC[L`, `ESC[M`) и символов (`ESC[@`, `ESC[P`, `ESC[X`), `ESC 7` / `ESC 8`,
  выбор кодировки `ESC ( B` (игнорируется), мягкий сброс `ESC[!p`;
- ответы на запросы: позиция курсора `ESC[6n` → `ESC[строка;столбецR`, `ESC[5n`, `ESC[c` — они приходят на вход
  программы;
- мышь: `ESC[?1000h` — нажатия, `1002` — ещё перетаскивание, `1003` — любое движение (`ESC[<35;x;yM`, только при
  смене клетки и не чаще 20 раз в секунду, пока режим не включён — не передаётся вовсе), `1006` — формат SGR
  (`ESC[<b;x;yM`), колесо — кнопки 64/65;
- клавиши с модификаторами как в xterm: Shift+↑ — `ESC[1;2A`, Ctrl+→ — `ESC[1;5C`, Shift+Tab — `ESC[Z`,
  Ctrl+Backspace — `^W`; после `ESC[?1h` (режим курсорных клавиш приложения) стрелки, Home и End приходят как
  `ESC O A`;
- F1–F12 программам не передаются: их забирает Minecraft.
- OSC 52 (`ESC]52;c;BASE64 BEL`) — положить текст в буфер обмена игрока, который смотрит на экран.

Текст — UTF-8.

### События (как в CC)

```cpp
cc::setProcessFlags(CC_PROC_INPUT);                 // клавиатура и мышь — событиями (CC_PROC_EVENTS — события машины)
int timer = cc::startTimer(1.0);
while (auto e = cc::pullEvent()) {                  // pullEvent("key"), pullEvent("", timeoutMs)
    if (e->name == "key") ...                       // e->args — JSON-массив аргументов
    if (e->name == "mouse_click") ...               // кнопка, x, y (в графике — пиксели)
    if (e->name == "timer" && e->args[0].asInt() == timer) ...
}
cc::queueEvent("my_event", cc::Json::array({1, "two"}));
```

### JSON

Данные периферии и событий — `cc::Json`:
`Json::parse(text)`, `.dump()`, `j["key"]`, `j[0]`, `.asInt() .asNumber() .asString() .asBool()`,
`Json::array({...})`, `Json::object({{"k", v}})`, `.push(v)`, `.set(k, v)`, `.isArray() .isObject()...`

### Графика

`cc::gfx::mode(w, h, bpp)`, `clear`, `pixel`, `fill`, `rect`, `line`, `circle`, `text`, `blit`, `palette`,
`present`, `target("top")` — в [разделе 5](05-graphics-input.md).

### Периферия, rednet, gps, Lua

`cc::Peripheral`, `cc::Object`, `cc::api`, `cc::Modem`, `cc::Monitor`, `cc::Speaker`, `cc::Printer`,
`cc::Drive`, `cc::Inventory`, `cc::EnergyStorage`, `cc::FluidStorage`, `cc::rednet`, `cc::gps` — в
[разделе 6](06-peripherals.md).

```cpp
auto r = cc::lua("local a, b = ... return a + b", cc::Json::array({1, 2}));  // r.value[0] == 3
```

### Сеть

Соединения с другими компьютерами по имени и порту, как сокеты TCP (см. [Сеть](03-system.md#сеть)). Сокет —
обычный файловый дескриптор: `read`, `write`, `close`, `poll`, `cc::waitAny`.

```cpp
int fd = cc::net::connect("base", 1234);       // или -ECONNREFUSED, -ETIMEDOUT, -ENETDOWN, -EADDRINUSE
cc::net::sendAll(fd, "hello\n", 6);
char buf[256];
ssize_t n = read(fd, buf, sizeof buf);          // 0 — другая сторона закрыла соединение
close(fd);

int l = cc::net::listen(1234);                  // сервер (порты ниже 1024 — только root)
while (true) {
    int c = cc::net::accept(l);                 // следующее соединение; кто это — cc::net::info(c)->remote
    cc::net::sendAll(c, "hi\n", 3);
    close(c);
}
```

`cc::net::shutdown(fd)` закрывает только направление записи (другая сторона прочитает конец файла),
`cc::net::status()` перечисляет сокеты компьютера (то, что показывает `ss`).

### Система

`cc::systemInfo()` (номер, метка, версия, время, размеры экрана), `cc::computerId()`, `cc::setLabel(s)`,
`cc::redstoneInput(side)`, `cc::setRedstone(side, level)`, `cc::side("left")`.

## Пример

```cpp
#include <baremetal.hpp>
#include <cstdio>

int main() {
    // Мигающая лампа на редстоуне сзади, пока не нажата клавиша
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

Больше примеров — в `/usr/share/examples`: `hello.cpp`, `exceptions.cpp`, `circles.cpp` (графика),
`peripherals.cpp`, `lua.cpp`, `framebuffer.cpp`, `paint.cpp` (только `/dev/fb0` и `/dev/input`).
