#!/usr/bin/env python3
"""The mod's logo: pixel art (40x40, drawn from scratch) scaled to 400x400 for mod sites, and 128x128 for the mod
list (logoFile in neoforge.mods.toml).

    python3 scripts/gen_logo.py   ->  docs/images/logo.png, src/main/resources/ccbaremetal_logo.png
"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent

BG = (31, 24, 43, 255)            # deep purple night
BG_GLOW = (45, 33, 66, 255)
BODY = (157, 87, 212, 255)        # the WASM computer's amethyst
BODY_DARK = (110, 52, 160, 255)
BODY_LIGHT = (190, 133, 236, 255)
SCREEN = (14, 12, 20, 255)
TEXT = (240, 240, 240, 255)
GREEN = (127, 204, 25, 255)       # CC's lime, the prompt
SHARD = (220, 180, 255, 255)

N = 40


def rect(px, x0, y0, x1, y1, colour):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[x, y] = colour


def draw() -> Image.Image:
    img = Image.new("RGBA", (N, N), BG)
    px = img.load()
    # a soft glow behind the computer
    for y in range(N):
        for x in range(N):
            if (x - 20) ** 2 + (y - 19) ** 2 < 16 ** 2:
                px[x, y] = BG_GLOW
    # the computer: body with a light top-left edge and a dark bottom-right edge
    x0, y0, x1, y1 = 7, 6, 32, 31
    rect(px, x0, y0, x1, y1, BODY)
    rect(px, x0, y0, x1, y0, BODY_LIGHT)
    rect(px, x0, y0, x0, y1, BODY_LIGHT)
    rect(px, x0, y1, x1, y1, BODY_DARK)
    rect(px, x1, y0, x1, y1, BODY_DARK)
    # the screen
    rect(px, 10, 9, 29, 24, SCREEN)
    # "$" in lime (5x7) and "_" cursor in white
    dollar = [
        "..#..",
        ".####",
        "#.#..",
        ".###.",
        "..#.#",
        "####.",
        "..#..",
    ]
    for dy, row in enumerate(dollar):
        for dx, c in enumerate(row):
            if c == "#":
                px[12 + dx, 11 + dy] = GREEN
    rect(px, 19, 17, 23, 17, TEXT)  # the cursor
    # the power light and a vent on the body
    rect(px, 28, 27, 29, 28, GREEN)
    rect(px, 11, 27, 18, 27, BODY_DARK)
    rect(px, 11, 29, 18, 29, BODY_DARK)
    # the stand
    rect(px, 15, 32, 24, 33, BODY_DARK)
    rect(px, 12, 34, 27, 35, BODY)
    rect(px, 12, 35, 27, 35, BODY_DARK)
    # amethyst clusters at the computer's feet: tall crystals, light on the left face, dark on the right
    def crystal(cx, base, height):
        for i in range(height):
            y = base - i
            half = 0 if i >= height - 1 else 1
            for x in range(cx - half, cx + half + 1):
                px[x, y] = SHARD if x < cx else (BODY_LIGHT if x == cx else BODY)
    for cx, h in ((3, 6), (5, 9), (7, 4)):
        crystal(cx, 35, h)
    for cx, h in ((32, 4), (34, 9), (36, 6)):
        crystal(cx, 35, h)
    return img


def main() -> None:
    art = draw()
    logo = art.resize((400, 400), Image.NEAREST)
    out = ROOT / "docs/images/logo.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    logo.save(out)
    small = art.resize((120, 120), Image.NEAREST)
    padded = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    padded.paste(small, (4, 4))
    padded.save(ROOT / "src/main/resources/ccbaremetal_logo.png")
    print(out)


if __name__ == "__main__":
    main()
