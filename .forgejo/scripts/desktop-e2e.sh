#!/usr/bin/env bash
# End-to-end test: the real desktop client gets REAL internet through the
# phone's networking code. Topology (desktop / phone / target sibling
# containers) in e2e-lib.sh; desktop-e2e-inner.sh runs the scenarios inside
# `desktop`. The deeper nightly suites (integrity at scale, failure and
# recovery, throughput baseline, upgrade) are in tunnel-deep.sh.
#
# Usage: desktop-e2e.sh <dir with etphonehome, common-tests> <standin install dir>
set -euo pipefail
BIN_DIR=$1
STANDIN_DIR=$2
HERE=$(cd "$(dirname "$0")" && pwd)
E2E_ID="etph-e2e-${GITHUB_RUN_ID:-local}-$$"
# shellcheck source=e2e-lib.sh
. "$HERE/e2e-lib.sh"
trap e2e_cleanup EXIT

e2e_net_up
e2e_up_target
e2e_up_phone "$STANDIN_DIR"
# WiFi, plaintext allowed (TLS also accepted: the app auto-detects)
start_peer wifi-open --listen 0.0.0.0:9001 --token PlainTok123 --keepalive-secs 1 --stats-secs 1
# WiFi as the app ships it: token + encryption required
start_peer wifi-tls --listen 0.0.0.0:9002 --token TlsTok456 --require-tls --stats-secs 1
# USB (no token; reached through the adb-forward shim)
start_peer usb --listen 0.0.0.0:8888
start_peer usb2 --listen 0.0.0.0:8890
# A phone WITHOUT ETPH Pro (ProGate): one computer over USB, no routers.
start_peer usbfree --listen 0.0.0.0:8891 --free
# The phone hangs up after 3 s (auto-reconnect scenarios)
start_peer hangup --listen 0.0.0.0:9003 --token RecTok --disconnect-after-secs 3
wait_peers wifi-open wifi-tls usb usb2 hangup

e2e_up_desktop "$BIN_DIR"
docker cp "$HERE/desktop-e2e-inner.sh" "$DESKTOP:/e2e/run.sh"
docker cp "$HERE/../../scripts/quick-start-linux.sh" "$DESKTOP:/e2e/qs/scripts/quick-start-linux.sh"

set +e
docker exec "$DESKTOP" bash /e2e/run.sh
STATUS=$?
set -e
dump_peers 30
exit $STATUS
