"""CC: Tweaked's own block textures, recoloured from gold (advanced tier) to amethyst (the WASM tier).

Only the gold body is recoloured: the grey frame and the black screen stay exactly as in CC. Every pixel keeps its
own shade, so the stone-like grain of CC's texture survives; only the hue moves from gold to amethyst.

The textures are read from the CC: Tweaked jar in the Gradle cache (the one this project builds against).
"""
import colorsys
import glob
import io
import os
import zipfile

from PIL import Image

CC_VERSION = "1.120.0"


def cc_jar():
    pattern = os.path.expanduser(
        f"~/.gradle/caches/modules-2/files-2.1/cc.tweaked/cc-tweaked-1.21.1-forge/{CC_VERSION}/*/cc-tweaked-1.21.1-forge-{CC_VERSION}.jar")
    jars = glob.glob(pattern)
    if not jars:
        raise SystemExit(f"CC: Tweaked {CC_VERSION} jar not found in the Gradle cache: run ./gradlew compileJava first")
    return jars[0]


_zip = None


def cc_texture(name, folder="block"):
    """A texture from assets/computercraft/textures/FOLDER (block, gui, gui/sprites/buttons...), as RGBA."""
    return Image.open(io.BytesIO(cc_file(f"assets/computercraft/textures/{folder}/{name}.png"))).convert("RGBA")


def cc_file(path):
    global _zip
    if _zip is None:
        _zip = zipfile.ZipFile(cc_jar())
    with _zip.open(path) as f:
        return f.read()


# Gold sits around hue 57 with lightness ~0.65; amethyst is a purple around hue 272, a bit darker.
GOLD_HUE = 57 / 360
AMETHYST_HUE = 272 / 360


def is_body(rgba):
    """Gold body pixel (CC's frame and screen are pure grey)."""
    r, g, b, a = rgba
    if a == 0:
        return False
    _, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    return s > 0.25 and l > 0.3


def amethyst(img):
    """Recolour the gold body to amethyst, pixel by pixel (keeps every pixel's relative hue and shade)."""
    out = img.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if not is_body((r, g, b, a)):
                continue
            h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
            h = (AMETHYST_HUE + (h - GOLD_HUE) * 0.6) % 1.0
            l = l * 0.84
            s = min(1.0, s * 0.85)
            nr, ng, nb = colorsys.hls_to_rgb(h, l, s)
            px[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255), a)
    return out
