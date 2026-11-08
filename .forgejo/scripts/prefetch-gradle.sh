#!/usr/bin/env bash
# Pre-seed the Gradle wrapper distribution with a retrying download.
# services.gradle.org redirects to a GitHub release asset, and GitHub answers
# 504 for minutes at a time from the rw-man runners' egress (2026-11-08: every
# Android/nightly job died in gradle-wrapper's single, non-retrying download).
# Wrapper layout: $GRADLE_USER_HOME/wrapper/dists/<name>/<base36(md5(url))>/<zip>;
# an existing zip is unpacked instead of downloaded. Best effort: on failure the
# wrapper's own download still runs.
set -u
props="$(git rev-parse --show-toplevel)/android/gradle/wrapper/gradle-wrapper.properties"
url=$(sed -n 's/^distributionUrl=//p' "$props" | sed 's/\\//g')
[ -n "$url" ] || exit 0
zipname=${url##*/}
name=${zipname%.zip}
hash=$(python3 - "$url" <<'P'
import hashlib, sys
n = int.from_bytes(hashlib.md5(sys.argv[1].encode()).digest(), "big")
d = "0123456789abcdefghijklmnopqrstuvwxyz"
s = ""
while n:
    n, r = divmod(n, 36)
    s = d[r] + s
print(s)
P
)
dir="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/$name/$hash"
if [ -f "$dir/$zipname" ] || [ -f "$dir/$zipname.ok" ]; then echo "gradle dist already present"; exit 0; fi
mkdir -p "$dir"
# Official sha256 (served by Gradle itself, not from the GitHub asset) lets us
# also accept the zip from a public mirror when the official redirect is down.
sha=""
for i in 1 2 3; do
  sha=$(curl -fsSL --connect-timeout 15 --max-time 30 "$url.sha256" 2>/dev/null | tr -d '[:space:]')
  [ "${#sha}" -eq 64 ] && break
  sha=""; sleep 5
done
sources=("$url" "$url" "$url")
[ -n "$sha" ] && sources+=("https://mirrors.cloud.tencent.com/gradle/$zipname" "$url" "https://mirrors.cloud.tencent.com/gradle/$zipname")
n=0
for src in "${sources[@]}"; do
  n=$((n + 1))
  if curl -fsSL --connect-timeout 15 --max-time 300 -o "$dir/$zipname.part" "$src" \
     && unzip -tq "$dir/$zipname.part" >/dev/null 2>&1 \
     && { [ -z "$sha" ] || [ "$(sha256sum "$dir/$zipname.part" | cut -d' ' -f1)" = "$sha" ]; }; then
    mv "$dir/$zipname.part" "$dir/$zipname"
    echo "gradle dist fetched (try $n, $src)"
    exit 0
  fi
  echo "gradle dist download failed (try $n: $src), retrying in 10s"
  rm -f "$dir/$zipname.part"; sleep 10
done
echo "gradle dist prefetch gave up; the wrapper will try itself"
exit 0
