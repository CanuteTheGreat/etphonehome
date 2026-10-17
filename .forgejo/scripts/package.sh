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
# Usage: package.sh <linux|windows|gui|android> <version>
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
    EXE="$ROOT/desktop/target/x86_64-pc-windows-gnu/release/etphonehome.exe"
    cp "$EXE" "$STAGE/etphonehome.exe"
    (cd "$STAGE" && zip -q "$DIST/etphonehome-windows-$VER.zip" etphonehome.exe)
    unzip -l "$DIST/etphonehome-windows-$VER.zip" | has 'etphonehome.exe'
    # Must be a real PE executable.
    [ "$(head -c 2 "$STAGE/etphonehome.exe")" = MZ ]
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
  *)
    echo "unknown kind: $KIND" >&2
    exit 2
    ;;
esac
ls -la "$DIST"
