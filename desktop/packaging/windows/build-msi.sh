#!/usr/bin/env bash
# Build the Windows installer (MSI) and the portable zip on Linux.
#
#   build-msi.sh <bin dir with etphonehome.exe + etph-tray.exe> <version> <out dir>
#
# Bundles Wintun 0.14.1 (wintun.net, signed by WireGuard LLC; checked by
# SHA-256 here) and registers the ETPH service. Needs: wixl (msitools),
# curl, unzip, zip, python3 + Pillow-free icon writer below.
set -euo pipefail
BIN=$(readlink -f "$1")
VER=${2#v}
OUT=$(mkdir -p "$3" && readlink -f "$3")
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
# MSI versions are numeric: ci-1234abcd builds use the crate version.
if ! [[ "$VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  VER=$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/desktop/Cargo.toml" | head -1)
fi
TAG=${2}
WINTUN_URL=https://www.wintun.net/builds/wintun-0.14.1.zip
WINTUN_SHA=07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51
S=$(mktemp -d)
trap 'rm -rf "$S"' EXIT
curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 --connect-timeout 20 --max-time 180 "$WINTUN_URL" -o "$S/wintun.zip"
echo "$WINTUN_SHA  $S/wintun.zip" | sha256sum -c -
unzip -q "$S/wintun.zip" -d "$S"
cp "$S/wintun/bin/amd64/wintun.dll" "$S/wintun.dll"
cp "$S/wintun/LICENSE.txt" "$S/wintun-LICENSE.txt"
for f in etphonehome.exe etph-tray.exe; do
  if command -v x86_64-w64-mingw32-strip >/dev/null; then
    x86_64-w64-mingw32-strip -o "$S/$f" "$BIN/$f"
  else
    cp "$BIN/$f" "$S/$f"
  fi
done
cp "$ROOT/LICENSE" "$S/LICENSE.txt"
sed 's/$/\r/' "$HERE/README.txt" >"$S/README.txt"
python3 "$HERE/../make-icon.py" ico "$S/etph.ico"
for f in etphonehome.exe etph-tray.exe wintun.dll; do
  [ "$(head -c 2 "$S/$f")" = MZ ] || { echo "$f is not a PE file" >&2; exit 1; }
done
wixl -a x64 -D Version="$VER" -D SourceDir="$S" -o "$OUT/etph-windows-$TAG.msi" "$HERE/etph.wxs"
# Portable zip: same files, for people who don't want an installer.
(cd "$S" && zip -q "$OUT/etphonehome-windows-$TAG.zip" etphonehome.exe etph-tray.exe wintun.dll wintun-LICENSE.txt LICENSE.txt README.txt)
# Sanity: the MSI really carries the binaries, the DLL and the service.
if command -v msiinfo >/dev/null; then
  msiinfo export "$OUT/etph-windows-$TAG.msi" File | grep -q wintun.dll
  msiinfo export "$OUT/etph-windows-$TAG.msi" ServiceInstall | grep -q 'service --windows-service'
fi
ls -la "$OUT"
