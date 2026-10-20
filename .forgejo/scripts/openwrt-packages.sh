#!/usr/bin/env bash
# Build every OpenWrt package (all arches, .ipk + .apk, LuCI app) into $2,
# plus the installer, SHA256SUMS, sizes.tsv and index.txt (file list for the
# VM test). Used by openwrt.yml (every push) and release.yml (tags).
#
#   openwrt-packages.sh <version> <out-dir> [<bin-dir>]
set -euo pipefail
VER=${1:?version}
OUT=$(realpath -m "${2:?out dir}")
BINS=$(realpath -m "${3:-$PWD/openwrt-bin}")
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
if [ ! -x "$BINS/mips-unknown-linux-musl/etphonehome" ]; then
  bash "$ROOT/openwrt/build-openwrt-binaries.sh" "$BINS"
fi
# apk v3 `mkpkg` for OpenWrt 25.x packages: Alpine's static apk-tools
# (OpenWrt's own apk is built without mkpkg).
APKDIR=${APK_TOOLS_DIR:-$HOME/.cache/etph-apk-tools}
if [ ! -x "$APKDIR/sbin/apk.static" ]; then
  mkdir -p "$APKDIR"
  MIRROR=https://dl-cdn.alpinelinux.org/alpine/edge/main/$(uname -m)
  curl -fsSL --retry 3 "$MIRROR/APKINDEX.tar.gz" | tar -xzO APKINDEX > "$APKDIR/APKINDEX"
  V=$(awk '/^P:apk-tools-static$/{f=1} f&&/^V:/{print substr($0,3); exit}' "$APKDIR/APKINDEX")
  curl -fsSL --retry 3 "$MIRROR/apk-tools-static-$V.apk" | tar -xz -C "$APKDIR" 2>/dev/null || true
fi
"$APKDIR/sbin/apk.static" --version
rm -rf "$OUT"
mkdir -p "$OUT"
python3 "$ROOT/openwrt/build-packages.py" --version "$VER" --bins "$BINS" --out "$OUT" --apk-tool "$APKDIR/sbin/apk.static"
cp "$ROOT/openwrt/etph-openwrt-install.sh" "$OUT/"
(cd "$OUT" && ls > index.txt)
cat "$OUT/sizes.tsv"
