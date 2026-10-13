# 9. Устройство и сборка

## Сборка

Нужны Java 21 и [wasi-sdk](https://github.com/WebAssembly/wasi-sdk) 34 в `tools/` (из него собираются система и
sysroot компилятора для игры):

```sh
mkdir -p tools
curl -sSL https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-34/wasi-sdk-34.0-x86_64-linux.tar.gz | tar xz -C tools
```

```sh
./gradlew build                 # jar в build/libs
./gradlew compileJava           # быстрая проверка Java
./gradlew runClient             # клиент для проверки
make -C userland                # пересобрать систему (C++) в src/main/rootfs
```

Первая сборка скачивает clang (YoWASP, npm) и переводит его в байткод JVM: ~4 минуты и 4 ГБ памяти; дальше этот
шаг пропускается, пока clang не меняется.

## Устройство

| Где | Что |
|---|---|
| `src/main/java/dev/ccbaremetal/kernel` | машина WASM без кода Minecraft (кроме `Machine.Host`): виртуальная ФС, процессы на своих потоках (Chicory), WASI, системные вызовы `ccbaremetal`, терминал и TTY, фреймбуфер, `/dev` `/proc` `/sys`, мост к периферии CC, CraftOS/Lua (Cobalt) |
| `src/main/java/dev/ccbaremetal/computer` | `ComputerCore` (машина, экраны, ввод, питание) и блок компьютера |
| `src/main/java/dev/ccbaremetal/pocket`, `turtle` | карманный компьютер и черепашка: модули в слотах, API черепашки, `ITurtleAccess`/`IPocketAccess` для улучшений CC и аддонов |
| `src/main/java/dev/ccbaremetal/monitor`, `client`, `network` | пиксельный монитор, окна, рендер, сеть |
| `src/main/java/dev/ccbaremetal/unicode` | Unicode для компьютеров CC (`Utf8Layout`, `CcText`, миксины в `ccbaremetal.mixins.json`) |
| `userland/` | система на C++: `sh`, `coreutils`, `textutils`, `ccutils`, `userutils` (пользователи, sudo, lock), `ssh`, `edit`, `pkg`, `periph`, `init`, драйвер компилятора `cc`, библиотека `libbaremetal` (`include/baremetal.hpp`, `baremetal.h`, `linux/input.h`) |
| `src/main/rootfs` | собранная система (в git); Gradle упаковывает её в jar как `ccbaremetal/system.zip` |
| `userland/build-toolchain.sh`, `src/jittool` | компилятор для игры: clang 22 + sysroot wasi-sdk с libc++; на этапе сборки clang переводится в байткод JVM и «облегчается» (полный модуль занял бы ~2 ГБ памяти) |
| `scripts/` | генераторы текстур и шрифта |

Ключевые решения:

- Программы — модули WASI; Chicory переводит их в байткод JVM (кэш в `<папка игры>/ccbaremetal/jit-cache`), clang
  приходит уже переведённым внутри jar.
- Поддерживается только современная обработка исключений WebAssembly (`try_table`/`exnref`), поэтому C++
  собирается с `-fwasm-exceptions -mllvm -wasm-use-legacy-eh=false`.
- У программ WASI нет текущей папки; программы системы получают её из `$PWD` (`crt_cwd.o`), а для clang и binutils
  пути делает абсолютными драйвер `cc`.
- Совместимость с аддонами общая: методы периферии строит генератор CC, объекты из ответов становятся дескрипторами
  (`kernel/proc/ObjectTable`, вызов `obj_call`), API аддонов создаются для каждой машины
  (`kernel/periph/AddonApis`).
- Устройства устроены как в Linux (`kernel/dev`): `DeviceTree` хранит шину периферии, драйверы (по файлу на
  драйвер в `kernel/dev/drivers`: пиксельный монитор, монитор, динамик, принтер, модем; берёт первый подошедший) и
  устройства по классам; `/dev` и `/sys` только показывают это дерево, и новый вид устройства — один файл драйвера.
  Собственные устройства компьютера (fb0, клавиатура, мышь, редстоун) — в `ComputerDevices`. Периферия без драйвера
  остаётся в `/dev/periph`.
- Сеть (`kernel/net/Net`) — небольшой TCP в ядре, который отправляет через устройства класса net (`NetDevice`:
  wlanN/ethN драйвера модема, `Loopback`): сокеты — файловые дескрипторы; компьютер слушает канал модема,
  вычисленный из его имени, пакеты несут порты, номера и подтверждения и отправляются снова при потере. Пакеты
  обрабатываются в одном общем потоке, никогда внутри `queueEvent` (передача доходит до других компьютеров
  синхронно). `ssh`/`scp` — обычные программы поверх неё.
- Unicode для CC: CC хранит байты, рисование сжимает UTF-8 по отрезкам; все миксины необязательны
  (`require = 0`), форк CC запрещён.

## Предпросмотр отрисовки

`BAREMETAL_RENDER_PREVIEW=preview.png ./gradlew runClient` — клиент рисует пример терминала CC с Unicode настоящим
рендером CC, окна компьютера, кармана и черепашки, иконки предметов, сохраняет PNG и закрывается. Можно запускать без
окна внутри `kwin_wayland --virtual --xwayland`.

## Текстуры и шрифт

- `python3 scripts/gen_computer_textures.py` — WASM-компьютер, черепашка и карманный компьютер: продвинутые
  версии CC, золото перекрашено в аметист (`scripts/cc_textures.py`, оригиналы берутся из jar CC: Tweaked в кеше
  Gradle);
- `python3 scripts/gen_gui_textures.py` — окно компьютера: рамка, боковая панель и кнопки CC, панель модулей;
- `python3 scripts/gen_monitor_textures.py` — пиксельный монитор: продвинутый монитор CC в аметисте, связные текстуры,
  модели и состояния блока;
- шрифт 6×9 — `src/main/resources/ccbaremetal/font/glyphs.txt` (рисуется вручную), составные буквы с диакритикой —
  `scripts/gen_font_compose.py` → `compose.txt`.
