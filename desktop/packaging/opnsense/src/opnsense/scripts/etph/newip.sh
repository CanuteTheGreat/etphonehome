#!/bin/sh
# ETPH up/down hook (`newip.sh up|down`, ETPH_IFNAME=etph0): let OPNsense
# (re)configure the interface assigned to etph0 -- gateway monitoring
# (dpinger binds to the link's address), routes, firewall -- whenever the
# phone link comes or goes.
#
# "up" is forced: a reconnecting phone recreates etph0 with the SAME address
# (10.0.0.1), and an unforced rc.newwanip stops at "No IP change detected",
# leaving dpinger on the destroyed device and ETPH_GW down for good.
# Backgrounded (all fds closed) so the client isn't held up meanwhile.
IF="${ETPH_IFNAME:-etph0}"
if [ "${1:-up}" = "down" ]; then
    nohup /usr/local/sbin/configctl interface newip "$IF" </dev/null >/dev/null 2>&1 &
else
    nohup /usr/local/sbin/configctl interface newip "$IF" force </dev/null >/dev/null 2>&1 &
fi
exit 0
