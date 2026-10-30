#!/usr/bin/env python3
"""png-luma.py FILE FX FY: print the relative luminance (0..1) of the pixel at fraction (FX, FY).
Stdlib-only PNG reader (8-bit RGB/RGBA, non-interlaced: what `adb exec-out screencap -p` gives)."""
import struct
import sys
import zlib

data = open(sys.argv[1], "rb").read()
assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
pos, idat, w = 8, b"", 0
while pos < len(data):
    n, typ = struct.unpack(">I4s", data[pos:pos + 8])
    body = data[pos + 8:pos + 8 + n]
    if typ == b"IHDR":
        w, h, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
        assert depth == 8 and ctype in (2, 6) and interlace == 0, (depth, ctype, interlace)
    elif typ == b"IDAT":
        idat += body
    pos += 12 + n
bpp = 3 if ctype == 2 else 4
raw = zlib.decompress(idat)
stride = w * bpp
rows, prev = [], bytearray(stride)
for y in range(h):
    f = raw[y * (stride + 1)]
    line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
    for i in range(stride):
        a = line[i - bpp] if i >= bpp else 0
        b = prev[i]
        c = prev[i - bpp] if i >= bpp else 0
        if f == 1:
            line[i] = (line[i] + a) & 255
        elif f == 2:
            line[i] = (line[i] + b) & 255
        elif f == 3:
            line[i] = (line[i] + (a + b) // 2) & 255
        elif f == 4:
            p = a + b - c
            pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
            pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
            line[i] = (line[i] + pr) & 255
    rows.append(line)
    prev = line
x, y = int(w * float(sys.argv[2])), int(h * float(sys.argv[3]))
r, g, b = rows[y][x * bpp:x * bpp + 3]
lin = lambda v: (v / 255 / 12.92) if v / 255 <= 0.03928 else ((v / 255 + 0.055) / 1.055) ** 2.4
print(f"{0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b):.4f}")
