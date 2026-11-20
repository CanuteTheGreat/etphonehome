#!/bin/bash
# Cross-builds the root-mode helper (desktop/tun-pipe) for the four Android ABIs and drops it
# into android/app/src/main/jniLibs/<abi>/libtun_pipe.so, so the APK carries it. Android only
# extracts (and lets su exec) files named lib*.so from the APK's native library directory, hence
# the name; build.gradle sets useLegacyPackaging so they are really extracted to disk.
# Needs the Android SDK from setup-android-sdk.sh (installs the NDK itself) and a Rust toolchain
# (installs rustup if missing).
set -euo pipefail

NDK_VERSION="27.2.12479018"
API=26 # the app's minSdk
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-$HOME/android-sdk}"

if ! command -v cargo >/dev/null 2>&1; then
  curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain stable >/dev/null
  export PATH="$HOME/.cargo/bin:$PATH"
fi

if [ ! -d "$SDK_ROOT/ndk/$NDK_VERSION" ]; then
  yes | "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_ROOT" "ndk;$NDK_VERSION" >/dev/null
fi
BIN="$SDK_ROOT/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "$BIN/aarch64-linux-android$API-clang" ] || { echo "NDK clang not found in $BIN" >&2; exit 1; }

# abi dir | rust target | clang triple
for row in "arm64-v8a|aarch64-linux-android|aarch64-linux-android" \
           "armeabi-v7a|armv7-linux-androideabi|armv7a-linux-androideabi" \
           "x86_64|x86_64-linux-android|x86_64-linux-android" \
           "x86|i686-linux-android|i686-linux-android"; do
  IFS='|' read -r abi target triple <<<"$row"
  rustup target add "$target" >/dev/null
  up="$(echo "$target" | tr 'a-z-' 'A-Z_')"
  export "CARGO_TARGET_${up}_LINKER=$BIN/${triple}${API}-clang"
  (cd "$ROOT/desktop" && cargo build --release -p etphonehome-tun-pipe --bin tun-pipe --target "$target" \
    --config 'profile.release.opt-level="z"' --config 'profile.release.strip=true')
  out="$ROOT/android/app/src/main/jniLibs/$abi"
  mkdir -p "$out"
  cp "$ROOT/desktop/target/$target/release/tun-pipe" "$out/libtun_pipe.so"
  echo "tun-pipe $abi: $(stat -c %s "$out/libtun_pipe.so") bytes"
done
