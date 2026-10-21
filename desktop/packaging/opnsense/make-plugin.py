#!/usr/bin/env python3
"""Build the OPNsense plugin package os-etph (pkg(8) format).

    make-plugin.py <version> <out dir>

Same layout as a plugin from opnsense/plugins: src/ here is /usr/local on
the firewall (MVC model/controllers/view under opnsense/mvc, configd actions,
templates, the plugins.inc.d hook). Depends on the etphonehome package
(client + rc.d service). Install both with `pkg add`.
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

POST_INSTALL = """for f in /tmp/opnsense_menu_cache.xml /tmp/opnsense_acl_cache.json; do rm -f "$f"; done
if [ -x /usr/local/opnsense/scripts/firmware/register.php ]; then
  /usr/local/opnsense/scripts/firmware/register.php install os-etph >/dev/null 2>&1 || true
fi
/usr/local/sbin/configctl template reload OPNsense/Etph >/dev/null 2>&1 || true
service configd restart >/dev/null 2>&1 || true
/usr/local/etc/rc.configure_plugins POST_INSTALL >/dev/null 2>&1 || true
echo "ETPH plugin installed: Interfaces > Phone uplink (ETPH)"
"""

POST_DEINSTALL = """for f in /tmp/opnsense_menu_cache.xml /tmp/opnsense_acl_cache.json; do rm -f "$f"; done
service configd restart >/dev/null 2>&1 || true
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
            files["/usr/local/" + rel] = (open(p, "rb").read(), 0o755 if os.access(p, os.X_OK) else 0o644)
    compact = {
        "name": "os-etph",
        "origin": "opnsense/os-etph",
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
        "desc": "OPNsense plugin for ETPH: Interfaces > Phone uplink (ETPH) runs the "
                "ETPH client so an Android phone (ETPH app, ETPH Pro) becomes the "
                "assignable interface etph0, usable as a gateway in gateway groups "
                "for WAN failover.",
        "categories": ["net"],
        "deps": {"etphonehome": {"origin": "net/etphonehome", "version": version}},
    }
    manifest = dict(compact)
    manifest["files"] = {p: "1$" + hashlib.sha256(d).hexdigest() for p, (d, _) in files.items()}
    manifest["scripts"] = {"post-install": POST_INSTALL, "post-deinstall": POST_DEINSTALL}
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, f"os-etph-{version}.pkg")
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
