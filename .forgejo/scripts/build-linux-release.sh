#!/usr/bin/env bash
# Build a Linux release binary that runs on every distro we claim, not only
# on ones as new as the CI image: link against glibc $LINUX_GLIBC (default
# 2.31: Debian 11+, Ubuntu 20.04+, every current Fedora/Arch/openSUSE) with
# cargo-zigbuild, libdbus vendored + static (desktop/common/Cargo.toml).
# Building natively on the CI image (Ubuntu 24.04) required GLIBC_2.39, so
# the released client did not even start on Debian 12 or Ubuntu 22.04
# (found by the nightly distro matrix).
#
# The result is copied to desktop/target/release/<bin>, where package.sh and
# the e2e jobs expect it. Fails if the binary needs a newer glibc anyway.
#
# Usage: build-linux-release.sh [cargo package] [binary name]
set -euo pipefail
PKG=${1:-etphonehome-linux}
BIN=${2:-etphonehome}
GLIBC=${LINUX_GLIBC:-2.31}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
case "$(uname -m)" in
  x86_64 | amd64) ARCH=x86_64 ;;
  aarch64 | arm64) ARCH=aarch64 ;;
  *) echo "unsupported arch $(uname -m)" >&2; exit 1 ;;
esac
TARGET=$ARCH-unknown-linux-gnu
ZIG_VERSION=${ZIG_VERSION:-0.13.0}
TOOLS=${ZIG_HOME:-$HOME/.cache/etph-zig}
if ! command -v zig >/dev/null; then
  if [ ! -x "$TOOLS/zig-linux-$ARCH-$ZIG_VERSION/zig" ]; then
    mkdir -p "$TOOLS"
    for attempt in 1 2 3; do
      curl -fsSL "https://ziglang.org/download/$ZIG_VERSION/zig-linux-$ARCH-$ZIG_VERSION.tar.xz" | tar -C "$TOOLS" -xJ && break
      [ "$attempt" -eq 3 ] && exit 1
      sleep 10
    done
  fi
  export PATH="$TOOLS/zig-linux-$ARCH-$ZIG_VERSION:$PATH"
fi
command -v cargo-zigbuild >/dev/null || cargo install cargo-zigbuild --locked
rustup target add "$TARGET" >/dev/null 2>&1 || true
cd "$ROOT/desktop"
cargo zigbuild --release -p "$PKG" --target "$TARGET.$GLIBC"
mkdir -p target/release
cp "target/$TARGET/release/$BIN" "target/release/$BIN"
NEED=$(objdump -T "target/release/$BIN" | grep -o 'GLIBC_[0-9.]*' | sed 's/GLIBC_//' | sort -uV | tail -1)
echo "$BIN: newest glibc symbol needed: $NEED (target $GLIBC)"
if [ "$(printf '%s\n%s\n' "$NEED" "$GLIBC" | sort -V | tail -1)" != "$GLIBC" ]; then
  echo "binary needs glibc $NEED > $GLIBC" >&2
  exit 1
fi
if objdump -p "target/release/$BIN" | grep -q 'NEEDED.*libdbus'; then
  echo "binary still links libdbus dynamically" >&2
  exit 1
fi
objdump -p "target/release/$BIN" | grep NEEDED
