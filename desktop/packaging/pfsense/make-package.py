#!/usr/bin/env python3
"""Build the pfSense package pfSense-pkg-etph (pkg(8) format).

    make-package.py <version> <out dir>

Same layout as a package from pfsense/FreeBSD-ports (net/pfSense-pkg-*):
src/ here is the firewall's filesystem -- the package GUI definition and
its PHP under /usr/local/pkg, the status page under /usr/local/www, the
privilege file and the info.xml that /etc/rc.packages registers. Depends on
the etphonehome package (the client, FreeBSD 14 build for pfSense CE 2.7.x).
Install both with `pkg add`.
"""
import hashlib
import io
import json
import os
import sys
import tarfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "src")
NAME = "pfSense-pkg-etph"

# What ports' pkg-install.in does for every pfSense package: register the
# package (menu, service, privileges) in config.xml and run its install hook.
POST_INSTALL = f"""/usr/local/bin/php -f /etc/rc.packages {NAME} POST-INSTALL
echo "ETPH package installed: Interfaces > Phone uplink (ETPH)"
"""
PRE_DEINSTALL = f"""/usr/local/bin/php -f /etc/rc.packages {NAME} DEINSTALL
"""


def main():
    version, out = sys.argv[1].lstrip("v"), sys.argv[2]
    if not version[:1].isdigit():
        version = "0.0.0." + version.replace("-", ".")
    files = {}
    for root, _, names in os.walk(SRC):
        for n in sorted(names):
            p = os.path.join(root, n)
            rel = os.path.relpath(p, SRC)
            data = open(p, "rb").read().replace(b"%%PKGVERSION%%", version.encode())
            files["/" + rel] = (data, 0o755 if os.access(p, os.X_OK) else 0o644)
    compact = {
        "name": NAME,
        "origin": f"net/{NAME}",
        "version": version,
        "comment": "ETPH: an Android phone as an uplink / WAN failover",
        "maintainer": "support@errant.solutions",
        "www": "https://errant.solutions/etphonehome/",
        "abi": "FreeBSD:*",
        "arch": "freebsd:*",
        "prefix": "/usr/local",
        "flatsize": sum(len(d) for d, _ in files.values()),
        "licenselogic": "single",
        "licenses": ["MIT"],
        "desc": "pfSense package for ETPH: Interfaces > Phone uplink (ETPH) runs the "
                "ETPH client as a service so an Android phone (ETPH app, ETPH Pro) "
                "becomes the assignable interface etph0, usable as a gateway in "
                "gateway groups for WAN failover.",
        "categories": ["net"],
        "deps": {"etphonehome": {"origin": "net/etphonehome", "version": version}},
    }
    manifest = dict(compact)
    manifest["files"] = {p: "1$" + hashlib.sha256(d).hexdigest() for p, (d, _) in files.items()}
    manifest["scripts"] = {"post-install": POST_INSTALL, "pre-deinstall": PRE_DEINSTALL}
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, f"{NAME}-{version}.pkg")
    now = int(time.time())
    with tarfile.open(path, "w:xz", format=tarfile.PAX_FORMAT) as tar:
        for name, data, mode in [
            ("+COMPACT_MANIFEST", json.dumps(compact, indent=1).encode(), 0o644),
            ("+MANIFEST", json.dumps(manifest, indent=1).encode(), 0o644),
        ] + [(p, d, m) for p, (d, m) in sorted(files.items())]:
            ti = tarfile.TarInfo(name)
            ti.size, ti.mode, ti.mtime = len(data), mode, now
            ti.uname = ti.gname = "root"
            tar.addfile(ti, io.BytesIO(data))
    print(path)


if __name__ == "__main__":
    main()
