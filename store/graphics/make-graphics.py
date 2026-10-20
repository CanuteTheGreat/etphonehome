#!/usr/bin/env python3
"""Play store graphics for "Errant Solutions ETPH", generated from the app's own resources.

  icon-512.png            512x512 32-bit PNG: the adaptive launcher icon (background colour +
                          foreground vector from android/app/src/main/res), cropped to the 72dp
                          area a launcher shows, as Play wants it (full square, Play rounds it).
  feature-graphic.png     1024x500 24-bit PNG (no alpha): brand dark background, the icon, the
                          app name and the tagline.

Phone screenshots come from the real app on the CI emulator (.forgejo/scripts/store-screenshots.sh,
workflow store-screenshots.yml) and are copied into phone/ by hand from that run's artifact.

Needs rsvg-convert (librsvg2-bin) and Pillow. Run from the repo root:
    python3 store/graphics/make-graphics.py
"""
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
OUT = os.path.dirname(os.path.abspath(__file__))
A = "{http://schemas.android.com/apk/res/android}"

# errant.solutions site palette
BG, PANEL, LINE, TEXT, MUTED, ACCENT = "#0b0d12", "#12151d", "#232734", "#e6e8ee", "#8b93a3", "#6ee7b7"
NAME = "Errant Solutions ETPH"
TAGLINE = "Share your phone's internet with your computer or router over USB or Wi-Fi"
FONT = "DejaVu Sans, Liberation Sans, sans-serif"


def launcher_background():
    colors = ET.parse(os.path.join(RES, "values", "colors.xml")).getroot()
    for c in colors.iter("color"):
        if c.get("name") == "ic_launcher_background":
            v = c.text.strip()
            return "#" + v[-6:]
    raise SystemExit("ic_launcher_background colour not found")


def vector_to_svg_group(path):
    """android <vector> -> (viewport size, SVG <g> markup). Supports group scale/translate and
    path translateX/Y, fillColor (what the launcher foreground uses)."""
    root = ET.parse(path).getroot()
    vw = float(root.get(A + "viewportWidth"))

    def conv(node):
        out = []
        for ch in node:
            if ch.tag == "group":
                t = []
                tx, ty = ch.get(A + "translateX"), ch.get(A + "translateY")
                if tx or ty:
                    t.append(f"translate({tx or 0} {ty or 0})")
                sx, sy = ch.get(A + "scaleX"), ch.get(A + "scaleY")
                if sx or sy:
                    t.append(f"scale({sx or 1} {sy or 1})")
                out.append(f'<g transform="{" ".join(t)}">{conv(ch)}</g>')
            elif ch.tag == "path":
                fill = ch.get(A + "fillColor", "#000000")
                if len(fill) == 9:  # #AARRGGBB
                    fill = "#" + fill[3:]
                tx, ty = ch.get(A + "translateX"), ch.get(A + "translateY")
                tr = f' transform="translate({tx or 0} {ty or 0})"' if (tx or ty) else ""
                out.append(f'<path d="{ch.get(A + "pathData")}" fill="{fill}"{tr}/>')
        return "".join(out)

    return vw, conv(root)


def icon_svg(size):
    vw, fg = vector_to_svg_group(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"))
    # A launcher shows the middle 72 of the 108dp adaptive canvas.
    inset = vw * 18 / 108
    view = vw - 2 * inset
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
            f'viewBox="{inset} {inset} {view} {view}">'
            f'<rect x="0" y="0" width="{vw}" height="{vw}" fill="{launcher_background()}"/>{fg}</svg>')


def feature_svg():
    vw, fg = vector_to_svg_group(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"))
    inset = vw * 18 / 108
    view = vw - 2 * inset
    icon = 300
    s = icon / view
    ix, iy = 88, 100
    # Tagline in two lines, split near the middle on a word boundary.
    words = TAGLINE.split()
    best = min(range(1, len(words)), key=lambda i: abs(len(" ".join(words[:i])) - len(" ".join(words[i:]))))
    l1, l2 = " ".join(words[:best]), " ".join(words[best:])
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">
  <defs>
    <radialGradient id="glow" cx="0.22" cy="0.5" r="0.6">
      <stop offset="0" stop-color="#1b3a5c" stop-opacity="0.9"/>
      <stop offset="1" stop-color="{BG}" stop-opacity="0"/>
    </radialGradient>
    <clipPath id="r"><rect x="{ix}" y="{iy}" width="{icon}" height="{icon}" rx="66"/></clipPath>
  </defs>
  <rect width="1024" height="500" fill="{BG}"/>
  <rect width="1024" height="500" fill="url(#glow)"/>
  <rect x="{ix - 2}" y="{iy + 10}" width="{icon + 4}" height="{icon}" rx="66" fill="#000" opacity="0.35"/>
  <g clip-path="url(#r)">
    <g transform="translate({ix} {iy}) scale({s}) translate({-inset} {-inset})">
      <rect width="{vw}" height="{vw}" fill="{launcher_background()}"/>{fg}
    </g>
  </g>
  <text x="452" y="196" font-family="{FONT}" font-size="25" font-weight="bold" letter-spacing="4" fill="{ACCENT}">ERRANT SOLUTIONS</text>
  <text x="448" y="282" font-family="{FONT}" font-size="96" font-weight="bold" fill="{TEXT}">ETPH</text>
  <rect x="452" y="310" width="72" height="5" rx="2.5" fill="{ACCENT}"/>
  <text font-family="{FONT}" font-size="27" fill="#c7ccd6">
    <tspan x="452" y="362">{l1.replace("'", "&#8217;")}</tspan>
    <tspan x="452" y="400">{l2.replace("'", "&#8217;")}</tspan>
  </text>
</svg>'''


def render(svg, out, w, h, alpha):
    with tempfile.TemporaryDirectory() as d:
        src, png = os.path.join(d, "in.svg"), os.path.join(d, "out.png")
        open(src, "w").write(svg)
        subprocess.run(["rsvg-convert", "-w", str(w), "-h", str(h), "-o", png, src], check=True)
        im = Image.open(png).convert("RGBA")
        if not alpha:
            flat = Image.new("RGB", im.size, BG)
            flat.paste(im, mask=im.split()[3])
            im = flat
        im.save(out, optimize=True)
    print(f"wrote {os.path.relpath(out, ROOT)} {Image.open(out).size} {Image.open(out).mode}")


def main():
    assert len(NAME) <= 30
    render(icon_svg(512), os.path.join(OUT, "icon-512.png"), 512, 512, alpha=True)
    render(feature_svg(), os.path.join(OUT, "feature-graphic.png"), 1024, 500, alpha=False)


if __name__ == "__main__":
    sys.exit(main())
