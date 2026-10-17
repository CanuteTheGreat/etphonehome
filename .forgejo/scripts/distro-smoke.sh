#!/usr/bin/env bash
# Platform matrix: the released Linux client, unchanged, on the distros
# people actually run, each doing the real thing -- a WiFi+TLS tunnel to the
# phone core (standin) from a container whose only way out is that tunnel:
# 5 MiB download with digest, public HTTPS, DNS, Ctrl-C cleanup.
#
# A distro whose glibc is older than the build's fails to even start the
# binary: that is reported per distro (and is what decides the documented
# minimum).
#
# Usage: distro-smoke.sh <bin dir with etphonehome> <standin dir>
# DISTROS overrides the image list.
set -uo pipefail
BIN_DIR=$1
STANDIN_DIR=$2
HERE=$(cd "$(dirname "$0")" && pwd)
DISTROS=${DISTROS:-debian:12 debian:13 ubuntu:22.04 ubuntu:24.04 fedora:42 archlinux:latest opensuse/tumbleweed:latest}
E2E_ID="etph-distro-${GITHUB_RUN_ID:-local}-$$"
# shellcheck source=e2e-lib.sh
. "$HERE/e2e-lib.sh"
ALL_DESKTOPS=()
cleanup_all() {
  for d in "${ALL_DESKTOPS[@]}"; do docker rm -f -v "$d" >/dev/null 2>&1 || true; done
  e2e_cleanup
}
trap cleanup_all EXIT
PASS=0
FAIL=0
RESULTS=()

e2e_net_up
e2e_up_target
e2e_up_phone "$STANDIN_DIR"
start_peer tls --listen 0.0.0.0:9102 --token DistroTok --require-tls
wait_peers tls
WANT=$(docker exec "$TARGET" cat /srv/blob.sha256)

i=0
for img in $DISTROS; do
  i=$((i + 1))
  DESKTOP="$E2E_ID-desktop-$i"
  ALL_DESKTOPS+=("$DESKTOP")
  echo
  echo "=============== $img ==============="
  if ! docker pull -q "${PLAT[@]}" "$img" >/dev/null 2>&1; then
    echo "FAIL: $img: image pull failed"
    FAIL=$((FAIL + 1)); RESULTS+=("$img: image pull failed"); continue
  fi
  if ! E2E_DESKTOP_IMAGE=$img e2e_up_desktop "$BIN_DIR" >/tmp/distro-$i.log 2>&1; then
    tail -20 /tmp/distro-$i.log
    echo "FAIL: $img: could not set up the desktop container"
    FAIL=$((FAIL + 1)); RESULTS+=("$img: setup failed"); continue
  fi
  D() { docker exec "$DESKTOP" bash -c "$1"; }
  OS=$(D '. /etc/os-release; echo "$PRETTY_NAME"; ldd --version 2>&1 | head -1 | grep -o "[0-9.]*$"' | tr '\n' ' ')
  if ! D '/e2e/bin/etphonehome --version' >/tmp/ver-$i.log 2>&1; then
    echo "FAIL: $img ($OS): the client does not start: $(head -3 /tmp/ver-$i.log | tr '\n' ' ')"
    FAIL=$((FAIL + 1)); RESULTS+=("$img ($OS): binary does not start"); continue
  fi
  D 'echo "nameserver 8.8.8.8" > /etc/resolv.conf'
  docker exec -d "$DESKTOP" bash -c "export PATH=/e2e/shim:/e2e/bin:\$PATH RUST_LOG=info; exec etphonehome wifi --host $PHONE_IP --port 9102 --token DistroTok --interface etph0 >/e2e/logs/client.log 2>&1"
  up=0
  for _ in $(seq 1 200); do D 'ip -4 addr show etph0 2>/dev/null | grep -q 10.0.0.1' && { up=1; break; }; sleep 0.1; done
  ok=1
  why=""
  if [ $up = 1 ]; then
    GOT=$(D "curl -sS -m 60 http://$TARGET_IP:8080/blob.bin | sha256sum | cut -d' ' -f1")
    [ "$GOT" = "$WANT" ] || { ok=0; why+=" sha256"; }
    CODE=$(D "curl -sS -m 30 -o /dev/null -w '%{http_code}' https://example.com/")
    [ "$CODE" = 200 ] || { ok=0; why+=" https=$CODE"; }
    D 'getent ahostsv4 example.com >/dev/null' || { ok=0; why+=" dns"; }
    D 'pkill -INT -x etphonehome'
    for _ in $(seq 1 100); do D 'pgrep -x etphonehome >/dev/null' || break; sleep 0.1; done
    if D 'pgrep -x etphonehome >/dev/null || ip link show etph0 >/dev/null 2>&1 || ip route | grep -q etph0'; then
      ok=0; why+=" cleanup"
    fi
  else
    ok=0
    why=" no tunnel: $(D 'tail -5 /e2e/logs/client.log' | tr '\n' ' ')"
  fi
  if [ $ok = 1 ]; then
    echo "PASS: $img ($OS): WiFi+TLS tunnel, 5 MiB intact, public HTTPS, DNS, Ctrl-C cleanup"
    PASS=$((PASS + 1)); RESULTS+=("$img ($OS): ok")
  else
    D 'tail -30 /e2e/logs/client.log' || true
    echo "FAIL: $img ($OS):$why"
    FAIL=$((FAIL + 1)); RESULTS+=("$img ($OS): FAIL$why")
  fi
  docker rm -f -v "$DESKTOP" >/dev/null 2>&1
done

echo
echo "distro matrix ($(uname -m)):"
for r in "${RESULTS[@]}"; do echo "  $r"; done
echo "distro summary: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
