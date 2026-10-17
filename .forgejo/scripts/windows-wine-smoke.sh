#!/usr/bin/env bash
# Execute the Windows client (not just compile it): wine runs the real
# etphonehome.exe against the phone core (standin) on loopback. Proves on
# the Windows ABI: CLI and help, config/profile files, and a real WiFi
# session up to the point where Windows needs the Wintun driver -- TCP,
# cert-pinned TLS 1.3, the protocol handshake (checked on the phone side
# too), wrong-token and plaintext refusals. Creating the TUN adapter needs
# real Windows + wintun.dll; the client must then fail with that advice.
#
# Usage: windows-wine-smoke.sh <etphonehome.exe> <standin dir>
set -uo pipefail
EXE=$(readlink -f "$1")
STANDIN=$2
W=$(command -v wine64 || command -v wine)
export WINEDEBUG=-all WINEPREFIX=${WINEPREFIX:-$HOME/.wine-etph}
LOG=$(mktemp -d)
PASS=0
FAIL=0
pass() { echo "PASS: $*"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $*"; FAIL=$((FAIL + 1)); }
win() { timeout 120 "$W" "$EXE" "$@" 2>&1 | tr -d '\r'; return "${PIPESTATUS[0]}"; }

"$W" wineboot -i >/dev/null 2>&1 || true
"$STANDIN/bin/standin" --listen 127.0.0.1:9301 --token WinTok --require-tls >"$LOG/tls.log" 2>&1 &
P1=$!
"$STANDIN/bin/standin" --listen 127.0.0.1:9302 --token WinTok >"$LOG/plain.log" 2>&1 &
P2=$!
trap 'kill $P1 $P2 2>/dev/null' EXIT
for _ in $(seq 1 100); do grep -q "listening on" "$LOG/tls.log" && grep -q "listening on" "$LOG/plain.log" && break; sleep 0.2; done

V=$(win --version)
echo "$V" | grep -q "etphonehome" && pass "etphonehome.exe runs: $V" || fail "--version ($V)"
win --help | grep -q "wifi" && pass "--help lists the commands" || fail "--help"

CFG='C:\etph-test.toml'
win --config "$CFG" save-profile winwifi -t wifi --host 127.0.0.1 --port 9301 -d "wine test" >"$LOG/save.log"
if win --config "$CFG" list-profiles | grep -q winwifi; then
  pass "profiles are saved to and read from a Windows path"
else
  cat "$LOG/save.log"
  fail "profile save/list on Windows"
fi

win wifi --host 127.0.0.1 --port 9301 --token WinTok --interface etph0 >"$LOG/tls-client.log"
RC=$?
if grep -q "TLS handshake complete" "$LOG/tls-client.log" && grep -q "Handshake successful" "$LOG/tls-client.log" \
  && grep -q "Handshake complete" "$LOG/tls.log"; then
  pass "Windows client: cert-pinned TLS 1.3 + protocol handshake with the phone core (both sides agree)"
else
  tail -30 "$LOG/tls-client.log"
  tail -10 "$LOG/tls.log"
  fail "Windows client TLS session"
fi
if [ "$RC" -ne 0 ] && grep -qi "wintun" "$LOG/tls-client.log"; then
  pass "without the Wintun driver it stops with a clear wintun.dll message (exit $RC)"
else
  tail -10 "$LOG/tls-client.log"
  fail "missing Wintun error (exit $RC)"
fi

win wifi --host 127.0.0.1 --port 9301 --token WrongTok --interface etph0 >"$LOG/bad.log"
if [ $? -ne 0 ] && grep -q "TLS handshake failed" "$LOG/bad.log"; then
  pass "wrong pairing token fails closed on Windows"
else
  tail -10 "$LOG/bad.log"
  fail "wrong token on Windows"
fi
win wifi --host 127.0.0.1 --port 9301 --token WinTok --no-tls --interface etph0 >"$LOG/plain-refused.log"
grep -q "requires an encrypted connection" "$LOG/plain-refused.log" \
  && pass "phone requiring encryption refuses --no-tls with its reason (Windows)" \
  || { tail -10 "$LOG/plain-refused.log"; fail "plaintext refusal on Windows"; }
win wifi --host 127.0.0.1 --port 9302 --token WinTok --no-tls --interface etph0 >"$LOG/plain.log2"
grep -q "Handshake successful" "$LOG/plain.log2" \
  && pass "plaintext session where the phone allows it (Windows)" \
  || { tail -10 "$LOG/plain.log2"; fail "plaintext handshake on Windows"; }

echo "windows (wine) summary: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
