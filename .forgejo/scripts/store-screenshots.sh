#!/usr/bin/env bash
# Play store phone screenshots of the REAL app on an Android 16 (API 36) emulator, in the states a
# user sees: USB ready, Wi-Fi pairing (token + encryption switch), connected over USB with live
# traffic stats, connected over Wi-Fi + TLS, Bluetooth. The traffic in the stats is real: the
# release desktop client runs in a network namespace whose only way out is the phone.
# Screen 1080x1920 (9:16, what Play wants), clean status bar via System UI demo mode.
#
# Usage: store-screenshots.sh <apk> <etphonehome desktop binary> <out dir>
# Writes <out>/NN-name.png (+ NN-name.xml uiautomator dumps). Copy the PNGs into
# store/graphics/phone/ and push a set-play-listing-<n> tag.
set -uo pipefail
APK=$(readlink -f "$1")
BIN=$(readlink -f "$2")
OUT=$(mkdir -p "$3" && readlink -f "$3")
API=36
SDK=${ANDROID_HOME:-$HOME/android-sdk}
export PATH="$SDK/platform-tools:$SDK/emulator:$SDK/cmdline-tools/latest/bin:$PATH"
PKG=solutions.errant.etph
ACT=$PKG/com.etphonehome.MainActivity
NS=etphshots
LOG=$OUT/logs
mkdir -p "$LOG"
PIDS=()
cleanup() {
  ip netns pids "$NS" 2>/dev/null | xargs -r kill -9 2>/dev/null
  ip netns del "$NS" 2>/dev/null
  for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done
  adb emu kill >/dev/null 2>&1
  sleep 2
  pkill -f "qemu-system.*etphshots" 2>/dev/null
  rm -f /tmp/etphshots-fwd-*.sock
}
trap cleanup EXIT
die() { echo "FAIL: $*"; exit 1; }

# --- emulator --------------------------------------------------------------------------
IMG="system-images;android-$API;google_apis;x86_64"
yes | sdkmanager --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
sdkmanager --sdk_root="$SDK" "emulator" "platform-tools" "$IMG" >/dev/null || die "sdkmanager"
echo no | avdmanager create avd -n etphshots -k "$IMG" -d pixel_5 --force >/dev/null
emulator -avd etphshots -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics \
  -gpu swiftshader_indirect -accel on -memory 3072 -cores 2 -port 5556 >"$LOG/emulator.log" 2>&1 &
PIDS+=($!)
export ANDROID_SERIAL=emulator-5556
adb start-server >/dev/null
for _ in $(seq 1 180); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
  sleep 2
done
[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] || { tail -40 "$LOG/emulator.log"; die "emulator did not boot"; }
for k in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $k 0; done
adb shell svc power stayon true
adb shell wm dismiss-keyguard >/dev/null 2>&1
adb shell input keyevent 82
adb shell wm size 1080x1920
adb shell wm density 420
# Gesture navigation: no 3-button bar at the bottom of the screenshots.
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural >/dev/null 2>&1
sleep 5
for _ in $(seq 1 10); do adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1; sleep 1; done

# Clean status bar: System UI demo mode (fixed clock, full battery + Wi-Fi, no notification icons).
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
adb shell settings put global sysui_demo_allowed 1
demo enter
demo clock -e hhmm 0941
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e fully true
demo network -e mobile show -e datatype none -e level 4 -e fully true
demo notifications -e visible false
demo status -e volume hide -e bluetooth hide -e location hide -e alarm hide -e sync hide -e tty hide -e eri hide -e mute hide -e speakerphone hide

adb install -r "$APK" >"$LOG/install.log" 2>&1 || { cat "$LOG/install.log"; die "install"; }
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb logcat -c

# --- UI helpers (as in android-emulator-e2e.sh) ------------------------------------------
dump() { adb exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierchary dumped to.*//' >"$LOG/ui.xml"; }
ui() {
  python3 - "$LOG/ui.xml" "$PKG" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except ET.ParseError:
    sys.exit(1)
for n in root.iter("node"):
    if n.get("package") == sys.argv[2] and (n.get("resource-id") or "").endswith(":id/" + sys.argv[3]):
        a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds")))
        print(f"{(a+c)//2} {(b+d)//2} {b} {d}|{n.get('text')}")
        sys.exit(0)
sys.exit(1)
PY
}
tap() {
  local r
  for _ in 1 2 3 4 5; do
    dump
    r=$(ui "$1") && { set -- ${r%%|*}; adb shell input tap "$1" "$2"; return 0; }
    adb shell input swipe 540 1400 540 900 200
    sleep 1
  done
  return 1
}
text_of() { dump; local r; r=$(ui "$1") && printf '%s\n' "${r#*|}"; }
launch() { adb shell am start -W -n "$ACT" >/dev/null; sleep 2; }
top() { adb shell input swipe 540 500 540 1700 150; adb shell input swipe 540 500 540 1700 150; sleep 1; }
# Scroll so the view with this id is fully on screen (bottom above y=1820).
reveal() {
  local r b
  for _ in 1 2 3 4; do
    dump
    r=$(ui "$1") || { adb shell input swipe 540 1400 540 1000 300; sleep 1; continue; }
    r=${r%%|*}; set -- $r
    b=$4
    [ "$b" -le 1820 ] && return 0
    adb shell input swipe 540 1400 540 $((1400 - (b - 1700))) 600
    sleep 1
  done
}
wait_log() { local n=$(($2 * 2)); for _ in $(seq 1 $n); do adb logcat -d -s ETPhoneHome:* 2>/dev/null | grep -q "$1" && return 0; sleep 0.5; done; return 1; }
SHOTS=0
shot() { # name
  sleep 1
  demo notifications -e visible false
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
  sleep 1
  adb exec-out screencap -p >"$OUT/$1.png"
  dump; cp "$LOG/ui.xml" "$OUT/$1.xml"
  python3 -c "
import struct,sys
d=open(sys.argv[1],'rb').read(24); w,h=struct.unpack('>II',d[16:24]); print(f'shot {sys.argv[1]} {w}x{h}')
assert (w,h)==(1080,1920), 'not 1080x1920'" "$OUT/$1.png" || die "screenshot size"
  SHOTS=$((SHOTS + 1))
}

# --- desktop namespace: its only way out is the phone --------------------------------------
SRV=$(mktemp -d)
head -c 104857600 /dev/urandom >"$SRV/big.bin"
(cd "$SRV" && exec python3 -m http.server 8080 --bind 127.0.0.1 >"$LOG/http.log" 2>&1) &
PIDS+=($!)
ip netns del "$NS" 2>/dev/null
ip netns add "$NS"
ip -n "$NS" link set lo up
mkdir -p "/etc/netns/$NS" /tmp/etphshots-shim
echo "nameserver 8.8.8.8" >"/etc/netns/$NS/resolv.conf"
printf '#!/bin/sh\ncase "$*" in devices*) printf "List of devices attached\\nemulator-5556\\tdevice\\n" ;; esac\nexit 0\n' >/tmp/etphshots-shim/adb
printf '#!/bin/sh\nexit 0\n' >/tmp/etphshots-shim/resolvectl
chmod +x /tmp/etphshots-shim/adb /tmp/etphshots-shim/resolvectl
bridge_port() {
  rm -f "/tmp/etphshots-fwd-$1.sock"
  socat "UNIX-LISTEN:/tmp/etphshots-fwd-$1.sock,fork" "TCP:127.0.0.1:$1" &
  PIDS+=($!)
  ip netns exec "$NS" socat "TCP-LISTEN:$1,bind=127.0.0.1,fork,reuseaddr" "UNIX-CONNECT:/tmp/etphshots-fwd-$1.sock" &
  PIDS+=($!)
}
D() { ip netns exec "$NS" bash -c "$1"; }
client() { local name=$1; shift; ip netns exec "$NS" env PATH="/tmp/etphshots-shim:$PATH" "$BIN" "$@" >"$LOG/$name.log" 2>&1 & CLIENT=$!; }
client_stop() { kill -INT "$CLIENT" 2>/dev/null; for _ in $(seq 1 100); do kill -0 "$CLIENT" 2>/dev/null || return 0; sleep 0.1; done; kill -9 "$CLIENT" 2>/dev/null; }
wait_iface() { for _ in $(seq 1 $(($1 * 10))); do D 'ip -4 addr show etph0 2>/dev/null | grep -q 10.0.0.1' && return 0; sleep 0.1; done; return 1; }
browse() { # realistic traffic through the phone: a few sites + a big download
  for u in https://example.com/ https://www.wikipedia.org/ https://www.debian.org/ https://www.kernel.org/; do
    D "curl -sS -m 20 -o /dev/null $u" || true
  done
  D "curl -sS -m 180 -o /dev/null http://10.0.2.2:8080/big.bin" || true
}

# 1. USB, ready
launch
tap radioUsb || die "radioUsb"
top
shot 01-usb-ready

# 2. Wi-Fi pairing: address + token, encryption switch
tap radioWifi || die "radioWifi"
sleep 2
top
TOKEN=$(text_of wifiIpText | grep -o 'Auth Token: [^ ]*' | head -1 | cut -d' ' -f3)
[ -n "$TOKEN" ] || echo "warn: no token visible (emulator Wi-Fi?)"
shot 02-wifi-pairing

# 3. Connected over USB, live stats after real traffic
tap radioUsb
sleep 1
tap startButton || die "start USB"
wait_log "USB server listening" 20 || die "USB server"
adb forward tcp:8888 tcp:8888 >/dev/null
bridge_port 8888
sleep 1
client usb usb --interface etph0 --reconnect --max-retries 0
wait_iface 30 || { tail -20 "$LOG/usb.log"; die "USB tunnel"; }
browse
sleep 3
launch
top
reveal statsText
grep -q "Desktop connected (USB" "$LOG/ui.xml" || { cat "$LOG/ui.xml" | head -c 3000; die "USB status not connected"; }
shot 03-connected-usb
client_stop
launch
tap stopButton
wait_log "Stopping tethering" 15
sleep 3

# 4. Connected over Wi-Fi + TLS
if [ -n "$TOKEN" ]; then
  adb logcat -c
  launch
  tap radioWifi
  sleep 1
  tap startButton || die "start WiFi"
  wait_log "WiFi server listening" 20 || die "WiFi server"
  adb forward tcp:18888 tcp:8888 >/dev/null
  bridge_port 18888
  sleep 1
  client wifi wifi --host 127.0.0.1 --port 18888 --token "$TOKEN" --interface etph0
  wait_iface 30 || { tail -20 "$LOG/wifi.log"; die "WiFi tunnel"; }
  browse
  sleep 3
  launch
  top
  reveal statsText
  shot 04-connected-wifi-encrypted
  client_stop
  launch
  tap stopButton
  wait_log "Stopping tethering" 15
  sleep 3
fi

# 5. Bluetooth
launch
tap radioBluetooth && { sleep 2; top; shot 05-bluetooth; }

echo "screenshots: $SHOTS in $OUT"
[ "$SHOTS" -ge 4 ] || die "fewer than 4 screenshots"
