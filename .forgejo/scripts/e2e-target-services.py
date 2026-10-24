#!/usr/bin/env python3
"""Deterministic services on the e2e `target` container (e2e-lib.sh).

  UDP :7001  echo every datagram back to its sender (one socket, no fork per
             datagram, large receive buffer: socat's UDP-RECVFROM,fork lost
             about half of a 50-flow burst, which measured socat, not the
             relay)
  TCP :7002  read until the client half-closes, answer with the sha256 hex
             digest of everything received (upload integrity)
  UDP :53    a stub DNS server for the "20 parallel downloads by NAME" checks
             (the real-phone QA's failing step): every A question for a
             *.etph.test name is answered with $DNS_ANSWER (default: the
             address this host uses to reach the asker); every other
             question (AAAA, ...) gets an empty NOERROR answer, so a resolver
             that asks both families like glibc's getaddrinfo does is
             satisfied the way a real name server would. Bound to $DNS_BIND
             (default 0.0.0.0).
"""
import hashlib
import os
import socket
import struct
import threading


def udp_echo():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 8 << 20)
    s.bind(("0.0.0.0", 7001))
    while True:
        data, addr = s.recvfrom(65535)
        s.sendto(data, addr)


def dns_reply(query, answer_ip):
    """Reply to one DNS query (A -> answer_ip; anything else: no data)."""
    if len(query) < 12:
        return None
    ident, flags, qd = struct.unpack("!HHH", query[:6])
    if qd != 1 or flags & 0x8000:
        return None
    i = 12
    while i < len(query) and query[i]:
        i += query[i] + 1
    qend = i + 5
    if qend > len(query):
        return None
    qtype = struct.unpack("!H", query[i + 1 : i + 3])[0]
    question = query[12:qend]
    name = query[12:i]
    is_ours = name.endswith(b"\x04etph\x04test")
    if qtype == 1 and is_ours:
        rd = flags & 0x0100
        head = struct.pack("!HHHHHH", ident, 0x8180 if rd else 0x8080, 1, 1, 0, 0)
        rr = b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 30, 4) + socket.inet_aton(answer_ip)
        return head + question + rr
    head = struct.pack("!HHHHHH", ident, 0x8180 if flags & 0x0100 else 0x8080, 1, 0, 0, 0)
    return head + question


def dns_server():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 8 << 20)
    s.bind((os.environ.get("DNS_BIND", "0.0.0.0"), 53))
    while True:
        data, addr = s.recvfrom(4096)
        ip = os.environ.get("DNS_ANSWER")
        if not ip:
            probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            probe.connect(addr)
            ip = probe.getsockname()[0]
            probe.close()
        r = dns_reply(data, ip)
        if r:
            s.sendto(r, addr)


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
threading.Thread(target=dns_server, daemon=True).start()
sha_sink()
