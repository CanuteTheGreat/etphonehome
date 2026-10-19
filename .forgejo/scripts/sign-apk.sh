#!/usr/bin/env bash
# Runs ONLY on the dedicated etphonehome-release-signer runner (rw-man-04, host mode).
# Release APK for direct download (Forgejo release, errant.solutions/etphonehome): downloads the
# UNSIGNED release APK that build-android uploaded as a run artifact, requires its sha256 to equal
# that job's output, zipaligns and signs it (apksigner, v1+v2+v3) with the ETPH release key -- the
# same key as the Play upload key, registered for solutions.errant.etph in Android developer
# verification (cert SHA-256 01:11:AF:...:68:40) -- and checks the signer cert. Never builds.
#
# env: EXPECTED_SHA256, REPO_TOKEN, RELEASE_KEYSTORE_B64, RELEASE_KEYSTORE_STORE_PASSWORD,
#      RELEASE_KEYSTORE_KEY_ALIAS, RELEASE_KEYSTORE_KEY_PASSWORD
# out: $1 = path for the signed APK
set -euo pipefail
OUT="${1:?usage: sign-apk.sh <signed-out.apk>}"
: "${EXPECTED_SHA256:?}" "${REPO_TOKEN:?}" "${RELEASE_KEYSTORE_B64:?}"
: "${RELEASE_KEYSTORE_STORE_PASSWORD:?}" "${RELEASE_KEYSTORE_KEY_ALIAS:?}" "${RELEASE_KEYSTORE_KEY_PASSWORD:?}"
[[ "$EXPECTED_SHA256" =~ ^[0-9a-f]{64}$ ]] || { echo "bad EXPECTED_SHA256" >&2; exit 1; }
# The registered certificate (Android developer verification + Play upload key). A different key
# here would ship APKs that Android's verification rejects and that can't update each other.
WANT_FP=01:11:AF:00:57:D7:E9:28:90:FB:CE:02:E9:92:7A:85:E5:EA:B2:AD:AC:2C:00:76:55:E1:7C:B0:CF:7F:68:40

W=$(mktemp -d)
umask 077
cleanup() {
  [ -f "$W/ks.jks" ] && shred -u "$W/ks.jks" 2>/dev/null
  rm -rf "$W"
}
trap cleanup EXIT

API="$GITHUB_SERVER_URL/api/v1/repos/$GITHUB_REPOSITORY"
auth=(-H "Authorization: token $REPO_TOKEN")
curl -fsS "${auth[@]}" "$API/actions/runs/$GITHUB_RUN_ID/artifacts" -o "$W/arts.json"
ART_ID=$(python3 - "$W/arts.json" <<'EOF'
import json,sys
d=json.load(open(sys.argv[1]))
arts=d if isinstance(d,list) else d.get("artifacts", [])  # Forgejo: bare list
ids=[a["id"] for a in arts if a.get("name")=="android-unsigned-apk"]
print(ids[-1] if ids else "")
EOF
)
[ -n "$ART_ID" ] || { echo "artifact android-unsigned-apk not found in run $GITHUB_RUN_ID" >&2; cat "$W/arts.json" >&2; exit 1; }
curl -fsSL "${auth[@]}" "$API/actions/artifacts/$ART_ID/zip" -o "$W/art.zip"
python3 - "$W/art.zip" "$W/unsigned.apk" <<'EOF'
import sys,zipfile
z=zipfile.ZipFile(sys.argv[1])
names=[n for n in z.namelist() if n.endswith(".apk")]
assert len(names)==1, f"expected exactly one .apk in artifact, got {z.namelist()}"
open(sys.argv[2],"wb").write(z.read(names[0]))
EOF
GOT=$(sha256sum "$W/unsigned.apk" | awk '{print $1}')
[ "$GOT" = "$EXPECTED_SHA256" ] || { echo "sha256 mismatch: build job reported $EXPECTED_SHA256, artifact is $GOT - refusing to sign" >&2; exit 1; }
echo "unsigned APK sha256 verified: $GOT"
PRE=$(apksigner verify "$W/unsigned.apk" 2>&1 || true)
if grep -q '^Verifies' <<<"$PRE"; then echo "input APK is already signed - expected an unsigned build" >&2; exit 1; fi

zipalign -f -p 4 "$W/unsigned.apk" "$W/aligned.apk"
printf '%s' "$RELEASE_KEYSTORE_B64" | base64 -d > "$W/ks.jks"
export KS_PASS="$RELEASE_KEYSTORE_STORE_PASSWORD" KEY_PASS="$RELEASE_KEYSTORE_KEY_PASSWORD"
apksigner sign --ks "$W/ks.jks" --ks-key-alias "$RELEASE_KEYSTORE_KEY_ALIAS" \
  --ks-pass env:KS_PASS --key-pass env:KEY_PASS --out "$OUT" "$W/aligned.apk"
unset KS_PASS KEY_PASS
apksigner verify --verbose --print-certs "$OUT" > "$W/verify.txt"
grep -E '^Verifies|Verified using v[123]|certificate SHA-256' "$W/verify.txt"
grep -q '^Verifies' "$W/verify.txt" || { echo "apksigner verify failed" >&2; exit 1; }
GOT_FP=$(sed -nE 's/.*certificate SHA-256 digest: *([0-9a-f]+).*/\1/p' "$W/verify.txt" | sed -n 1p | tr a-f A-F | sed 's/../&:/g; s/:$//')
[ "$GOT_FP" = "$WANT_FP" ] || { echo "signer cert $GOT_FP is not the registered ETPH key $WANT_FP" >&2; exit 1; }
python3 - "$OUT" <<'EOF'
import re, sys, zipfile
names = zipfile.ZipFile(sys.argv[1]).namelist()
assert "AndroidManifest.xml" in names and any(re.fullmatch(r"classes\d*\.dex", n) for n in names), "not an APK"
EOF
echo "signed + verified: $(sha256sum "$OUT" | awk '{print $1}')  cert SHA-256 $GOT_FP"
