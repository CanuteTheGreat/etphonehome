#!/usr/bin/env python3
"""Fail if anything we SHIP names the owner's private git host or its software.

Users must only ever see errant.solutions (downloads: /download/etphonehome/<tag>/<file>) or the public
GitHub repository. The words 'canutethegreat.com' and 'forgejo' must not appear (case-insensitive) in:

  source mode (default, every push): every git-tracked file that ships or documents the product, i.e.
      everything except CI definitions (.forgejo/, .github/, ci/), which users never receive.
  artifact mode (release.yml, before the release is created): python3 check-no-internal-hosts.py DIR...
      every file under DIR, looking inside tar/zip/gzip/xz/zstd/ar containers (.pkg .ipk .apk .tar.gz .zip
      .msi-as-bytes) and in UTF-16 text too, so a script inside a package or a string in a binary is found.
"""
import gzip
import io
import lzma
import os
import re
import subprocess
import sys
import tarfile
import zipfile

NEEDLES = (b"canutethegreat.com", b"forgejo")
# CI definitions are not shipped.
SKIP_PREFIXES = (".forgejo/", ".github/", "ci/")
MAX_DEPTH = 4


def hits_in(data: bytes):
    low = data.translate(bytes.maketrans(bytes(range(65, 91)), bytes(range(97, 123))))
    out = set()
    for n in NEEDLES:
        if n in low or n.decode().encode("utf-16-le") in low:
            out.add(n.decode())
    return sorted(out)


def unpack(name: str, data: bytes):
    """Yield (name, bytes) for the members of a container, or nothing if it isn't one."""
    try:
        if data[:2] == b"\x1f\x8b":
            inner = gzip.decompress(data)
            yield name + "!gz", inner
            return
        if data[:6] == b"\xfd7zXZ\x00":
            yield name + "!xz", lzma.decompress(data)
            return
        if data[:4] == b"\x28\xb5\x2f\xfd":
            import subprocess as sp
            r = sp.run(["zstd", "-dc"], input=data, capture_output=True)
            if r.returncode == 0:
                yield name + "!zst", r.stdout
            return
        if data[:4] == b"PK\x03\x04":
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                for i in z.infolist():
                    if not i.is_dir():
                        yield f"{name}!{i.filename}", z.read(i)
            return
        if data[:8] == b"!<arch>\n":  # ipk
            pos = 8
            while pos + 60 <= len(data):
                hdr = data[pos:pos + 60]
                size = int(hdr[48:58].strip() or 0)
                yield f"{name}!{hdr[:16].decode(errors='replace').strip()}", data[pos + 60:pos + 60 + size]
                pos += 60 + size + (size & 1)
            return
        if len(data) > 262 and data[257:262] == b"ustar":
            with tarfile.open(fileobj=io.BytesIO(data)) as t:
                for m in t:
                    if m.isfile():
                        fh = t.extractfile(m)
                        if fh:
                            yield f"{name}!{m.name}", fh.read()
    except Exception as e:  # unreadable container: still scanned as raw bytes by the caller
        print(f"note: could not unpack {name}: {e}", file=sys.stderr)


def scan(name: str, data: bytes, depth: int = 0):
    found = [(name, h) for h in hits_in(data)]
    if depth < MAX_DEPTH:
        for n, d in unpack(name, data):
            found += scan(n, d, depth + 1)
    return found


def main():
    found = []
    if len(sys.argv) > 1:
        n = 0
        for root in sys.argv[1:]:
            paths = [root] if os.path.isfile(root) else [os.path.join(d, f) for d, _, fs in os.walk(root) for f in fs]
            for p in paths:
                n += 1
                found += scan(p, open(p, "rb").read())
        what = f"{n} release files"
    else:
        files = subprocess.check_output(["git", "ls-files", "-z"], text=True).split("\0")
        n = 0
        for f in files:
            if not f or f.startswith(SKIP_PREFIXES) or not os.path.isfile(f):
                continue
            n += 1
            found += scan(f, open(f, "rb").read())
        what = f"{n} tracked files"
    for name, h in found:
        print(f"FAIL: {name} mentions '{h}'")
    if found:
        print("Shipped files must point at https://errant.solutions/download/etphonehome/<tag>/<file> or the public GitHub repo.")
        sys.exit(1)
    print(f"ok: none of {what} mentions {', '.join(n.decode() for n in NEEDLES)}")


if __name__ == "__main__":
    main()
