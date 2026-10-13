# 1. Quick start and the computer window

## First start

1. Craft a WASM computer (recipe in [section 2](02-blocks.md)) and place it.
2. Right click the block to open its window. A computer that is off is turned on with **Enter** (or space) or the
   power button on the sidebar on the left.
3. On the first start a setup asks for the computer's name, your user name and password (and, optionally, a root
   password; without one root works through `sudo`). Then log in with your name: the prompt `name@computer:~$`
   appears. From here on it is a normal Unix-like console:

```sh
help                     # shell built-ins and syntax
man intro                # system overview
ls /bin                  # commands
cd /tmp && c++ /usr/share/examples/hello.cpp -o hello && ./hello
craftos                  # CraftOS, CC: Tweaked's shell, if you want Lua
```

A computer does not turn itself off: when its chunk is unloaded or the server stops, it remembers that it was on and
boots again. A computer's files are kept in the world: `<world>/computercraft/ccbaremetal/computer/<id>`.

## The computer window

The window is made like CC: Tweaked's advanced computer, only in amethyst: the terminal inside CC's border, a sidebar
with buttons on the left. The window takes up almost the whole game screen: the terminal gets exactly as many
columns and rows as fit at the chosen font size, and programs receive a `term_resize` event.

The border and sidebar are drawn at Minecraft's GUI scale and do not change; the font size only changes the text.
A font pixel covers a whole number of screen pixels, so pixels are always square. In graphics mode the image is
shown at the largest whole scale that fits the window; a fractional scale is only used when the resolution is
bigger than the whole window.

Sidebar buttons (each has a tooltip):

- **power**: turn on / off (as in CC);
- **reboot**;
- **terminate** the program (like Ctrl+T in CC);
- **bigger / smaller**: font size (only the text changes, the border stays as it is).

Above the border: the computer's id and the terminal size (in graphics mode the resolution, number of colours and
current screen traffic), plus short notices such as "Copied N characters".

## Keys

| Action | Keys |
|---|---|
| Copy the selection | **Ctrl+Shift+C** (or Ctrl+Insert; Ctrl+C when something is selected) |
| Paste | **Ctrl+Shift+V** (or Ctrl+V, Shift+Insert, middle mouse button) |
| Select | drag with the mouse; double click: word, triple click: line; Ctrl+Shift+A: everything |
| Right click | copy the selection, or paste when nothing is selected |
| Scroll back (2000 lines) | mouse wheel, Shift+PgUp/PgDn, Shift+Home/End |
| Font size | Ctrl+'+' / Ctrl+'-' / Ctrl+0 (auto), Ctrl+wheel, the "+" / "−" sidebar buttons |
| Interrupt a program | Ctrl+C |
| Terminate (CraftOS programs) | hold Ctrl+T for a second |
| Reboot / shut down | hold Ctrl+R / Ctrl+S for a second |

Shortcuts work in any keyboard layout (both the key code and the physical key position are checked).

If a program uses the mouse itself (CraftOS, for example), select text with Shift held. Full-screen programs (`edit`,
`more`) use a separate screen: after they exit the console comes back as it was, and the mouse wheel works as the
arrow keys in them.

Pasting into programs that support it (`edit`) arrives as one block (bracketed paste mode, as in Linux terminals),
so the indentation of pasted code does not drift. Programs can also put text into your clipboard themselves (Ctrl+C
in `edit` does this); "Copied N characters" then appears above the border.

## Copying files in from your computer

Files and folders can be dragged into the computer window straight from the file manager (as in CC: Tweaked):
they land in the current folder of the shell (or of the running program), with the permissions of whoever uses the
computer. If such files already exist, the window asks whether to replace them. The limit per drop is
`upload_max_kb` in the [configuration](08-config.md) (16 MB). Programs (WebAssembly modules and `#!` scripts) arrive
executable, so a folder with a program built on a PC runs at once.

## Logging in

At every start (and after `exit`) a `computer-name login:` prompt appears. The user from the setup is in the group
`wheel` and may do anything with `sudo`; the `root` account always exists. Computers made before the setup existed
keep their `user`: while it has no password, they log in by themselves. More about users in
[section 3](03-system.md#users-and-permissions).

## In-game help

`man keys`: every key of the window. `man intro`: overview. The full list of pages is in the
[contents](README.md).
