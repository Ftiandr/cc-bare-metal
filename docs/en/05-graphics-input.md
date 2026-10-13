# 5. Graphics and input

## The screen

Every WASM computer has two modes: a text terminal and a graphics framebuffer.

- The framebuffer is indexed: **1, 2, 4 or 8 bits per pixel** (2, 4, 16 or 256 colours), with a 24-bit palette.
  8 bits: CC's colours (0–15), a 6×6×6 cube (16–231), greys (232–255).
- Size up to **1280×800**. By default it is the largest standard resolution (16:9, 16:10, 4:3, 3:2: 480×270,
  512×288, 512×320, 640×360, 640×480, 800×450, 960×540, 1280×720...) that fits the window's text area, with 256
  colours. Until a mode is set explicitly, the size follows the window size; `fbset auto` goes back to that.
- **Economical**: only changed rectangles go over the network, packed by colour depth and compressed, and only to
  players who have the screen open; the rate is limited by the `max_sync_kb_per_tick` setting. Text mode is sent as
  characters. The window title shows the resolution, number of colours and current traffic.
- The game window scales the image to its own size.

## Three ways to draw

### 1. `cc::gfx` (C++) and `gfx` (Lua)

```cpp
auto info = cc::systemInfo();
cc::gfx::mode(info["screen_width"].asInt(), info["screen_height"].asInt(), 8);
cc::gfx::clear(0);
cc::gfx::fill(10, 10, 50, 30, 196);
cc::gfx::line(0, 0, 100, 60, 15);
cc::gfx::circle(160, 90, 40, 46, true);
cc::gfx::text(4, 4, "Hello", 15);        // 6×9 font, Unicode; also bg and scale
cc::gfx::palette(1, {0xFF8800});
cc::gfx::present();                       // wait until the frame is shown
```

Also `rect`, `pixel`, `getPixel`, `scroll`, `blit(x, y, w, h, pixels, stride)`, `info()`, `textMode()`. When the
program exits, the screen goes back to text mode.

In Lua: `gfx.setMode(w, h, bpp[, grey])`, `gfx.textMode()`, `gfx.getDefaultSize()`, `gfx.getMode()`,
`gfx.clear`, `gfx.setPixel`, `gfx.fill`, `gfx.line`, `gfx.rect`, `gfx.circle`, `gfx.text`, `gfx.blit`,
`gfx.setPalette`, `gfx.present`, `gfx.setTarget("top")`.

### 2. `/dev/fb0`, as on Linux

The screen is a file: **one byte (colour index) per pixel**, offset = `y * width + x`. Any program and any utility
can draw:

```sh
fbset 320x200x8                                # mode (or through /sys)
head -c 64000 /dev/urandom > /dev/fb0          # noise over the whole screen
```

The image stays after the program exits; any key brings the text screen back.

`/sys/class/graphics/fb0/` holds files that can be read and written:

| File | Value |
|---|---|
| `virtual_size` | `512,288`; writing `320,200` changes the resolution, `auto` restores the default |
| `bits_per_pixel` | `8` (1, 2, 4 or 8) |
| `stride` | bytes per line of `/dev/fb0` (= width) |
| `palette` | lines of `INDEX RRGGBB`; writing such lines changes colours |
| `display` | `text` or `graphics`; writing switches the screen |
| `default_size`, `max_size`, `name` | read-only |

`fbset` shows the mode; `fbset WxH[xBPP]`, `fbset -g W H`, `fbset -depth N`, `fbset -pal N RRGGBB`, `fbset auto`,
`fbset text|graphics`, `-fb /dev/fbN` for another framebuffer. Example: `/usr/share/examples/framebuffer.cpp`.

### 3. Pixel monitors

`cc::gfx::target("top")` (C++) or `gfx.setTarget("top")` (Lua) sends every drawing function to the monitor instead
of the screen (`target("")` switches back). The same monitor is the file `/dev/fb1` (`/dev/fb2`... for the next
ones, numbered as they appear) with settings in `/sys/class/graphics/fbN` (where `device` is the peripheral name). The
monitor's peripheral methods are in [section 2](02-blocks.md).

## Mouse and keyboard

### As events

With the `CC_PROC_INPUT` flag a program receives `key`, `key_up`, `char`, `paste`, `mouse_click`, `mouse_drag`,
`mouse_up`, `mouse_scroll`. In graphics mode mouse coordinates are **framebuffer pixels** (the window converts them
taking the scale into account); in text mode they are cells. Pixel monitor: `pixel_monitor_touch` (name, x, y).

### As `/dev/input` files, as on Linux

| Device | Format |
|---|---|
| `/dev/input/event0` | keyboard: `struct input_event` (24 bytes: time, type, code, value), Linux `KEY_*` codes; value 1 pressed, 0 released, 2 repeat |
| `/dev/input/event1` | mouse: `EV_ABS ABS_X/ABS_Y` (pixels in graphics, cells from 0 in text), `EV_KEY BTN_LEFT/RIGHT/MIDDLE`, `EV_REL REL_WHEEL` |
| `/dev/input/mice` | mouse in PS/2 format: 3-byte packets (buttons, dx, dy) |
| `/dev/input/event2`... | touchscreens of pixel monitors and CC monitors, numbered as they are attached (`/sys/class/input/eventN` says which). A touch is `ABS_X`, `ABS_Y`, `BTN_TOUCH 1`, `SYN_REPORT`, then `BTN_TOUCH 0`, `SYN_REPORT`. Coordinates are the monitor pixel from 0 in its current resolution (the cell from 0 on CC monitors) |

Which device is which is shown in `/sys/class/input/eventN`: `name` (`keyboard`, `mouse`, `pixel_monitor`,
`monitor`), and for touchscreens also `device` (peripheral name), `fb` (its `/dev/fbN`) and `size` (`W,H`, the
range of `ABS_X` and `ABS_Y`). `evtest -l` lists the devices:

```
$ evtest -l
/dev/input/event0	keyboard
/dev/input/event1	mouse
/dev/input/event2	pixel_monitor top (fb1, 128x64)
```

**The keyboard item** (see [section 2](02-blocks.md)) adds a device while a player types with it:
`/dev/input/eventN` with `name` `keyboard` and `player`. Its keys arrive as above and also go to the terminal and to
`event0` like typing in the window; the mouse (which also turns the player's head) is relative, as on a real computer: `EV_REL REL_X/REL_Y`,
`REL_WHEEL`, `BTN_LEFT/RIGHT/MIDDLE`. For the foreground program (Lua, `cc::pullEvent`) the same arrives as events:
`mouse_relative` (dx, dy), `mouse_button` (button 0 left / 1 right / 2 middle, down), `mouse_wheel` (steps, up is
positive); every program sees `keyboard_attach` and `keyboard_detach` (device, player).

```lua
-- turn a turtle with the mouse while watching it
while true do
  local _, dx = os.pullEvent("mouse_relative")
  if dx > 20 then turtle.turnRight() elseif dx < -20 then turtle.turnLeft() end
end
```

A whole pixel monitor without CC events: write the image to `/dev/fb1`, read touches from `/dev/input/event2`.

```c
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <unistd.h>

int main(void) {
    int fd = open("/dev/input/event2", O_RDONLY);
    struct input_event e;
    int x = 0, y = 0;
    while (read(fd, &e, sizeof e) == sizeof e) {
        if (e.type == EV_ABS && e.code == ABS_X) x = e.value;
        if (e.type == EV_ABS && e.code == ABS_Y) y = e.value;
        if (e.type == EV_KEY && e.code == BTN_TOUCH && e.value == 1) printf("touch %d %d\n", x, y);
    }
}
```

A complete program is `/usr/share/examples/touchpaint.cpp`: a paint program for a pixel monitor using only
`/dev/fbN` and `/dev/input/eventN`. At the bottom is a 16-colour palette and a cell with a cross (clear); touching
anywhere else draws a dot, and the coordinates of every touch are printed to the terminal.

```sh
cd /tmp && c++ /usr/share/examples/touchpaint.cpp -o touchpaint && ./touchpaint      # first pixel monitor
./touchpaint top                                                                     # the monitor named top
```

A touch in Minecraft is instant (a right click), so press and release arrive right after each other; monitors have
no dragging. While nobody has a device open, touches cost nothing.

The `<linux/input.h>` header is in the system, so Linux code builds unchanged. Every reader gets every event, and
keys still go to the terminal too (as on Linux without EVIOCGRAB). While a program has the mouse open, the window
also sends plain cursor motion (up to 20 times a second); when nobody reads the mouse, motion is not sent.

`evtest /dev/input/event0` (or `event1`, `event2`, `mice`) prints events, `evtest -l` lists the devices. Example:
`/usr/share/examples/paint.cpp`, a paint program using only `/dev/fb0` and `/dev/input`: the left button draws,
the right one erases, 1–9 pick a colour, Esc quits.
