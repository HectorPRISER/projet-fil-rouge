#!/usr/bin/env python3
"""Annote une capture de flamegraph (rectangle + texte) pour le rapport
d'audit. Usage:
    python3 tools/annotate_flamegraph.py in.png out.png "x,y,w,h,texte" ...
"""
import sys
from PIL import Image, ImageDraw, ImageFont

try:
    FONT = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 16)
except OSError:
    FONT = ImageFont.load_default()


def main():
    in_path, out_path = sys.argv[1], sys.argv[2]
    annotations = sys.argv[3:]

    img = Image.open(in_path).convert("RGB")
    draw = ImageDraw.Draw(img)

    for spec in annotations:
        x, y, w, h, text = spec.split(",", 4)
        x, y, w, h = int(x), int(y), int(w), int(h)
        draw.rectangle([x, y, x + w, y + h], outline=(255, 60, 60), width=3)
        text_y = max(0, y - 22)
        bbox = draw.textbbox((x, text_y), text, font=FONT)
        draw.rectangle(bbox, fill=(255, 60, 60))
        draw.text((x, text_y), text, fill=(255, 255, 255), font=FONT)

    img.save(out_path)
    print(f"-> {out_path}")


if __name__ == "__main__":
    main()
