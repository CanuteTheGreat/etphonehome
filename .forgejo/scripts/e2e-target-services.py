#!/usr/bin/env python3
"""Deterministic services on the e2e `target` container (e2e-lib.sh).

  UDP :7001  echo every datagram back to its sender (one socket, no fork per
             datagram, large receive buffer: socat's UDP-RECVFROM,fork lost
             about half of a 50-flow burst, which measured socat, not the
             relay)
  TCP :7002  read until the client half-closes, answer with the sha256 hex
             digest of everything received (upload integrity)
"""
import hashlib
import socket
import threading


def udp_echo():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 8 << 20)
    s.bind(("0.0.0.0", 7001))
    while True:
        data, addr = s.recvfrom(65535)
        s.sendto(data, addr)


def sha_sink_client(c):
    with c:
        h = hashlib.sha256()
        while True:
            d = c.recv(1 << 20)
            if not d:
                break
            h.update(d)
        c.sendall(h.hexdigest().encode() + b"\n")


def sha_sink():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", 7002))
    s.listen(64)
    while True:
        c, _ = s.accept()
        threading.Thread(target=sha_sink_client, args=(c,), daemon=True).start()


threading.Thread(target=udp_echo, daemon=True).start()
sha_sink()
