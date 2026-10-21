#!/usr/bin/env python3
"""Build the FreeBSD package (pkg(8) format) on any OS.

    make-pkg.py <etphonehome binary> <version> <out dir> [amd64|aarch64]

A pkg is a compressed tar whose first members are +COMPACT_MANIFEST and
+MANIFEST (JSON is valid UCL), followed by the files at their absolute
paths; +MANIFEST lists each file's SHA-256 ("1$<hex>"), which `pkg check -s`
verifies. Works on FreeBSD 14 and later, and on the FreeBSD-based firewalls
(pfSense CE, OPNsense) with `pkg add`.
"""
import hashlib
import io
import json
import os
import sys
import tarfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))

COMMENT = "Internet from your Android phone over USB or Wi-Fi (ETPH)"
DESC = """ETPH shares an Android phone's internet with this computer or router
through the ETPH app on the phone: over USB (built-in ADB host through
libusb, no Android SDK needed) or Wi-Fi with cert-pinned TLS 1.3. On a
FreeBSD-based firewall (pfSense, OPNsense) the phone can be a WAN for
failover. WWW: https://errant.solutions/etphonehome/"""

POST_INSTALL = """mkdir -p /usr/local/etc/etphonehome
chmod 700 /usr/local/etc/etphonehome
echo "ETPH installed: sysrc etphonehome_enable=YES && service etphonehome start"
echo "Then: etphonehome ctl usb   (or save a Wi-Fi phone, see etphonehome --help)"
"""


def main():
    binary, version, out = sys.argv[1], sys.argv[2].lstrip("v"), sys.argv[3]
    arch = sys.argv[4] if len(sys.argv) > 4 else "amd64"
    if not version[:1].isdigit():
        version = "0.0.0." + version.replace("-", ".")
    pkg_arch = {"amd64": ("amd64", "x86:64"), "aarch64": ("aarch64", "aarch64:64")}[arch]
    files = {
        "/usr/local/bin/etphonehome": (open(binary, "rb").read(), 0o755),
        "/usr/local/etc/rc.d/etphonehome": (open(os.path.join(HERE, "etphonehome.rc"), "rb").read(), 0o755),
        "/usr/local/share/licenses/etphonehome/LICENSE": (open(os.path.join(ROOT, "LICENSE"), "rb").read(), 0o644),
    }
    flatsize = sum(len(d) for d, _ in files.values())
    compact = {
        "name": "etphonehome",
        "origin": "net/etphonehome",
        "version": version,
        "comment": COMMENT,
        "maintainer": "support@errant.solutions",
        "www": "https://errant.solutions/etphonehome/",
        "abi": f"FreeBSD:14:{pkg_arch[0]}",
        "arch": f"freebsd:14:{pkg_arch[1]}",
        "prefix": "/usr/local",
        "flatsize": flatsize,
        "licenselogic": "single",
        "licenses": ["MIT"],
        "desc": DESC,
        "categories": ["net"],
    }
    manifest = dict(compact)
    manifest["files"] = {p: "1$" + hashlib.sha256(d).hexdigest() for p, (d, _) in files.items()}
    manifest["directories"] = {"/usr/local/share/licenses/etphonehome/": "y"}
    manifest["scripts"] = {"post-install": POST_INSTALL}
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, f"etphonehome-{version}-freebsd-{arch}.pkg")
    now = int(time.time())
    with tarfile.open(path, "w:xz", format=tarfile.PAX_FORMAT) as tar:
        for name, data, mode in [
            ("+COMPACT_MANIFEST", json.dumps(compact, indent=1).encode(), 0o644),
            ("+MANIFEST", json.dumps(manifest, indent=1).encode(), 0o644),
        ] + [(p, d, m) for p, (d, m) in files.items()]:
            ti = tarfile.TarInfo(name)
            ti.size = len(data)
            ti.mode = mode
            ti.mtime = now
            ti.uname = ti.gname = "root"
            tar.addfile(ti, io.BytesIO(data))
    print(path)


if __name__ == "__main__":
    main()
