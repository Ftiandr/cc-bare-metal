# 6. Периферия CC: Tweaked и аддонов

## Как это устроено

Под конкретные моды никакого кода нет: мод использует те же механизмы, что и сам CC: Tweaked, поэтому блоки
**любых аддонов** работают так же, как на обычном компьютере CC:

- **Периферия** — на любой стороне компьютера и через проводные модемы CC. Методы берутся у самой периферии тем
  же генератором, которым CC строит методы для Lua (`@LuaFunction`, динамическая и универсальная периферия
  инвентарей, энергии и жидкостей). Методы, которые ждут событий или выполняются в основном потоке сервера, тоже
  работают.
- **Объекты**, которые возвращают методы (у аддонов это бывает: «предмет», «сущность», функция), доступны и в Lua,
  и в C++ (`cc::Object`).
- **Глобальные API аддонов** (которые мод добавляет на каждый компьютер через `registerAPIFactory`) создаются и для
  WASM-компьютеров: в Lua — как глобальные переменные, в C++ — `cc::api`.
- **События** аддонов (`chat`, `monitor_touch`, `modem_message`...) приходят в программы.

**Строки** передаются аддонам ровно так же, как их передаёт Lua на компьютере CC: байтами UTF-8 (в Java — один
символ на байт; «привет» — 12 символов). Аддоны рассчитаны именно на это: те, что хранят, сравнивают или пересылают
строки, получают те же данные, что и от обычного компьютера. В ответах C++-программы получают обычный текст
(UTF-8 раскодируется обратно), Lua — те же байты.

Поэтому аддоны, которые показывают текст игрокам, ведут себя как с обычным компьютером CC: например, чат-бокс
Advanced Peripherals без опции UTF-8 покажет кириллицу «кракозябрами». Включите её: в AP 0.7.x —
`sendMessage(текст, nil, nil, nil, nil, true)` (шестой аргумент), в AP 0.8+ — `sendMessage(текст, {utf8 = true})`.

Не работают только аддоны, которые встраиваются в код самих компьютеров CC (например, свои редакторы или
расширения их окна).

## Из командной строки

```sh
lsperiph                         # что подключено
periph methods left              # методы
periph call left getSize         # вызвать
periph call chatBox_0 sendMessage "Привет"
periph api                       # API аддонов и их методы
periph api NAME METHOD ARGS...
```

Аргументы: числа, `true`/`false`/`nil`, JSON (`{...}`, `[...]`), остальное — строки.

## Из C++

```cpp
for (auto &p : cc::Peripheral::list()) printf("%s\n", p.name.c_str());   // имена и типы
cc::Peripheral chat("chatBox_0");
auto r = chat.invoke("sendMessage", "Привет");          // любой метод, аргументы — как в C++
if (!r) puts(r.error.c_str()); else r.value[0];         // результаты — JSON-массив
auto m = cc::Peripheral::find("monitor");               // первая периферия такого типа

// Объект, который вернул метод
if (auto obj = cc::Object::from(r.value[0])) obj->invoke("getName");

// Глобальные API аддонов
for (auto &name : cc::api::list()) ...
if (auto api = cc::api::get("someapi")) api->invoke("method", 1, "two");
```

Удобные классы для стандартной периферии CC (`find()` берёт первую, или создайте по имени):

| Класс | Методы |
|---|---|
| `cc::Modem` | `open close isOpen closeAll transmit isWireless namesRemote nameLocal`; `cc::receiveModem(timeout)` |
| `cc::Monitor` | `write setCursorPos cursorPos size clear clearLine scroll setTextColour setBackgroundColour setTextScale setCursorBlink isColour blit setPaletteColour` (цвета — `cc::colors::red`...) |
| `cc::Speaker` | `playNote playSound playAudio stop` |
| `cc::Printer` | `newPage endPage write setCursorPos pageSize setPageTitle inkLevel paperLevel` |
| `cc::Drive` | `isDiskPresent label setLabel hasData mountPath diskId hasAudio audioTitle playAudio stopAudio eject` |
| `cc::Inventory` | `size list itemDetail itemLimit pushItems pullItems` (сундуки и машины любых модов) |
| `cc::EnergyStorage`, `cc::FluidStorage` | `energy capacity`; `tanks pushFluid pullFluid` |

## rednet и gps

`cc::rednet` и `cc::gps` повторяют протоколы CC (`rom/apis/rednet.lua`, `gps.lua`) сообщение в сообщение, поэтому
C++-программы и Lua-компьютеры CC общаются друг с другом:

```cpp
cc::rednet::open();                                         // все модемы
cc::rednet::send(5, cc::Json::object({{"cmd", "go"}}), "ctl");
if (auto m = cc::rednet::receive("ctl", 10)) ...            // m->sender, m->message, m->protocol
cc::rednet::host("ctl", "base"); auto ids = cc::rednet::lookup("ctl");
if (auto p = cc::gps::locate()) printf("%g %g %g\n", p->x, p->y, p->z);
```

Команды: `rednet open|close|status|send ID MSG [PROTO]|broadcast MSG|receive [PROTO] [TIMEOUT]|listen|lookup|host`,
`gps locate | gps host X Y Z`. Сообщения — текст, числа или JSON; Lua-компьютеры получают таблицы.

## Команды для стандартной периферии

| Команда | Что делает |
|---|---|
| `monitor [-n NAME] [-s SCALE] [-c COLOUR] [-C] [TEXT...]` | текст на монитор CC; без текста — строки из stdin (`ls \| monitor`) |
| `speaker note|sound|play FILE.dfpwm|stop` | ноты, звуки Minecraft, проигрывание DFPWM |
| `lp [-t TITLE] [FILE...]` | печать с переносом и страницами |
| `disk [info|eject|label [TEXT]|play|stop]`, `eject` | дисковод |
| `inv [NAME]`, `inv push FROM TO SLOT [N]`, `inv detail NAME SLOT` | инвентари |
| `energy` | энергия и жидкости всех подключённых хранилищ |
| `redstone [probe]` | таблица входов и выходов на всех сторонах |
| `redstone get SIDE [output]` | уровень входа (или выхода) 0–15: печатается и возвращается кодом выхода (`$?`) |
| `redstone set SIDE LEVEL\|on\|off` | выход 0–15 |
| `redstone pulse SIDE [COUNT] [PERIOD]` | импульсы (по умолчанию один, период 0,5 с) |
| `redstone wait SIDE [on\|off\|change]` | ждать сигнала на входе (по умолчанию — появления) |

## Периферия как файлы: /dev/periph

Любая периферия любого мода — папка `/dev/periph/<имя>/` с файлом на каждый метод:

```sh
ls /dev/periph/left                        # методы, плюс type и events
cat /dev/periph/left/getSize               # вызов без аргументов -> [51,19]
echo 5 6 9 > /dev/periph/left/setPixel     # вызов с аргументами
echo 5 6 > /dev/periph/left/getPixel; cat /dev/periph/left/getPixel   # аргументы, затем результат (хранится 10 с)
cat /dev/periph/left/type                  # типы
cat /dev/periph/left/events                # события этой периферии, по JSON-массиву на строку
cat /dev/periph/events                     # все события компьютера
```

Чтение файла метода **вызывает** метод, как и запись. Поэтому `cat`, `grep -r` или `head` по папке периферии
выполняют её методы: например, `cat /dev/periph/top/closeAll` закроет на модеме все каналы. Каналы модема общие на
весь компьютер, поэтому закроются и у других программ. Сетевой канал компьютера ядро откроет снова в течение секунды
(`ssh` и `nc` продолжат работать), а другим программам, возможно, придётся перезапуститься. Безопасно смотреть: `ls` и `cat .../type`.

Аргументы — слова (числа, `true/false/nil`, `"текст"`) или один JSON-массив. Результат — JSON-массив. Ошибки
печатаются в stderr. JSON берите в одинарные кавычки, иначе оболочка съест внутренние двойные:
`echo '[1, "привет"]' > /dev/periph/top/writePage` (или просто `echo 1 привет > ...`). Если кавычки всё же
съедены (`[1, привет]`), такой плоский список тоже понимается: элементы читаются как слова. Если вызов не удался,
`echo` возвращает ошибку, и цепочка `&&` останавливается. Программа может открыть файл метода на чтение и запись: записать аргументы и строку, прочитать
ответ.

Как в Linux, **драйверы** превращают знакомую периферию в устройства своего класса (graphics, input, sound,
printer, tty, net, redstone): у каждого есть узел в `/dev` и атрибуты в `/sys/class/<класс>/<имя>`; периферия, которую
не знает ни один драйвер, по-прежнему доступна через `/dev/periph`. `lsdev` показывает все устройства с их узлами и
периферией, а также периферию без драйвера; `/sys/bus/peripheral/devices/<имя>/` — типы, драйвер и устройства каждой
периферии. Номера выдаются по мере появления (наименьший свободный: первый пиксельный монитор — `fb1`, `fb0` — экран).

| Узел | Что это |
|---|---|
| `/dev/fb1`, `/dev/fb2`... | пиксельные мониторы, как `/dev/fb0` |
| `/dev/dsp0`... | динамики: сырой звук, 8 бит со знаком, 48 кГц, моно (`head -c 48000 /dev/urandom > /dev/dsp0`) |
| `/dev/lp0`... | принтеры: текст печатается со страницами при закрытии (`cat notes.txt > /dev/lp0`); уровни `ink`, `paper` — в `/sys/class/printer/lp0` |
| `/dev/monitor0`... | мониторы CC как терминал: переносы, цвета ANSI, курсор (`printf '\033[31mкрасный\n' > /dev/monitor0`) |
| `wlan0`, `eth0`... | модемы как сетевые интерфейсы (без узла, как в Linux): `/sys/class/net/<имя>`, `ip link` |

Редстоун: `/sys/class/redstone/<сторона>/input` (чтение) и `output` (0–15, запись).

Пока файлы не открыты, всё это ничего не стоит: имена и методы ищутся только при обращении.

**Если устройство пропало**, пока с ним работают (монитор сломали, модем отключили, дискету вынули), всё как в Linux:
открытые файлы устройства отвечают ошибкой «No such device», файлы на вынутой дискете — «Input/output error».
Программа, ждавшая событий или касаний, просыпается с этой ошибкой и не висит; `cat` и другие утилиты сообщают
о ней и завершаются с кодом 1.

## Компьютер как периферия

WASM-компьютер виден соседним компьютерам CC как периферия `computer`: `turnOn`, `shutdown`, `reboot`, `getID`,
`isOn`, `getLabel`.
