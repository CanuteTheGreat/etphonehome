#!/usr/bin/env bash
# Cross-compile the router client (`etphonehome`, no Bluetooth/D-Bus) as
# static musl binaries for the CPU families OpenWrt runs on, size-optimised
# (profile release-small), into $OUT/<rust-target>/etphonehome.
#
#   build-openwrt-binaries.sh <out-dir> [rust-target ...]
#
# aarch64 / armv7 (hard-float: OpenWrt ARMv7 targets with a VFP unit -- every
# arch name ending in _vfp*/_neon*; ring's assembly needs it) / x86_64 use
# stable Rust. MIPS (mips_24kc, mipsel_24kc:
# ath79, ramips MT7621 -- most GL.iNet and TP-Link routers) is a tier-3 Rust
# target since 1.72: built with nightly + -Zbuild-std. zig links everything
# against its bundled musl (cargo-zigbuild), so no OpenWrt SDK is needed.
set -euo pipefail
OUT=$(realpath -m "${1:?usage: build-openwrt-binaries.sh <out-dir> [targets...]}")
shift
TARGETS=("$@")
[ ${#TARGETS[@]} -gt 0 ] || TARGETS=(x86_64-unknown-linux-musl aarch64-unknown-linux-musl armv7-unknown-linux-musleabihf mipsel-unknown-linux-musl mips-unknown-linux-musl)
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ZIG_VERSION=${ETPH_OWRT_ZIG_VERSION:-0.14.1}
NIGHTLY=${ETPH_NIGHTLY:-nightly-2026-10-01}
HOSTARCH=$(uname -m)
TOOLS=${ZIG_HOME:-$HOME/.cache/etph-zig}
if ! { command -v zig >/dev/null && [ "$(zig version)" = "$ZIG_VERSION" ]; }; then
  # zig >= 0.14 names the MIPS musl ABIs the way cargo-zigbuild asks for them
  # (mips-linux-musleabi); 0.13 can't provide a libc for MIPS.
  if [ ! -x "$TOOLS/zig-$HOSTARCH-linux-$ZIG_VERSION/zig" ]; then
    mkdir -p "$TOOLS"
    for attempt in 1 2 3; do
      curl -fsSL "https://ziglang.org/download/$ZIG_VERSION/zig-$HOSTARCH-linux-$ZIG_VERSION.tar.xz" | tar -C "$TOOLS" -xJ && break
      [ "$attempt" -eq 3 ] && exit 1
      sleep 10
    done
  fi
  export PATH="$TOOLS/zig-$HOSTARCH-linux-$ZIG_VERSION:$PATH"
fi
command -v cargo-zigbuild >/dev/null || cargo install cargo-zigbuild --locked
cd "$ROOT/desktop"
for t in "${TARGETS[@]}"; do
  echo "=== $t"
  case "$t" in
    mips*-unknown-linux-musl)
      rustup toolchain install "$NIGHTLY" --profile minimal --component rust-src >/dev/null
      # Tier 3: rustup ships no self-contained crt objects for MIPS, so zig
      # supplies crt1.o & co. along with its musl.
      RUSTFLAGS="${RUSTFLAGS:-} -C target-feature=+crt-static -C link-self-contained=no" \
        cargo +"$NIGHTLY" zigbuild -Zbuild-std=std,panic_abort --profile release-small \
          -p etphonehome-linux --no-default-features --target "$t"
      ;;
    *)
      rustup target add "$t" >/dev/null
      RUSTFLAGS="${RUSTFLAGS:-} -C target-feature=+crt-static" \
        cargo zigbuild --profile release-small -p etphonehome-linux --no-default-features --target "$t"
      ;;
  esac
  mkdir -p "$OUT/$t"
  cp "target/$t/release-small/etphonehome" "$OUT/$t/etphonehome"
  # Static, or OpenWrt's musl loader would have to match ours exactly.
  if file "$OUT/$t/etphonehome" | grep -q 'dynamically linked'; then
    echo "$t: binary is dynamically linked" >&2
    file "$OUT/$t/etphonehome" >&2
    exit 1
  fi
  printf '%s\t%s bytes\n' "$t" "$(stat -c %s "$OUT/$t/etphonehome")"
done
