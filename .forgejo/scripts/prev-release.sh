#!/usr/bin/env bash
# Fetch the previous release for the upgrade suite (tunnel-deep.sh):
#   <out>/bin/etphonehome      the released Linux x86_64 client (asset)
#   <out>/standin/             the phone core of that release, built from its tag
#   <out>/config.example.toml  the example config that release shipped
# "Previous" = the newest published release whose tag is not HEAD itself
# (falling back to the newest release when HEAD is the release commit, so the
# shipped artifacts are still checked against this build). Needs the tags
# (actions/checkout fetch-depth: 0). Prints the tag on the last line.
#
# Usage: prev-release.sh <out dir>
set -euo pipefail
OUT=$1
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
API=${GITHUB_SERVER_URL:-https://git.canutethegreat.com}/api/v1/repos/${GITHUB_REPOSITORY:-CanuteTheGreat/etphonehome}
mkdir -p "$OUT/bin"
HEAD=$(git -C "$ROOT" rev-parse HEAD)
curl -fsS "$API/releases?limit=20" -o "$OUT/releases.json"
TAG=$(python3 - "$OUT/releases.json" "$ROOT" "$HEAD" <<'PY'
import json, subprocess, sys
rels = [r for r in json.load(open(sys.argv[1])) if not r["draft"] and not r["prerelease"]]
root, head = sys.argv[2], sys.argv[3]
def commit(tag):
    try:
        return subprocess.check_output(["git", "-C", root, "rev-list", "-n1", tag], text=True).strip()
    except subprocess.CalledProcessError:
        return None
pick = next((r for r in rels if commit(r["tag_name"]) not in (None, head)), rels[0] if rels else None)
print(pick["tag_name"] if pick else "")
PY
)
[ -n "$TAG" ] || { echo "no published release found" >&2; exit 1; }
URL=$(python3 -c "
import json,sys
r=[r for r in json.load(open('$OUT/releases.json')) if r['tag_name']=='$TAG'][0]
print([a['browser_download_url'] for a in r['assets'] if a['name'].startswith('etphonehome-linux-')][0])")
curl -fsSL "$URL" -o "$OUT/linux.tar.gz"
tar -C "$OUT/bin" -xzf "$OUT/linux.tar.gz"
"$OUT/bin/etphonehome" --version
rm -rf "$OUT/src"
git -C "$ROOT" worktree add --detach "$OUT/src" "$TAG" >/dev/null
cp "$OUT/src/desktop/config.example.toml" "$OUT/config.example.toml"
if [ -d "$OUT/src/android/core/standin" ]; then
  (cd "$OUT/src/android/core" && ../gradlew --no-daemon --max-workers=2 -q installDist)
  cp -r "$OUT/src/android/core/standin/build/install/standin" "$OUT/standin"
else
  echo "release $TAG has no phone core stand-in: phone-side upgrade checks need one" >&2
  exit 1
fi
git -C "$ROOT" worktree remove --force "$OUT/src"
echo "$TAG"
