#!/bin/bash
# Real phone as the WAN of a real OpenWrt router over USB, without the owner's hardware:
# the official OpenWrt arm64 rootfs (armsr-armv8) runs in an isolated, capped container on
# crystalcave with the router packages installed, procd/netifd running, and the test phone
# (SM-N986U on crystalcave's USB) passed through. The router's only way to the internet is the
# phone: the container's own uplink (a private docker network, used only to install packages)
# is taken down before the test.
#
#   with-ssherpa-phone --wait 600 -- bash router-usb-realphone.sh <outdir> <pkgdir> [apk]
#
#   pkgdir  etph-*_aarch64_generic.apk (+ luci-app-etph-*_noarch.apk) from the openwrt-packages
#           CI artifact or a release; or BIN=<static aarch64 etphonehome> to test a fresh build
#           on top of the packages (the files in openwrt/etph/files are copied in too).
#   apk     optional ETPH APK to install first (a -PbillingTestHooks=true build can be made Pro:
#           routers need ETPH Pro on the phone).
#   MODES   "aoa adb" (default): the accessory link (no USB debugging) and the ADB route.
#
# Isolation: own network namespace; the docker network is 192.168.237.0/24 and the tunnel
# 192.168.238.0/24 (never 10/8 or 172.31/16, which crystalcave routes to the RedWeek VPN);
# crystalcave's own routes are not changed (the docker network only adds its /24). adb on crystalcave is
# paused only for the ADB-route test (the router has to claim the phone's adb interface) and the
# router uses crystalcave's already-authorized adb key, so no "Allow USB debugging?" tap is needed.
set -u
OUT=$(readlink -f "${1:?outdir}"); mkdir -p "$OUT"
PKGDIR=$(readlink -f "${2:?pkgdir}")
APK=${3:-}
MODES=${MODES:-aoa adb}
export ANDROID_SERIAL=${ANDROID_SERIAL:-R5CR70PMMQR}
PKG=${PKG:-solutions.errant.etph}
IMAGE=${IMAGE:-openwrt/rootfs:armsr-armv8-25.12.6}
CT=etph-rt-usb
NET=etph-rt-net
DL_BYTES=${DL_BYTES:-25000000}
PASS=0; FAIL=0
pass() { echo "PASS: $*"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $*"; FAIL=$((FAIL + 1)); }
X() { docker exec "$CT" sh -c "$1"; }

dump() { adb shell uiautomator dump /sdcard/etph-ui.xml >/dev/null 2>&1; adb pull /sdcard/etph-ui.xml "$OUT/ui.xml" >/dev/null 2>&1; adb shell rm -f /sdcard/etph-ui.xml; }
# node center + text by resource-id suffix (any package when $2 = any) or by text regex (T:<re>)
ui() { python3 - "$OUT/ui.xml" "$PKG" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[3]
for n in ET.parse(sys.argv[1]).getroot().iter("node"):
    rid = n.get("resource-id") or ""
    if want.startswith("T:"):
        ok = re.search(want[2:], n.get("text") or "", re.I) and n.get("clickable") == "true"
    elif want.startswith("R:"):
        ok = rid.endswith(want[2:])
    else:
        ok = n.get("package") == sys.argv[2] and rid.endswith(":id/" + want)
    if ok:
        a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds"))); print(f"{(a+c)//2} {(b+d)//2}|{n.get('text')}"); sys.exit(0)
sys.exit(1)
PY
}
tap() { local r; for _ in 1 2 3 4; do dump; r=$(ui "$1") && { adb shell input tap ${r%%|*}; return 0; }; adb shell input swipe 540 1600 540 1000 200; sleep 1; done; return 1; }
text_of() { dump; ui "$1" | cut -d'|' -f2-; }
screen_text() { dump; python3 -c "
import sys, xml.etree.ElementTree as ET
print(' | '.join(t for t in (n.get('text') for n in ET.parse('$OUT/ui.xml').getroot().iter('node')) if t))"; }
launch() { adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1; adb shell am start -W -n $PKG/com.etphonehome.MainActivity >/dev/null; sleep 2; }

cleanup() {
  echo "--- cleanup"
  docker rm -f "$CT" >/dev/null 2>&1
  docker network rm "$NET" >/dev/null 2>&1
  adb start-server >/dev/null 2>&1
  for _ in $(seq 20); do adb get-state >/dev/null 2>&1 && break; sleep 1; done
  tap stopButton >/dev/null 2>&1
  # Leave accessory mode: back to the phone's normal USB functions.
  adb shell svc usb resetUsbGadget >/dev/null 2>&1 || true
  sleep 3; adb wait-for-device
  adb shell ip link | grep -q tun0 && echo "phone has tun0!" || echo "phone: no tun0"
  echo "RESULT: $PASS passed, $FAIL failed"
}
trap cleanup EXIT

port_of_phone() {
  for d in /sys/bus/usb/devices/*; do
    [ -f "$d/serial" ] && [ "$(cat "$d/serial")" = "$ANDROID_SERIAL" ] && { basename "$d"; return; }
  done
}

echo "--- phone"
adb get-state >/dev/null || { echo "phone $ANDROID_SERIAL not on adb"; exit 1; }
if [ -n "$APK" ]; then
  r=$(adb install -r "$APK" 2>&1); echo "$r" | tail -1
  if echo "$r" | grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE; then adb uninstall $PKG >/dev/null; adb install "$APK" | tail -1; fi
fi
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
# Routers need ETPH Pro on the phone; test builds take a Pro override.
adb shell am broadcast -n "$PKG/com.etphonehome.billing.TestHookReceiver" --es pro true >/dev/null 2>&1
echo "app: $(adb shell dumpsys package $PKG | grep -m1 versionName | tr -d ' ')"
echo "phone USB port on crystalcave: $(port_of_phone)"

echo "--- router container ($IMAGE)"
docker rm -f "$CT" >/dev/null 2>&1; docker network rm "$NET" >/dev/null 2>&1
docker network create --subnet 192.168.237.0/24 "$NET" >/dev/null
docker run -d --name "$CT" --hostname etph-usb-router \
  --memory=512m --memory-swap=512m --cpus=2 --pids-limit=256 \
  --cap-add NET_ADMIN --cap-add NET_RAW --device /dev/net/tun \
  -v /dev/bus/usb:/dev/bus/usb --device-cgroup-rule='c 189:* rmw' \
  --network "$NET" --ip 192.168.237.2 -v "$PKGDIR":/pkgs:ro --entrypoint /sbin/init "$IMAGE" >/dev/null || exit 1
sleep 8
# WAN for the package install only: static on the private docker network, metric 50.
X "uci set network.lan.device=lo; uci set network.lan.proto=static; uci set network.lan.ipaddr=127.0.0.1;
   uci set network.wan=interface; uci set network.wan.device=eth0; uci set network.wan.proto=static;
   uci set network.wan.ipaddr=192.168.237.2/24; uci set network.wan.gateway=192.168.237.1;
   uci add_list network.wan.dns=1.1.1.1; uci set network.wan.metric=50; uci commit network;
   /etc/init.d/network restart" >/dev/null 2>&1
for _ in $(seq 30); do X "ip -4 route show default | grep -q eth0" && break; sleep 1; done
X "apk update >/dev/null 2>&1; apk add curl kmod-tun >/dev/null 2>&1; apk add --allow-untrusted /pkgs/etph-*_aarch64_generic.apk >/dev/null 2>&1; ls /pkgs/luci-app-etph-*.apk >/dev/null 2>&1 && apk add --allow-untrusted /pkgs/luci-app-etph-*.apk >/dev/null 2>&1; true"
if [ -n "${BIN:-}" ]; then
  docker cp "$BIN" "$CT:/usr/sbin/etphonehome"
  tar -C "$(dirname "$0")/../openwrt/etph/files" -cf - . | docker exec -i "$CT" tar -C / -xf -
fi
V=$(X "etphonehome --version"); echo "router client: $V"
[ -n "$V" ] && pass "router: etph installed in OpenWrt $(X '. /etc/openwrt_release; echo $DISTRIB_RELEASE') ($V)" || { fail "etph not installed"; exit 1; }
# The router's adb key = crystalcave's (already allowed on the test phone). The package makes its
# own key in the background at install: let that finish first, or it overwrites ours.
for _ in $(seq 60); do X "pgrep -f adb-keygen >/dev/null" || break; sleep 2; done
X "mkdir -p /etc/etph/.android"; docker cp ~/.android/adbkey "$CT:/etc/etph/.android/adbkey"; docker cp ~/.android/adbkey.pub "$CT:/etc/etph/.android/adbkey.pub"
X "HOME=/etc/etph etphonehome adb-fingerprint 2>/dev/null | tail -1"
# The phone uplink, then cut the container's own uplink: from here on the phone is the only way out.
X "uci -q delete network.wan; uci -q delete network.wan6;
   uci set network.phone=interface; uci set network.phone.proto=etph; uci set network.phone.transport=usb;
   uci set network.phone.ipaddr=192.168.238.1/24; uci set network.phone.metric=10; uci set network.phone.auto=0;
   uci commit network; /etc/init.d/network restart" >/dev/null 2>&1
sleep 3
docker network disconnect "$NET" "$CT"
# dnsmasq runs in a ujail, which can't start inside a container: resolve straight through the
# tunnel (the phone relays DNS to 8.8.8.8 like dnsmasq would forward it on a real router).
X "rm -f /etc/resolv.conf; echo 'nameserver 8.8.8.8' > /etc/resolv.conf"
X "ip -4 route show default | grep -q . && echo STILL-ROUTED; ubus call network get_proto_handlers | grep -q '\"etph\"'" && pass "netifd has the etph protocol handler" || fail "netifd: no etph handler"
X "wget -q -T 5 -O /dev/null https://example.com" && fail "router still has internet without the phone" || pass "router has no internet before the phone"

run_mode() {
  local mode=$1 t0
  echo "=== USB link: $mode"
  launch; tap radioUsb; sleep 1
  st=$(text_of statusText | head -1)
  echo "$st" | grep -qi "waiting" || { tap startButton; sleep 3; }
  echo "phone: $(text_of statusText | head -1)"
  if [ "$mode" = adb ]; then
    # The router needs the phone's adb interface: no adb server may hold it meanwhile.
    for _ in $(seq 10); do adb kill-server >/dev/null 2>&1; sleep 1; pgrep -x adb >/dev/null || break; done
    pgrep -ax adb && echo "NOTE: an adb server is running anyway (another session?)"
  fi
  X "uci set network.phone.usb_link=$mode; uci commit network; ifup phone"
  sleep 1; X "ps w | grep -v grep | grep 'etphonehome --router' | head -1"
  t0=$(date +%s)
  if [ "$mode" = aoa ]; then
    # The phone switches to accessory mode; Android asks "Open ETPH ...?" (unless already allowed).
    for _ in $(seq 40); do
      sleep 2
      X "ifstatus phone | grep -q '\"up\": true'" && break
      dump
      # Android's own dialog (SystemUI), not our app: "Open ETPH to handle ...?" / "Allow ETPH ...?"
      if python3 - "$OUT/ui.xml" "$PKG" <<'PY'
import sys, xml.etree.ElementTree as ET
nodes = list(ET.parse(sys.argv[1]).getroot().iter("node"))
sys.exit(0 if any(n.get("package") not in (sys.argv[2], None) and "ETPH" in (n.get("text") or "") for n in nodes) else 1)
PY
      then
        S=$(screen_text); echo "PHONE DIALOG: $S" | tee -a "$OUT/aoa-dialog.txt"
        adb exec-out screencap -p > "$OUT/aoa-dialog-$(date +%s).png" 2>/dev/null
        r=$(ui "R:android:id/button1") || r=$(ui "T:^(OK|Allow|Open)$")
        [ -n "$r" ] && adb shell input tap "${r%%|*}" && echo "tapped ${r#*|}"
      fi
    done
    X "etphonehome usb-list" | tee "$OUT/usb-list-$mode.txt"
  else
    for _ in $(seq 40); do sleep 2; X "ifstatus phone | grep -q '\"up\": true'" && break; done
  fi
  X "cat /var/run/etph/phone.json" > "$OUT/status-$mode.json"
  if X "ifstatus phone | grep -q '\"up\": true'"; then
    pass "[$mode] netifd: proto etph up over USB to the real phone ($(( $(date +%s) - t0 ))s)"
  else
    fail "[$mode] uplink not up: $(X 'logread | grep -i etph | tail -12'; cat "$OUT/status-$mode.json")"
    X "ifdown phone"; [ "$mode" = adb ] && adb start-server; return
  fi
  X "ip -4 route show default; ip route get 1.1.1.1" | tee "$OUT/routes-$mode.txt"
  grep -q "dev etph-phone" "$OUT/routes-$mode.txt" && pass "[$mode] default route via the phone" || fail "[$mode] no route via the phone"
  T=$(X "curl -s -m 20 https://example.com | grep -o '<title>[^<]*'")
  [ "$T" = "<title>Example Domain" ] && pass "[$mode] HTTPS + DNS through the phone" || fail "[$mode] HTTPS: $T"
  X "ping -c 5 -W 3 1.1.1.1 | tail -1" | tee "$OUT/ping-$mode.txt"
  # Client CPU time per transferred MB (utime+stime ticks of etphonehome): the number that
  # decides the speed on slow router CPUs (a 775 MHz MIPS 24Kc does ~1/10 of this core).
  cpu() { X 'awk "{print \$14+\$15}" /proc/$(pidof etphonehome)/stat'; }
  c0=$(cpu)
  for i in 1 2; do
    X "curl -s -m 120 -o /dev/null -w '%{size_download} %{time_total} %{speed_download}\n' 'https://speed.cloudflare.com/__down?bytes=$DL_BYTES'" | tee -a "$OUT/speed-$mode.txt"
  done
  c1=$(cpu); hz=$(X "getconf CLK_TCK 2>/dev/null || echo 100")
  echo "client CPU: $(awk -v a="$c0" -v b="$c1" -v hz="$hz" -v n="$DL_BYTES" 'BEGIN{printf "%.3f s per 100 MB downloaded", (b-a)/hz/(2*n)*1e8}')" | tee -a "$OUT/speed-$mode.txt"
  X "head -c 5000000 /dev/urandom > /tmp/up.bin; curl -s -m 120 -o /dev/null -w 'up %{size_upload} %{time_total} %{speed_upload}\n' --data-binary @/tmp/up.bin https://speed.cloudflare.com/__up; rm -f /tmp/up.bin" | tee -a "$OUT/speed-$mode.txt"
  best=$(awk '$1 ~ /^[0-9]+$/ && $1 > 0 {print $3}' "$OUT/speed-$mode.txt" | sort -n | tail -1)
  [ -n "$best" ] && awk -v b="$best" 'BEGIN{exit !(b > 0)}' && pass "[$mode] download $(awk -v b="$best" 'BEGIN{printf "%.2f MB/s (%.1f Mbit/s)", b/1e6, b*8/1e6}')" || fail "[$mode] download failed"
  if [ "$mode" = aoa ]; then
    PS=$(text_of statusText | head -1); PD=$(text_of statsText | head -1)
    echo "phone during the session: $PS / $PD" | tee "$OUT/phone-ui-$mode.txt"
    echo "$PS $PD" | grep -q "Router" && echo "$PD" | grep -q "OpenWrt" && pass "[$mode] phone names the router: $PD" || fail "[$mode] phone UI: $PS / $PD"
  fi
  X "top -bn1 | grep -m1 etphonehome; grep -E 'VmRSS' /proc/\$(pidof etphonehome)/status" | tee "$OUT/rss-$mode.txt"
  grep -o '"device": "[^"]*"' "$OUT/status-$mode.json"
  X "ifdown phone"; sleep 2
  if [ "$mode" = adb ]; then adb start-server >/dev/null; for _ in $(seq 20); do adb get-state >/dev/null 2>&1 && break; sleep 1; done; fi
  ST=$(text_of statusText | head -1); D=$(text_of statsText | head -1)
  echo "phone after: $ST / $D"
}

for m in $MODES; do run_mode "$m"; done
