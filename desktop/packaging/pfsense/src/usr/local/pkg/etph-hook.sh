#!/bin/sh
# ETPH up/down hook (`etph-hook.sh up|down`, ETPH_IFNAME=etph0): the phone
# link came or went. Re-arm what depends on the interface -- gateway
# monitoring (dpinger binds to the link's address), routes, firewall rules --
# in the background so the client isn't held up. Not "interface newip":
# rc.newwanip restarts every package when an address changes, which would
# restart this service on every reconnect.
nohup /usr/local/bin/php -f /usr/local/pkg/etph-linkchange.php "${1:-up}" "${ETPH_IFNAME:-etph0}" \
    >/dev/null 2>&1 &
exit 0
