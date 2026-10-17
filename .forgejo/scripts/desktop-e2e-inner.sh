#!/usr/bin/env bash
# End-to-end scenarios for the desktop client, run as root inside a
# container that has CAP_NET_ADMIN and /dev/net/tun (see desktop-e2e.sh).
#
# The Android side is played by `testpeer` (same wire protocol, same
# cert-pinned TLS, answers ICMP echo). The client under test is the real
# release `etphonehome` binary with a real TUN device; "proof" in every
# scenario is traffic actually crossing the tunnel (ping replies coming back
# from 10.0.0.2), not just a process starting.
set -euo pipefail

BIN=/e2e/bin
export PATH="$BIN:/e2e/shim:$PATH"
export RUST_LOG=info
LOGS=/e2e/logs
mkdir -p "$LOGS"
PASS=0
FAIL=0

pass() { echo "PASS: $*"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $*"; FAIL=$((FAIL + 1)); }

wait_for_iface() {
  for _ in $(seq 1 100); do
    ip link show "$1" >/dev/null 2>&1 && ip -4 addr show "$1" | grep -q 10.0.0.1 && return 0
    sleep 0.1
  done
  return 1
}

wait_gone() {
  for _ in $(seq 1 100); do
    ip link show "$1" >/dev/null 2>&1 || return 0
    sleep 0.1
  done
  return 1
}

stop() { kill -INT "$1" 2>/dev/null || true; wait "$1" 2>/dev/null || true; }

dump() {
  echo "----- $1 -----"
  cat "$LOGS/$1" || true
}

# --- 0. privileged unit tests that `cargo test` skips -------------------
if /e2e/bin/common-tests --ignored --test-threads=1 tun_interface 2>&1 | tee "$LOGS/tun-tests.log" | grep -q "test result: ok. 2 passed"; then
  pass "TUN device unit tests (2, normally #[ignore]d) ran with CAP_NET_ADMIN"
else
  dump tun-tests.log; fail "TUN device unit tests"
fi

# --- 1. WiFi, plaintext + token: ping through the tunnel ------------------
testpeer --listen 127.0.0.1:8888 --token PlainTok123 --keepalive-secs 1 >"$LOGS/peer1.log" 2>&1 &
PEER=$!
sleep 0.5
etphonehome wifi --host 127.0.0.1 --port 8888 --token PlainTok123 --interface etph0 >"$LOGS/client1.log" 2>&1 &
CLI=$!
if wait_for_iface etph0 && ping -c 3 -W 2 -I etph0 10.0.0.2 >"$LOGS/ping1.log" 2>&1; then
  pass "wifi plaintext: 3/3 pings answered through etph0"
else
  dump client1.log; dump peer1.log; dump ping1.log; fail "wifi plaintext ping"
fi
sleep 1.5 # let a StatsUpdate/Keepalive arrive while connected
stop $CLI
if grep -q "client sent Disconnect" "$LOGS/peer1.log"; then
  pass "Ctrl-C sends Disconnect to the phone"
else
  dump peer1.log; fail "Ctrl-C Disconnect"
fi
grep -q "answered ICMP echo request #3" "$LOGS/peer1.log" && pass "peer saw 3 echo requests" || fail "peer echo count"
wait_gone etph0 && pass "TUN interface removed on exit" || fail "etph0 left behind"
kill $PEER; wait $PEER 2>/dev/null || true

# --- 2. wrong token is rejected with the phone's reason --------------------
testpeer --listen 127.0.0.1:8888 --token RightTok >"$LOGS/peer2.log" 2>&1 &
PEER=$!
sleep 0.5
if etphonehome wifi --host 127.0.0.1 --port 8888 --token WrongTok --interface etph0 >"$LOGS/client2.log" 2>&1; then
  fail "wrong token connected"
elif grep -q "invalid token" "$LOGS/client2.log" && ! ip link show etph0 >/dev/null 2>&1; then
  pass "wrong token refused with 'invalid token', no TUN created"
else
  dump client2.log; fail "wrong token error message"
fi
kill $PEER; wait $PEER 2>/dev/null || true

# --- 3. cert-pinned TLS: right token pings, wrong token fails closed ------
testpeer --listen 127.0.0.1:8889 --tls --token TlsTok456 >"$LOGS/peer3.log" 2>&1 &
PEER=$!
sleep 0.5
etphonehome wifi --host 127.0.0.1 --port 8889 --token TlsTok456 --tls --interface etph0 >"$LOGS/client3.log" 2>&1 &
CLI=$!
if wait_for_iface etph0 && ping -c 3 -W 2 -I etph0 10.0.0.2 >"$LOGS/ping3.log" 2>&1 \
  && grep -q "TLS handshake complete: protocol=Some(TLSv1_3)" "$LOGS/peer3.log" \
  && grep -q "ENCRYPTED" "$LOGS/client3.log"; then
  pass "wifi TLS 1.3: 3/3 pings answered through the encrypted tunnel"
else
  dump client3.log; dump peer3.log; fail "wifi TLS ping"
fi
stop $CLI
wait_gone etph0 || true
if etphonehome wifi --host 127.0.0.1 --port 8889 --token NotTheTok --tls --interface etph0 >"$LOGS/client3b.log" 2>&1; then
  fail "TLS with wrong pairing token connected"
elif grep -q "TLS handshake failed" "$LOGS/client3b.log"; then
  pass "TLS with wrong pairing token fails closed"
else
  dump client3b.log; fail "TLS wrong token message"
fi
kill $PEER; wait $PEER 2>/dev/null || true

# --- 4. USB mode (adb shim) + setup-adb + discover-ip ---------------------
# The stand-in listens on the "phone's" loopback (127.0.0.2, see the adb shim
# in desktop-e2e.sh), so the tunnel only comes up if the client forwards
# computer -> phone (`adb forward`), like the real app's loopback server.
: >/e2e/adb-calls.log
testpeer --listen 127.0.0.2:8890 >"$LOGS/peer4.log" 2>&1 &
PEER=$!
sleep 0.5
etphonehome usb --port 8890 --interface etph0 >"$LOGS/client4.log" 2>&1 &
CLI=$!
if wait_for_iface etph0 && ping -c 2 -W 2 -I etph0 10.0.0.2 >/dev/null 2>&1 \
  && grep -qx "forward tcp:8890 tcp:8890" /e2e/adb-calls.log; then
  pass "usb: ran 'adb forward tcp:8890 tcp:8890' and pinged the phone stand-in through the tunnel"
else
  dump client4.log; cat /e2e/adb-calls.log; fail "usb mode"
fi
stop $CLI
wait_gone etph0 || true
kill $PEER; wait $PEER 2>/dev/null || true
if etphonehome setup-adb --port 9999 >/dev/null 2>&1 && grep -qx "forward tcp:9999 tcp:9999" /e2e/adb-calls.log; then
  pass "setup-adb runs adb forward with the given port"
else
  fail "setup-adb"
fi
if etphonehome discover-ip 2>&1 | grep -q "Android WiFi IP: 192.168.43.17"; then
  pass "discover-ip parses the phone's wlan0 address"
else
  fail "discover-ip"
fi

# --- 5. profile with a filter rule: ICMP blocked, UDP still forwarded -----
CFG=/e2e/config.toml
cat >"$CFG" <<'EOF'
[default]
auto_reconnect = false
interface = "etph0"

[auth]
wifi_token = "ProfileTok"

[[profiles]]
name = "filtered"
type = "wifi"
host = "127.0.0.1"
port = 8891

[profiles.filter]
enabled = true
mode = "blacklist"
log_blocked = true

[[profiles.filter.rules]]
id = "no-ping"
action = "block"
protocol = "icmp"
EOF
testpeer --listen 127.0.0.1:8891 --token ProfileTok >"$LOGS/peer5.log" 2>&1 &
PEER=$!
sleep 0.5
etphonehome --config "$CFG" connect filtered >"$LOGS/client5.log" 2>&1 &
CLI=$!
if wait_for_iface etph0; then
  if ping -c 2 -W 1 -I etph0 10.0.0.2 >/dev/null 2>&1; then
    fail "filter: blocked ICMP got through"
  else
    pass "filter: ICMP blocked by profile rule"
  fi
  echo -n "udp-through-filter" >/dev/udp/10.0.0.2/9999 || true
  sleep 1
  if grep -q "udp-through-filter" "$LOGS/peer5.log" && ! grep -q "answered ICMP" "$LOGS/peer5.log"; then
    pass "filter: non-matching UDP still forwarded, no ICMP reached the phone"
  else
    dump peer5.log; fail "filter selectivity"
  fi
else
  dump client5.log; fail "profile connect"
fi
stop $CLI
wait_gone etph0 || true
kill $PEER; wait $PEER 2>/dev/null || true

# --- 6. auto-reconnect after the phone disconnects ------------------------
testpeer --listen 127.0.0.1:8892 --token RecTok --disconnect-after 2 >"$LOGS/peer6.log" 2>&1 &
PEER=$!
sleep 0.5
etphonehome wifi --host 127.0.0.1 --port 8892 --token RecTok --interface etph0 --reconnect --max-retries 5 >"$LOGS/client6.log" 2>&1 &
CLI=$!
wait_for_iface etph0 || true
ping -c 8 -i 0.5 -W 1 10.0.0.2 >"$LOGS/ping6.log" 2>&1 || true
CONNS=$(grep -c "HandshakeReq" "$LOGS/peer6.log" || true)
if [ "${CONNS:-0}" -ge 2 ] && grep -q "Reconnecting in" "$LOGS/client6.log"; then
  pass "auto-reconnect: phone disconnected, client reconnected ($CONNS handshakes)"
else
  dump client6.log; dump peer6.log; fail "auto-reconnect"
fi
stop $CLI
wait_gone etph0 || true
kill $PEER; wait $PEER 2>/dev/null || true

# --- 7. no reconnect without --reconnect ----------------------------------
testpeer --listen 127.0.0.1:8893 --token OnceTok --disconnect-after 1 >"$LOGS/peer7.log" 2>&1 &
PEER=$!
sleep 0.5
etphonehome wifi --host 127.0.0.1 --port 8893 --token OnceTok --interface etph0 >"$LOGS/client7.log" 2>&1 &
CLI=$!
wait_for_iface etph0 || true
ping -c 3 -i 0.5 -W 1 10.0.0.2 >/dev/null 2>&1 || true
sleep 2
if ! kill -0 $CLI 2>/dev/null && [ "$(grep -c HandshakeReq "$LOGS/peer7.log")" -eq 1 ]; then
  pass "without --reconnect the client exits after the phone disconnects"
else
  dump client7.log; fail "reconnect should be off by default"
fi
stop $CLI
kill $PEER; wait $PEER 2>/dev/null || true

# --- 8. scripts/quick-start-linux.sh drives a USB session end to end -------
: >/e2e/adb-calls.log
testpeer --listen 127.0.0.2:8888 >"$LOGS/peer8.log" 2>&1 &
PEER=$!
sleep 0.5
mkdir -p /e2e/qs/desktop/target/release
cp /e2e/bin/etphonehome /e2e/qs/desktop/target/release/etphonehome
(echo "" | bash /e2e/qs/scripts/quick-start-linux.sh >"$LOGS/quickstart.log" 2>&1; echo "exit=$?" >>"$LOGS/quickstart.log") &
QS=$!
if wait_for_iface etph0 && ping -c 2 -W 2 -I etph0 10.0.0.2 >/dev/null 2>&1; then
  pass "quick-start-linux.sh: set up adb forward and brought the tunnel up"
else
  dump quickstart.log; fail "quick-start-linux.sh tunnel"
fi
pkill -INT -x etphonehome || true
wait $QS 2>/dev/null || true
if grep -qx "forward tcp:8888 tcp:8888" /e2e/adb-calls.log && grep -qx "forward --remove tcp:8888" /e2e/adb-calls.log \
  && grep -q "exit=0" "$LOGS/quickstart.log"; then
  pass "quick-start-linux.sh: removed the adb forward on Ctrl-C and exited 0"
else
  dump quickstart.log; cat /e2e/adb-calls.log; fail "quick-start-linux.sh cleanup"
fi
kill $PEER; wait $PEER 2>/dev/null || true

echo "e2e summary: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
