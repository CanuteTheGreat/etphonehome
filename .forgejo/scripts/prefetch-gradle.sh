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
for i in 1 2 3 4 5 6 7 8; do
  if curl -fsSL --connect-timeout 15 --max-time 300 -o "$dir/$zipname.part" "$url" \
     && unzip -tq "$dir/$zipname.part" >/dev/null 2>&1; then
    mv "$dir/$zipname.part" "$dir/$zipname"
    echo "gradle dist fetched (attempt $i): $dir/$zipname"
    exit 0
  fi
  echo "gradle dist download failed (attempt $i/8), retrying in 15s"
  rm -f "$dir/$zipname.part"; sleep 15
done
echo "gradle dist prefetch gave up; the wrapper will try itself"
exit 0
