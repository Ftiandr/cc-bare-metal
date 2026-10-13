# 7. Unicode for CC: Tweaked computers

CC: Bare Metal adds Cyrillic and other scripts to CC: Tweaked's **ordinary** computers, monitors, pocket computers,
printers and printouts. CC is not forked or changed on disk: everything is done from the CC: Bare Metal jar with
small hooks (mixins). Remove the mod and CC is fully original again.

## What works

- **Display** of UTF-8 with CC: Bare Metal's 6×9 pixel font (where CC has its own glyph, such as `é`, `ñ`, arrows,
  `♥`, CC's glyph is used): the computer window, monitors, pocket computers, printouts.
- **Typing** characters that are not in CC's charset: they reach the computer as a `paste` event with a UTF-8 string
  (`read()`, `shell` and `edit` understand it).
- **Pasting** from the clipboard encodes such characters as UTF-8 instead of dropping them.
- **Labels and titles**: `os.setComputerLabel`, disk labels and printed page titles keep UTF-8 (CC replaced some
  bytes with `?`), and item names, the printout tooltip and its window title show the text. Lua reads back exactly
  the bytes it wrote.
- **The printer** stores characters on the page: a line of a sheet holds 25 letters in any language.
- **The cursor, mouse clicks and monitor touches** point where they should.

## Why nothing breaks

CC stores text as bytes: one screen cell is one byte, and every program, the ROM (`window`, `read`, `edit`) and
addons like CC: Graphics count positions in bytes. That does not change: `#`, `string.len`, the Lua engine and the
ROM files are as before. Only when drawing is a UTF-8 byte sequence shown as one character. So that columns, frames
and buttons do not shift, text is compacted within a segment (up to a change of background colour or two spaces in
a row), and the space freed is left empty at the end of the segment: everything after it stays where the program
put it.

## CraftOS and Lua on a WASM computer

On a WASM computer, pocket computer and turtle, `craftos` and `lua` follow the same rule: Lua writes bytes to the
terminal (as in CC), and the screen assembles letters from them. Typed Cyrillic reaches Lua as `paste`. In addition,
`read()` here moves by letters rather than bytes: arrows, Backspace and Delete work on a whole letter. This change
only applies inside the WASM machine: CC's ROM and ordinary CC computers are not changed.

## Limitations

- On computer and monitor screens a line holds the screen width **in bytes**, and a Cyrillic letter is 2 bytes:
  long text wraps at about half the line (that is how programs that count width in bytes behave). Printers do not
  have this limitation.
- On ordinary CC computers, in `read()` and `edit` a Cyrillic letter takes two Backspace presses (a character like
  `Ð` shows in between). On WASM computers `read()` deletes the whole letter; `edit` from CC's ROM still works by
  bytes.
- Programs that only listen to the `char` event will not see Cyrillic: it arrives as `paste`.
- The font has no capital Greek letters and no CJK characters (they are drawn as a box).
- If CC's config selects the `tbo` monitor renderer instead of the default one, Unicode is not drawn on monitors.

## Configuration

Client config `ccbaremetal-client.toml`, section `cc_tweaked`, `unicode = true`: turn it off to get CC's behaviour
back completely.
