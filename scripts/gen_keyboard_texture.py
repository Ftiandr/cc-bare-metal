#!/usr/bin/env python3
"""The keyboard's texture (16x16), flat like CC's own items, in the amethyst of the WASM pocket computer.
The item uses all of it; the placed block's model takes its rows 4-12 (top) and edges (sides).

    python3 scripts/gen_keyboard_texture.py   ->  src/main/resources/assets/ccbaremetal/textures/item/keyboard.png
"""
from pathlib import Path

from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/ccbaremetal/textures/item/keyboard.png"

BODY = (157, 87, 212, 255)      # the WASM pocket computer's amethyst
EDGE = (110, 52, 160, 255)      # its outline, darker
LIGHT = (180, 118, 228, 255)    # the top edge, catching the light
KEY = (236, 236, 236, 255)


def main() -> None:
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    top, bottom = 4, 12
    for y in range(top, bottom + 1):
        for x in range(16):
            corner = (x in (0, 15)) and (y in (top, bottom))
            if corner:
                continue
            edge = x in (0, 15) or y in (top, bottom)
            px[x, y] = (LIGHT if y == top and not x in (0, 15) else EDGE) if edge else BODY
    # two rows of keys (1 px, 1 px apart, the second shifted like a real keyboard), then a space bar between keys
    for x in range(2, 14, 2):
        px[x, 6] = KEY
    for x in range(3, 14, 2):
        px[x, 8] = KEY
    for x in (2, 13):
        px[x, 10] = KEY
    for x in range(4, 12):
        px[x, 10] = KEY
    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT)
    print(OUT)


if __name__ == "__main__":
    main()
