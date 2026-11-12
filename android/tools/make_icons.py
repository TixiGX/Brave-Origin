#!/usr/bin/env python3
"""Rigenera le icone Android di Brave Origin.

Sorgente: product_logo_256.png estratto dal pacchetto ufficiale
brave-origin-<ver>-linux-amd64.zip (contiene il logo Origin originale:
leone bianco su fondo scuro trasparente agli angoli).

Uso:  python3 tools/make_icons.py /percorso/product_logo_256.png
"""
import os
import sys

from PIL import Image, ImageDraw

SRC = sys.argv[1] if len(sys.argv) > 1 else "product_logo_256.png"
RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "res")
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def lion_mask(img):
    """Isola il leone bianco dal fondo scuro usando la luminanza come alpha."""
    w, h = img.size
    out = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    src, dst = img.load(), out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = src[x, y]
            if a == 0:
                continue
            lum = (r * 299 + g * 587 + b * 114) // 1000
            alpha = max(0, min(255, int((lum - 90) * 255 / 165)))
            if alpha > 8:
                dst[x, y] = (255, 255, 255, alpha)
    return out.crop(out.getbbox())


def main():
    icon = Image.open(SRC).convert("RGBA")
    lion = lion_mask(icon)

    for name, factor in DENSITIES.items():
        directory = os.path.join(RES, "mipmap-" + name)
        os.makedirs(directory, exist_ok=True)

        size = int(48 * factor)
        icon.resize((size, size), Image.LANCZOS).save(os.path.join(directory, "ic_launcher.png"))

        round_icon = icon.resize((size, size), Image.LANCZOS)
        mask = Image.new("L", (size * 4, size * 4), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
        round_icon.putalpha(mask.resize((size, size), Image.LANCZOS))
        round_icon.save(os.path.join(directory, "ic_launcher_round.png"))

        canvas_size = int(108 * factor)
        canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
        target = int(canvas_size * 0.58)
        scale = target / max(lion.size)
        resized = lion.resize(
            (max(1, int(lion.size[0] * scale)), max(1, int(lion.size[1] * scale))), Image.LANCZOS
        )
        canvas.paste(
            resized, ((canvas_size - resized.size[0]) // 2, (canvas_size - resized.size[1]) // 2), resized
        )
        canvas.save(os.path.join(directory, "ic_launcher_foreground.png"))
        canvas.save(os.path.join(directory, "ic_launcher_monochrome.png"))


if __name__ == "__main__":
    main()
