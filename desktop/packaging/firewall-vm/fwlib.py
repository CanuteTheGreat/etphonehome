#!/usr/bin/env python3
"""Shared harness for the firewall VM tests (OPNsense, pfSense CE).

Network around the VM (all on the runner):
  em0 LAN   tap fwlan on bridge fwbr (host 192.168.1.2); netns "lanpc" is a
            LAN client (DHCP from the firewall).
  em1 WAN   tap fwwan, host 192.0.2.1/24 with DHCP (dnsmasq) and NAT to the
            internet: the primary uplink; "failing" it = link down.
  em2 PHONE tap fwph, host 198.51.100.1/24 with DHCP: the Wi-Fi network the
            phone is on; the phone core (standin) listens on the host.
"""
import os
import re
import shlex
import socket
import subprocess
import time

PASS, FAIL = [], []


def log(m):
    print(m, flush=True)


def check(ok, what, detail=""):
    (PASS if ok else FAIL).append(what)
    log(("PASS: " if ok else "FAIL: ") + what)
    if not ok and detail:
        log("    " + str(detail).replace("\n", "\n    ")[-3000:])
    return ok


def sh(cmd, check_rc=True, timeout=600):
    r = subprocess.run(cmd, shell=True, text=True, capture_output=True, timeout=timeout)
    if check_rc and r.returncode:
        raise RuntimeError(f"{cmd}: {r.stdout}{r.stderr}")
    return r


def setup_net(work):
    for c in ["ip netns del lanpc", "ip link del fwbr", "ip link del fwlan", "ip link del fwwan",
              "ip link del fwph", "ip link del lanv0", "pkill -f dnsmasq.fw"]:
        sh(c, check_rc=False)
    sh("ip link add fwbr type bridge && ip link set fwbr up && ip addr add 192.168.1.2/24 dev fwbr")
    sh("ip tuntap add dev fwlan mode tap && ip link set fwlan master fwbr && ip link set fwlan up")
    os.makedirs("/etc/netns/lanpc", exist_ok=True)
    open("/etc/netns/lanpc/resolv.conf", "w").write("nameserver 192.168.1.1\n")
    sh("ip netns add lanpc && ip link add lanv0 type veth peer name lanv1 && ip link set lanv0 master fwbr && "
       "ip link set lanv0 up && ip link set lanv1 netns lanpc && ip netns exec lanpc ip link set lo up && "
       "ip netns exec lanpc ip link set lanv1 up")
    sh("ip tuntap add dev fwwan mode tap && ip link set fwwan up && ip addr add 192.0.2.1/24 dev fwwan")
    sh("ip tuntap add dev fwph mode tap && ip link set fwph up && ip addr add 198.51.100.1/24 dev fwph")
    sh("sysctl -qw net.ipv4.ip_forward=1")
    sh("iptables -t nat -C POSTROUTING -s 192.0.2.0/24 ! -d 192.0.2.0/24 -j MASQUERADE 2>/dev/null || "
       "iptables -t nat -A POSTROUTING -s 192.0.2.0/24 ! -d 192.0.2.0/24 -j MASQUERADE")
    up = open("/etc/resolv.conf").read()
    ns = re.findall(r"^nameserver\s+(\S+)", up, re.M)
    dns = ns[0] if ns else "1.1.1.1"
    sh(f"dnsmasq --conf-file=/dev/null --pid-file={work}/dnsmasq.fw.pid --interface=fwwan --interface=fwph "
       f"--bind-interfaces --except-interface=lo --dhcp-range=fwwan,192.0.2.100,192.0.2.150,1h "
       f"--dhcp-range=fwph,198.51.100.100,198.51.100.150,1h --dhcp-option=fwph,3 "
       f"--dhcp-option=fwwan,6,192.0.2.1 --server={dns} --no-resolv --log-facility={work}/dnsmasq.log")


def wan(up):
    sh(f"ip link set fwwan {'up' if up else 'down'}")


def lan_dhcp():
    sh("ip netns exec lanpc sh -c 'pkill -f \"dhclient.*lanv1\"; true'", check_rc=False)
    for prog in ("dhclient -1 -v lanv1", "udhcpc -i lanv1 -n -q"):
        r = sh(f"ip netns exec lanpc timeout 60 {prog}", check_rc=False, timeout=90)
        if r.returncode == 0:
            break
    return sh("ip netns exec lanpc ip -4 addr show lanv1", check_rc=False).stdout


def lan_curl(url="https://example.com", t=20):
    r = sh(f"ip netns exec lanpc curl -sS -m {t} {url}", check_rc=False, timeout=t + 10)
    return r.returncode == 0 and "Example Domain" in r.stdout, (r.stdout + r.stderr)[-500:]


def start_standin(standin, token, logpath, extra=""):
    sh("pkill -f 'standi[n].*--listen 198.51.100.1:9301'", check_rc=False)
    time.sleep(1)
    f = open(logpath, "a")
    p = subprocess.Popen(
        f"exec {standin}/bin/standin --listen 198.51.100.1:9301 --token {token} --require-tls "
        f"--stats-log --stats-secs 5 --device-name 'ETPH stand-in' {extra}",
        shell=True, stdout=f, stderr=subprocess.STDOUT)
    for _ in range(150):
        if "listening on" in open(logpath).read():
            return p
        time.sleep(0.2)
    raise RuntimeError("standin did not start")


class Console:
    """Serial console over QEMU's unix socket."""

    def __init__(self, sock, logpath):
        for _ in range(100):
            try:
                self.s = socket.socket(socket.AF_UNIX)
                self.s.connect(sock)
                break
            except OSError:
                time.sleep(0.2)
        self.s.settimeout(0.5)
        self.buf = ""
        self.log = open(logpath, "a")

    def read(self):
        try:
            d = self.s.recv(65536).decode("utf-8", "replace")
        except socket.timeout:
            return ""
        self.log.write(d)
        self.log.flush()
        if "\x1b[6n" in d:  # cursor position query (pfSense's resizewin): answer 24x80
            self.s.sendall(b"\x1b[24;80R")
        self.buf += d
        return d

    def expect(self, pattern, timeout):
        end = time.time() + timeout
        while time.time() < end:
            m = re.search(pattern, self.buf)
            if m:
                self.buf = self.buf[m.end():]
                return m
            self.read()
        raise TimeoutError(f"console: no {pattern!r} in {timeout}s; last output: {self.buf[-800:]!r}")

    def send(self, text):
        self.s.sendall(text.encode())

    def sh(self, cmd, timeout=120):
        marker = f"__RC{int(time.time() * 1000) % 100000}__"
        self.buf = ""
        self.send(f"{cmd}; echo {marker}$?\n")
        m = self.expect(re.escape(marker) + r"(\d+)", timeout)
        return int(m.group(1))


class Ssh:
    def __init__(self, key, host="192.168.1.1", user="root"):
        self.key, self.host, self.user = key, host, user

    def __call__(self, cmd, timeout=180):
        full = ["ssh", "-i", self.key, "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null",
                "-o", "LogLevel=ERROR", "-o", "ConnectTimeout=10", f"{self.user}@{self.host}",
                # root's login shell is csh (OPNsense) or the console menu (pfSense)
                "/bin/sh -c " + shlex.quote(cmd)]
        try:
            r = subprocess.run(full, text=True, capture_output=True, timeout=timeout)
            return r.returncode, r.stdout + r.stderr
        except subprocess.TimeoutExpired:
            return 124, "timeout"

    def put(self, src, dst):
        sh(f"scp -i {self.key} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR "
           f"{src} {self.user}@{self.host}:{dst}")


def qemu_cmd(disk, sock, extra=(), wan_first=False):
    """em0/em1/em2 = LAN/WAN/PHONE (OPNsense's defaults), or WAN/LAN/PHONE
    with wan_first (pfSense's defaults)."""
    lan = ["-netdev", "tap,id=lan,ifname=fwlan,script=no,downscript=no", "-device", "e1000,netdev=lan"]
    wan_ = ["-netdev", "tap,id=wan,ifname=fwwan,script=no,downscript=no", "-device", "e1000,netdev=wan"]
    nics = wan_ + lan if wan_first else lan + wan_
    return ["qemu-system-x86_64", "-enable-kvm", "-m", "2048", "-smp", "2", "-display", "none",
            "-drive", f"file={disk},format=qcow2,if=virtio", *nics,
            "-netdev", "tap,id=ph,ifname=fwph,script=no,downscript=no", "-device", "e1000,netdev=ph",
            "-serial", f"unix:{sock},server,nowait", *extra]


def wait_until(fn, timeout, step=3):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        ok, last = fn()
        if ok:
            return True, last
        time.sleep(step)
    return False, last
