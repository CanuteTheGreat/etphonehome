#!/usr/bin/env bash
# Cross-compile the desktop client for FreeBSD on a Linux runner: a sysroot
# from FreeBSD's own base.txz (headers, libc, libusb), clang as the C
# compiler (ring, libusb bindings) and lld as the linker.
#
#   source .forgejo/scripts/freebsd-cross-env.sh [arch]   # amd64 (default) | arm64
#   cargo build --release --target "$FREEBSD_TARGET" -p etphonehome-bsd
#
# The release is built against the oldest supported FreeBSD release, so the
# binary runs on it and everything newer (FreeBSD keeps libc ABI-compatible
# within and across major versions with compat libs; pfSense CE 2.7/2.8 and
# OPNsense 24/25 are FreeBSD 14).
# (sourced: no set -e here, it would leak into the caller)
FBSD_ARCH=${1:-amd64}
FBSD_REL=${FBSD_REL:-14.4-RELEASE}
case "$FBSD_ARCH" in
  amd64) FREEBSD_TARGET=x86_64-unknown-freebsd; TRIPLE=x86_64-unknown-freebsd14 ;;
  arm64) FREEBSD_TARGET=aarch64-unknown-freebsd; TRIPLE=aarch64-unknown-freebsd14 ;;
  *) echo "unknown arch $FBSD_ARCH" >&2; return 1 ;;
esac
# Per arch, always: FREEBSD_SYSROOT is exported below, so a second `source ... arm64` in the same
# shell must not pick up the amd64 sysroot (v0.9.0 release: aarch64 objects linked against amd64 Scrt1.o).
SYSROOT=${FREEBSD_SYSROOT_BASE:-$HOME/.cache/freebsd-sysroot}/$FBSD_REL-$FBSD_ARCH
if [ ! -f "$SYSROOT/.ok" ]; then
  mkdir -p "$SYSROOT"
  url="https://download.freebsd.org/releases/$FBSD_ARCH/$FBSD_REL/base.txz"
  [ "$FBSD_ARCH" = arm64 ] && url="https://download.freebsd.org/releases/arm64/aarch64/$FBSD_REL/base.txz"
  curl -fsI "$url" >/dev/null 2>&1 || url=${url/download.freebsd.org\/releases/ftp-archive.freebsd.org\/pub\/FreeBSD-Archive\/old-releases}
  for i in 1 2 3; do
    curl -fsSL --retry 3 "$url" -o "$SYSROOT/base.txz" && break
    sleep 10
  done
  tar -xJf "$SYSROOT/base.txz" -C "$SYSROOT" ./lib ./usr/lib ./usr/include ./usr/libdata/pkgconfig 2>/dev/null \
    || tar -xJf "$SYSROOT/base.txz" -C "$SYSROOT" lib usr/lib usr/include usr/libdata/pkgconfig
  rm -f "$SYSROOT/base.txz"
  touch "$SYSROOT/.ok"
fi
command -v clang >/dev/null && command -v ld.lld >/dev/null || {
  echo "freebsd-cross-env: needs clang and lld (apt-get install clang lld)" >&2
  return 1
}
WRAP=$SYSROOT/.bin
mkdir -p "$WRAP"
cat >"$WRAP/cc" <<EOF
#!/bin/sh
exec clang --target=$TRIPLE --sysroot=$SYSROOT -fuse-ld=lld "\$@"
EOF
chmod +x "$WRAP/cc"
tu=$(echo "$FREEBSD_TARGET" | tr 'a-z-' 'A-Z_')
tl=$(echo "$FREEBSD_TARGET" | tr '-' '_')
export "CC_${tl}=$WRAP/cc" "AR_${tl}=llvm-ar" "CARGO_TARGET_${tu}_LINKER=$WRAP/cc"
command -v llvm-ar >/dev/null || export "AR_${tl}=ar"
export PKG_CONFIG_ALLOW_CROSS=1
export "PKG_CONFIG_SYSROOT_DIR_${tl}=$SYSROOT" "PKG_CONFIG_LIBDIR_${tl}=$SYSROOT/usr/libdata/pkgconfig"
export FREEBSD_TARGET FREEBSD_SYSROOT=$SYSROOT
rustup target add "$FREEBSD_TARGET" >/dev/null 2>&1 || true
echo "FreeBSD cross env: $FREEBSD_TARGET, sysroot $SYSROOT"
