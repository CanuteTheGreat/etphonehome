#!/usr/bin/env bash
# The REAL Android app (APK on an Android emulator, KVM) giving a desktop
# real internet. Everything the JVM stand-in can't prove: the app's UI and
# foreground service, its USB server behind a real `adb forward`, Bouncy
# Castle TLS on the Android runtime, the userspace relay on ART, and the
# phone keeping its own connectivity (no VPN, no tun0) while it relays.
#
# The desktop is the release `etphonehome` in a network namespace whose only
# way out is the tunnel (its loopback port 8888 is bridged to the adb
# forward through a unix socket). "The internet" for the phone is the
# emulator's own network; services on this host are reachable from the
# phone as 10.0.2.2 (bulk download/upload with digests, UDP).
#
#   USB         start from the UI, isolation check, HTTPS/DNS/bulk/upload/
#               UDP/parallel through the phone, phone's own HTTP during the
#               session, status + live stats in the UI
#   app killed  force-stop mid-transfer: desktop drops the tunnel, no
#               leftovers; relaunch + Start: desktop reconnects by itself
#   Stop        the UI Stop button ends the session and the service
#   WiFi TLS    (images with WiFi) token from the UI, TLS 1.3 on ART
#   upgrade     (with a previous APK) install it, note the pairing token,
#               upgrade in place to this build: token kept, app works
#
# Usage: android-emulator-e2e.sh <api> <image tag> <apk> <etphonehome> [<previous apk>]
set -uo pipefail
API=$1
IMGTAG=$2
APK=$(readlink -f "$3")
BIN=$(readlink -f "$4")
PREV_APK=${5:+$(readlink -f "$5")}
HERE=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_HOME:-$HOME/android-sdk}
export PATH="$SDK/platform-tools:$SDK/emulator:$SDK/cmdline-tools/latest/bin:$PATH"
LOG=${EMU_LOG:-$PWD/emulator-results/api$API}
mkdir -p "$LOG"
PKG=com.etphonehome
NS=etphdesk$API
PASS=0
FAIL=0
FAILED=()
pass() { echo "PASS: [API $API] $*"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: [API $API] $*"; FAIL=$((FAIL + 1)); FAILED+=("$*"); }
now_ms() { date +%s%3N; }
PIDS=()
cleanup() {
  ip netns pids "$NS" 2>/dev/null | xargs -r kill -9 2>/dev/null
  ip netns del "$NS" 2>/dev/null
  for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done
  adb emu kill >/dev/null 2>&1
  sleep 2
  pkill -f "qemu-system.*etph$API" 2>/dev/null
  rm -f /tmp/etph-fwd-*.sock
}
trap cleanup EXIT

# --- emulator -----------------------------------------------------------------
IMG="system-images;android-$API;$IMGTAG;x86_64"
yes | sdkmanager --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
sdkmanager --sdk_root="$SDK" "emulator" "platform-tools" "$IMG" >/dev/null || { echo "sdkmanager failed"; exit 1; }
# A phone-sized screen (the default AVD is 320x640, where system dialogs
# cover the app).
echo no | avdmanager create avd -n "etph$API" -k "$IMG" -d pixel_5 --force >/dev/null
ls -l /dev/kvm
emulator -avd "etph$API" -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics \
  -gpu swiftshader_indirect -accel on -memory 3072 -cores 2 -port 5554 >"$LOG/emulator.log" 2>&1 &
PIDS+=($!)
export ANDROID_SERIAL=emulator-5554
adb start-server >/dev/null
T0=$(now_ms)
booted=0
for _ in $(seq 1 180); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { booted=1; break; }
  sleep 2
done
if [ $booted != 1 ]; then
  tail -40 "$LOG/emulator.log"
  echo "FAIL: [API $API] emulator did not boot"
  exit 1
fi
echo "emulator API $API booted in $(( ($(now_ms) - T0) / 1000 ))s"
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell svc power stayon true
adb shell wm dismiss-keyguard >/dev/null 2>&1
adb shell input keyevent 82
# Let the freshly booted system settle: launcher up, and no "isn't
# responding" dialog left over the screen (they took every tap on API 34).
settle() {
  for _ in $(seq 1 20); do
    adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
    F=$(adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r')
    case "$F" in
      *"Not Responding"* | *"Application Error"*) adb shell input keyevent KEYCODE_ENTER; sleep 2 ;;
      *null* | "") sleep 2 ;;
      *) return 0 ;;
    esac
  done
}
settle

# --- UI helpers -----------------------------------------------------------------
dump() {
  local i
  for i in 1 2 3; do
    adb exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierchary dumped to.*//' >"$LOG/ui.xml"
    # A system dialog over the app ("isn't responding", etc.): clear it.
    if grep -q 'package="com.etphonehome"' "$LOG/ui.xml"; then return 0; fi
    settle
    adb shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
    sleep 2
  done
}
ui() { # id -> "x y|text"
  python3 - "$LOG/ui.xml" "$PKG:id/$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except ET.ParseError:
    sys.exit(1)
for n in root.iter("node"):
    if n.get("resource-id") == sys.argv[2]:
        a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds")))
        print(f"{(a+c)//2} {(b+d)//2}|{n.get('text')}")
        sys.exit(0)
sys.exit(1)
PY
}
tap() { # id
  local r
  for _ in 1 2 3 4 5; do
    dump
    r=$(ui "$1") && { adb shell input tap ${r%%|*}; return 0; }
    # scrolled off screen? scroll down a bit and retry
    adb shell input swipe 540 1500 540 900 200
    sleep 1
  done
  return 1
}
text_of() { dump; ui "$1" | cut -d'|' -f2-; }
launch() { adb shell am start -W -n "$PKG/.MainActivity" >/dev/null; sleep 2; }
wait_log() { # pattern seconds
  local n=$(($2 * 2))
  for _ in $(seq 1 $n); do adb logcat -d -s ETPhoneHome:* 2>/dev/null | grep -q "$1" && return 0; sleep 0.5; done
  return 1
}
start_mode() { # radio id
  launch
  tap "$1" || return 1
  sleep 1
  tap startButton || return 1
  # Android 13+: notification permission prompt on first Start.
  sleep 2
  dump
  if grep -q 'permission_allow_button' "$LOG/ui.xml"; then
    python3 - "$LOG/ui.xml" <<'PY' | { read -r x y && adb shell input tap "$x" "$y"; }
import re, sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter("node"):
    if n.get("resource-id", "").endswith("permission_allow_button"):
        a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds"))); print((a+c)//2, (b+d)//2); break
PY
  fi
  return 0
}
service_count() { adb shell dumpsys activity services $PKG 2>/dev/null | grep -c ServiceRecord; }

# --- host services the phone reaches as 10.0.2.2 ----------------------------------
SRV=$(mktemp -d)
head -c 52428800 /dev/urandom >"$SRV/big.bin"
sha256sum "$SRV/big.bin" | cut -d' ' -f1 >"$SRV/big.sha256"
for i in $(seq -w 0 29); do head -c 262144 /dev/urandom >"$SRV/p$i"; done
(cd "$SRV" && sha256sum p* >par.sha256)
(cd "$SRV" && exec python3 -m http.server 8080 --bind 127.0.0.1 >"$LOG/http.log" 2>&1) &
PIDS+=($!)
python3 "$HERE/e2e-target-services.py" >"$LOG/services.log" 2>&1 &
PIDS+=($!)
HOST_FROM_PHONE=10.0.2.2

# --- desktop namespace ----------------------------------------------------------
ip netns del "$NS" 2>/dev/null
ip netns add "$NS"
ip -n "$NS" link set lo up
mkdir -p "/etc/netns/$NS" /tmp/etph-shim
echo "nameserver 8.8.8.8" >"/etc/netns/$NS/resolv.conf"
# adb inside the namespace: the real forward is made outside by this script.
cat >/tmp/etph-shim/adb <<'EOF'
#!/bin/sh
echo "$*" >> /tmp/etph-shim/adb-calls.log
case "$*" in devices*) printf 'List of devices attached\nemulator-5554\tdevice\n' ;; esac
exit 0
EOF
printf '#!/bin/sh\necho "$*" >> /tmp/etph-shim/resolvectl.log\n' >/tmp/etph-shim/resolvectl
chmod +x /tmp/etph-shim/adb /tmp/etph-shim/resolvectl
bridge_port() { # desktop-ns port -> this host's 127.0.0.1:port (the adb forward)
  rm -f "/tmp/etph-fwd-$1.sock"
  socat "UNIX-LISTEN:/tmp/etph-fwd-$1.sock,fork" "TCP:127.0.0.1:$1" &
  PIDS+=($!)
  ip netns exec "$NS" socat "TCP-LISTEN:$1,bind=127.0.0.1,fork,reuseaddr" "UNIX-CONNECT:/tmp/etph-fwd-$1.sock" &
  PIDS+=($!)
}
D() { ip netns exec "$NS" bash -c "$1"; }
client() { # name args...
  local name=$1
  shift
  ip netns exec "$NS" env PATH="/tmp/etph-shim:$PATH" RUST_LOG=info "$BIN" "$@" >"$LOG/$name.log" 2>&1 &
  CLIENT=$!
}
client_stop() { kill -INT "$CLIENT" 2>/dev/null; for _ in $(seq 1 100); do kill -0 "$CLIENT" 2>/dev/null || return 0; sleep 0.1; done; kill -9 "$CLIENT" 2>/dev/null; return 1; }
iface_up() { D 'ip -4 addr show etph0 2>/dev/null | grep -q 10.0.0.1'; }
wait_iface() { local n=$(($1 * 10)); for _ in $(seq 1 $n); do iface_up && return 0; sleep 0.1; done; return 1; }
wait_iface_gone() { local n=$(($1 * 10)); for _ in $(seq 1 $n); do D 'ip link show etph0 >/dev/null 2>&1' || return 0; sleep 0.1; done; return 1; }
BASE_ROUTES=$(D 'ip -4 route show | sort')
no_leftovers() {
  if iface_up || [ "$(D 'ip -4 route show | sort')" != "$BASE_ROUTES" ]; then fail "$1: TUN/route left behind"; else pass "$1: no TUN device or route left behind"; fi
}
traffic() { # label: the full real-internet check through the phone
  local code want got
  code=$(D "curl -sS -m 30 -o /dev/null -w '%{http_code}' https://example.com/")
  [ "$code" = 200 ] && pass "$1: public HTTPS https://example.com -> 200 through the phone" || fail "$1: HTTPS ($code)"
  D 'getent ahostsv4 example.org >/dev/null' && pass "$1: DNS through the phone" || fail "$1: DNS"
  want=$(cat "$SRV/big.sha256")
  got=$(D "curl -sS -m 180 http://$HOST_FROM_PHONE:8080/big.bin | sha256sum | cut -d' ' -f1")
  [ "$want" = "$got" ] && pass "$1: 50 MiB download through the phone, sha256 intact" || fail "$1: 50 MiB download (got $got)"
}
phone_own_http() { # the phone's own traffic works while it relays
  local r
  r=$(adb shell 'printf "GET / HTTP/1.0\r\nHost: example.com\r\n\r\n" | toybox nc -w 10 example.com 80 | head -1' 2>/dev/null | tr -d '\r')
  echo "$r" | grep -q " 200" && pass "$1: the phone's own HTTP works during the session ($r)" || fail "$1: phone's own connectivity ($r)"
  if adb shell ip link 2>/dev/null | grep -q "tun0"; then fail "$1: a tun0 (VPN) exists on the phone"; else pass "$1: no VPN/tun0 on the phone"; fi
}

if D "curl -sS -m 5 -o /dev/null https://example.com/" 2>/dev/null; then
  echo "FAIL: desktop namespace reaches the internet without the tunnel: test would prove nothing"; exit 1
fi
pass "isolation: the desktop namespace has no way out before tethering"

# --- upgrade: previous APK -> this build -----------------------------------------
HAS_WIFI=0
adb shell ip -4 addr show wlan0 2>/dev/null | grep -q "inet " && HAS_WIFI=1
if [ -n "$PREV_APK" ]; then
  adb uninstall "$PKG" >/dev/null 2>&1
  adb install "$PREV_APK" >"$LOG/install-prev.log" 2>&1 || { cat "$LOG/install-prev.log"; fail "previous APK did not install"; }
  OLDV=$(adb shell dumpsys package $PKG | grep -m1 versionName | tr -d ' \r')
  launch; tap radioWifi; sleep 1
  TOK1=$(text_of wifiIpText | grep -o 'Auth Token: [^ ]*' | head -1 | cut -d' ' -f3)
  if adb install -r "$APK" >"$LOG/install-up.log" 2>&1; then
    NEWV=$(adb shell dumpsys package $PKG | grep -m1 versionName | tr -d ' \r')
    launch; tap radioWifi; sleep 1
    TOK2=$(text_of wifiIpText | grep -o 'Auth Token: [^ ]*' | head -1 | cut -d' ' -f3)
    pass "upgrade in place: $OLDV -> $NEWV installed over the previous release (adb install -r)"
    if [ "$HAS_WIFI" = 1 ]; then
      [ -n "$TOK1" ] && [ "$TOK1" = "$TOK2" ] && pass "upgrade kept the pairing token (saved desktops keep working)" \
        || fail "upgrade changed/lost the pairing token ('$TOK1' -> '$TOK2')"
    fi
  else
    cat "$LOG/install-up.log"
    fail "this build does not install over the previous release (users could not upgrade)"
    adb install "$APK" >/dev/null 2>&1
  fi
else
  adb install -r "$APK" >"$LOG/install.log" 2>&1 || { cat "$LOG/install.log"; echo "FAIL: install"; exit 1; }
fi
[ "$API" -ge 33 ] && adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb logcat -c

# --- USB, started from the UI ----------------------------------------------------
start_mode radioUsb
if wait_log "USB server listening" 20; then
  pass "USB mode started from the UI (foreground service listening on loopback)"
else
  adb logcat -d | tail -40
  fail "USB server did not start"
fi
adb forward tcp:8888 tcp:8888 >/dev/null
bridge_port 8888
sleep 1
client usb usb --interface etph0 --reconnect --max-retries 0
if wait_iface 30; then
  pass "desktop tunnel up over the real app (USB via adb forward)"
  traffic "USB"
  D "cd /tmp && rm -rf par && mkdir par && cd par && for i in \$(seq -w 0 29); do curl -sS -m 120 -o p\$i http://$HOST_FROM_PHONE:8080/p\$i & done; wait; cp $SRV/par.sha256 . && sha256sum -c par.sha256 >/tmp/par.check 2>&1"
  [ "$(grep -c ': OK$' /tmp/par.check)" = 30 ] && pass "USB: 30 parallel downloads, all intact" || fail "USB parallel ($(grep -c ': OK$' /tmp/par.check)/30)"
  head -c 16777216 /dev/urandom >/tmp/up.bin
  UP=$(D "socat -t 60 -T 60 - TCP:$HOST_FROM_PHONE:7002,shut-down </tmp/up.bin")
  [ "$UP" = "$(sha256sum </tmp/up.bin | cut -d' ' -f1)" ] && pass "USB: 16 MiB upload through the phone, sha256 intact at the far end" || fail "USB upload ($UP)"
  U=$(D "echo udp-through-the-app | socat -t 3 - UDP:$HOST_FROM_PHONE:7001")
  [ "$U" = udp-through-the-app ] && pass "USB: UDP relayed by the app" || fail "USB: UDP ($U)"
  phone_own_http "USB"
  ST=$(text_of statusText)
  echo "$ST" | grep -q "Desktop connected (USB" && pass "UI status: '$ST'" || fail "UI status ('$ST')"
  SX=$(text_of statsText)
  echo "$SX" | grep -Eq "[0-9.]+ ?(MB|GB)" && pass "UI live stats show the relayed traffic: $(echo "$SX" | tr '\n' ' ' | cut -c1-120)" || fail "UI stats ('$SX')"

  # --- the app is killed mid-transfer, then relaunched ---------------------------
  ip netns exec "$NS" curl -sS -m 300 -o /dev/null "http://$HOST_FROM_PHONE:8080/big.bin" 2>/dev/null &
  sleep 1
  T0=$(now_ms)
  adb shell am force-stop $PKG
  wait_iface_gone 15 && pass "app killed: desktop removed the tunnel in $(( ($(now_ms) - T0) / 1000 ))s" || fail "app killed: tunnel still up"
  no_leftovers "app killed"
  [ "$(service_count)" = 0 ] && pass "app killed: no service left running on the phone" || fail "app killed: service still running"
  sleep 3
  kill -0 "$CLIENT" 2>/dev/null && pass "app killed: desktop keeps retrying" || fail "app killed: desktop client exited"
  adb logcat -c
  start_mode radioUsb
  wait_log "USB server listening" 20
  adb forward tcp:8888 tcp:8888 >/dev/null
  T0=$(now_ms)
  if wait_iface 60; then
    pass "app relaunched + Start: desktop reconnected by itself in $(( ($(now_ms) - T0) / 1000 ))s"
    CODE=$(D "curl -sS -m 30 -o /dev/null -w '%{http_code}' https://example.com/")
    [ "$CODE" = 200 ] && pass "after relaunch: public HTTPS 200" || fail "after relaunch: HTTPS ($CODE)"
  else
    tail -20 "$LOG/usb.log"
    fail "no reconnect after the app was relaunched"
  fi

  # --- Stop button ------------------------------------------------------------------
  launch
  T0=$(now_ms)
  tap stopButton
  wait_log "TetherService destroyed" 15 && pass "Stop button: service destroyed" || fail "Stop button: service not destroyed"
  wait_iface_gone 15 && pass "Stop button: desktop tunnel ended in $(( ($(now_ms) - T0) / 1000 ))s" || fail "Stop: desktop tunnel still up"
  no_leftovers "Stop button"
  [ "$(service_count)" = 0 ] && pass "Stop button: no service left" || fail "Stop: service still running"
else
  tail -30 "$LOG/usb.log"
  adb logcat -d -s ETPhoneHome:* | tail -30
  fail "no tunnel over the real app (USB)"
fi
client_stop

# --- WiFi + TLS (Bouncy Castle on ART) --------------------------------------------
if [ "$HAS_WIFI" = 1 ]; then
  adb logcat -c
  launch
  tap radioWifi
  sleep 1
  TOKEN=$(text_of wifiIpText | grep -o 'Auth Token: [^ ]*' | head -1 | cut -d' ' -f3)
  start_mode radioWifi
  if [ -n "$TOKEN" ] && wait_log "WiFi server listening" 20; then
    adb forward tcp:18888 tcp:8888 >/dev/null
    bridge_port 18888
    sleep 1
    client wifi wifi --host 127.0.0.1 --port 18888 --token "$TOKEN" --interface etph0
    if wait_iface 30; then
      wait_log "TLS handshake complete: TLSv1.3" 5 && pass "WiFi: TLS 1.3 session with the app (logcat: $(adb logcat -d -s ETPhoneHome:* | grep -o 'TLS handshake complete: [^ ]* [^ ]*' | tail -1))" \
        || fail "WiFi: no TLS 1.3 on the phone"
      grep -q "ENCRYPTED" "$LOG/wifi.log" && pass "WiFi: desktop reports ENCRYPTED" || fail "WiFi: desktop not encrypted"
      traffic "WiFi+TLS"
      phone_own_http "WiFi+TLS"
      ST=$(text_of statusText)
      echo "$ST" | grep -q "encrypted" && pass "UI status: '$ST'" || fail "UI WiFi status ('$ST')"
    else
      tail -20 "$LOG/wifi.log"
      fail "no WiFi+TLS tunnel to the real app"
    fi
    client_stop
    wait_iface_gone 10
    no_leftovers "WiFi Ctrl-C"
    wait_log "Desktop requested disconnect" 10 && pass "WiFi Ctrl-C: the app was told (Disconnect)" || fail "WiFi Ctrl-C: no Disconnect seen by the app"
    # A wrong token never gets a tunnel.
    if ip netns exec "$NS" env PATH="/tmp/etph-shim:$PATH" timeout 30 "$BIN" wifi --host 127.0.0.1 --port 18888 --token WrongToken0 --interface etph0 >"$LOG/wifi-bad.log" 2>&1; then
      fail "WiFi: wrong token connected"
    else
      grep -q "TLS handshake failed" "$LOG/wifi-bad.log" && ! iface_up && pass "WiFi: wrong token fails closed (TLS pin mismatch), no TUN" || { tail -5 "$LOG/wifi-bad.log"; fail "WiFi wrong token message"; }
    fi
  else
    fail "WiFi mode did not start (token='$TOKEN')"
  fi
else
  echo "info: [API $API] this image has no emulated WiFi: WiFi checks run on the image that has it"
fi

adb logcat -d >"$LOG/logcat.txt" 2>&1
echo
echo "emulator API $API summary: $PASS passed, $FAIL failed"
for f in "${FAILED[@]}"; do echo "  failed: $f"; done
[ "$FAIL" -eq 0 ]
