#!/usr/bin/env python3
"""WASM computer textures: CC: Tweaked's advanced computer, recoloured from gold to amethyst (the tier above CC's
grey and gold computers). Frame and screen are CC's own; the blinking cursor overlay is CC's computer_blink.
The WASM pocket computer item and the WASM turtle likewise: CC's advanced pocket computer and turtle in amethyst.

    python3 scripts/gen_computer_textures.py      (writes src/main/resources/assets/ccbaremetal/textures/block/)
"""
import os

from cc_textures import amethyst, cc_file, cc_texture

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ccbaremetal", "textures", "block")
ITEMS = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "ccbaremetal", "textures", "item")


def main():
    os.makedirs(OUT, exist_ok=True)
    for part in ("front", "side", "top"):
        amethyst(cc_texture(f"computer_advanced_{part}")).save(os.path.join(OUT, f"wasm_computer_{part}.png"))
    cc_texture("computer_blink").save(os.path.join(OUT, "wasm_computer_blink.png"))
    with open(os.path.join(OUT, "wasm_computer_blink.png.mcmeta"), "wb") as f:
        f.write(cc_file("assets/computercraft/textures/block/computer_blink.png.mcmeta"))

    # Turtle: CC's advanced turtle, every face in amethyst.
    for part in ("front", "back", "backpack", "bottom", "left", "right", "top"):
        amethyst(cc_texture(f"turtle_advanced_{part}")).save(os.path.join(OUT, f"wasm_turtle_{part}.png"))

    # Pocket computer: CC's advanced pocket computer body in amethyst; frame, screen, cursor and light are CC's.
    os.makedirs(ITEMS, exist_ok=True)
    amethyst(cc_texture("pocket_computer_advanced", "item")).save(os.path.join(ITEMS, "wasm_pocket_computer.png"))
    for part in ("frame", "on", "light", "blink"):
        cc_texture(f"pocket_computer_{part}", "item").save(os.path.join(ITEMS, f"wasm_pocket_computer_{part}.png"))
    with open(os.path.join(ITEMS, "wasm_pocket_computer_blink.png.mcmeta"), "wb") as f:
        f.write(cc_file("assets/computercraft/textures/item/pocket_computer_blink.png.mcmeta"))


if __name__ == "__main__":
    main()
