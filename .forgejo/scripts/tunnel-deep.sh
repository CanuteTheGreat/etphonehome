#!/usr/bin/env bash
# Nightly tunnel suite: what a real user does with the tether, at scale and
# when things go wrong. Same topology as the per-push e2e (e2e-lib.sh): the
# real release client on a real TUN device in a container whose only way out
# is the tunnel, and the Android app's own networking core (standin) as the
# phone. Every check moves real bytes and verifies them.
#
#   integrity    256 MiB download + 64 MiB upload, sha256 both ends; public
#                internet bulk download with a known digest; 100 parallel
#                TCP downloads of distinct files; 50 parallel UDP flows; 300
#                short connections; the relay's flow table drains afterwards
#   failure      USB unplugged mid-transfer, WiFi dropped mid-transfer, app
#                killed mid-transfer, desktop Ctrl-C and crash mid-transfer,
#                lossy/slow link: bounded detection, no stuck TUN/route on
#                the desktop, the phone frees the session, clean recovery
#   performance  throughput (USB, WiFi+TLS, both directions) and latency
#                (in-tunnel TCP round trip, ping to the phone) written to
#                perf.json and compared with ci/perf-baseline.json: more
#                than PERF_FACTOR (default 2x) worse fails
#   upgrade      previous release desktop <-> HEAD phone core and HEAD
#                desktop <-> previous phone core (real traffic both ways);
#                a phone speaking a future major version gets a clear error
#                and no TUN; the previous release's config file still loads
#
# Usage: tunnel-deep.sh <bin dir: etphonehome> <standin dir>
#          [<prev desktop bin dir> <prev standin dir>]
# SUITES (default "integrity failure performance upgrade") picks sections.
set -uo pipefail
# Failure injection (USB/WiFi drops, crashes, lossy links) + public-internet downloads:
# colo lane only (rw-man-01), refused on the EC2 hosts rw-man-02..05 (GuardDuty).
sh "$(dirname "$0")/ec2-guard.sh" "deep tunnel suite (failure injection)" || exit 1
BIN_DIR=$1
STANDIN_DIR=$2
PREV_BIN_DIR=${3:-}
PREV_STANDIN_DIR=${4:-}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
SUITES=${SUITES:-integrity failure performance upgrade}
OUT=${DEEP_OUT:-$ROOT/deep-results}
PERF_FACTOR=${PERF_FACTOR:-2}
E2E_ID="etph-deep-${GITHUB_RUN_ID:-local}-$$"
E2E_DESKTOP_MEM=1536m
E2E_TARGET_MEM=1g
# shellcheck source=e2e-lib.sh
. "$HERE/e2e-lib.sh"
trap e2e_cleanup EXIT
mkdir -p "$OUT"
PASS=0
FAIL=0
FAILED=()
pass() { echo "PASS: $*"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $*"; FAIL=$((FAIL + 1)); FAILED+=("$*"); }
want() { case " $SUITES " in *" $1 "*) return 0 ;; esac; return 1; }
section() { echo; echo "=============== $* ==============="; }

# --- topology ---------------------------------------------------------------
e2e_net_up
# Big random blob for bulk/throughput, 100 distinct 512 KiB files for the
# parallel test, each with its digest.
e2e_up_target '
  head -c 268435456 /dev/urandom > /srv/big.bin && sha256sum /srv/big.bin | cut -d" " -f1 > /srv/big.sha256
  mkdir -p /srv/par && for i in $(seq -w 0 99); do head -c 524288 /dev/urandom > /srv/par/f$i; done
  (cd /srv/par && sha256sum f* > /srv/par.sha256)'
e2e_up_phone "$STANDIN_DIR"
if [ -n "$PREV_STANDIN_DIR" ]; then docker cp "$PREV_STANDIN_DIR" "$PHONE:/standin-prev"; fi
# Peers behave like the shipped app (30 s keepalive, 5 s stats) unless noted.
start_peer tls --listen 0.0.0.0:9102 --token DeepTok --require-tls --stats-log --quiet
start_peer usb --listen 0.0.0.0:8891 --stats-log --quiet
start_peer usb-chaos --listen 0.0.0.0:8892 --stats-log
start_peer wifi-chaos --listen 0.0.0.0:9104 --token DropTok --require-tls --stats-log
start_peer killme --listen 0.0.0.0:9105 --token KillTok --require-tls
start_peer ctrlc --listen 0.0.0.0:9106 --token IntTok --require-tls --stats-log --quiet
start_peer future --listen 0.0.0.0:9107 --token FutTok --advertise-version 0x0200
if [ -n "$PREV_STANDIN_DIR" ]; then
  STANDIN=/standin-prev start_peer prev-tls --listen 0.0.0.0:9201 --token PrevTok --require-tls
  STANDIN=/standin-prev start_peer prev-usb --listen 0.0.0.0:8893
fi
wait_peers "${E2E_PEERS[@]}"

BINS=$(mktemp -d)
cp "$BIN_DIR/etphonehome" "$BINS/etphonehome"
[ -n "$PREV_BIN_DIR" ] && cp "$PREV_BIN_DIR/etphonehome" "$BINS/etphonehome-prev"
e2e_up_desktop "$BINS"
rm -rf "$BINS"
# Record any `sysctl` the client runs (it must not flip host-wide settings).
docker exec -i "$DESKTOP" sh -c 'cat > /e2e/shim/sysctl && chmod +x /e2e/shim/sysctl' <<'EOF'
#!/bin/sh
echo "$*" >> /e2e/sysctl-calls.log
exit 0
EOF
docker exec "$DESKTOP" sh -c 'echo "nameserver 8.8.8.8" > /etc/resolv.conf'

# --- helpers (run things in the desktop) ------------------------------------
D() { docker exec "$DESKTOP" bash -c "$1"; }
P() { docker exec "$PHONE" bash -c "$1"; }
ENVSET='export PATH=/e2e/shim:/e2e/bin:$PATH RUST_LOG=info;'
# client NAME ARGS... -- start `etphonehome ARGS` in the background.
client() {
  local name=$1
  shift
  docker exec -d "$DESKTOP" bash -c "$ENVSET exec $CLIENT_BIN $* >/e2e/logs/$name.log 2>&1"
}
CLIENT_BIN=etphonehome
client_alive() { D 'pgrep -x etphonehome >/dev/null || pgrep -x etphonehome-pre >/dev/null'; }
client_stop() { # signal
  D "pkill -${1:-INT} -f '^(/e2e/bin/)?etphonehome' ; true" >/dev/null 2>&1
  for _ in $(seq 1 100); do client_alive || return 0; sleep 0.1; done
  D "pkill -KILL -f '^(/e2e/bin/)?etphonehome'; true" >/dev/null 2>&1
  return 1
}
iface_up() { D 'ip -4 addr show etph0 2>/dev/null | grep -q 10.0.0.1'; }
wait_iface() { # seconds
  local n=$((${1:-15} * 10))
  for _ in $(seq 1 $n); do iface_up && return 0; sleep 0.1; done
  return 1
}
wait_iface_gone() {
  local n=$((${1:-15} * 10))
  for _ in $(seq 1 $n); do D 'ip link show etph0 >/dev/null 2>&1' || return 0; sleep 0.1; done
  return 1
}
routes() { D 'ip -4 route show | sort'; }
peer_count() { P "grep -c '$2' /peerlogs/$1.log 2>/dev/null || true"; }
wait_peer_count() { # peer pattern want seconds
  local n=$(($4 * 5))
  for _ in $(seq 1 $n); do [ "$(peer_count "$1" "$2")" -ge "$3" ] && return 0; sleep 0.2; done
  return 1
}
now_ms() { date +%s%3N; }
clog() { echo "----- client log $1 -----"; D "tail -n ${2:-40} /e2e/logs/$1.log" || true; }
plog() { echo "----- phone log $1 -----"; P "tail -n ${2:-40} /peerlogs/$1.log" || true; }
BASE_ROUTES=$(routes)
no_leftovers() { # label
  if iface_up || [ "$(routes)" != "$BASE_ROUTES" ]; then
    D 'ip -4 route; ip link show etph0' || true
    fail "$1: desktop left a TUN device or route behind (stuck tunnel)"
  else
    pass "$1: no TUN device or route left behind on the desktop"
  fi
}
# Start a long download in the background; its result goes to /e2e/logs/NAME.dl
bg_download() { # name url
  docker exec -d "$DESKTOP" bash -c "curl -sS -m 300 -o /dev/null -w '%{http_code} %{size_download}' '$2' >/e2e/logs/$1.dl 2>/e2e/logs/$1.dlerr; echo \" exit=\$?\" >>/e2e/logs/$1.dl"
}
dl_done() { D "grep -q exit= /e2e/logs/$1.dl 2>/dev/null"; }
wait_dl_done() { local n=$(($2 * 5)); for _ in $(seq 1 $n); do dl_done "$1" && return 0; sleep 0.2; done; return 1; }
sha_get() { D "curl -sS -m ${2:-120} '$1' | sha256sum | cut -d' ' -f1"; }
quick_traffic() { # label -> HTTP 5 MiB with digest + public HTTPS
  local want got code
  want=$(D "curl -sS -m 20 http://$TARGET_IP:8080/blob.sha256")
  got=$(sha_get "http://$TARGET_IP:8080/blob.bin" 60)
  code=$(D "curl -sS -m 30 -o /dev/null -w '%{http_code}' https://example.com/" 2>/dev/null)
  if [ -n "$want" ] && [ "$want" = "$got" ] && [ "$code" = 200 ]; then
    pass "$1: 5 MiB sha256 intact + public HTTPS 200 through the tunnel"
  else
    fail "$1: traffic after recovery (sha ok=$([ "$want" = "$got" ] && echo y || echo n), https=$code)"
  fi
}
# A receive-only connection that was open when the link died can't notice
# by itself (it sends nothing, so nothing gets reset): like on any laptop
# whose WiFi blips, the app's own timeout ends it. Reported, not asserted.
stale_flow_reset() { # download-name label
  if wait_dl_done "$1" 5; then
    echo "info: $2: the transfer cut by the outage ended ($(D "cat /e2e/logs/$1.dl" | tr -s ' '))"
  else
    echo "info: $2: the transfer cut by the outage is still waiting for its own timeout"
  fi
}
# Latency: 200 round trips of 64 bytes on one TCP connection through the
# tunnel and relay (median, p95), and TCP connection setup time (median).
measure_latency() {
  D "python3 - <<'PY'
import socket, time, statistics
s=socket.create_connection(('$TARGET_IP',7000), timeout=10)
s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
rtts=[]
for i in range(200):
    m=(b'%064d' % i)
    t=time.perf_counter(); s.sendall(m); got=b''
    while len(got)<64: got+=s.recv(64-len(got))
    rtts.append((time.perf_counter()-t)*1000)
    assert got==m
s.close()
conn=[]
for i in range(30):
    t=time.perf_counter(); c=socket.create_connection(('$TARGET_IP',7000), timeout=10); conn.append((time.perf_counter()-t)*1000); c.close()
rtts.sort()
print('%.2f %.2f %.2f' % (statistics.median(rtts), rtts[int(len(rtts)*0.95)], statistics.median(conn)))
PY"
}
LINK_DEV=$(D "ip route get $PHONE_IP | grep -o 'dev [^ ]*' | cut -d' ' -f2")
declare -A PERF

# =============================================================================
if want integrity; then
  section "integrity and concurrency (WiFi, TLS as shipped)"
  : >/e2e/sysctl-calls.log 2>/dev/null || true
  client deep-tls wifi --host "$PHONE_IP" --port 9102 --token DeepTok --interface etph0
  if wait_iface 20; then
    pass "wifi+TLS tunnel up"
    # 256 MiB down, digest-checked, timed.
    WANT=$(D "curl -sS -m 20 http://$TARGET_IP:8080/big.sha256")
    T0=$(now_ms)
    GOT=$(sha_get "http://$TARGET_IP:8080/big.bin" 600)
    T1=$(now_ms)
    if [ -n "$WANT" ] && [ "$WANT" = "$GOT" ]; then
      PERF[wifi_tls_down_mibps]=$(python3 -c "print(round(256/(($T1-$T0)/1000),2))")
      pass "256 MiB download through the relay, sha256 matches (${PERF[wifi_tls_down_mibps]} MiB/s)"
    else
      fail "256 MiB download integrity (want=$WANT got=$GOT)"
    fi
    # 64 MiB up: the target answers with the sha256 of what it received.
    D 'head -c 67108864 /dev/urandom > /tmp/up64.bin'
    UPWANT=$(D 'sha256sum /tmp/up64.bin | cut -d" " -f1')
    T0=$(now_ms)
    UPGOT=$(D "socat -t 120 -T 120 - TCP:$TARGET_IP:7002,shut-down </tmp/up64.bin")
    T1=$(now_ms)
    if [ "$UPWANT" = "$UPGOT" ]; then
      PERF[wifi_tls_up_mibps]=$(python3 -c "print(round(64/(($T1-$T0)/1000),2))")
      pass "64 MiB upload through the relay, sha256 at the far end matches (${PERF[wifi_tls_up_mibps]} MiB/s)"
    else
      fail "64 MiB upload integrity (want=$UPWANT got=$UPGOT)"
    fi
    # Public internet bulk: a 10 MB HTTPS download through the tunnel must
    # be byte-identical to the same download made directly by the phone.
    PUB="https://speed.cloudflare.com/__down?bytes=10000000"
    ZWANT=$(P "curl -sS -m 120 '$PUB' | sha256sum | cut -d' ' -f1")
    ZGOT=$(sha_get "$PUB" 120)
    [ "$ZGOT" = "$ZWANT" ] || ZGOT=$(sha_get "$PUB" 120)
    ZSIZE=$(D "curl -sS -m 120 -o /dev/null -w '%{size_download}' '$PUB'")
    if [ -n "$ZWANT" ] && [ "$ZGOT" = "$ZWANT" ] && [ "${ZSIZE:-0}" -ge 10000000 ]; then
      pass "public internet: 10 MB HTTPS download through the tunnel is byte-identical to the phone's own download"
    else
      fail "public internet 10 MB download (tunnel $ZGOT, phone $ZWANT, size $ZSIZE)"
    fi
    # 100 parallel TCP downloads of distinct files, each digest-checked.
    D "mkdir -p /tmp/par && cd /tmp/par && rm -f f* && curl -sS -m 20 -o /tmp/par.sha256 http://$TARGET_IP:8080/par.sha256 &&
       for i in \$(seq -w 0 99); do curl -sS -m 120 -o f\$i http://$TARGET_IP:8080/par/f\$i & done; wait;
       sha256sum -c /tmp/par.sha256 >/tmp/par.check 2>&1; echo \$? >/tmp/par.rc" || true
    POK=$(D 'grep -c ": OK$" /tmp/par.check' 2>/dev/null)
    [ "${POK:-0}" -eq 100 ] && pass "100 parallel TCP downloads of distinct files, all 100 sha256 intact" \
      || { D 'grep -v ": OK$" /tmp/par.check | head' ; fail "parallel downloads (${POK:-0}/100 intact)"; }
    # 50 parallel UDP flows, 20 numbered datagrams each, echoed back.
    UDPRES=$(D "python3 - <<'PY'
import os, socket, select, time
T=('$TARGET_IP', 7001)
socks=[]
for f in range(50):
    s=socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.setblocking(False); s.connect(T); socks.append(s)
sent={}
got={}
corrupt=0
for r in range(20):
    for f,s in enumerate(socks):
        p=('flow%02d-seq%02d-' % (f, r)).encode()+os.urandom(200).hex().encode()
        sent[(f,r)]=p
        s.send(p)
    time.sleep(0.02)
deadline=time.time()+10
while time.time()<deadline and len(got)<len(sent):
    rd,_,_=select.select(socks,[],[],0.5)
    for s in rd:
        f=socks.index(s)
        while True:
            try: d=s.recv(2048)
            except BlockingIOError: break
            try: r=int(d[10:12])
            except ValueError: corrupt+=1; continue
            if sent.get((f,r))==d: got[(f,r)]=1
            else: corrupt+=1
print(len(got), len(sent), corrupt)
PY")
    set -- $UDPRES
    if [ "${1:-0}" -ge 990 ] && [ "${3:-1}" -eq 0 ]; then
      pass "50 parallel UDP flows: $1/$2 datagrams echoed intact, 0 corrupted or crossed between flows"
    else
      fail "parallel UDP flows (got=${1:-?}/${2:-?} corrupt=${3:-?})"
    fi
    # Connection churn: 300 short connections in a row.
    COK=$(D "ok=0; for i in \$(seq 1 300); do curl -sS -m 10 -o /dev/null -w '%{http_code}' http://$TARGET_IP:8080/blob.sha256 2>/dev/null | grep -q 200 && ok=\$((ok+1)); done; echo \$ok")
    [ "$COK" -eq 300 ] && pass "300 short TCP connections in a row all succeeded" || fail "connection churn ($COK/300)"
    # DNS: distinct real names through the relay.
    DOK=$(D 'ok=0; for n in example.com example.org wikipedia.org debian.org kernel.org python.org rust-lang.org cloudflare.com google.com mozilla.org; do getent ahostsv4 $n >/dev/null && ok=$((ok+1)); done; echo $ok')
    [ "$DOK" -eq 10 ] && pass "DNS: 10/10 distinct public names resolved through the relay" || fail "DNS ($DOK/10)"
    LAT=$(measure_latency)
    set -- $LAT
    if [ -n "${1:-}" ]; then
      PERF[wifi_rtt_median_ms]=$1
      PERF[wifi_rtt_p95_ms]=$2
      pass "WiFi+TLS latency: in-tunnel TCP round trip median ${1} ms, p95 ${2} ms; TCP connect median ${3} ms"
    else
      fail "WiFi latency measurement"
    fi
    # The relay's flow table must drain once the flows are done (no leak).
    sleep 12
    LAST=$(P "grep 'relay stats' /peerlogs/tls.log | tail -1")
    ACT=$(echo "$LAST" | sed -n 's/.*activeTcp=\([0-9]*\).*/\1/p')
    OPENED=$(echo "$LAST" | sed -n 's/.*tcpFlows=\([0-9]*\).*/\1/p')
    if [ -n "$ACT" ] && [ "$ACT" -le 2 ] && [ "${OPENED:-0}" -ge 400 ]; then
      pass "phone relay drained: $OPENED TCP flows opened, $ACT still active afterwards"
    else
      echo "$LAST"
      fail "relay flow table did not drain (active=$ACT opened=$OPENED)"
    fi
    if D 'grep -q "ip_forward" /e2e/sysctl-calls.log 2>/dev/null'; then
      fail "client changed the host-wide IP forwarding setting ($(D 'cat /e2e/sysctl-calls.log'))"
    else
      pass "client leaves host-wide sysctls (IP forwarding) alone"
    fi
    client_stop INT && wait_iface_gone 10
    no_leftovers "after the integrity session"
  else
    clog deep-tls
    plog tls
    fail "wifi+TLS tunnel for the integrity suite"
    client_stop KILL
  fi
fi

# =============================================================================
if want failure; then
  section "failure and recovery"

  # --- USB cable pulled mid-transfer ------------------------------------------
  client usb-unplug usb --port 8892 --interface etph0 --reconnect --max-retries 0
  if wait_iface 20; then
    H0=$(peer_count usb-chaos "Handshake complete")
    bg_download unplug "http://$TARGET_IP:8080/big.bin"
    sleep 2
    T0=$(now_ms)
    D 'touch /e2e/unplugged; pkill -f "TCP-LISTEN:8892" ; true'
    if wait_iface_gone 15; then
      pass "USB unplug: desktop tore the tunnel down in $(( ($(now_ms) - T0) / 1000 ))s"
    else
      fail "USB unplug: etph0 still up 15s after the cable was pulled"
    fi
    no_leftovers "USB unplug"
    wait_peer_count usb-chaos "session over" 1 15 && pass "USB unplug: phone ended the session and waits for the next desktop" \
      || { plog usb-chaos; fail "USB unplug: phone still holds the session"; }
    sleep 4
    client_alive && pass "USB unplug: client keeps retrying while the phone is unplugged" \
      || { clog usb-unplug; fail "USB unplug: client gave up while unplugged"; }
    D 'rm -f /e2e/unplugged'
    T0=$(now_ms)
    if wait_iface 45; then
      pass "USB replug: tunnel back by itself in $(( ($(now_ms) - T0) / 1000 ))s"
      stale_flow_reset unplug "USB replug"
      quick_traffic "USB replug"
    else
      clog usb-unplug 60
      fail "USB replug: no reconnect within 45s"
    fi
  else
    clog usb-unplug
    fail "usb tunnel for the unplug test"
  fi
  client_stop INT
  wait_iface_gone 10

  # --- WiFi drops mid-transfer (packets silently vanish) ------------------------
  client wifi-drop wifi --host "$PHONE_IP" --port 9104 --token DropTok --interface etph0 --reconnect --max-retries 0
  if wait_iface 20; then
    bg_download drop "http://$TARGET_IP:8080/big.bin"
    sleep 2
    ENDED0=$(peer_count wifi-chaos "Session ended")
    T0=$(now_ms)
    D "tc qdisc add dev $LINK_DEV root netem loss 100%"
    DESK_S=""
    PHONE_S=""
    for _ in $(seq 1 600); do
      [ -z "$DESK_S" ] && ! iface_up && DESK_S=$(( ($(now_ms) - T0) / 1000 ))
      [ -z "$PHONE_S" ] && [ "$(peer_count wifi-chaos "Session ended")" -gt "$ENDED0" ] && PHONE_S=$(( ($(now_ms) - T0) / 1000 ))
      [ -n "$DESK_S" ] && [ -n "$PHONE_S" ] && break
      sleep 0.2
    done
    [ -n "$DESK_S" ] && [ "$DESK_S" -le 45 ] && pass "WiFi drop: desktop noticed the dead link and removed the tunnel in ${DESK_S}s" \
      || fail "WiFi drop: desktop kept a dead tunnel for ${DESK_S:->120}s (limit 45s)"
    [ -n "$PHONE_S" ] && [ "$PHONE_S" -le 60 ] && pass "WiFi drop: phone noticed the silent desktop and freed the session in ${PHONE_S}s" \
      || fail "WiFi drop: phone still held the dead session after ${PHONE_S:->120}s (limit 60s; it can't accept the desktop back until then)"
    no_leftovers "WiFi drop"
    D "tc qdisc del dev $LINK_DEV root"
    T0=$(now_ms)
    if wait_iface 60; then
      pass "WiFi back: reconnected by itself in $(( ($(now_ms) - T0) / 1000 ))s"
      stale_flow_reset drop "WiFi back"
      quick_traffic "WiFi back"
    else
      clog wifi-drop 60
      plog wifi-chaos 60
      fail "WiFi back: no working reconnect within 60s"
    fi
  else
    clog wifi-drop
    fail "wifi tunnel for the drop test"
  fi
  client_stop INT
  wait_iface_gone 10
  D "tc qdisc del dev $LINK_DEV root 2>/dev/null; true"

  # --- the app is killed mid-transfer, then relaunched --------------------------
  client app-kill wifi --host "$PHONE_IP" --port 9105 --token KillTok --interface etph0 --reconnect --max-retries 0
  if wait_iface 20; then
    bg_download kill "http://$TARGET_IP:8080/big.bin"
    sleep 2
    T0=$(now_ms)
    kill_peer 0.0.0.0:9105
    if wait_iface_gone 10; then
      pass "app killed: desktop removed the tunnel in $(( ($(now_ms) - T0) / 1000 ))s"
    else
      fail "app killed: tunnel still up 10s after the app died"
    fi
    no_leftovers "app killed"
    sleep 5
    client_alive && pass "app killed: client keeps retrying while the app is gone" || { clog app-kill; fail "app killed: client gave up"; }
    start_peer killme --listen 0.0.0.0:9105 --token KillTok --require-tls
    T0=$(now_ms)
    if wait_iface 45; then
      pass "app relaunched: desktop reconnected by itself in $(( ($(now_ms) - T0) / 1000 ))s"
      stale_flow_reset kill "app relaunched"
      quick_traffic "app relaunched"
    else
      clog app-kill 60
      fail "app relaunched: no reconnect within 45s"
    fi
  else
    clog app-kill
    fail "wifi tunnel for the app-kill test"
  fi
  client_stop INT
  wait_iface_gone 10

  # --- desktop Ctrl-C mid-transfer ----------------------------------------------
  client ctrlc wifi --host "$PHONE_IP" --port 9106 --token IntTok --interface etph0
  if wait_iface 20; then
    D 'pgrep -x etphonehome' >/dev/null
    bg_download ctrlc "http://$TARGET_IP:8080/big.bin"
    sleep 2
    DISC0=$(peer_count ctrlc "Desktop requested disconnect")
    T0=$(now_ms)
    if client_stop INT; then
      pass "Ctrl-C mid-transfer: client exited in $(( ($(now_ms) - T0) / 1000 ))s"
    else
      fail "Ctrl-C mid-transfer: client did not exit within 10s"
    fi
    D 'grep -q "Ctrl-C received" /e2e/logs/ctrlc.log' || { clog ctrlc; fail "Ctrl-C: no shutdown message"; }
    wait_iface_gone 5
    no_leftovers "Ctrl-C mid-transfer"
    wait_peer_count ctrlc "Desktop requested disconnect" $((DISC0 + 1)) 10 \
      && pass "Ctrl-C mid-transfer: the phone was told (Disconnect), not left waiting" \
      || { plog ctrlc; fail "Ctrl-C mid-transfer: phone got no Disconnect"; }
    sleep 7
    LAST=$(P "grep 'relay stats' /peerlogs/ctrlc.log | tail -1")
    H0=$(peer_count ctrlc "Handshake complete")
    client ctrlc2 wifi --host "$PHONE_IP" --port 9106 --token IntTok --interface etph0
    if wait_iface 20 && [ "$(peer_count ctrlc "Handshake complete")" -gt "$H0" ]; then
      pass "after Ctrl-C a new session starts at once"
      quick_traffic "after Ctrl-C"
    else
      clog ctrlc2
      fail "no new session after Ctrl-C"
    fi
    # Crash: SIGKILL mid-transfer (no Disconnect possible).
    bg_download crash "http://$TARGET_IP:8080/big.bin"
    sleep 2
    END0=$(peer_count ctrlc "Session ended")
    client_stop KILL
    wait_iface_gone 5 && pass "desktop crash: the kernel removed etph0 with the process" || fail "desktop crash: etph0 left behind"
    no_leftovers "desktop crash"
    wait_peer_count ctrlc "Session ended" $((END0 + 1)) 15 \
      && pass "desktop crash: phone noticed and freed the session" \
      || { plog ctrlc; fail "desktop crash: phone still holds the session"; }
    client ctrlc3 wifi --host "$PHONE_IP" --port 9106 --token IntTok --interface etph0
    wait_iface 20 && quick_traffic "after a desktop crash" || { clog ctrlc3; fail "no new session after a desktop crash"; }
  else
    clog ctrlc
    fail "wifi tunnel for the Ctrl-C test"
  fi
  client_stop INT
  wait_iface_gone 10

  # --- slow, lossy link (2% loss, 80 ms) ----------------------------------------
  D "tc qdisc add dev $LINK_DEV root netem delay 40ms loss 2%"
  client lossy wifi --host "$PHONE_IP" --port 9106 --token IntTok --interface etph0
  if wait_iface 30; then
    WANT=$(D "curl -sS -m 30 http://$TARGET_IP:8080/blob.sha256")
    GOT=$(sha_get "http://$TARGET_IP:8080/blob.bin" 240)
    [ -n "$WANT" ] && [ "$WANT" = "$GOT" ] && pass "lossy link (2% loss, 80 ms RTT): 5 MiB download intact" \
      || fail "lossy link download (want=$WANT got=$GOT)"
    CODE=$(D "curl -sS -m 60 -o /dev/null -w '%{http_code}' https://example.com/")
    [ "$CODE" = 200 ] && pass "lossy link: public HTTPS 200" || fail "lossy link: HTTPS ($CODE)"
  else
    clog lossy
    fail "tunnel over a lossy link"
  fi
  client_stop INT
  wait_iface_gone 10
  D "tc qdisc del dev $LINK_DEV root 2>/dev/null; true"
fi

# =============================================================================
if want performance; then
  section "performance baseline (USB, plaintext, the fastest path)"
  client perf-usb usb --port 8891 --interface etph0
  if wait_iface 20; then
    WANT=$(D "curl -sS -m 20 http://$TARGET_IP:8080/big.sha256")
    T0=$(now_ms)
    GOT=$(sha_get "http://$TARGET_IP:8080/big.bin" 600)
    T1=$(now_ms)
    if [ "$WANT" = "$GOT" ]; then
      PERF[usb_down_mibps]=$(python3 -c "print(round(256/(($T1-$T0)/1000),2))")
      pass "USB: 256 MiB down intact at ${PERF[usb_down_mibps]} MiB/s"
    else
      fail "USB 256 MiB integrity"
    fi
    D '[ -f /tmp/up64.bin ] || head -c 67108864 /dev/urandom > /tmp/up64.bin'
    UPWANT=$(D 'sha256sum /tmp/up64.bin | cut -d" " -f1')
    T0=$(now_ms)
    UPGOT=$(D "socat -t 120 -T 120 - TCP:$TARGET_IP:7002,shut-down </tmp/up64.bin")
    T1=$(now_ms)
    if [ "$UPWANT" = "$UPGOT" ]; then
      PERF[usb_up_mibps]=$(python3 -c "print(round(64/(($T1-$T0)/1000),2))")
      pass "USB: 64 MiB up intact at ${PERF[usb_up_mibps]} MiB/s"
    else
      fail "USB 64 MiB upload integrity"
    fi
    LAT=$(measure_latency)
    set -- $LAT
    if [ -n "${1:-}" ]; then
      PERF[rtt_median_ms]=$1
      PERF[rtt_p95_ms]=$2
      PERF[connect_median_ms]=$3
      pass "USB latency: in-tunnel TCP round trip median ${1} ms, p95 ${2} ms; TCP connect median ${3} ms"
    else
      fail "latency measurement"
    fi
  else
    clog perf-usb
    fail "usb tunnel for the performance suite"
  fi
  client_stop INT
  wait_iface_gone 10
  # The same download straight from the phone container: how fast this runner
  # is today, so a slow runner isn't mistaken for a slow relay.
  T0=$(now_ms)
  P "curl -sS -m 300 -o /dev/null http://$TARGET_IP:8080/big.bin" && T1=$(now_ms) \
    && PERF[direct_down_mibps]=$(python3 -c "print(round(256/(($T1-$T0)/1000),2))")
  {
    echo "{"
    first=1
    for k in "${!PERF[@]}"; do
      [ $first -eq 1 ] || echo ","
      first=0
      printf '  "%s": %s' "$k" "${PERF[$k]}"
    done
    echo
    echo "}"
  } >"$OUT/perf.json"
  echo "measured:"
  cat "$OUT/perf.json"
  if python3 "$HERE/perf-compare.py" "$ROOT/ci/perf-baseline.json" "$OUT/perf.json" "$PERF_FACTOR"; then
    pass "performance within ${PERF_FACTOR}x of ci/perf-baseline.json"
  else
    fail "performance regression against ci/perf-baseline.json (see table above)"
  fi
fi

# =============================================================================
if want upgrade; then
  section "upgrade / protocol compatibility"
  if [ -n "$PREV_BIN_DIR" ] && [ -n "$PREV_STANDIN_DIR" ]; then
    D '/e2e/bin/etphonehome-prev --version' | sed 's/^/previous desktop: /'
    # previous desktop -> HEAD phone
    CLIENT_BIN=/e2e/bin/etphonehome-prev
    client prev-desk-tls wifi --host "$PHONE_IP" --port 9102 --token DeepTok --interface etph0
    if wait_iface 20; then quick_traffic "previous desktop release <-> HEAD phone (WiFi TLS)"; else clog prev-desk-tls; fail "previous desktop <-> HEAD phone (WiFi TLS): no tunnel"; fi
    client_stop INT; wait_iface_gone 10
    client prev-desk-usb usb --port 8891 --interface etph0
    if wait_iface 20; then quick_traffic "previous desktop release <-> HEAD phone (USB)"; else clog prev-desk-usb; fail "previous desktop <-> HEAD phone (USB): no tunnel"; fi
    client_stop INT; wait_iface_gone 10
    CLIENT_BIN=etphonehome
    # HEAD desktop -> previous phone
    client head-prev-tls wifi --host "$PHONE_IP" --port 9201 --token PrevTok --interface etph0
    if wait_iface 20; then quick_traffic "HEAD desktop <-> previous phone release (WiFi TLS)"; else clog head-prev-tls; plog prev-tls; fail "HEAD desktop <-> previous phone (WiFi TLS): no tunnel"; fi
    sleep 40 # longer than the previous phone's keepalive: an idle session must survive
    iface_up && D "curl -sS -m 20 -o /dev/null http://$TARGET_IP:8080/blob.sha256" \
      && pass "HEAD desktop <-> previous phone: idle session (40s) stays up and usable" \
      || { clog head-prev-tls; plog prev-tls; fail "HEAD desktop <-> previous phone: idle session dropped"; }
    client_stop INT; wait_iface_gone 10
    client head-prev-usb usb --port 8893 --interface etph0
    if wait_iface 20; then quick_traffic "HEAD desktop <-> previous phone release (USB)"; else clog head-prev-usb; fail "HEAD desktop <-> previous phone (USB): no tunnel"; fi
    client_stop INT; wait_iface_gone 10
    # Real user data: profiles saved by the previous release, used by HEAD.
    D "$ENVSET etphonehome-prev --config /e2e/up.toml save-profile up-usb -t usb --port 8891 -d 'saved by previous' &&
       etphonehome-prev --config /e2e/up.toml save-profile up-wifi -t wifi --host $PHONE_IP --port 9102 &&
       sed -i 's/^wifi_token = .*/wifi_token = \"DeepTok\"/' /e2e/up.toml && grep -q DeepTok /e2e/up.toml ||
       printf '\n[auth]\nwifi_token = \"DeepTok\"\n' >> /e2e/up.toml" >/dev/null 2>&1
    if D "$ENVSET etphonehome --config /e2e/up.toml list-profiles" 2>&1 | grep -q up-wifi; then
      pass "profiles saved by the previous release are listed by HEAD"
      client up-connect --config /e2e/up.toml connect up-usb
      if wait_iface 20; then quick_traffic "HEAD connects a profile saved by the previous release (USB)"; else clog up-connect; fail "HEAD could not connect a profile saved by the previous release"; fi
      client_stop INT; wait_iface_gone 10
    else
      D 'cat /e2e/up.toml' || true
      fail "HEAD cannot read the config file the previous release wrote"
    fi
    # The previous release's shipped example config still loads.
    if [ -n "${PREV_CONFIG:-}" ] && [ -f "$PREV_CONFIG" ]; then
      docker cp "$PREV_CONFIG" "$DESKTOP:/e2e/prev-config.toml"
      if D "$ENVSET etphonehome --config /e2e/prev-config.toml list-profiles >/e2e/logs/prev-config.log 2>&1"; then
        pass "previous release's config.example.toml loads in HEAD"
      else
        clog prev-config
        fail "HEAD cannot load the previous release's example config"
      fi
    fi
  else
    echo "no previous release binaries given: cross-version checks skipped"
  fi
  # A phone from the future (protocol major 2): clear error, no tunnel, no retry storm.
  T0=$(now_ms)
  D "$ENVSET timeout 60 etphonehome wifi --host $PHONE_IP --port 9107 --token FutTok --no-tls --interface etph0 --reconnect --max-retries 0 >/e2e/logs/future.log 2>&1; echo \$? >/e2e/logs/future.rc"
  RC=$(D 'cat /e2e/logs/future.rc')
  SECS=$(( ($(now_ms) - T0) / 1000 ))
  if D 'grep -qi "protocol version mismatch" /e2e/logs/future.log' && [ "$RC" != 0 ] && [ "$RC" != 124 ] && ! iface_up; then
    pass "future protocol major: clear 'Protocol version mismatch' error, exit $RC after ${SECS}s, no TUN, no endless retries"
  else
    clog future
    fail "future protocol major (exit=$RC after ${SECS}s; 124 = still retrying after 60s)"
  fi
  no_leftovers "version mismatch"
fi

dump_peers 15 >"$OUT/peer-logs.txt" 2>&1
docker cp "$DESKTOP:/e2e/logs" "$OUT/desktop-logs" >/dev/null 2>&1 || true
echo
echo "deep suite summary: $PASS passed, $FAIL failed"
for f in "${FAILED[@]}"; do echo "  failed: $f"; done
[ "$FAIL" -eq 0 ]
