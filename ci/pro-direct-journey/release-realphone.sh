#!/bin/bash
# v0.9.5 RELEASE APK (signed, as downloaded from errant.solutions) on the real test phone with a LIVE comp key:
# activates offline-verified Pro, fetches the live signed revocation list, then (after the key is revoked on
# the server by the caller between phase A and B) refuses it.   usage: release-realphone.sh A|B <apk> <key> <evd>
set -uo pipefail
PH=$1; APK=$2; KEY=$3; EVD=$4
export ANDROID_SERIAL=${ANDROID_SERIAL:-R5CR70PMMQR}
PKG=solutions.errant.etph
OUT=$(mktemp -d); mkdir -p "$EVD"; P=0; F=0
pass() { echo "PASS $*"; P=$((P+1)); }
fail() { echo "FAIL $*"; F=$((F+1)); }
. /home/canutethegreat/.hermes/skills/personal-infra-ops/etphonehome-real-device-qa/scripts/etph-phone-lib.sh
main_pro() { launch; dump; grep -q "ETPH Pro (license key)" "$OUT/ui.xml"; }
link() { adb shell am start -a android.intent.action.VIEW -d "etph://activate?key=$1" $PKG >/dev/null 2>&1; sleep 2; dump; }
if [ "$PH" = A ]; then
  adb uninstall $PKG >/dev/null 2>&1   # debug (CI key) build -> release signer: replace
  adb install "$APK" > "$OUT/i.log" 2>&1; grep -q Success "$OUT/i.log" && pass "release APK installed ($(adb shell dumpsys package $PKG | grep -m1 versionName | tr -d ' '))" || fail "install $(tail -1 $OUT/i.log)"
  adb logcat -c
  launch; tap upgradeButton; sleep 2; dump
  grep -q "Buy ETPH Pro (US\$15" "$OUT/ui.xml" && pass "release APK, Free: Buy ETPH Pro (US\$15, one time) + Enter license key" || fail "release Upgrade screen"
  shot r1-release-free-light.png
  link "$KEY"; grep -q "Activate this license key?" "$OUT/ui.xml" && pass "live key link -> confirm dialog" || fail "no dialog"
  tap button1; sleep 4
  adb shell am force-stop $PKG; main_pro && pass "LIVE comp key activates Pro in the release build (survives restart)" || fail "release build not Pro"
  adb logcat -d -s ETPhoneHome:* | grep -E "license|revocation" | tail -4 | tee "$EVD/r-logcat-A.txt"
  grep -q "revocation list applied" "$EVD/r-logcat-A.txt" && pass "live signed revocation list fetched from errant.solutions and applied" || fail "live revocation list not applied"
  adb shell input swipe 540 900 540 1700 200; sleep 1; shot r2-release-pro-light.png
else
  adb logcat -c
  # Remove + re-enter: activation fetches the list right away; the revoked key must end up refused.
  launch; adb shell am start -a android.intent.action.VIEW -d "etph://activate?key=x" $PKG >/dev/null; sleep 2
  adb shell input keyevent KEYCODE_BACK; sleep 1; tap licenseButton; sleep 1; dump
  XY=$(python3 - "$OUT/ui.xml" <<'PY'
import re,sys,xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter("node"):
    if (n.get("text") or "").lower() == "remove license key" and n.get("clickable") == "true":
        a,b,c,d=map(int,re.findall(r"\d+",n.get("bounds"))); print((a+c)//2,(b+d)//2); break
PY
)
  [ -n "$XY" ] && adb shell input tap $XY; sleep 2
  link "$KEY"; tap button1; sleep 5; dump; shot r3-revoked-light.png
  adb shell am force-stop $PKG; launch; dump
  grep -q "ETPH Pro (license key)" "$OUT/ui.xml" && fail "revoked live key still gives Pro" || pass "key revoked on the server -> refused by the release build after its online check"
  adb logcat -d -s ETPhoneHome:* | grep -E "license|revocation" | tail -5 | tee "$EVD/r-logcat-B.txt"
fi
adb shell am force-stop $PKG
echo "RESULT $PH pass=$P fail=$F"; [ $F -eq 0 ]
