#!/usr/bin/env bash
# Build ETPH for macOS on a Mac: arm64-only (Apple silicon, macOS 26+) CLI and
# menu-bar app, ad-hoc signed, as an installer package plus a CLI tarball.
#
#   build-pkg.sh <version> <out dir>
#
# Signing: with a Developer ID (APPLE_DEVELOPER_ID_APP / _INSTALLER in the
# keychain) the binaries get hardened-runtime signatures, the pkg an
# installer signature, and NOTARIZE=1 submits it; without one (today: the
# release-signer secrets carry no Apple identity) everything is ad-hoc
# signed, which Apple Silicon requires to run at all, and Gatekeeper asks the
# user once (docs/DESKTOP.md "macOS: first launch").
set -euo pipefail
VER=$1
OUT=$(mkdir -p "$2" && cd "$2" && pwd)
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
NUM=${VER#v}
[[ "$NUM" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || NUM=$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/desktop/Cargo.toml" | head -1)
cd "$ROOT/desktop"
# Apple silicon + macOS 26 or newer only (owner decision 2026-10-30): one arm64
# slice, no x86_64, no lipo. MIN_MACOS feeds the deployment target, Info.plist's
# LSMinimumSystemVersion and the installer's distribution file.
MIN_MACOS=26.0
T=aarch64-apple-darwin
rustup target add "$T" >/dev/null
MACOSX_DEPLOYMENT_TARGET=$MIN_MACOS cargo build --release --target "$T" -p etphonehome-macos -p etphonehome-tray
S=$(mktemp -d)
trap 'rm -rf "$S"' EXIT
mkdir -p "$S/root/usr/local/bin" "$S/root/Applications/ETPH.app/Contents/MacOS" \
  "$S/root/Applications/ETPH.app/Contents/Resources" "$S/root/Library/LaunchDaemons" "$S/root/Library/LaunchAgents"
install -m 755 "target/$T/release/etphonehome" "$S/root/usr/local/bin/etphonehome"
install -m 755 "target/$T/release/etph-tray" "$S/root/Applications/ETPH.app/Contents/MacOS/etph-tray"
[ "$(lipo -archs "$S/root/usr/local/bin/etphonehome")" = arm64 ]
[ "$(lipo -archs "$S/root/Applications/ETPH.app/Contents/MacOS/etph-tray")" = arm64 ]
install -m 755 "$HERE/etph-uninstall" "$S/root/usr/local/bin/etph-uninstall"
sed "s/@VERSION@/$NUM/g; s/@MIN_MACOS@/$MIN_MACOS/g" "$HERE/Info.plist" >"$S/root/Applications/ETPH.app/Contents/Info.plist"
# Icon from the app's launcher icon.
mkdir "$S/etph.iconset"
for n in 16 32 128 256 512; do
  sips -z $n $n "$ROOT/store/graphics/icon-512.png" --out "$S/etph.iconset/icon_${n}x${n}.png" >/dev/null
  d=$((n * 2))
  if [ $d -le 512 ]; then
    sips -z $d $d "$ROOT/store/graphics/icon-512.png" --out "$S/etph.iconset/icon_${n}x${n}@2x.png" >/dev/null
  fi
done
iconutil -c icns -o "$S/root/Applications/ETPH.app/Contents/Resources/etph.icns" "$S/etph.iconset"
cp "$HERE/solutions.errant.etph.plist" "$S/root/Library/LaunchDaemons/"
cp "$HERE/solutions.errant.etph.menubar.plist" "$S/root/Library/LaunchAgents/"
chmod 644 "$S/root/Library/LaunchDaemons/"*.plist "$S/root/Library/LaunchAgents/"*.plist

APP_ID=${APPLE_DEVELOPER_ID_APP:--}
if [ "$APP_ID" = - ]; then
  codesign --force -s - "$S/root/usr/local/bin/etphonehome"
  codesign --force -s - "$S/root/Applications/ETPH.app"
else
  codesign --force --options runtime --timestamp -s "$APP_ID" "$S/root/usr/local/bin/etphonehome"
  codesign --force --options runtime --timestamp -s "$APP_ID" "$S/root/Applications/ETPH.app"
fi
codesign --verify --verbose "$S/root/usr/local/bin/etphonehome"
codesign --verify --verbose "$S/root/Applications/ETPH.app"

cp -R "$HERE/scripts" "$S/scripts"
chmod 755 "$S/scripts/"*
PKG="$OUT/etph-macos-$VER.pkg"
pkgbuild --root "$S/root" --scripts "$S/scripts" --identifier solutions.errant.etph \
  --version "$NUM" --install-location / "$S/etph-component.pkg"
# Distribution file: the installer itself refuses Intel Macs (hostArchitectures)
# and macOS older than MIN_MACOS (allowed-os-versions + installation-check, which
# gives the clear message).
mkdir -p "$S/pkgs" "$S/resources"
mv "$S/etph-component.pkg" "$S/pkgs/etph-component.pkg"
sed "s/@VERSION@/$NUM/g; s/@MIN_MACOS@/$MIN_MACOS/g" "$HERE/distribution.xml" >"$S/distribution.xml"
sed "s/@MIN_MACOS@/$MIN_MACOS/g" "$HERE/welcome.html" >"$S/resources/welcome.html"
PB=(productbuild --distribution "$S/distribution.xml" --resources "$S/resources" --package-path "$S/pkgs")
if [ -n "${APPLE_DEVELOPER_ID_INSTALLER:-}" ]; then
  "${PB[@]}" --sign "$APPLE_DEVELOPER_ID_INSTALLER" "$PKG"
  if [ "${NOTARIZE:-0}" = 1 ]; then
    xcrun notarytool submit "$PKG" --keychain-profile etph-notary --wait
    xcrun stapler staple "$PKG"
  fi
else
  "${PB[@]}" "$PKG"
fi
pkgutil --expand "$PKG" "$S/expanded"
grep -q 'hostArchitectures="arm64"' "$S/expanded/Distribution"
grep -q "os-version min=\"$MIN_MACOS\"" "$S/expanded/Distribution"
# The CLI alone, for scripts and people who don't want the app.
tar -C "$S/root/usr/local/bin" -czf "$OUT/etphonehome-macos-$VER.tar.gz" etphonehome etph-uninstall
pkgutil --payload-files "$S/pkgs/etph-component.pkg" >"$S/payload.txt"
grep -q 'usr/local/bin/etphonehome' "$S/payload.txt"
(cd "$OUT" && shasum -a 256 "etph-macos-$VER.pkg" "etphonehome-macos-$VER.tar.gz" >"SHA256SUMS-macos-$VER")
ls -la "$OUT"
