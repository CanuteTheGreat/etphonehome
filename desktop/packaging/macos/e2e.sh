#!/usr/bin/env bash
# ETPH on a real Mac (GitHub-hosted macOS runner, Apple Silicon or Intel):
# install the .pkg, then connect to the phone's networking core (standin)
# on this machine and use the internet through it.
#
#   e2e.sh <etph-macos-*.pkg> <standin install dir>
#
# The stand-in binds its relay sockets to the Mac's real address
# (--bind-address). macOS scopes a socket bound to an address to that
# address's interface, and a scoped lookup for en0 only sees en0's scoped
# routes: the primary interface has no scoped default, so the tunnel's
# 0/1 + 128/1 won and the stand-in's sends failed with "No route to host"
# (every DNS query timed out). Test plumbing adds en0's scoped default
# (`route add -ifscope en0 default <gw>`, what macOS itself gives every
# non-primary service), so the stand-in's own traffic leaves through en0 and
# everything else on the Mac goes through utun. On a real setup the phone is
# another device and none of this is needed.
set -uo pipefail
PKG=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
STANDIN=$2
TOKEN=MacE2eTok
W=$(mktemp -d)
PASS=0
FAIL=0
pass() { echo "PASS: $*"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $*"; FAIL=$((FAIL + 1)); }
cleanup() {
  sudo pkill -INT -f 'etphonehome wifi' 2>/dev/null
  [ -n "${SP:-}" ] && kill "$SP" 2>/dev/null
  [ -n "${SCOPED:-}" ] && sudo route -n delete -ifscope "$DEFIF" default >/dev/null 2>&1
  true
}
diag() {
  echo "--- client log (tail)"; tail -25 "$W/cli.log" 2>/dev/null
  echo "--- stand-in log (tail)"; tail -25 "$W/standin.log" 2>/dev/null
  echo "--- scoped lookup for the stand-in"; route -n get -ifscope "$DEFIF" 8.8.8.8 2>&1 | head -8
  netstat -rn -f inet | head -24
}
trap cleanup EXIT
echo "macOS $(sw_vers -productVersion) $(uname -m)"

# ---- install --------------------------------------------------------------
if sudo installer -pkg "$PKG" -target / >"$W/install.log" 2>&1; then
  pass "installer -pkg ($(basename "$PKG"))"
else
  cat "$W/install.log"; fail "installer"
fi
lipo -info /usr/local/bin/etphonehome | grep -q 'x86_64 arm64\|arm64 x86_64' \
  && pass "universal binary (Apple Silicon + Intel)" || fail "not universal: $(lipo -info /usr/local/bin/etphonehome)"
codesign -dv /Applications/ETPH.app 2>&1 | grep -q 'Signature=adhoc' \
  && pass "menu-bar app ad-hoc signed (no Developer ID configured)" || fail "app signature: $(codesign -dv /Applications/ETPH.app 2>&1)"
etphonehome --version | grep -q etphonehome && pass "CLI on PATH: $(etphonehome --version)" || fail "CLI"
for _ in $(seq 1 30); do [ -S /var/run/etphonehome.sock ] && break; sleep 1; done
sudo launchctl print system/solutions.errant.etph 2>/dev/null | grep -q 'state = running' \
  && pass "launchd runs the ETPH service as root" || fail "service not running: $(sudo launchctl print system/solutions.errant.etph 2>&1 | head -20; sudo tail -20 /Library/Logs/ETPH/service.log)"
ST=$(etphonehome ctl status --json 2>&1)
echo "$ST" | grep -q '"state":"idle"' && pass "unprivileged ctl talks to the service (idle)" || fail "ctl status: $ST"
etphonehome usb-list --json | grep -q '^\[' && pass "usb-list enumerates USB through IOKit/libusb" || fail "usb-list"

# ---- the phone ------------------------------------------------------------
DEFIF=$(route -n get default | awk '/interface:/{print $2}')
GW=$(route -n get default | awk '/gateway:/{print $2}')
IP=$(ipconfig getifaddr "$DEFIF")
echo "Mac address for the stand-in's own traffic: $IP ($DEFIF, gateway $GW)"
# Test plumbing only (see the top): en0's scoped default for the stand-in.
if sudo route -n add -ifscope "$DEFIF" default "$GW" >/dev/null 2>&1; then
  SCOPED=1; echo "scoped default via $GW on $DEFIF for the stand-in's own traffic"
else
  echo "could not add a scoped default on $DEFIF:"; netstat -rn -f inet | grep default
fi
"$STANDIN/bin/standin" --listen 127.0.0.1:9301 --token "$TOKEN" --require-tls --stats-log --stats-secs 2 \
  --bind-address "$IP" >"$W/standin.log" 2>&1 &
SP=$!
for _ in $(seq 1 100); do grep -q "listening on" "$W/standin.log" && break; sleep 0.2; done

# ---- CLI session ----------------------------------------------------------
sudo env ETPH_TOKEN="$TOKEN" etphonehome wifi --host 127.0.0.1 --port 9301 >"$W/cli.log" 2>&1 &
for _ in $(seq 1 60); do grep -q "Tethering connection established" "$W/cli.log" && break; sleep 1; done
if grep -q "Tethering connection established" "$W/cli.log" && grep -q "TLS handshake complete" "$W/cli.log"; then
  pass "Wi-Fi session up (cert-pinned TLS 1.3)"
else
  tail -30 "$W/cli.log"; fail "session"
fi
IF=$(sed -n 's/.*Tethering connection established on \(utun[0-9]*\).*/\1/p' "$W/cli.log" | head -1)
ifconfig "$IF" 2>/dev/null | grep -q 'inet 10.0.0.1 --> 10.0.0.2' \
  && pass "utun interface $IF (kernel control socket, no kext): 10.0.0.1 -> 10.0.0.2" || fail "utun: $(ifconfig "$IF" 2>&1)"
R1=$(route -n get 1.1.1.1 | awk '/interface:/{print $2}')
R2=$(route -n get 200.1.1.1 | awk '/interface:/{print $2}')
[ "$R1" = "$IF" ] && [ "$R2" = "$IF" ] && pass "0/1 + 128/1 routes through $IF" || fail "routes: $R1 $R2"
printf 'show State:/Network/Service/etphonehome-%s/DNS\n' "$IF" | scutil | grep -q 8.8.8.8 \
  && pass "scutil: DNS service for $IF published with the phone's DNS server" || fail "scutil DNS key missing"
if scutil --dns | grep -q 'nameserver\[[0-9]*\] : 8.8.8.8'; then
  echo "INFO: scutil --dns lists 8.8.8.8 as a resolver"
else
  echo "INFO: scutil --dns does not list 8.8.8.8:"; scutil --dns | sed -n 1,30p
fi
B0=$(netstat -ibn -I "$IF" | awk 'NR==2{print $(NF-4)}')
if curl -sS -m 30 https://example.com | grep -q "Example Domain"; then pass "HTTPS through the phone"; else fail "curl"; diag; fi
B1=$(netstat -ibn -I "$IF" | awk 'NR==2{print $(NF-4)}')
[ "${B1:-0}" -gt "${B0:-0}" ] && pass "the request really crossed $IF ($B0 -> $B1 bytes in)" || fail "no bytes on $IF ($B0 -> $B1)"
dscacheutil -q host -a name example.org | grep -q ip_address && pass "name resolution while tethered" || fail "dscacheutil"
curl -sS -m 60 -o "$W/5m" 'https://speed.cloudflare.com/__down?bytes=5000000' && [ "$(stat -f %z "$W/5m")" = 5000000 ] \
  && pass "5 MB download through the phone" || fail "5 MB download"
sudo pkill -INT -f 'etphonehome wifi'
sleep 4
ifconfig "$IF" >/dev/null 2>&1 && fail "$IF still exists after Ctrl-C" || pass "Ctrl-C removes $IF and its routes"
printf 'show State:/Network/Service/etphonehome-%s/DNS\n' "$IF" | scutil | grep -q 'No such key' \
  && pass "scutil DNS entry removed" || fail "DNS entry left behind"
grep -q "Disconnect\|session over" "$W/standin.log" && pass "the phone saw a clean disconnect" || fail "no disconnect on the phone"

# ---- the menu-bar service path --------------------------------------------
CFG="/Library/Application Support/ETPH/config.toml"
sudo etphonehome --config "$CFG" save-profile ci -t wifi --host 127.0.0.1 --port 9301 --token "$TOKEN" >/dev/null \
  && pass "saved a phone for the service" || fail "save-profile"
etphonehome ctl connect ci >/dev/null 2>&1
for _ in $(seq 1 40); do etphonehome ctl status --json | grep -q '"state":"connected"' && break; sleep 1; done
ST=$(etphonehome ctl status --json)
echo "$ST" | grep -q '"state":"connected"' && echo "$ST" | grep -q '"phone_pro":true' \
  && pass "service connected (status shows the phone and ETPH Pro)" || fail "service connect: $ST $(sudo tail -20 /Library/Logs/ETPH/service.log)"
curl -sS -m 30 https://example.com | grep -q "Example Domain" && pass "HTTPS through the service's session" || fail "curl via service"
etphonehome ctl disconnect >/dev/null
sleep 2
etphonehome ctl status --json | grep -q '"state":"idle"' && pass "ctl disconnect" || fail "disconnect"
pgrep -f ETPH.app/Contents/MacOS/etph-tray >/dev/null && pass "menu-bar app running for the logged-in user" \
  || { open -a /Applications/ETPH.app; sleep 5; pgrep -f etph-tray >/dev/null && pass "menu-bar app starts" || fail "menu-bar app"; }

# ---- uninstall ------------------------------------------------------------
sudo etph-uninstall >/dev/null && [ ! -e /usr/local/bin/etphonehome ] && [ ! -e /Applications/ETPH.app ] \
  && ! sudo launchctl print system/solutions.errant.etph >/dev/null 2>&1 \
  && pass "etph-uninstall removes app, CLI and service" || fail "uninstall"

echo "macOS e2e summary: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
