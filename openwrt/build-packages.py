#!/usr/bin/env python3
"""Build OpenWrt packages for Errant Solutions ETPH (phone as WAN).

    build-packages.py --version 0.8.0 --bins <dir> --out <dir> [--apk-tool apk]

<bins>/<rust-target>/etphonehome come from build-openwrt-binaries.sh. Output:
  etph_<ver>-r1_<arch>.ipk   opkg: OpenWrt <= 24.10 and GL.iNet firmware
  etph-<ver>-r1_<arch>.apk   apk v3: OpenWrt 25.x (needs `apk mkpkg`)
  luci-app-etph_<ver>-r1_all.ipk / luci-app-etph-<ver>-r1_all.apk
  sizes.tsv                  binary / package size per arch

OpenWrt only installs a package whose architecture is listed in its own
arch list, so each binary is packaged once per OpenWrt arch name of that
CPU family (the content is identical; the binaries are static musl).
"""
import argparse
import gzip
import hashlib
import io
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))

# OpenWrt arch name -> Rust target whose static binary runs there.
ARCHES = {
    "x86_64": "x86_64-unknown-linux-musl",
    "aarch64_cortex-a53": "aarch64-unknown-linux-musl",   # GL.iNet ipq60xx/mt7981, RPi 3/4 (64-bit), many
    "aarch64_cortex-a72": "aarch64-unknown-linux-musl",   # RPi 4, mvebu armada 8k
    "aarch64_cortex-a76": "aarch64-unknown-linux-musl",   # RPi 5
    "aarch64_generic": "aarch64-unknown-linux-musl",      # armsr/armv8, rockchip
    # ARMv7 with a VFP unit (hard-float binary; FPU-less cores such as
    # bcm53xx's plain arm_cortex-a9 are not supported).
    "arm_cortex-a7_neon-vfpv4": "armv7-unknown-linux-musleabihf",  # ipq40xx (GL-B1300, AP1300), mt7623, sunxi
    "arm_cortex-a7_vfpv4": "armv7-unknown-linux-musleabihf",
    "arm_cortex-a9_vfpv3-d16": "armv7-unknown-linux-musleabihf",   # mvebu (WRT1900AC..)
    "arm_cortex-a9_neon": "armv7-unknown-linux-musleabihf",        # imx6
    "arm_cortex-a15_neon-vfpv4": "armv7-unknown-linux-musleabihf", # ipq806x
    "arm_cortex-a5_vfpv4": "armv7-unknown-linux-musleabihf",       # at91
    "arm_cortex-a8_vfpv3": "armv7-unknown-linux-musleabihf",       # omap, sunxi a10
    "mips_24kc": "mips-unknown-linux-musl",       # ath79 (GL-AR750S, AR300M, TP-Link)
    "mipsel_24kc": "mipsel-unknown-linux-musl",   # ramips mt7621/mt76x8 (GL-MT300N, MT1300)
    "mipsel_74kc": "mipsel-unknown-linux-musl",   # bcm47xx, lantiq
}

DESC = ("Errant Solutions ETPH: use a phone running the ETPH app as this router's "
        "internet (WAN) over USB (built-in ADB host, no adb package) or Wi-Fi (TLS). Adds the netifd protocol "
        "'etph'.")
URL = "https://errant.solutions/etphonehome/"
MAINT = "Errant Solutions <support@errant.solutions>"

POSTINST = r"""#!/bin/sh
[ -n "$IPKG_INSTROOT" ] && exit 0
[ -n "$APK_INSTROOT" ] && exit 0
mkdir -p /etc/etph /var/run/etph
chmod 700 /etc/etph
/etc/init.d/etph enable 2>/dev/null
/etc/init.d/etph restart 2>/dev/null
# netifd reads protocol handlers only when it starts: a new `proto etph`
# needs one `/etc/init.d/network restart` (LuCI's setup does it for you).
if ! ubus call network get_proto_handlers 2>/dev/null | grep -q '"etph"'; then
	echo "etph: run '/etc/init.d/network restart' once (or set the uplink up in LuCI > Network > Phone uplink) so netifd loads the new 'etph' protocol"
fi
exit 0
"""

PRERM = r"""#!/bin/sh
[ -n "$IPKG_INSTROOT" ] && exit 0
[ -n "$APK_INSTROOT" ] && exit 0
/etc/init.d/etph stop 2>/dev/null
/etc/init.d/etph disable 2>/dev/null
exit 0
"""

LUCI_POSTINST = r"""#!/bin/sh
[ -n "$IPKG_INSTROOT" ] && exit 0
[ -n "$APK_INSTROOT" ] && exit 0
rm -rf /tmp/luci-indexcache* /tmp/luci-modulecache 2>/dev/null
/etc/init.d/rpcd reload 2>/dev/null
exit 0
"""


def tar_gz(members, path=None):
    """members: list of (arcname, bytes or None for dir, mode)."""
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0) as gz:
        with tarfile.open(fileobj=gz, mode="w", format=tarfile.GNU_FORMAT) as t:
            for name, data, mode in members:
                ti = tarfile.TarInfo(name)
                ti.mtime = 0
                ti.uid = ti.gid = 0
                ti.uname = ti.gname = "root"
                if data is None:
                    ti.type = tarfile.DIRTYPE
                    ti.mode = mode or 0o755
                    t.addfile(ti)
                else:
                    ti.size = len(data)
                    ti.mode = mode
                    t.addfile(ti, io.BytesIO(data))
    out = buf.getvalue()
    if path:
        with open(path, "wb") as f:
            f.write(out)
    return out


def tree_members(root):
    """Files under root as tar members ./path, dirs first, sorted."""
    members = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        rel = os.path.relpath(dirpath, root)
        if rel != ".":
            members.append(("./" + rel + "/", None, 0o755))
        for fn in sorted(filenames):
            p = os.path.join(dirpath, fn)
            r = os.path.relpath(p, root)
            members.append(("./" + r, open(p, "rb").read(), os.stat(p).st_mode & 0o777))
    return members


def stage(pkg_files, binary=None):
    d = tempfile.mkdtemp(prefix="etph-pkg-")
    shutil.copytree(pkg_files, d, dirs_exist_ok=True)
    if binary:
        os.makedirs(os.path.join(d, "usr/sbin"), exist_ok=True)
        shutil.copy2(binary, os.path.join(d, "usr/sbin/etphonehome"))
        os.chmod(os.path.join(d, "usr/sbin/etphonehome"), 0o755)
    return d


def installed_size(root):
    return sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(root) for f in fs)


def build_ipk(root, name, version, arch, depends, desc, out, postinst, prerm=None, conffiles=()):
    control = (
        f"Package: {name}\nVersion: {version}\nDepends: {depends}\nSource: {URL}\n"
        f"License: MIT\nSection: net\nURL: {URL}\nMaintainer: {MAINT}\n"
        f"Architecture: {arch}\nInstalled-Size: {installed_size(root)}\n"
        f"Description: {desc}\n"
    )
    cm = [("./", None, 0o755), ("./control", control.encode(), 0o644),
          ("./postinst", postinst.encode(), 0o755)]
    if prerm:
        cm.append(("./prerm", prerm.encode(), 0o755))
    if conffiles:
        cm.append(("./conffiles", "".join(c + "\n" for c in conffiles).encode(), 0o644))
    control_tgz = tar_gz(cm)
    data_tgz = tar_gz([("./", None, 0o755)] + tree_members(root))
    tar_gz([("./debian-binary", b"2.0\n", 0o644), ("./data.tar.gz", data_tgz, 0o644),
            ("./control.tar.gz", control_tgz, 0o644)], out)


def build_apk(apk_tool, root, name, version, arch, depends, desc, out, postinst, prerm=None):
    with tempfile.TemporaryDirectory() as tmp:
        cmd = [apk_tool, "mkpkg",
               "--info", f"name:{name}", "--info", f"version:{version}",
               "--info", f"arch:{arch}", "--info", f"description:{desc}",
               "--info", "license:MIT", "--info", f"url:{URL}",
               "--info", f"origin:{name}", "--info", f"maintainer:{MAINT}",
               "--files", root, "--output", out]
        if depends:
            cmd += ["--info", "depends:" + depends]
        pi = os.path.join(tmp, "post-install")
        open(pi, "w").write(postinst)
        cmd += ["--script", "post-install:" + pi, "--script", "post-upgrade:" + pi]
        if prerm:
            pr = os.path.join(tmp, "pre-deinstall")
            open(pr, "w").write(prerm)
            cmd += ["--script", "pre-deinstall:" + pr]
        subprocess.run(cmd, check=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True)
    ap.add_argument("--bins", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--apk-tool", default=os.environ.get("APK_TOOL"))
    ap.add_argument("--arch", action="append", help="only these OpenWrt arches")
    a = ap.parse_args()
    ver = a.version.lstrip("v")
    pkgver = ver + "-r1"
    os.makedirs(a.out, exist_ok=True)
    rows = ["arch\trust_target\tbinary_bytes\tipk_bytes\tapk_bytes"]
    arches = a.arch or list(ARCHES)
    for arch in arches:
        target = ARCHES[arch]
        binary = os.path.join(a.bins, target, "etphonehome")
        if not os.path.exists(binary):
            print(f"skip {arch}: no {binary}", file=sys.stderr)
            continue
        root = stage(os.path.join(HERE, "etph/files"), binary)
        ipk = os.path.join(a.out, f"etph_{pkgver}_{arch}.ipk")
        build_ipk(root, "etph", pkgver, arch, "libc, kmod-tun", DESC, ipk, POSTINST, PRERM,
                  conffiles=["/etc/config/etph"])
        apk_size = ""
        if a.apk_tool:
            apk = os.path.join(a.out, f"etph-{pkgver}_{arch}.apk")
            build_apk(a.apk_tool, root, "etph", pkgver, arch, "kmod-tun", DESC, apk, POSTINST, PRERM)
            apk_size = str(os.path.getsize(apk))
        rows.append(f"{arch}\t{target}\t{os.path.getsize(binary)}\t{os.path.getsize(ipk)}\t{apk_size}")
        shutil.rmtree(root)
    luci = stage(os.path.join(HERE, "luci-app-etph/files"))
    ldesc = "LuCI page for the ETPH phone uplink: status, pairing, setup (Network > Phone uplink)"
    build_ipk(luci, "luci-app-etph", pkgver, "all", "etph, luci-base", ldesc,
              os.path.join(a.out, f"luci-app-etph_{pkgver}_all.ipk"), LUCI_POSTINST)
    if a.apk_tool:
        build_apk(a.apk_tool, luci, "luci-app-etph", pkgver, "noarch", "etph luci-base", ldesc,
                  os.path.join(a.out, f"luci-app-etph-{pkgver}_noarch.apk"), LUCI_POSTINST)
    shutil.rmtree(luci)
    open(os.path.join(a.out, "sizes.tsv"), "w").write("\n".join(rows) + "\n")
    with open(os.path.join(a.out, "SHA256SUMS"), "w") as f:
        for fn in sorted(os.listdir(a.out)):
            if fn.endswith((".ipk", ".apk")):
                f.write(hashlib.sha256(open(os.path.join(a.out, fn), "rb").read()).hexdigest() + "  " + fn + "\n")
    print("\n".join(rows))


if __name__ == "__main__":
    main()
