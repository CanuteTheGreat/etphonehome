#!/usr/bin/env bash
# Runs ONLY on the dedicated etphonehome-release-signer runner (rw-man-04, host mode).
# (Same script as SSHerpa's .forgejo/scripts/sign-aab.sh.)
# Downloads the UNSIGNED release AAB that build-release-aab uploaded as a run
# artifact, requires its sha256 to equal that job's reported checksum, signs it
# with the upload keystore (jarsigner) and verifies the result. Never builds.
#
# env: EXPECTED_SHA256, REPO_TOKEN (job token, read access to this run's
#      artifacts), RELEASE_KEYSTORE_B64, RELEASE_KEYSTORE_STORE_PASSWORD,
#      RELEASE_KEYSTORE_KEY_ALIAS, RELEASE_KEYSTORE_KEY_PASSWORD
# out: $1 = path for the signed AAB
set -euo pipefail
OUT="${1:?usage: sign-aab.sh <signed-out.aab>}"
: "${EXPECTED_SHA256:?}" "${REPO_TOKEN:?}" "${RELEASE_KEYSTORE_B64:?}"
: "${RELEASE_KEYSTORE_STORE_PASSWORD:?}" "${RELEASE_KEYSTORE_KEY_ALIAS:?}" "${RELEASE_KEYSTORE_KEY_PASSWORD:?}"
[[ "$EXPECTED_SHA256" =~ ^[0-9a-f]{64}$ ]] || { echo "bad EXPECTED_SHA256" >&2; exit 1; }

W=$(mktemp -d)
umask 077
cleanup() {
  [ -f "$W/ks.jks" ] && shred -u "$W/ks.jks" 2>/dev/null
  rm -rf "$W"
}
trap cleanup EXIT

API="$GITHUB_SERVER_URL/api/v1/repos/$GITHUB_REPOSITORY"
auth=(-H "Authorization: token $REPO_TOKEN")
# This run's artifact named app-release-unsigned-aab.
curl -fsS "${auth[@]}" "$API/actions/runs/$GITHUB_RUN_ID/artifacts" -o "$W/arts.json"
ART_ID=$(python3 - "$W/arts.json" <<'EOF'
import json,sys
d=json.load(open(sys.argv[1]))
arts=d if isinstance(d,list) else d.get("artifacts", [])  # Forgejo 17: bare list
ids=[a["id"] for a in arts if a.get("name")=="app-release-unsigned-aab"]
print(ids[-1] if ids else "")
EOF
)
[ -n "$ART_ID" ] || { echo "artifact app-release-unsigned-aab not found in run $GITHUB_RUN_ID" >&2; cat "$W/arts.json" >&2; exit 1; }
curl -fsSL "${auth[@]}" "$API/actions/artifacts/$ART_ID/zip" -o "$W/art.zip"
python3 - "$W/art.zip" "$W/unsigned.aab" <<'EOF'
import sys,zipfile
z=zipfile.ZipFile(sys.argv[1])
names=[n for n in z.namelist() if n.endswith(".aab")]
assert len(names)==1, f"expected exactly one .aab in artifact, got {z.namelist()}"
open(sys.argv[2],"wb").write(z.read(names[0]))
EOF
GOT=$(sha256sum "$W/unsigned.aab" | awk '{print $1}')
if [ "$GOT" != "$EXPECTED_SHA256" ]; then
  echo "sha256 mismatch: build job reported $EXPECTED_SHA256, artifact is $GOT - refusing to sign" >&2
  exit 1
fi
echo "unsigned AAB sha256 verified: $GOT"
# Capture first, then grep: `jarsigner | grep -q` under pipefail SIGPIPEs jarsigner on an early match.
PRE_VERIFY=$(jarsigner -verify "$W/unsigned.aab" 2>/dev/null || true)
if grep -q '^jar verified' <<<"$PRE_VERIFY"; then
  echo "input AAB is already signed - expected an unsigned build" >&2
  exit 1
fi

printf '%s' "$RELEASE_KEYSTORE_B64" | base64 -d > "$W/ks.jks"
export KS_PASS="$RELEASE_KEYSTORE_STORE_PASSWORD" KEY_PASS="$RELEASE_KEYSTORE_KEY_PASSWORD"
jarsigner -keystore "$W/ks.jks" -storepass:env KS_PASS -keypass:env KEY_PASS \
  -signedjar "$OUT" "$W/unsigned.aab" "$RELEASE_KEYSTORE_KEY_ALIAS"
# Not -strict: the Play upload key is self-signed, which -strict reports as an
# untrusted chain (exit 4). Integrity is checked here, identity just below.
jarsigner -verify "$OUT" | tee "$W/verify.txt"
grep -q '^jar verified' "$W/verify.txt" || { echo "signature verification failed" >&2; exit 1; }
# The signer certificate in the AAB must be the keystore's upload certificate.
WANT_FP=$(keytool -list -keystore "$W/ks.jks" -storepass:env KS_PASS -alias "$RELEASE_KEYSTORE_KEY_ALIAS" \
  | sed -nE 's/.*SHA-?256[^:]*: *([0-9A-F:]+).*/\1/p' | sed -n 1p)
GOT_FP=$(keytool -printcert -jarfile "$OUT" | sed -nE 's/^[[:space:]]*SHA256: *([0-9A-F:]+).*/\1/p' | sed -n 1p)
unset KS_PASS KEY_PASS
[ -n "$WANT_FP" ] && [ "$WANT_FP" = "$GOT_FP" ] || { echo "signer cert mismatch (keystore $WANT_FP, aab $GOT_FP)" >&2; exit 1; }
echo "signed + verified: $(sha256sum "$OUT" | awk '{print $1}')  upload cert SHA-256 $GOT_FP"
