#!/bin/bash
# ETPH Pro direct purchase on the real test phone (SM-N986U): sideloaded CI debug APK (DIRECT channel), the
# license key bought in the Stripe test-mode journey (journey.py), activated through the key page's link and by
# pasting; Pro survives a restart; screenshots light + dark. Run under with-ssherpa-phone.
#   usage: pro-direct-realphone.sh <apk> <key-file(json)> <evidence-dir>
set -uo pipefail
APK=$1; KEYJSON=$2; EVD=$3
export ANDROID_SERIAL=${ANDROID_SERIAL:-R5CR70PMMQR}
PKG=solutions.errant.etph
OUT=$(mktemp -d); mkdir -p "$EVD"
P=0; F=0
pass() { echo "PASS $*"; P=$((P+1)); }
fail() { echo "FAIL $*"; F=$((F+1)); }
. /home/canutethegreat/.hermes/skills/personal-infra-ops/etphonehome-real-device-qa/scripts/etph-phone-lib.sh
KEY=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["key"])' "$KEYJSON")
PID=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["purchase_id"])' "$KEYJSON")
night() { adb shell cmd uimode night "$1" >/dev/null; sleep 1; }
upgrade() { launch; tap upgradeButton; sleep 2; dump; }
# Pro hides the main screen's Upgrade button: reach the Pro screen through its activation link instead
# (a dummy key opens the confirm dialog on the Pro screen; Back dismisses it).
pro_screen() { launch; adb shell am start -a android.intent.action.VIEW -d "etph://activate?key=x" $PKG >/dev/null; sleep 2; adb shell input keyevent KEYCODE_BACK; sleep 1; dump; }
main_pro() { launch; dump; grep -q "ETPH Pro (license key)" "$OUT/ui.xml"; }

ORIG_NIGHT=$(adb shell cmd uimode night | awk '{print $NF}')
# Same signer as the installed copy? A release/Play build can't be replaced by a debug one.
if adb shell pm path $PKG >/dev/null 2>&1; then
  adb install -r -d "$APK" >"$OUT/install.log" 2>&1 || { adb uninstall $PKG >/dev/null; adb install "$APK" >"$OUT/install.log" 2>&1; }
else
  adb install "$APK" >"$OUT/install.log" 2>&1
fi
grep -q Success "$OUT/install.log" && pass "debug APK sideloaded ($(adb shell dumpsys package $PKG | grep -m1 versionName | tr -d ' '))" || fail "install: $(tail -1 $OUT/install.log)"
INST=$(adb shell pm list packages -i $PKG | sed 's/.*installer=//')
pass "installer recorded by Android: '${INST}' (not com.android.vending -> DIRECT channel)"
adb shell pm clear $PKG >/dev/null   # fresh state: no Pro from an earlier run
adb logcat -c

night no
upgrade
ST=$(ui upgradeStatus | cut -d'|' -f2-)
grep -q "errant.solutions" <<<"$ST" && pass "Free, DIRECT: '$ST'" || fail "upgrade status '$ST'"
BT=$(ui buyButton | cut -d'|' -f2-); grep -q 'US\$15' <<<"$BT" && pass "Buy button '$BT'" || fail "buy button '$BT'"
ui licenseButton >/dev/null && pass "Enter license key shown" || fail "no Enter license key"
ui restoreButton >/dev/null && fail "Play's Restore shown on a DIRECT copy" || pass "Play's Restore not shown"
shot 01-direct-free-light.png
night yes; upgrade; shot 02-direct-free-dark.png; night no
adb logcat -d | grep -q BillingClient && fail "DIRECT copy started Google Play Billing" || pass "Google Play Billing never started"

# Buy opens the browser on the errant.solutions purchase page.
upgrade; tap buyButton; sleep 3
TOP=$(adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity")
grep -qvi "$PKG" <<<"$TOP" && pass "Buy opened another app (browser): ${TOP##* }" || fail "Buy didn't leave the app ($TOP)"
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell am force-stop com.android.chrome 2>/dev/null; adb shell am force-stop com.sec.android.app.sbrowser 2>/dev/null

# 1) Activation link (what the key page / email button does): confirm dialog, then Pro.
adb shell am start -a android.intent.action.VIEW -d "etph://activate?key=$KEY" $PKG >/dev/null; sleep 2; dump
grep -q "Activate this license key?" "$OUT/ui.xml" && pass "etph://activate link opened the confirm dialog" || fail "no confirm dialog"
shot 03-activate-dialog-light.png
tap button1; sleep 2; dump
grep -q "ETPH Pro is active" "$OUT/ui.xml" || grep -q "license key" "$OUT/ui.xml"
wait_log "license" 5 >/dev/null
adb shell am force-stop $PKG; sleep 1
main_pro && pass "after activation + app restart: main screen says 'ETPH Pro (license key)'" || fail "main screen not Pro after activation"
ui upgradeButton >/dev/null && fail "main screen still offers Upgrade" || pass "main screen: Upgrade button gone (Pro)"
adb shell input swipe 540 900 540 1700 200; sleep 1; shot 04-main-pro-light.png
night yes; launch; adb shell input swipe 540 900 540 1700 200; sleep 1; shot 05-main-pro-dark.png
pro_screen; ST=$(ui upgradeStatus | cut -d'|' -f2-)
grep -q "Pro (license $PID)" <<<"$ST" && pass "Pro screen: '$ST'" || fail "Pro screen status: '$ST'"
shot 06-pro-license-dark.png; night no; pro_screen; shot 07-pro-license-light.png

# 2) Remove the key, then paste it (the manual path).
pro_screen; tap licenseButton; sleep 1; dump
grep -q "Remove license key" "$OUT/ui.xml" && { tap button1 2>/dev/null || true; }
python3 - "$OUT/ui.xml" <<'PY' >"$OUT/rm.xy"
import re,sys,xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter("node"):
    if (n.get("text") or "").lower() == "remove license key" and n.get("clickable") == "true":
        a,b,c,d=map(int,re.findall(r"\d+",n.get("bounds"))); print((a+c)//2,(b+d)//2); break
PY
[ -s "$OUT/rm.xy" ] && adb shell input tap $(cat "$OUT/rm.xy")
sleep 2; adb shell am force-stop $PKG; upgrade
ST=$(ui upgradeStatus | cut -d'|' -f2-)
grep -q "errant.solutions" <<<"$ST" && pass "key removed -> Free again" || fail "remove key: '$ST'"
tap licenseButton; sleep 1; tap licenseInput
adb shell input text "$KEY"; sleep 1; tap button1; sleep 2
adb shell am force-stop $PKG
main_pro && pass "pasted key activates Pro (survives restart)" || fail "paste did not activate Pro"
adb logcat -d -s ETPhoneHome:* | grep -iE "licen|revoc" | tail -5 > "$EVD/logcat-license.txt"

adb shell cmd uimode night "$ORIG_NIGHT" >/dev/null
adb shell am force-stop $PKG
echo "purchase id $PID; evidence $EVD"
echo "RESULT pass=$P fail=$F"
[ $F -eq 0 ]
