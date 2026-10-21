#!/usr/bin/env python3
"""App icons for the desktop installers from the app's own launcher icon
(store/graphics/icon-512.png, rendered by store/graphics/make-graphics.py).

    make-icon.py ico OUT.ico      Windows (16..256 px)
    make-icon.py png SIZE OUT.png any size (macOS iconset, FreeBSD)
"""
import os
import sys

from PIL import Image

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "store", "graphics", "icon-512.png")


def main():
    img = Image.open(SRC).convert("RGBA")
    if sys.argv[1] == "ico":
        img.save(sys.argv[2], sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    elif sys.argv[1] == "png":
        n = int(sys.argv[2])
        img.resize((n, n), Image.LANCZOS).save(sys.argv[3])
    else:
        sys.exit("usage: make-icon.py ico OUT | png SIZE OUT")


if __name__ == "__main__":
    main()
