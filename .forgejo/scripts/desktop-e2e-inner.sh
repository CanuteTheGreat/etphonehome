#!/usr/bin/env bash
# End-to-end scenarios, run as root inside the `desktop` container (see
# desktop-e2e.sh): real release `etphonehome`, real TUN device, and the
# Android app's networking core (`standin`) as the phone. The desktop's only
# network is an internal one; everything it reaches beyond the phone has to
# go through the tunnel and the phone's userspace relay.
#
# Proof in every scenario is real traffic: public HTTPS, DNS, bulk TCP, UDP
# and ICMP through the tunnel -- not just a process starting.
set -uo pipefail

BIN=/e2e/bin
export PATH="/e2e/shim:$BIN:$PATH"
export RUST_LOG=info
LOGS=/e2e/logs
PEER=/e2e/peerlogs
mkdir -p "$LOGS"
PASS=0
FAIL=0
: "${PHONE_IP:?}" "${TARGET_IP:?}"

pass() { echo "PASS: $*"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $*"; FAIL=$((FAIL + 1)); }
dump() {
  echo "----- $1 -----"
  tail -n 60 "$1" 2>/dev/null || true
}

wait_for_iface() {
  for _ in $(seq 1 150); do
    ip -4 addr show "$1" 2>/dev/null | grep 10.0.0.1 >/dev/null && return 0
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

stop() {
  kill -INT "$1" 2>/dev/null || true
  wait "$1" 2>/dev/null || true
  wait_gone etph0 || true
}

handshakes() { grep -c "Handshake complete" "$PEER/$1.log" 2>/dev/null || true; }

# Name resolution as systemd-resolved would do it once the client has set
# DNS for etph0 (asserted below): queries to the phone's DNS server, which
# only the tunnel can reach.
echo "nameserver 8.8.8.8" >/etc/resolv.conf

# --- 0. privileged unit tests that `cargo test` skips -------------------
if /e2e/bin/common-tests --ignored --test-threads=1 tun_interface 2>&1 | tee "$LOGS/tun-tests.log" | grep "test result: ok. 2 passed" >/dev/null; then
  pass "TUN device unit tests (2, normally #[ignore]d) ran with CAP_NET_ADMIN"
else
  dump "$LOGS/tun-tests.log"
  fail "TUN device unit tests"
fi

# --- 1. without the tunnel the desktop has no way out ---------------------
if curl -sS -m 5 -o /dev/null "http://$TARGET_IP:8080/" 2>/dev/null || curl -sS -m 5 -o /dev/null https://example.com/ 2>/dev/null; then
  fail "desktop container reaches the network without the tunnel (test would prove nothing)"
else
  pass "isolation: before tethering the desktop cannot reach the target or the internet"
fi

# --- 2. WiFi as shipped (TLS by default): REAL INTERNET through the phone --
: >/e2e/resolvectl-calls.log
etphonehome wifi --host "$PHONE_IP" --port 9002 --token TlsTok456 --interface etph0 >"$LOGS/client-internet.log" 2>&1 &
CLI=$!
if wait_for_iface etph0; then
  pass "wifi (default TLS): tunnel up"
  sleep 1 # the client logs transport security / DNS right after creating etph0
  grep -q "ENCRYPTED (cert-pinned TLS 1.3)" "$LOGS/client-internet.log" \
    && grep -q "TLS handshake complete: TLSv1.3" "$PEER/wifi-tls.log" \
    && pass "wifi is encrypted by default: TLS 1.3 on both ends without --tls" \
    || { dump "$LOGS/client-internet.log"; dump "$PEER/wifi-tls.log"; fail "default TLS"; }
  grep -qx "dns etph0 8.8.8.8" /e2e/resolvectl-calls.log \
    && pass "client pointed DNS for etph0 at the phone's DNS server (resolvectl dns etph0 8.8.8.8)" \
    || { cat /e2e/resolvectl-calls.log; fail "DNS setup"; }
  ip route get 1.1.1.1 | grep "dev etph0" >/dev/null && pass "default route goes through etph0" || { ip route; fail "route"; }

  ping -c 3 -W 2 -I etph0 10.0.0.2 >"$LOGS/ping-phone.log" 2>&1 \
    && pass "ICMP: 3/3 pings to the phone (10.0.0.2) answered" || { dump "$LOGS/ping-phone.log"; fail "ping phone"; }

  if getent ahostsv4 example.com >"$LOGS/dns.log" 2>&1 && [ -s "$LOGS/dns.log" ]; then
    pass "DNS over UDP through the relay: example.com -> $(awk 'NR==1{print $1}' "$LOGS/dns.log")"
  else
    dump "$LOGS/dns.log"
    fail "DNS resolution through the tunnel"
  fi

  CODE=$(curl -sS -m 30 -o "$LOGS/example.html" -w '%{http_code}' https://example.com/ 2>"$LOGS/curl-https.err")
  if [ "$CODE" = "200" ] && grep -q "Example Domain" "$LOGS/example.html"; then
    pass "REAL INTERNET: HTTPS GET https://example.com/ -> 200 'Example Domain' through the phone"
  else
    dump "$LOGS/curl-https.err"
    fail "HTTPS to the public internet (code=$CODE)"
  fi
  CODE=$(curl -sS -m 30 -o /dev/null -w '%{http_code}' http://example.com/ 2>"$LOGS/curl-http.err")
  [ "$CODE" = "200" ] && pass "REAL INTERNET: HTTP GET http://example.com/ -> 200" \
    || { dump "$LOGS/curl-http.err"; fail "HTTP to the public internet (code=$CODE)"; }

  WANT=$(curl -sS -m 20 "http://$TARGET_IP:8080/blob.sha256")
  GOT=$(curl -sS -m 120 "http://$TARGET_IP:8080/blob.bin" | sha256sum | cut -d' ' -f1)
  if [ -n "$WANT" ] && [ "$WANT" = "$GOT" ]; then
    pass "TCP bulk: 5 MiB download through the relay, sha256 matches"
  else
    fail "5 MiB download (want=$WANT got=$GOT)"
  fi

  head -c 300000 /dev/urandom >/tmp/up.bin
  ECHO=$(socat -t 10 - "TCP:$TARGET_IP:7000,shut-down" </tmp/up.bin | sha256sum | cut -d' ' -f1)
  [ "$ECHO" = "$(sha256sum </tmp/up.bin | cut -d' ' -f1)" ] \
    && pass "TCP both ways: 300 KB echoed back intact (upload + download, half-close)" \
    || fail "TCP echo"

  UDP=$(echo "udp-through-phone-$$" | socat -t 3 - "UDP:$TARGET_IP:7001")
  [ "$UDP" = "udp-through-phone-$$" ] && pass "UDP: datagram relayed and the echo came back" \
    || fail "UDP echo (got '$UDP')"

  ping -c 2 -W 3 "$TARGET_IP" >"$LOGS/ping-target.log" 2>&1 \
    && pass "ICMP: ping to a host beyond the phone answered through the relay" \
    || { dump "$LOGS/ping-target.log"; fail "ping beyond the phone"; }

  OK=0
  for i in $(seq 1 20); do
    (curl -sS -m 30 -o /dev/null -w '%{http_code}\n' "http://$TARGET_IP:8080/blob.sha256" >"$LOGS/par-$i.out" 2>&1) &
  done
  wait_jobs=$(jobs -p | grep -v "^$CLI$")
  for j in $wait_jobs; do wait "$j" 2>/dev/null; done
  for i in $(seq 1 20); do grep -qx 200 "$LOGS/par-$i.out" && OK=$((OK + 1)); done
  [ "$OK" -eq 20 ] && pass "20 parallel TCP connections all succeeded" || fail "parallel connections ($OK/20)"

  sleep 1.5
  stop $CLI
  grep -q "Desktop requested disconnect" "$PEER/wifi-tls.log" && pass "Ctrl-C sends Disconnect to the phone" \
    || { dump "$PEER/wifi-tls.log"; fail "Ctrl-C Disconnect"; }
  wait_gone etph0 && pass "TUN interface removed on exit" || fail "etph0 left behind"
else
  dump "$LOGS/client-internet.log"
  dump "$PEER/wifi-tls.log"
  fail "wifi (default TLS) tunnel"
  stop $CLI
fi

# --- 3. the shipped app refuses plaintext WiFi with a clear reason ---------
if etphonehome wifi --host "$PHONE_IP" --port 9002 --token TlsTok456 --no-tls --interface etph0 >"$LOGS/client-plain-refused.log" 2>&1; then
  fail "plaintext connected to a phone that requires encryption"
elif grep -q "requires an encrypted connection" "$LOGS/client-plain-refused.log" && ! ip link show etph0 >/dev/null 2>&1; then
  pass "phone requiring encryption refuses --no-tls with its reason, no TUN created"
else
  dump "$LOGS/client-plain-refused.log"
  fail "plaintext refusal message"
fi

# --- 4. plaintext where the phone allows it; wrong token rejected ----------
etphonehome wifi --host "$PHONE_IP" --port 9001 --token PlainTok123 --no-tls --interface etph0 >"$LOGS/client-plain.log" 2>&1 &
CLI=$!
if wait_for_iface etph0 && curl -sS -m 20 -o /dev/null "http://$TARGET_IP:8080/blob.sha256" \
  && grep -q "PLAINTEXT" "$LOGS/client-plain.log"; then
  pass "wifi --no-tls (phone switch off): relayed HTTP works, reported as PLAINTEXT"
else
  dump "$LOGS/client-plain.log"
  fail "wifi plaintext"
fi
stop $CLI
if etphonehome wifi --host "$PHONE_IP" --port 9001 --token WrongTok --no-tls --interface etph0 >"$LOGS/client-badtok.log" 2>&1; then
  fail "wrong token connected"
elif grep -q "invalid token" "$LOGS/client-badtok.log" && ! ip link show etph0 >/dev/null 2>&1; then
  pass "wrong token refused with 'invalid token', no TUN created"
else
  dump "$LOGS/client-badtok.log"
  fail "wrong token error message"
fi

# --- 5. TLS with the wrong pairing token fails closed -----------------------
if etphonehome wifi --host "$PHONE_IP" --port 9001 --token NotTheTok --interface etph0 >"$LOGS/client-tls-bad.log" 2>&1; then
  fail "TLS with wrong pairing token connected"
elif grep -q "TLS handshake failed" "$LOGS/client-tls-bad.log"; then
  pass "TLS with the wrong pairing token fails closed (pin mismatch)"
else
  dump "$LOGS/client-tls-bad.log"
  fail "TLS wrong token message"
fi

# --- 6. USB mode (adb shim) + setup-adb + discover-ip ---------------------
: >/e2e/adb-calls.log
etphonehome usb --port 8890 --interface etph0 >"$LOGS/client-usb.log" 2>&1 &
CLI=$!
if wait_for_iface etph0 && grep -qx "forward tcp:8890 tcp:8890" /e2e/adb-calls.log \
  && curl -sS -m 20 -o /dev/null "http://$TARGET_IP:8080/blob.sha256" \
  && curl -sS -m 30 -o /dev/null https://example.com/; then
  pass "usb: 'adb forward tcp:8890 tcp:8890', then HTTP and public HTTPS through the phone"
else
  dump "$LOGS/client-usb.log"
  cat /e2e/adb-calls.log
  fail "usb mode internet"
fi
stop $CLI
if etphonehome setup-adb --port 9999 >/dev/null 2>&1 && grep -qx "forward tcp:9999 tcp:9999" /e2e/adb-calls.log; then
  pass "setup-adb runs adb forward with the given port"
else
  fail "setup-adb"
fi
# (Never `cmd | grep -q` under pipefail: grep exits early, cmd gets SIGPIPE
# and the pipeline fails even on a match.)
if etphonehome discover-ip >"$LOGS/discover-ip.log" 2>&1 && grep -q "Android WiFi IP: 192.168.43.17" "$LOGS/discover-ip.log"; then
  pass "discover-ip parses the phone's wlan0 address"
else
  fail "discover-ip"
fi

# --- 7. profile filter: rules sent to the phone and enforced ---------------
CFG=/e2e/config.toml
cat >"$CFG" <<EOF
[default]
auto_reconnect = false
interface = "etph0"

[auth]
wifi_token = "TlsTok456"

[[profiles]]
name = "filtered"
type = "wifi"
host = "$PHONE_IP"
port = 9002

[profiles.filter]
enabled = true
mode = "blacklist"
log_blocked = true

[[profiles.filter.rules]]
id = "no-ping"
action = "block"
protocol = "icmp"

[[profiles.filter.rules]]
id = "no-echo-port"
action = "block"
protocol = "tcp"
dest_port = "7000"
EOF
etphonehome --config "$CFG" connect filtered >"$LOGS/client-filter.log" 2>&1 &
CLI=$!
if wait_for_iface etph0; then
  sleep 0.5
  grep -q "Traffic filter from desktop: enabled=true mode=BLACKLIST rules=2" "$PEER/wifi-tls.log" \
    && pass "filter: the profile's 2 rules were sent to the phone and applied there" \
    || { dump "$PEER/wifi-tls.log"; fail "filter config on the phone"; }
  if ping -c 2 -W 1 -I etph0 10.0.0.2 >/dev/null 2>&1; then fail "filter: blocked ICMP got through"; else pass "filter: ICMP blocked"; fi
  if [ "$(echo x | socat -t 3 - "TCP:$TARGET_IP:7000,connect-timeout=3" 2>/dev/null)" = x ]; then
    fail "filter: blocked TCP port 7000 got through"
  else
    pass "filter: TCP to the blocked port fails"
  fi
  curl -sS -m 20 -o /dev/null "http://$TARGET_IP:8080/blob.sha256" \
    && pass "filter: other TCP (port 8080) still relayed" || fail "filter selectivity"
else
  dump "$LOGS/client-filter.log"
  fail "profile connect"
fi
stop $CLI

# --- 8. auto-reconnect after the phone disconnects -------------------------
BEFORE=$(handshakes hangup)
etphonehome wifi --host "$PHONE_IP" --port 9003 --token RecTok --interface etph0 --reconnect --max-retries 5 >"$LOGS/client-reconnect.log" 2>&1 &
CLI=$!
wait_for_iface etph0 || true
sleep 8
AFTER=$(handshakes hangup)
if [ $((AFTER - BEFORE)) -ge 2 ] && grep -q "Reconnecting in" "$LOGS/client-reconnect.log"; then
  pass "auto-reconnect: phone disconnected, client reconnected ($((AFTER - BEFORE)) handshakes)"
else
  dump "$LOGS/client-reconnect.log"
  dump "$PEER/hangup.log"
  fail "auto-reconnect"
fi
stop $CLI

# --- 9. no reconnect without --reconnect ----------------------------------
BEFORE=$(handshakes hangup)
etphonehome wifi --host "$PHONE_IP" --port 9003 --token RecTok --interface etph0 >"$LOGS/client-once.log" 2>&1 &
CLI=$!
wait_for_iface etph0 || true
sleep 6
if ! kill -0 $CLI 2>/dev/null && [ $(($(handshakes hangup) - BEFORE)) -eq 1 ]; then
  pass "without --reconnect the client exits after the phone disconnects"
else
  dump "$LOGS/client-once.log"
  fail "reconnect should be off by default"
fi
stop $CLI

# --- 10. scripts/quick-start-linux.sh drives a USB session end to end ------
: >/e2e/adb-calls.log
mkdir -p /e2e/qs/desktop/target/release
cp /e2e/bin/etphonehome /e2e/qs/desktop/target/release/etphonehome
(echo "" | bash /e2e/qs/scripts/quick-start-linux.sh >"$LOGS/quickstart.log" 2>&1; echo "exit=$?" >>"$LOGS/quickstart.log") &
QS=$!
if wait_for_iface etph0 && curl -sS -m 20 -o /dev/null "http://$TARGET_IP:8080/blob.sha256"; then
  pass "quick-start-linux.sh: adb forward + tunnel up, HTTP through the phone"
else
  dump "$LOGS/quickstart.log"
  fail "quick-start-linux.sh tunnel"
fi
pkill -INT -x etphonehome || true
wait $QS 2>/dev/null || true
if grep -qx "forward tcp:8888 tcp:8888" /e2e/adb-calls.log && grep -qx "forward --remove tcp:8888" /e2e/adb-calls.log \
  && grep -q "exit=0" "$LOGS/quickstart.log"; then
  pass "quick-start-linux.sh: removed the adb forward on Ctrl-C and exited 0"
else
  dump "$LOGS/quickstart.log"
  cat /e2e/adb-calls.log
  fail "quick-start-linux.sh cleanup"
fi

# --- 11. ETPH Pro is enforced by the phone; the desktop says so clearly -----
# Peer `usbfree` is a phone without Pro (the app's ProGate): the first computer
# over USB is admitted, a second computer and any router are refused with
# ERR_PRO_REQUIRED, and the client explains that Pro is bought on the phone.
ETPH_CLIENT_ID=e2e-computer-a ETPH_CLIENT_NAME=laptop-a etphonehome usb --port 8891 --interface etph0 >"$LOGS/pro-free-a.log" 2>&1 &
CLI=$!
if wait_for_iface etph0 && curl -sS -m 20 -o /dev/null "http://$TARGET_IP:8080/blob.sha256"; then
  sleep 1
  grep -q "Phone: free version" "$LOGS/pro-free-a.log" \
    && pass "pro: free phone gives the first computer USB internet (client logs 'Phone: free version')" \
    || { dump "$LOGS/pro-free-a.log"; fail "pro: free phone tier not reported"; }
else
  dump "$LOGS/pro-free-a.log"; dump "$PEER/usbfree.log"
  fail "pro: free phone refused its first computer"
fi
stop $CLI
if ETPH_CLIENT_ID=e2e-computer-b ETPH_CLIENT_NAME=laptop-b timeout 30 etphonehome usb --port 8891 --interface etph0 >"$LOGS/pro-free-b.log" 2>&1; then
  fail "pro: a second computer connected to a free phone"
elif grep -q "Pro required on the phone: the free version works with one computer (\"laptop-a\")" "$LOGS/pro-free-b.log" \
  && grep -q "nothing needs to be bought or licensed on this computer or router" "$LOGS/pro-free-b.log" && ! ip link show etph0 >/dev/null 2>&1; then
  pass "pro: free phone refuses a second computer with 'Pro required on the phone', no TUN, no licence asked of the computer"
else
  dump "$LOGS/pro-free-b.log"; fail "pro: second computer refusal message"
fi
if ETPH_CLIENT_ID=e2e-router timeout 30 etphonehome --router usb --port 8891 --interface etph0 >"$LOGS/pro-free-router.log" 2>&1; then
  fail "pro: a router connected to a free phone"
elif grep -q "Pro required on the phone: routers are part of ETPH Pro" "$LOGS/pro-free-router.log"; then
  pass "pro: free phone refuses a router (--router) with 'Pro required on the phone: routers are part of ETPH Pro'"
else
  dump "$LOGS/pro-free-router.log"; fail "pro: router refusal message"
fi
ETPH_CLIENT_ID=e2e-router etphonehome --router usb --port 8890 --interface etph0 >"$LOGS/pro-router.log" 2>&1 &
CLI=$!
if wait_for_iface etph0 && curl -sS -m 20 -o /dev/null "http://$TARGET_IP:8080/blob.sha256"; then
  sleep 1
  grep -q "Phone: ETPH Pro" "$LOGS/pro-router.log" && grep -q "client e2e-router\|admitted" "$PEER/usb2.log" \
    && pass "pro: a phone with Pro admits the router (--router) and the client logs 'Phone: ETPH Pro'" \
    || { dump "$LOGS/pro-router.log"; dump "$PEER/usb2.log"; fail "pro: Pro phone / router admitted log"; }
else
  dump "$LOGS/pro-router.log"; fail "pro: Pro phone refused a router"
fi
stop $CLI

# Auto-start on plug-in: nothing listens on the phone -> the client asks the phone
# once per plug-in (`adb shell am start ... AutoStartActivity`); unplug + replug asks again.
: >/e2e/adb-calls.log
etphonehome usb --port 8899 --interface etph0 --reconnect --max-retries 0 >"$LOGS/autostart.log" 2>&1 &
CLI=$!
sleep 12
N1=$(grep -c "shell am start -n solutions.errant.etph/com.etphonehome.AutoStartActivity" /e2e/adb-calls.log)
touch /e2e/unplugged
kill -USR1 $CLI 2>/dev/null   # retry now: this attempt finds no phone
sleep 3
rm -f /e2e/unplugged
kill -USR1 $CLI 2>/dev/null   # plugged back in
sleep 10
N2=$(grep -c "shell am start -n solutions.errant.etph/com.etphonehome.AutoStartActivity" /e2e/adb-calls.log)
kill -INT $CLI 2>/dev/null; wait $CLI 2>/dev/null
if [ "$N1" = 1 ] && [ "$N2" = 2 ]; then
  pass "auto-start: the client asks the phone to start ETPH once per plug-in (adb shell am start AutoStartActivity: $N1 then $N2 after a replug)"
else
  cat /e2e/adb-calls.log; dump "$LOGS/autostart.log"
  fail "auto-start requests: $N1 before, $N2 after the replug (want 1, 2)"
fi

echo "e2e summary: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
