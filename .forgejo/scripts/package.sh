#!/usr/bin/env bash
# Package a built artifact into dist/ exactly as a tagged release ships it.
#
# Used by release.yml (tags) AND by the per-push CI build jobs, so the
# packaging steps are exercised on every push instead of only when someone
# tags a release. (They had silently broken: release.yml stripped/tarred
# `etphonehome-linux` / `etphonehome-windows.exe`, but the binaries are named
# `etphonehome` / `etphonehome.exe`, and it expected `app-release-unsigned.apk`
# although release builds are signed.)
#
# Usage: package.sh <linux|windows|gui|android|freebsd> <version>
set -euo pipefail
# Never pipe a listing into `grep -q`: grep exits at the first match, the
# lister gets SIGPIPE, and with pipefail the step fails with exit 141
# (happened on the Android job for 5de116b). has() reads the whole input.
has() { grep "$@" >/dev/null; }
KIND=$1
VER=$2
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DIST="$ROOT/dist"
mkdir -p "$DIST"
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

case "$KIND" in
  linux)
    BIN="$ROOT/desktop/target/release/etphonehome"
    strip -o "$STAGE/etphonehome" "$BIN"
    tar -C "$STAGE" -czf "$DIST/etphonehome-linux-$VER.tar.gz" etphonehome
    # Smoke-test the packaged binary itself.
    mkdir "$STAGE/x" && tar -C "$STAGE/x" -xzf "$DIST/etphonehome-linux-$VER.tar.gz"
    "$STAGE/x/etphonehome" --version
    "$STAGE/x/etphonehome" --help >/dev/null
    ;;
  windows)
    # MSI (service, tray app, Wintun, PATH) + the portable zip with the
    # same files; wintun.dll is what makes the TUN adapter work at all.
    REL="$ROOT/desktop/target/x86_64-pc-windows-gnu/release"
    bash "$ROOT/desktop/packaging/windows/build-msi.sh" "$REL" "$VER" "$DIST"
    for f in etphonehome.exe etph-tray.exe wintun.dll; do
      unzip -l "$DIST/etphonehome-windows-$VER.zip" | has "$f"
    done
    [ -s "$DIST/etph-windows-$VER.msi" ]
    ;;
  gui)
    strip -o "$STAGE/etphonehome-gui" "$ROOT/desktop/target/release/etphonehome-gui"
    tar -C "$STAGE" -czf "$DIST/etphonehome-gui-$VER.tar.gz" etphonehome-gui
    tar -tzf "$DIST/etphonehome-gui-$VER.tar.gz" | has -x etphonehome-gui
    ;;
  android)
    APK=$(ls "$ROOT"/android/app/build/outputs/apk/release/app-release*.apk | head -1)
    cp "$APK" "$DIST/etphonehome-$VER.apk"
    # An APK is a zip with a manifest and dex code.
    unzip -l "$DIST/etphonehome-$VER.apk" | has AndroidManifest.xml
    unzip -l "$DIST/etphonehome-$VER.apk" | has 'classes.*\.dex'
    ;;
  freebsd)
    # FreeBSD pkg (also pfSense CE / OPNsense: `pkg add`) + plain tarball,
    # amd64 and aarch64; built by .forgejo/scripts/freebsd-cross-env.sh.
    for a in amd64:x86_64-unknown-freebsd aarch64:aarch64-unknown-freebsd; do
      arch=${a%%:*}; triple=${a#*:}
      BIN="$ROOT/desktop/target/$triple/release/etphonehome"
      [ -f "$BIN" ] || continue
      STRIP=$(command -v llvm-strip || ls /usr/bin/llvm-strip-* 2>/dev/null | head -1)
      ${STRIP:-false} -o "$STAGE/etphonehome" "$BIN" 2>/dev/null || cp "$BIN" "$STAGE/etphonehome"
      python3 "$ROOT/desktop/packaging/freebsd/make-pkg.py" "$STAGE/etphonehome" "$VER" "$DIST" "$arch"
      cp "$ROOT/desktop/packaging/freebsd/etphonehome.rc" "$STAGE/etphonehome.rc"
      tar -C "$STAGE" -czf "$DIST/etphonehome-freebsd-$arch-$VER.tar.gz" etphonehome etphonehome.rc
      file "$STAGE/etphonehome" | has 'FreeBSD'
    done
    ls "$DIST"/etphonehome-*-freebsd14-amd64.pkg "$DIST"/etphonehome-*-freebsd15-amd64.pkg >/dev/null
    # Firewall integrations: the OPNsense plugin and the pfSense package.
    python3 "$ROOT/desktop/packaging/opnsense/make-plugin.py" "$VER" "$DIST"
    python3 "$ROOT/desktop/packaging/pfsense/make-package.py" "$VER" "$DIST"
    ;;
  *)
    echo "unknown kind: $KIND" >&2
    exit 2
    ;;
esac
ls -la "$DIST"
