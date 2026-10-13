#!/usr/bin/env python3
"""The computer window in CC: Tweaked's advanced computer style: CC's border and sidebar with the gold recoloured to
amethyst (like the blocks), CC's own power and terminate buttons, and buttons CC has no icon for (reboot, bigger
and smaller font) drawn the same way: a dark outline, see-through inside.

    python3 scripts/gen_gui_textures.py      (writes src/main/resources/assets/ccbaremetal/textures/gui/sprites/)
"""
import os

from PIL import Image

from cc_textures import amethyst, cc_file, cc_texture

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ccbaremetal", "textures", "gui", "sprites")

OUTLINE = (0x23, 0x23, 0x23, 255)        # CC's button outline
OUTLINE_HOVER = (0x14, 0x14, 0x14, 255)  # and when hovered

# 12x12, '#' = outline colour, '.' = transparent. Same rounded frame as CC's terminate button.
ICONS = {
    "reboot": [
        ".##########.",
        "############",
        "##........##",
        "##..###.#.##",
        "##.#...##.##",
        "##.#..###.##",
        "##.#......##",
        "##.#....#.##",
        "##..####..##",
        "##........##",
        "############",
        ".##########.",
    ],
    "zoom_in": [
        ".##########.",
        "############",
        "##........##",
        "##...##...##",
        "##...##...##",
        "##.######.##",
        "##.######.##",
        "##...##...##",
        "##...##...##",
        "##........##",
        "############",
        ".##########.",
    ],
    "modules": [
        ".##########.",
        "############",
        "##........##",
        "##.##..##.##",
        "##.##..##.##",
        "##........##",
        "##........##",
        "##.##..##.##",
        "##.##..##.##",
        "##........##",
        "############",
        ".##########.",
    ],
    "zoom_out": [
        ".##########.",
        "############",
        "##........##",
        "##........##",
        "##........##",
        "##.######.##",
        "##.######.##",
        "##........##",
        "##........##",
        "##........##",
        "############",
        ".##########.",
    ],
}


def icon(rows, colour):
    img = Image.new("RGBA", (12, 12), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == "#":
                img.putpixel((x, y), colour)
    return img


def panel(border):
    """A small CC-style panel (nine-slice, 4 px edges) cut from the recoloured border: its corners and edges, and
    the body in the middle."""
    img = Image.new("RGBA", (12, 12))
    w = border.width
    pieces = [((0, 0), (0, 0)), ((4, 0), (16, 0)), ((8, 0), (w - 4, 0)),
              ((0, 4), (0, 16)), ((4, 4), (5, 5)), ((8, 4), (w - 4, 16)),
              ((0, 8), (0, w - 4)), ((4, 8), (16, w - 4)), ((8, 8), (w - 4, w - 4))]
    for (dx, dy), (sx, sy) in pieces:
        img.paste(border.crop((sx, sy, sx + 4, sy + 4)), (dx, dy))
    return img


def slot(inside):
    """An 18x18 inventory slot: sunken (dark top/left, light bottom/right) as in vanilla, in amethyst tones."""
    img = Image.new("RGBA", (18, 18), inside)
    for i in range(18):
        img.putpixel((i, 0), (0x2B, 0x22, 0x3A, 255))
        img.putpixel((0, i), (0x2B, 0x22, 0x3A, 255))
        img.putpixel((i, 17), (0xE2, 0xD2, 0xFF, 255))
        img.putpixel((17, i), (0xE2, 0xD2, 0xFF, 255))
    img.putpixel((0, 17), (0x8B, 0x74, 0xB8, 255))
    img.putpixel((17, 0), (0x8B, 0x74, 0xB8, 255))
    return img


def nine_slice(border, size):
    return ('{\n  "gui": {\n    "scaling": {\n      "type": "nine_slice",\n      "border": %d,\n      "width": %d,\n'
            '      "height": %d\n    }\n  }\n}\n' % (border, size, size))


def main():
    os.makedirs(os.path.join(OUT, "buttons"), exist_ok=True)
    for name in ("border", "sidebar"):
        amethyst(cc_texture(f"{name}_advanced", "gui")).save(os.path.join(OUT, f"{name}.png"))
        with open(os.path.join(OUT, f"{name}.png.mcmeta"), "wb") as f:
            f.write(cc_file(f"assets/computercraft/textures/gui/{name}_advanced.png.mcmeta"))
    border = amethyst(cc_texture("border_advanced", "gui"))
    panel(border).save(os.path.join(OUT, "panel.png"))
    with open(os.path.join(OUT, "panel.png.mcmeta"), "w") as f:
        f.write(nine_slice(4, 12))
    slot((0x4A, 0x38, 0x6E, 255)).save(os.path.join(OUT, "slot.png"))
    slot((0x5E, 0x46, 0x8E, 255)).save(os.path.join(OUT, "module_slot.png"))
    for name in ("turned_on", "turned_off", "terminate"):
        for suffix in ("", "_hover"):
            cc_texture(name + suffix, "gui/sprites/buttons").save(os.path.join(OUT, "buttons", name + suffix + ".png"))
    for name, rows in ICONS.items():
        icon(rows, OUTLINE).save(os.path.join(OUT, "buttons", name + ".png"))
        icon(rows, OUTLINE_HOVER).save(os.path.join(OUT, "buttons", name + "_hover.png"))


if __name__ == "__main__":
    main()
