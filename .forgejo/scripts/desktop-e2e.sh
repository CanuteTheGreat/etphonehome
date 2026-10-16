#!/usr/bin/env bash
# Run desktop-e2e-inner.sh inside a throwaway container that is allowed to
# create TUN devices. The CI job container itself has no CAP_NET_ADMIN, but
# the runner mounts the host Docker socket, so we start a sibling container
# with exactly the extra rights a TUN client needs (NET_ADMIN + /dev/net/tun,
# not --privileged), capped at 1 GiB / 2 CPUs, and always remove it.
#
# Binaries are copied in with `docker cp` (bind mounts don't work from inside
# a job container: the workspace path only exists in the job container).
#
# Usage: desktop-e2e.sh <dir with etphonehome, testpeer, common-tests>
set -euo pipefail
BIN_DIR=$1
HERE=$(cd "$(dirname "$0")" && pwd)
IMAGE=${E2E_IMAGE:-catthehacker/ubuntu:act-latest}
NAME="etph-e2e-${GITHUB_RUN_ID:-local}-$$"

cleanup() { docker rm -f -v "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# Run the sibling on the same architecture the binaries were built for. A
# runner whose job container is emulated (e.g. amd64 on an Apple-silicon
# Docker host) would otherwise start a native arm64 sibling that cannot load
# the x86_64 binaries at all.
case "$(uname -m)" in
  x86_64 | amd64) PLATFORM=linux/amd64 ;;
  aarch64 | arm64) PLATFORM=linux/arm64 ;;
  *) PLATFORM="" ;;
esac

docker create --name "$NAME" ${PLATFORM:+--platform "$PLATFORM"} \
  --cap-add NET_ADMIN --device /dev/net/tun \
  --memory 1g --memory-swap 1g --cpus 2 \
  "$IMAGE" sleep 900 >/dev/null
docker start "$NAME" >/dev/null
docker exec "$NAME" mkdir -p /e2e/bin /e2e/shim
for f in etphonehome testpeer common-tests; do
  docker cp "$BIN_DIR/$f" "$NAME:/e2e/bin/$f"
done
docker cp "$HERE/desktop-e2e-inner.sh" "$NAME:/e2e/run.sh"
docker exec "$NAME" mkdir -p /e2e/qs/scripts
docker cp "$HERE/../../scripts/quick-start-linux.sh" "$NAME:/e2e/qs/scripts/quick-start-linux.sh"

# Fake `adb` that records its arguments; `adb shell ip addr show wlan0`
# returns realistic output so discover-ip can be exercised.
docker exec -i "$NAME" sh -c 'cat > /e2e/shim/adb && chmod +x /e2e/shim/adb' <<'EOF'
#!/bin/sh
echo "$*" >> /e2e/adb-calls.log
case "$*" in
  "devices -l")
    echo "List of devices attached"
    echo "R5CT1234567            device usb:1-1 product:e2e model:Stand_In"
    ;;
  devices)
    # Same format as real `adb devices`: serial<TAB>state
    echo "List of devices attached"
    printf 'R5CT1234567\tdevice\n'
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

# The runner image has no ping/iproute2: install them while the container
# still has the default bridge network, then cut it off from every network
# (scenarios only use loopback and the TUN link, and the client adds a
# default route via the TUN device).
if ! docker exec "$NAME" sh -c 'command -v ping >/dev/null && command -v ip >/dev/null'; then
  docker exec "$NAME" sh -c 'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq iproute2 iputils-ping procps >/dev/null'
fi
docker network disconnect bridge "$NAME"

docker exec "$NAME" bash /e2e/run.sh
