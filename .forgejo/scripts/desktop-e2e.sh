#!/usr/bin/env bash
# End-to-end test: the real desktop client gets REAL internet through the
# phone's networking code.
#
# Three sibling containers (the CI job container has no CAP_NET_ADMIN, but
# the runner mounts the host Docker socket):
#
#   desktop  real release `etphonehome` + a real TUN device. Only attached to
#            an --internal Docker network: its sole way out is the tunnel.
#   phone    the Android app's networking core (android/core: handshake,
#            cert-pinned TLS, userspace relay, traffic filter) run by the
#            plain-JVM `standin`. On the internal network (where the desktop
#            reaches it) and on the default bridge (its "mobile data").
#   target   a local web/UDP/TCP server on the bridge, for deterministic
#            bulk/parallel/UDP checks next to the public-internet ones.
#
# desktop-e2e-inner.sh runs the scenarios inside `desktop`. All containers
# are capped and always removed.
#
# Usage: desktop-e2e.sh <dir with etphonehome, common-tests> <standin install dir>
set -euo pipefail
BIN_DIR=$1
STANDIN_DIR=$2
HERE=$(cd "$(dirname "$0")" && pwd)
IMAGE=${E2E_IMAGE:-catthehacker/ubuntu:act-latest}
JRE_IMAGE=${E2E_JRE_IMAGE:-eclipse-temurin:17-jre-jammy}
ID="etph-e2e-${GITHUB_RUN_ID:-local}-$$"
DESKTOP="$ID-desktop"
PHONE="$ID-phone"
TARGET="$ID-target"
NET="$ID-tether"
LOGVOL="$ID-peerlogs"

cleanup() {
  docker rm -f -v "$DESKTOP" "$PHONE" "$TARGET" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  docker volume rm "$LOGVOL" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Same architecture as the binaries (an emulated amd64 job container on an
# Apple-silicon host would otherwise start native arm64 siblings).
case "$(uname -m)" in
  x86_64 | amd64) PLATFORM=linux/amd64 ;;
  aarch64 | arm64) PLATFORM=linux/arm64 ;;
  *) PLATFORM="" ;;
esac
PLAT=()
[ -n "$PLATFORM" ] && PLAT=(--platform "$PLATFORM")

docker network create --internal "$NET" >/dev/null
docker volume create "$LOGVOL" >/dev/null

# --- target: python web server (5 MB file), TCP echo, UDP echo -------------
docker create --name "$TARGET" "${PLAT[@]}" --memory 512m --memory-swap 512m --cpus 1 "$IMAGE" sleep 1500 >/dev/null
docker start "$TARGET" >/dev/null
docker exec "$TARGET" sh -c 'command -v socat >/dev/null || (apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq socat >/dev/null)'
docker exec "$TARGET" sh -c 'mkdir -p /srv && head -c 5242880 /dev/urandom > /srv/blob.bin && sha256sum /srv/blob.bin | cut -d" " -f1 > /srv/blob.sha256'
docker exec -d "$TARGET" sh -c 'cd /srv && exec python3 -m http.server 8080 >/tmp/http.log 2>&1'
docker exec -d "$TARGET" sh -c 'exec socat TCP-LISTEN:7000,fork,reuseaddr EXEC:cat'
docker exec -d "$TARGET" sh -c 'exec socat UDP-RECVFROM:7001,fork EXEC:cat'
TARGET_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$TARGET")

# --- phone: the standin, one instance per behaviour ------------------------
docker create --name "$PHONE" "${PLAT[@]}" --memory 1536m --memory-swap 1536m --cpus 2 \
  -v "$LOGVOL:/peerlogs" "$JRE_IMAGE" sleep 1500 >/dev/null
docker cp "$STANDIN_DIR" "$PHONE:/standin"
docker start "$PHONE" >/dev/null
docker network connect "$NET" "$PHONE"
PHONE_IP=$(docker inspect -f "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}" "$PHONE")
start_peer() { # name args...
  local name=$1
  shift
  docker exec -d -e JAVA_OPTS="-Xmx200m" "$PHONE" sh -c "exec /standin/bin/standin $* >/peerlogs/$name.log 2>&1"
}
# WiFi, plaintext allowed (TLS also accepted: the app auto-detects)
start_peer wifi-open --listen 0.0.0.0:9001 --token PlainTok123 --keepalive-secs 1 --stats-secs 1
# WiFi as the app ships it: token + encryption required
start_peer wifi-tls --listen 0.0.0.0:9002 --token TlsTok456 --require-tls --stats-secs 1
# USB (no token; reached through the adb-forward shim)
start_peer usb --listen 0.0.0.0:8888
start_peer usb2 --listen 0.0.0.0:8890
# The phone hangs up after 3 s (auto-reconnect scenarios)
start_peer hangup --listen 0.0.0.0:9003 --token RecTok --disconnect-after-secs 3
for f in wifi-open wifi-tls usb usb2 hangup; do
  for _ in $(seq 1 100); do
    docker exec "$PHONE" grep -q "listening on" "/peerlogs/$f.log" 2>/dev/null && break
    sleep 0.2
  done
done

# --- desktop ------------------------------------------------------------------
docker create --name "$DESKTOP" "${PLAT[@]}" \
  --cap-add NET_ADMIN --device /dev/net/tun \
  --memory 1g --memory-swap 1g --cpus 2 \
  -v "$LOGVOL:/e2e/peerlogs:ro" \
  -e PHONE_IP="$PHONE_IP" -e TARGET_IP="$TARGET_IP" \
  "$IMAGE" sleep 1500 >/dev/null
docker start "$DESKTOP" >/dev/null
docker exec "$DESKTOP" mkdir -p /e2e/bin /e2e/shim /e2e/qs/scripts
for f in etphonehome common-tests; do
  docker cp "$BIN_DIR/$f" "$DESKTOP:/e2e/bin/$f"
done
docker cp "$HERE/desktop-e2e-inner.sh" "$DESKTOP:/e2e/run.sh"
docker cp "$HERE/../../scripts/quick-start-linux.sh" "$DESKTOP:/e2e/qs/scripts/quick-start-linux.sh"

# Fake `adb` with real direction semantics: `adb forward tcp:A tcp:B` carries
# this computer's 127.0.0.1:A to the phone's port B (here: the phone
# container); `adb reverse` goes the other way and, like real adb against the
# app's busy port, fails -- so a client using the wrong direction can't connect.
docker exec -i "$DESKTOP" sh -c 'cat > /e2e/shim/adb && chmod +x /e2e/shim/adb' <<EOF
#!/bin/sh
echo "\$*" >> /e2e/adb-calls.log
case "\$*" in
  "devices -l")
    echo "List of devices attached"
    echo "R5CT1234567            device usb:1-1 product:e2e model:Stand_In"
    ;;
  devices)
    echo "List of devices attached"
    printf 'R5CT1234567\tdevice\n'
    ;;
  "forward --remove "*)
    p=\${3#tcp:}
    [ -f /e2e/fwd-\$p.pid ] && kill "\$(cat /e2e/fwd-\$p.pid)" 2>/dev/null; rm -f /e2e/fwd-\$p.pid
    ;;
  "forward tcp:"*" tcp:"*)
    a=\${2#tcp:}; b=\${3#tcp:}
    setsid socat TCP-LISTEN:\$a,bind=127.0.0.1,fork,reuseaddr TCP:$PHONE_IP:\$b >/dev/null 2>&1 &
    echo \$! > /e2e/fwd-\$a.pid
    for _ in 1 2 3 4 5 6 7 8 9 10; do ss -ltn | grep -q "127.0.0.1:\$a " && break; sleep 0.1; done
    echo "\$a"
    ;;
  "reverse tcp:"*" tcp:"*)
    echo "adb: error: cannot bind listener: Address already in use" >&2
    exit 1
    ;;
  "shell pm list packages")
    echo "package:com.etphonehome"
    ;;
  "shell ip addr show wlan0")
    echo "30: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP"
    echo "    link/ether 3a:11:22:33:44:55 brd ff:ff:ff:ff:ff:ff"
    echo "    inet 192.168.43.17/24 brd 192.168.43.255 scope global wlan0"
    ;;
esac
exit 0
EOF
# Fake `resolvectl` (no systemd-resolved in a container): records the DNS
# setup the client asks for.
docker exec -i "$DESKTOP" sh -c 'cat > /e2e/shim/resolvectl && chmod +x /e2e/shim/resolvectl' <<'EOF'
#!/bin/sh
echo "$*" >> /e2e/resolvectl-calls.log
exit 0
EOF

# Tools, while the desktop still has the default bridge; then cut it off so
# the tunnel is its only path to anything but the phone.
if ! docker exec "$DESKTOP" sh -c 'command -v ping && command -v ip && command -v ss && command -v socat && command -v curl && command -v getent && ldconfig -p | grep -q libdbus-1.so.3' >/dev/null; then
  docker exec "$DESKTOP" sh -c 'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq iproute2 iputils-ping procps socat curl libdbus-1-3 >/dev/null'
fi
docker network connect "$NET" "$DESKTOP"
docker network disconnect bridge "$DESKTOP"
docker exec "$DESKTOP" sh -c 'ip route del default 2>/dev/null; true'

set +e
docker exec "$DESKTOP" bash /e2e/run.sh
STATUS=$?
set -e
for f in wifi-open wifi-tls usb usb2 hangup; do
  echo "----- phone stand-in log: $f (last 30 lines) -----"
  docker exec "$PHONE" sh -c "tail -n 30 /peerlogs/$f.log" || true
done
exit $STATUS
