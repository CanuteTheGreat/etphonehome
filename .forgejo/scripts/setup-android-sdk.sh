#!/bin/bash
# Bootstraps a real Android SDK at $HOME/android-sdk on a fresh, ephemeral x86_64 CI runner
# that has nothing pre-installed. Hand-rolled rather than a marketplace action
# (android-actions/setup-android) because this Forgejo instance only mirrors an allowlist of
# GitHub Actions via data.forgejo.org and does not include it (confirmed live on ssherpa's own
# CI -- see .forgejo/scripts/setup-android-sdk.sh there for the original story). Installs
# platform 34 / build-tools matching this project's compileSdk 34 (a normal stable release, not
# ssherpa's point-release edge case).
set -e

SDK_ROOT="$HOME/android-sdk"
mkdir -p "$SDK_ROOT/cmdline-tools"
curl -sSL -o /tmp/cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q /tmp/cmdline-tools.zip -d "$SDK_ROOT/cmdline-tools"
mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
yes | "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_ROOT" --licenses >/dev/null 2>&1 || true
"$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_ROOT" "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null
