#!/usr/bin/env bash
# Shared topology for the tunnel end-to-end suites (sourced, not run):
#
#   desktop  real release `etphonehome` + a real TUN device, attached only to
#            an --internal Docker network: its sole way out is the tunnel.
#   phone    the Android app's networking core (android/core: handshake, TLS,
#            userspace relay, traffic filter) run by the plain-JVM `standin`.
#            On the internal network (where the desktop reaches it) and on the
#            default bridge (its "mobile data").
#   target   web/TCP/UDP servers on the bridge for deterministic bulk,
#            integrity, concurrency and latency checks next to the public
#            internet ones.
#
# The CI job container has no CAP_NET_ADMIN, but the runner mounts the host
# Docker socket, so these are sibling containers. All of them are memory/CPU
# capped and always removed (e2e_cleanup on EXIT).
#
# Callers set E2E_ID (unique per run) and then call, in order:
#   e2e_up_target [extra setup shell]   e2e_up_phone <standin dir>
#   start_peer <name> <standin args...> (any number; wait_peers waits)
#   e2e_up_desktop <bin dir>            (tools, shims, cut off from bridge)

E2E_IMAGE=${E2E_IMAGE:-catthehacker/ubuntu:act-latest}
E2E_JRE_IMAGE=${E2E_JRE_IMAGE:-eclipse-temurin:17-jre-jammy}
: "${E2E_ID:?set E2E_ID first}"
DESKTOP="$E2E_ID-desktop"
PHONE="$E2E_ID-phone"
TARGET="$E2E_ID-target"
NET="$E2E_ID-tether"
LOGVOL="$E2E_ID-peerlogs"
E2E_HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
E2E_PEERS=()

e2e_cleanup() {
  docker rm -f -v "$DESKTOP" "$PHONE" "$TARGET" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  docker volume rm "$LOGVOL" >/dev/null 2>&1 || true
}

# Same architecture as the binaries (an emulated amd64 job container on an
# Apple-silicon host would otherwise start native arm64 siblings).
case "$(uname -m)" in
  x86_64 | amd64) E2E_PLATFORM=linux/amd64 ;;
  aarch64 | arm64) E2E_PLATFORM=linux/arm64 ;;
  *) E2E_PLATFORM="" ;;
esac
PLAT=()
[ -n "$E2E_PLATFORM" ] && PLAT=(--platform "$E2E_PLATFORM")

e2e_net_up() {
  docker network create --internal "$NET" >/dev/null
  docker volume create "$LOGVOL" >/dev/null
}

# Target: /srv over HTTP :8080 (blob.bin 5 MiB + blob.sha256), TCP echo
# :7000, UDP echo :7001 and TCP sha256 sink :7002 (e2e-target-services.py).
# $1: extra shell run in the target after the standard files exist (bigger
# files etc.).
e2e_up_target() {
  local extra=${1:-true} mem=${E2E_TARGET_MEM:-512m}
  docker create --name "$TARGET" "${PLAT[@]}" --memory "$mem" --memory-swap "$mem" --cpus 1 "$E2E_IMAGE" sleep 7200 >/dev/null
  docker start "$TARGET" >/dev/null
  docker exec "$TARGET" sh -c 'command -v socat >/dev/null || (apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq socat >/dev/null)'
  docker exec "$TARGET" sh -c 'mkdir -p /srv && head -c 5242880 /dev/urandom > /srv/blob.bin && sha256sum /srv/blob.bin | cut -d" " -f1 > /srv/blob.sha256'
  docker exec "$TARGET" sh -c "$extra"
  docker exec -d "$TARGET" sh -c 'cd /srv && exec python3 -m http.server 8080 >/tmp/http.log 2>&1'
  docker exec -d "$TARGET" sh -c 'exec socat TCP-LISTEN:7000,fork,reuseaddr,backlog=256 EXEC:cat'
  docker cp "$E2E_HERE/e2e-target-services.py" "$TARGET:/srv-services.py"
  docker exec -d "$TARGET" sh -c 'exec python3 /srv-services.py >/tmp/services.log 2>&1'
  TARGET_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$TARGET")
  for _ in $(seq 1 50); do
    docker exec "$TARGET" sh -c 'ss -ltn | grep -q ":8080 " && ss -ltn | grep -q ":7002 "' && break
    sleep 0.2
  done
}

# Phone: one container, one standin process per behaviour (start_peer).
e2e_up_phone() {
  local standin_dir=$1 mem=${E2E_PHONE_MEM:-1536m}
  docker create --name "$PHONE" "${PLAT[@]}" --memory "$mem" --memory-swap "$mem" --cpus 2 \
    -v "$LOGVOL:/peerlogs" "$E2E_JRE_IMAGE" sleep 7200 >/dev/null
  docker cp "$standin_dir" "$PHONE:/standin"
  docker start "$PHONE" >/dev/null
  # curl: the phone's own downloads (compared with what the desktop gets).
  docker exec "$PHONE" sh -c 'command -v curl >/dev/null || (apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq curl procps >/dev/null)'
  docker exec "$PHONE" sh -c 'command -v pkill >/dev/null || (apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq procps >/dev/null)'
  docker network connect "$NET" "$PHONE"
  PHONE_IP=$(docker inspect -f "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}" "$PHONE")
}

# start_peer NAME ARGS... -- a standin ("the app") listening per ARGS, logging
# to /peerlogs/NAME.log. STANDIN overrides the install dir (e.g. an older
# release's standin for the upgrade suite).
start_peer() {
  local name=$1 dir=${STANDIN:-/standin}
  shift
  docker exec -d -e JAVA_OPTS="${PEER_JAVA_OPTS:--Xmx200m}" "$PHONE" sh -c "exec $dir/bin/standin $* >>/peerlogs/$name.log 2>&1"
  E2E_PEERS+=("$name")
}

# Kill a peer the way Android kills an app (no goodbye: the OS closes its
# sockets). Matches on its --listen address.
kill_peer() { # listen-address
  docker exec "$PHONE" sh -c "pkill -9 -f -- '--listen $1( |\$)'" 2>/dev/null || true
}

wait_peers() {
  local f
  for f in "$@"; do
    for _ in $(seq 1 150); do
      docker exec "$PHONE" grep -q "listening on" "/peerlogs/$f.log" 2>/dev/null && break
      sleep 0.2
    done
  done
}

dump_peers() {
  local f
  for f in "${E2E_PEERS[@]}"; do
    echo "----- phone stand-in log: $f (last ${1:-30} lines) -----"
    docker exec "$PHONE" sh -c "tail -n ${1:-30} /peerlogs/$f.log" || true
  done
}

# Desktop: binaries from $1 (etphonehome [+ common-tests]), adb/resolvectl
# shims, tools; then cut off from the bridge so the tunnel is its only way
# to anything but the phone.
e2e_up_desktop() {
  local bin_dir=$1 f mem=${E2E_DESKTOP_MEM:-1g}
  docker create --name "$DESKTOP" "${PLAT[@]}" \
    --cap-add NET_ADMIN --device /dev/net/tun \
    --memory "$mem" --memory-swap "$mem" --cpus 2 \
    -v "$LOGVOL:/e2e/peerlogs:ro" \
    -e PHONE_IP="$PHONE_IP" -e TARGET_IP="$TARGET_IP" \
    "${E2E_DESKTOP_IMAGE:-$E2E_IMAGE}" sleep 7200 >/dev/null
  docker start "$DESKTOP" >/dev/null
  docker exec "$DESKTOP" mkdir -p /e2e/bin /e2e/shim /e2e/logs /e2e/qs/scripts
  for f in "$bin_dir"/*; do
    docker cp "$f" "$DESKTOP:/e2e/bin/$(basename "$f")"
  done

  # Fake `adb` with real direction semantics: `adb forward tcp:A tcp:B`
  # carries this computer's 127.0.0.1:A to the phone's port B (here: the
  # phone container); `adb reverse` goes the other way and, like real adb
  # against the app's busy port, fails -- so a client using the wrong
  # direction can't connect. `/e2e/unplugged` present = cable pulled: no
  # device, forwards refused.
  docker exec -i "$DESKTOP" sh -c 'cat > /e2e/shim/adb && chmod +x /e2e/shim/adb' <<EOF
#!/bin/sh
echo "\$*" >> /e2e/adb-calls.log
if [ -e /e2e/unplugged ]; then
  case "\$*" in
    devices*) echo "List of devices attached"; exit 0 ;;
    *) echo "adb: error: no devices/emulators found" >&2; exit 1 ;;
  esac
fi
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
    [ -f /e2e/fwd-\$a.pid ] && kill "\$(cat /e2e/fwd-\$a.pid)" 2>/dev/null
    setsid socat TCP-LISTEN:\$a,bind=127.0.0.1,fork,reuseaddr,nodelay TCP:$PHONE_IP:\$b,nodelay >/dev/null 2>&1 &
    echo \$! > /e2e/fwd-\$a.pid
    for _ in 1 2 3 4 5 6 7 8 9 10; do ss -ltn | grep -q "127.0.0.1:\$a " && break; sleep 0.1; done
    echo "\$a"
    ;;
  "reverse tcp:"*" tcp:"*)
    echo "adb: error: cannot bind listener: Address already in use" >&2
    exit 1
    ;;
  "shell pm list packages")
    echo "package:solutions.errant.etph"
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

  # Tools and CA certificates, from whichever package manager the distro has.
  # (No libdbus: the client links it statically since build-linux-release.sh.)
  if ! docker exec "$DESKTOP" sh -c 'command -v ping && command -v ip && command -v tc && command -v ss && command -v socat && command -v curl && command -v getent && command -v python3 && command -v pgrep && [ -e /etc/ssl/certs/ca-certificates.crt -o -e /etc/pki/tls/certs/ca-bundle.crt -o -e /etc/ssl/ca-bundle.pem ]' >/dev/null 2>&1; then
    docker exec "$DESKTOP" sh -c '
      if command -v apt-get >/dev/null; then
        PKGS="iproute2 iputils-ping procps socat curl python3 ca-certificates"
        if ! { apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq $PKGS >/dev/null; }; then
          # End-of-life releases (Debian 11 since 2026-08) lose their
          # deb.debian.org pool: fetch from archive.debian.org instead.
          . /etc/os-release
          printf "deb http://archive.debian.org/debian %s main\ndeb http://archive.debian.org/debian-security %s-security main\ndeb http://archive.debian.org/debian %s-updates main\n" \
            "$VERSION_CODENAME" "$VERSION_CODENAME" "$VERSION_CODENAME" > /etc/apt/sources.list
          rm -f /etc/apt/sources.list.d/*
          apt-get -o Acquire::Check-Valid-Until=false update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq $PKGS >/dev/null
        fi
      elif command -v dnf >/dev/null; then
        dnf install -y -q iproute iproute-tc iputils procps-ng socat curl python3 ca-certificates >/dev/null
      elif command -v pacman >/dev/null; then
        pacman -Sy --noconfirm --needed iproute2 iputils procps-ng socat curl python ca-certificates >/dev/null
      elif command -v zypper >/dev/null; then
        zypper -n -q install iproute2 iputils procps socat curl python3 ca-certificates ca-certificates-mozilla >/dev/null
      else
        echo "no known package manager" >&2; exit 1
      fi' || { echo "installing the test tools failed in $DESKTOP" >&2; return 1; }
  fi
  docker network connect "$NET" "$DESKTOP"
  docker network disconnect bridge "$DESKTOP"
  docker exec "$DESKTOP" sh -c 'ip route del default 2>/dev/null; true'
}
