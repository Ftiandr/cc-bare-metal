# 2. Blocks, recipes, monitors, keyboard

## WASM computer

The third tier of computers: CC's normal computer is grey, the advanced one gold, the WASM computer amethyst. The
texture is in CC's style: a dark frame, a body with a light bottom strip, a screen; a running computer shows a
glowing `>` prompt and a blinking cursor.

Recipe (harder than an advanced computer, but without a long grind):

```
A D A      A: amethyst shard (6, tag c:gems/amethyst)
A C A      D: diamond (tag c:gems/diamond)
A R A      C: CC: Tweaked advanced computer
           R: block of redstone (tag c:storage_blocks/redstone)
```

Properties:

- **Sides** (`top`, `bottom`, `left`, `right`, `front`, `back`) work as on CC computers: left and right as seen by
  a player looking at the screen.
- **Peripherals** on any side and through CC's wired modems: see [section 6](06-peripherals.md).
- **Redstone**: outputs and inputs on every side (`redstone` in the console, `cc::setRedstone`,
  `/sys/class/redstone`).
- **The computer is a peripheral itself**, of type `computer`, for neighbouring CC computers: `turnOn`, `shutdown`,
  `reboot`, `getID`, `isOn`, `getLabel`.
- **Label** (`label set NAME` in the console) is shown as the item name.
- **Keeping data**: broken in creative mode, a computer with data (or a label) drops as an item and keeps its id
  and files, as in CC.

## WASM pocket computer

The same WASM computer, in your pocket: it runs while it is in a player's inventory. Right click opens its window,
the same as the WASM computer's, with a **Modules** button at the bottom of the sidebar.

Recipe:

```
A D A      A: amethyst shard (tag c:gems/amethyst)
A P A      D: diamond (tag c:gems/diamond)
A R A      P: CC: Tweaked advanced pocket computer
           R: block of redstone (tag c:storage_blocks/redstone)
```

### Modules instead of crafting

A CC pocket computer has one upgrade, and it has to be crafted in ("Noisy Pocket Computer"...). The WASM pocket
computer has **4 module slots** instead: the Modules button opens a small window with the slots and your inventory,
and any item registered in the game as a **CC pocket upgrade** goes there: CC's speaker, wireless and ender modems,
the player detector, chat box, geo scanner and so on from Advanced Peripherals and other addons. The tooltip over an
empty slot lists the items that fit (all of them with Shift); there is no per-mod code.

- The module in slot 1 is the `back` peripheral (where a CC pocket computer has its upgrade, so CC programs work);
  slots 2–4 are `slot2`, `slot3`, `slot4`. They show up in `/dev/periph`, `peripheral.find` and the C++ API.
- Inserting or removing a module attaches or detaches the peripheral immediately; programs receive the
  `peripheral` / `peripheral_detach` events.
- The same module can be installed only once (two different modems are fine).
- Quick install without the window: module in the off hand, **Shift+right click** with the pocket computer; it goes
  into the first free slot.
- The module window closes with the same button, Esc or the inventory key; while it is closed nothing covers the
  screen. Inventory mods (JEI, EMI, Inventory Profiles Next) only appear in the module window, where they are
  useful, and stay off the terminal.
- Installed modules are listed in the item's tooltip. Modules can light the lamp on the body in their colour
  (modems).

What the pocket computer does not have is **redstone**: in Minecraft a signal comes from a block at its position,
and an item in a hand has none. Blocks that need a position in the world (a redstone integrator, for example) only
exist as blocks: they are not registered as pocket upgrades and do not fit in a slot.

The computer runs while the item is in an inventory. Put it in a chest or drop it and it stops after a couple of
seconds; if it was on, it boots again once it is back in an inventory. Files are kept as for the WASM computer, by
the id stored in the item.

## WASM turtle

A WASM computer that moves, digs, builds and carries items. Its API is CC's turtle API with the same names,
arguments and results, so CC turtle programs work as they are: CraftOS (`craftos`, `lua file`) has the global
`turtle`, as on CC.

Recipe:

```
A D A      A: amethyst shard (tag c:gems/amethyst)
A T A      D: diamond (tag c:gems/diamond)
A R A      T: CC: Tweaked advanced turtle
           R: block of redstone (tag c:storage_blocks/redstone)
```

Control:

- **from the shell**: the `turtle` command: `turtle forward 3`, `turtle left`, `turtle dig`, `turtle refuel`,
  `turtle inv`, `turtle place`, `turtle drop`... (`man turtle`). A failure prints the reason, as in CC ("Movement
  obstructed", "Out of fuel"), and exits with status 1;
- **from programs**: the files `/dev/periph/turtle/<method>` (`cat /dev/periph/turtle/forward`), in C++
  `cc::Peripheral("turtle").invoke("forward")`, in Lua `turtle.forward()`.

Each action waits for its turn on the server, as in CC: a move or turn takes 8 ticks, with smooth animation.

**Upgrades also go into slots, no crafting.** The Modules button in the turtle's window opens a panel: two slots
(left and right) for any turtle upgrade from CC and its addons (pickaxe, axe, shovel, hoe, sword, crafting table,
modems, speaker, player detector...), the turtle's 4×4 inventory with a frame around the selected slot, its fuel
and your inventory. The tooltip over an empty slot lists the items that fit. A peripheral upgrade is the `left` or
`right` peripheral; the other sides (`front`, `back`, `top`, `bottom`) are the blocks around the turtle, as in CC.
Programs can swap upgrades themselves: `turtle equip left` (`turtle.equipLeft()`) swaps the selected item with the
left upgrade.

**Fuel** is anything that burns in a furnace (coal: 80 moves): `turtle refuel` burns the selected stack. Fuel and
upgrades are kept in the item when the turtle is broken; the inventory drops.

**Tools.** CC's tool upgrades (and addon tools made with its tool builder) are written for CC's internal turtle and
refuse to work on any other turtle. In that case the WASM turtle uses the tool's item itself, like a player: it digs
(drops go into its inventory) and attacks, and fires block break events, so claim protection works. Upgrades with
their own logic work directly.

What does not work: the Advanced Peripherals features that depend on CC turtle internals (the saddle, the automata
"hand") will not work on the WASM turtle; AP's ordinary peripheral upgrades do.

## Pixel monitor

A monitor that shows a framebuffer (an image, not text). Neighbouring monitors facing the same way join into one
rectangular screen (up to 8×6 blocks, configurable). The textures connect like those of CC's advanced monitor: the
frame is only drawn around the edge of the whole wall. By default a block has 64 pixels; the mode can go up to 128
pixels per block (1024 at most on a side).

Recipe (makes 2 monitors):

```
I A I      I: iron ingot
A M A      A: amethyst shard (tag c:gems/amethyst)
I A I      M: CC: Tweaked advanced monitor
```

The pixel monitor is a peripheral of type `pixel_monitor` and also works with ordinary CC computers (from Lua):

| Method | What it does |
|---|---|
| `getSize()`, `getMaxSize()`, `getBlockSize()` | resolution, maximum, size in blocks |
| `getMode()`, `setMode(bpp [, w, h [, grey]])` | colour depth 1/2/4/8 bits and resolution |
| `clear([c])`, `setPixel(x, y, c)`, `getPixel(x, y)`, `fill(x, y, w, h, c)` | pixels |
| `drawLine`, `drawRect`, `drawCircle(x, y, r, c [, filled])`, `drawText(x, y, text [, fg [, bg [, scale]]])` | shapes and text (6×9 font, Unicode) |
| `blit(x, y, w, h, bytes [, transparent])`, `blitHex(x, y, row)`, `read(x, y, w, h)` | blocks of pixels |
| `setPalette(i, rgb)`, `getPalette(i)`, `scroll(dy [, c])`, `flush()` | palette, scrolling, waiting until sent |

Right clicking the image gives a `pixel_monitor_touch` event with the arguments: monitor name, x, y, the pixel under
the cursor (from 0, in the current resolution). Only the image counts: a click on the frame does nothing, and a wall
of several blocks has no frames between blocks, so coordinates run across the whole wall. Every computer the monitor
is attached to receives the event (a WASM program needs `cc::wantEvents()`); it also shows up in
`/dev/periph/<name>/events`.

On a WASM computer the monitor is also a touch device, like a touchscreen on Linux: `/dev/input/event2` (the next
monitors are `event3`...). A program just reads `struct input_event` from `<linux/input.h>`; no CC events or
high-level calls are needed. Details in [section 5](05-graphics-input.md).

From a WASM computer the monitor is even faster to use through `cc::gfx::target("top")` and as the file `/dev/fb1`
(see [section 5](05-graphics-input.md)).

## Keyboard

Craft it from three stone buttons over amethyst, redstone, amethyst. Right-click a WASM computer or turtle with it:
the keyboard is linked to it and you start typing. Later a right-click anywhere within 64 blocks of that computer
does the same. A linked keyboard can also be **placed** (on a desk next to the monitors): right-click the block to
type; its computer must be within 64 blocks of it, and typing stops when you walk more than 8 blocks away. Breaking it
keeps the link. While typing there is no window: you see the world (monitors, the turtle at work) and turn your head
with the mouse as usual, but stand still, and **every key goes to the computer**, Esc included; mouse buttons, the
wheel and the movement go to it too. **Shift+Esc** stops.
The computer gets a keyboard device of its own for that time (see [section 5](05-graphics-input.md)).

## Textures

The WASM computer and pixel monitor textures are those of CC: Tweaked's advanced computer and advanced monitor with
the gold body recoloured to amethyst. The frame, screen and blinking cursor are the originals. The recolouring is
done per pixel, so the "stone" grain of the body stays the same. The scripts `scripts/gen_computer_textures.py` and
`scripts/gen_monitor_textures.py` do it, taking the originals from the CC: Tweaked jar. The original textures
belong to the authors of ComputerCraft / CC: Tweaked and are distributed under their license.
