#!/usr/bin/env python3
"""Pixel monitor textures, models and blockstates: CC: Tweaked's advanced monitor recoloured from gold to amethyst
(frame and screen untouched), with the same connected textures, so a wall of monitors shows one frame around the
whole screen.

Each block knows which neighbours belong to its screen (block state left/right/up/down, as seen by the viewer).
For every combination the model picks CC's texture whose frame is on exactly the outer edges: CC's monitor_advanced
textures are 0-3 top, 4-7 sides, 16-31 front (screen transparent: filled here with CC's screen colour, the picture
is drawn on top by the block entity renderer) and 32-47 back.

    python3 scripts/gen_monitor_textures.py
"""
import itertools
import json
import os

from cc_textures import amethyst, cc_texture

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ccbaremetal")
TEX = os.path.join(ROOT, "textures", "block")
MODELS = os.path.join(ROOT, "models", "block")
STATES = os.path.join(ROOT, "blockstates")

SCREEN = (0x11, 0x11, 0x11, 255)  # CC's screen colour (monitor_advanced_15, the item texture)
RANGES = {"top": range(0, 4), "side": range(4, 8), "front": range(16, 32), "back": range(32, 48)}


def mask_name(l, r, u, d):
    return f"{int(l)}{int(r)}{int(u)}{int(d)}"


def framed_edges(img):
    """Edges (L R U D in texture space) that carry CC's grey frame."""
    px = img.load()

    def grey(x, y):
        r, g, b, a = px[x, y]
        return a > 0 and max(r, g, b) - min(r, g, b) < 10

    return frozenset(k for k, (x, y) in {"L": (0, 8), "R": (15, 8), "U": (8, 0), "D": (8, 15)}.items() if grey(x, y))


def edges(left, right, up, down):
    """Frame wanted on each texture edge (True = framed)."""
    return frozenset(k for k, on in (("L", left), ("R", right), ("U", up), ("D", down)) if on)


def main():
    for f in os.listdir(TEX):
        if f.startswith("pixel_monitor_"):
            os.remove(os.path.join(TEX, f))
    for f in os.listdir(MODELS):
        if f.startswith("pixel_monitor"):
            os.remove(os.path.join(MODELS, f))

    index = {}
    for kind, numbers in RANGES.items():
        for n in numbers:
            index[(kind, framed_edges(cc_texture(f"monitor_advanced_{n}")))] = n
    used = set()

    def tex(kind, wanted):
        n = index[(kind, wanted)]
        used.add(n)
        return f"ccbaremetal:block/pixel_monitor_{n}"

    combos = list(itertools.product([False, True], repeat=4))
    for l, r, u, d in combos:
        # Model for a monitor facing north (front = north face); the blockstate rotates it.
        # North face: texture left = viewer's left. South (back): mirrored. Top/bottom: texture left is the
        # viewer's right, front and back edges always framed. East/west: only up/down neighbours matter.
        model = {
            "parent": "minecraft:block/block",
            "textures": {
                "particle": tex("side", edges(True, True, True, True)),
                "front": tex("front", edges(not l, not r, not u, not d)),
                "back": tex("back", edges(not r, not l, not u, not d)),
                "top": tex("top", edges(not r, not l, True, True)),
                "side": tex("side", edges(True, True, not u, not d)),
            },
            "elements": [{
                "from": [0, 0, 0], "to": [16, 16, 16],
                "faces": {
                    "north": {"texture": "#front", "cullface": "north"},
                    "south": {"texture": "#back", "cullface": "south"},
                    "east": {"texture": "#side", "cullface": "east"},
                    "west": {"texture": "#side", "cullface": "west"},
                    "up": {"texture": "#top", "cullface": "up"},
                    "down": {"texture": "#top", "cullface": "down"},
                },
            }],
        }
        name = "pixel_monitor" if not (l or r or u or d) else f"pixel_monitor_{mask_name(l, r, u, d)}"
        with open(os.path.join(MODELS, name + ".json"), "w") as f:
            json.dump(model, f, indent=2)
            f.write("\n")

    for n in sorted(used):
        img = amethyst(cc_texture(f"monitor_advanced_{n}"))
        if n in RANGES["front"]:
            px = img.load()
            for y in range(16):
                for x in range(16):
                    if px[x, y][3] == 0:
                        px[x, y] = SCREEN
        img.save(os.path.join(TEX, f"pixel_monitor_{n}.png"))

    variants = {}
    for facing, rot in [("north", 0), ("east", 90), ("south", 180), ("west", 270)]:
        for l, r, u, d in combos:
            key = f"down={str(d).lower()},facing={facing},left={str(l).lower()},right={str(r).lower()},up={str(u).lower()}"
            name = "pixel_monitor" if not (l or r or u or d) else f"pixel_monitor_{mask_name(l, r, u, d)}"
            v = {"model": f"ccbaremetal:block/{name}"}
            if rot:
                v["y"] = rot
            variants[key] = v
    with open(os.path.join(STATES, "pixel_monitor.json"), "w") as f:
        json.dump({"variants": variants}, f, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
