# 6. Peripherals of CC: Tweaked and addons

## How it works

There is no code for particular mods: the mod uses the same mechanisms as CC: Tweaked itself, so blocks of **any
addon** work as they do on an ordinary CC computer:

- **Peripherals** on any side of the computer and through CC's wired modems. Methods are taken from the peripheral
  by the same generator CC uses to build Lua methods (`@LuaFunction`, dynamic peripherals and the generic
  inventory, energy and fluid peripherals). Methods that wait for events or run on the server's main thread work
  too.
- **Objects** returned by methods (addons do this: "an item", "an entity", a function) are available both in Lua
  and in C++ (`cc::Object`).
- **Addon global APIs** (which a mod adds to every computer through `registerAPIFactory`) are created for WASM
  computers too: as globals in Lua, as `cc::api` in C++.
- Addon **events** (`chat`, `monitor_touch`, `modem_message`...) reach programs.

**Strings** are passed to addons exactly as Lua on a CC computer passes them: as UTF-8 bytes (in Java one char per
byte; "привет" is 12 chars). Addons expect precisely this: those that store, compare or forward strings get the
same data as from an ordinary computer. In results, C++ programs get normal text (the UTF-8 is decoded back), Lua
gets the same bytes.

So addons that show text to players behave as they do with an ordinary CC computer: for example, the Advanced
Peripherals chat box shows Cyrillic as garbage unless its UTF-8 option is on. Turn it on: in AP 0.7.x
`sendMessage(text, nil, nil, nil, nil, true)` (the sixth argument), in AP 0.8+ `sendMessage(text, {utf8 = true})`.

The only addons that do not work are those that hook into the code of CC's own computers (for example, their own
editors or extensions of CC's window).

## From the command line

```sh
lsperiph                         # what is attached
periph methods left              # methods
periph call left getSize         # call one
periph call chatBox_0 sendMessage "Hello"
periph api                       # addon APIs and their methods
periph api NAME METHOD ARGS...
```

Arguments: numbers, `true`/`false`/`nil`, JSON (`{...}`, `[...]`), anything else is a string.

## From C++

```cpp
for (auto &p : cc::Peripheral::list()) printf("%s\n", p.name.c_str());   // names and types
cc::Peripheral chat("chatBox_0");
auto r = chat.invoke("sendMessage", "Hello");           // any method, arguments as in C++
if (!r) puts(r.error.c_str()); else r.value[0];         // results are a JSON array
auto m = cc::Peripheral::find("monitor");               // the first peripheral of that type

// An object returned by a method
if (auto obj = cc::Object::from(r.value[0])) obj->invoke("getName");

// Addon global APIs
for (auto &name : cc::api::list()) ...
if (auto api = cc::api::get("someapi")) api->invoke("method", 1, "two");
```

Convenience classes for CC's standard peripherals (`find()` takes the first one, or construct one by name):

| Class | Methods |
|---|---|
| `cc::Modem` | `open close isOpen closeAll transmit isWireless namesRemote nameLocal`; `cc::receiveModem(timeout)` |
| `cc::Monitor` | `write setCursorPos cursorPos size clear clearLine scroll setTextColour setBackgroundColour setTextScale setCursorBlink isColour blit setPaletteColour` (colours: `cc::colors::red`...) |
| `cc::Speaker` | `playNote playSound playAudio stop` |
| `cc::Printer` | `newPage endPage write setCursorPos pageSize setPageTitle inkLevel paperLevel` |
| `cc::Drive` | `isDiskPresent label setLabel hasData mountPath diskId hasAudio audioTitle playAudio stopAudio eject` |
| `cc::Inventory` | `size list itemDetail itemLimit pushItems pullItems` (chests and machines of any mod) |
| `cc::EnergyStorage`, `cc::FluidStorage` | `energy capacity`; `tanks pushFluid pullFluid` |

## rednet and gps

`cc::rednet` and `cc::gps` follow CC's protocols (`rom/apis/rednet.lua`, `gps.lua`) message for message, so C++
programs and CC Lua computers talk to each other:

```cpp
cc::rednet::open();                                         // all modems
cc::rednet::send(5, cc::Json::object({{"cmd", "go"}}), "ctl");
if (auto m = cc::rednet::receive("ctl", 10)) ...            // m->sender, m->message, m->protocol
cc::rednet::host("ctl", "base"); auto ids = cc::rednet::lookup("ctl");
if (auto p = cc::gps::locate()) printf("%g %g %g\n", p->x, p->y, p->z);
```

Commands: `rednet open|close|status|send ID MSG [PROTO]|broadcast MSG|receive [PROTO] [TIMEOUT]|listen|lookup|host`,
`gps locate | gps host X Y Z`. Messages are text, numbers or JSON; Lua computers receive tables.

## Commands for standard peripherals

| Command | What it does |
|---|---|
| `monitor [-n NAME] [-s SCALE] [-c COLOUR] [-C] [TEXT...]` | text on a CC monitor; without text, lines from stdin (`ls \| monitor`) |
| `speaker note|sound|play FILE.dfpwm|stop` | notes, Minecraft sounds, DFPWM playback |
| `lp [-t TITLE] [FILE...]` | printing with wrapping and pages |
| `disk [info|eject|label [TEXT]|play|stop]`, `eject` | disk drive |
| `inv [NAME]`, `inv push FROM TO SLOT [N]`, `inv detail NAME SLOT` | inventories |
| `energy` | energy and fluids of every attached storage |
| `redstone [probe]` | table of inputs and outputs on every side |
| `redstone get SIDE [output]` | input (or output) level 0–15: printed and returned as the exit status (`$?`) |
| `redstone set SIDE LEVEL\|on\|off` | output 0–15 |
| `redstone pulse SIDE [COUNT] [PERIOD]` | pulses (one by default, period 0.5 s) |
| `redstone wait SIDE [on\|off\|change]` | wait for an input signal (by default, for it to appear) |

## Peripherals as files: /dev/periph

Every peripheral of every mod is a folder `/dev/periph/<name>/` with a file per method:

```sh
ls /dev/periph/left                        # methods, plus type and events
cat /dev/periph/left/getSize               # call without arguments -> [51,19]
echo 5 6 9 > /dev/periph/left/setPixel     # call with arguments
echo 5 6 > /dev/periph/left/getPixel; cat /dev/periph/left/getPixel   # arguments, then the result (kept for 10 s)
cat /dev/periph/left/type                  # types
cat /dev/periph/left/events                # this peripheral's events, one JSON array per line
cat /dev/periph/events                     # all of the computer's events
```

Reading a method's file **calls** the method, just as writing does. So `cat`, `grep -r` or `head` over a peripheral's
folder run its methods: `cat /dev/periph/top/closeAll`, for example, closes every channel of the modem. Modem
channels are shared by the whole computer, so other programs lose theirs too. The kernel opens the computer's network
channel again within a second (`ssh`, `nc` go on working); other programs may need a restart. Safe to look at: `ls` and `cat .../type`.

Arguments are words (numbers, `true/false/nil`, `"text"`) or one JSON array. The result is a JSON array. Errors go
to stderr. Put JSON in single quotes, or the shell eats the inner double quotes:
`echo '[1, "hello"]' > /dev/periph/top/writePage` (or simply `echo 1 hello > ...`). If the quotes were eaten anyway
(`[1, hello]`), such a flat list is understood too: its items are read as words. If a call fails, `echo` returns an
error and an `&&` chain stops. A program can open a method's file for reading and writing: write the arguments and
a newline, read the reply.

As on Linux, **drivers** turn known peripherals into devices of a class (graphics, input, sound, printer, tty, net,
redstone): each gets a node in `/dev` and attributes in `/sys/class/<class>/<name>`; peripherals no driver knows stay
reachable through `/dev/periph`. `lsdev` lists every device with its node and peripheral, and the peripherals without
a driver; `/sys/bus/peripheral/devices/<name>/` shows each peripheral's types, driver and devices. Numbers are given
as devices appear (the lowest free one: the first pixel monitor is `fb1`, `fb0` being the screen).

| Node | What it is |

|---|---|
| `/dev/fb1`, `/dev/fb2`... | pixel monitors, like `/dev/fb0` |
| `/dev/dsp0`... | speakers: raw audio, signed 8 bit, 48 kHz, mono (`head -c 48000 /dev/urandom > /dev/dsp0`) |
| `/dev/lp0`... | printers: text is printed with pages on close (`cat notes.txt > /dev/lp0`); `ink`, `paper` in `/sys/class/printer/lp0` |
| `/dev/monitor0`... | CC monitors as terminals: wrapping, ANSI colours, cursor (`printf '\033[31mred\n' > /dev/monitor0`) |
| `wlan0`, `eth0`... | modems as network interfaces (no node, as on Linux): `/sys/class/net/<name>`, `ip link` |

Redstone: `/sys/class/redstone/<side>/input` (read) and `output` (0–15, write).

While the files are not open, none of this costs anything: names and methods are only looked up on access.

**When a device goes away** while it is in use (a monitor broken, a modem disconnected, a floppy ejected), it is as
on Linux: open device files answer "No such device", files on an ejected floppy "Input/output error". A program
waiting for events or touches wakes up with that error instead of hanging; `cat` and the other utilities report it
and exit with status 1.

## The computer as a peripheral

Neighbouring CC computers see a WASM computer as a `computer` peripheral: `turnOn`, `shutdown`, `reboot`, `getID`,
`isOn`, `getLabel`.
