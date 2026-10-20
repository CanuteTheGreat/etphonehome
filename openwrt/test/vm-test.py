#!/usr/bin/env python3
"""End-to-end test of the ETPH router packages on a real OpenWrt VM (KVM).

    vm-test.py --release 24.10.4|25.12.2|latest-24|latest-25 --pkgs <dir>
               --standin <install dir> --work <dir> [--keep]

Boots the official OpenWrt x86_64 image with
  eth0 (LAN, br-lan 192.168.1.1) <- tap on a host bridge; a network
       namespace on that bridge plays a laptop behind the router,
  eth1 (WAN, QEMU user net) <- only used to reach the "phone": the phone's
       networking core (android/core standin) listening on the job
       container, 10.0.2.2:8888 from the VM. The WAN's own default route
       and DNS are switched off, so the router's ONLY internet is the
       tunnel through the phone.
Then: installs etph + luci-app-etph (opkg .ipk on 24.10, apk on 25.x), sets
the uplink up through the LuCI backend (Wi-Fi, TLS, token), and checks:
netifd interface up with a default route + the phone's DNS, the status file
(phone name, bytes), HTTPS from the router AND from the LAN laptop through
the phone, LuCI's rpcd ACL (file.exec of the helper with a real LuCI
session), auto-reconnect after the phone goes away, SIGUSR1 retry-now,
the USB transport's adb path (real OpenWrt adb package, procd adb server,
no phone -> clear error), unpair, and removal.

Prints PASS/FAIL lines; exit 1 on any failure. Needs root (tap/bridge/netns)
and /dev/kvm; Python stdlib + qemu-system-x86_64, ip, udhcpc or dhclient, curl.
"""
import argparse
import gzip
import http.server
import json
import os
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request

FAILS = []
TOKEN = "RouterTok3nE2E0ok"
DL = "https://downloads.openwrt.org"


def log(*a):
    print(*a, flush=True)


def check(ok, what, detail=""):
    log(("PASS: " if ok else "FAIL: ") + what + (f" -- {detail}" if (detail and not ok) else ""))
    if not ok:
        FAILS.append(what)
    return ok


def sh(cmd, check_rc=True, **kw):
    r = subprocess.run(cmd, shell=True, text=True, capture_output=True, **kw)
    if check_rc and r.returncode != 0:
        raise RuntimeError(f"{cmd}: rc={r.returncode} {r.stdout} {r.stderr}")
    return r


def resolve_release(rel):
    if not rel.startswith("latest-"):
        return rel
    series = rel.split("-", 1)[1]
    v = json.load(urllib.request.urlopen(DL + "/.versions.json", timeout=30))
    cands = [x for x in v["versions_list"] if x.startswith(series + ".") and "rc" not in x]
    cands.sort(key=lambda s: [int(p) for p in s.split(".")])
    return cands[-1]


class Console:
    """Serial console over a unix socket, read continuously (an unread
    socket blocks the guest's console writes)."""

    def __init__(self, path, logpath):
        self.buf = b""
        self.lock = threading.Lock()
        self.log = open(logpath, "ab")
        for _ in range(100):
            try:
                self.s = socket.socket(socket.AF_UNIX)
                self.s.connect(path)
                break
            except OSError:
                time.sleep(0.2)
        threading.Thread(target=self._reader, daemon=True).start()
        self.n = 0
        # Long commands go through a file served over the LAN: busybox's line
        # editor cuts input lines at a few hundred characters.
        self.stage_dir = None

    def _reader(self):
        while True:
            try:
                d = self.s.recv(4096)
            except OSError:
                return
            if not d:
                return
            self.log.write(d)
            self.log.flush()
            with self.lock:
                self.buf += d

    def expect(self, needle, timeout):
        end = time.time() + timeout
        nb = needle.encode()
        while time.time() < end:
            with self.lock:
                i = self.buf.find(nb)
                if i >= 0:
                    out = self.buf[:i]
                    self.buf = self.buf[i + len(nb):]
                    return out.decode(errors="replace")
            time.sleep(0.1)
        raise TimeoutError(f"console: '{needle}' not seen in {timeout}s")

    def send(self, s):
        self.s.sendall(s.encode())

    def sh(self, cmd, timeout=120):
        """Run cmd in the guest shell; returns (rc, output). The markers are
        printed by echo from split strings ("ETPH""RC1Z"), so the console's
        echo of the typed command line can never match them."""
        self.n += 1
        tag = f"ETPHRC{self.n}Z"
        if len(cmd) > 200 and self.stage_dir:
            name = f"cmd{self.n}.sh"
            with open(os.path.join(self.stage_dir, name), "w") as f:
                f.write(cmd + "\n")
            cmd = f"wget -q -O /tmp/{name} http://192.168.1.2:8000/{name} && . /tmp/{name}"
        with self.lock:
            self.buf = b""
        q = f'"ETPH""RC{self.n}Z'
        self.send(f'( {cmd} ) >/tmp/.o 2>&1; echo {q}=$?="; echo {q}B"; cat /tmp/.o; echo {q}E"\n')
        rcpart = self.expect(f"{tag}B", timeout)
        rc = -1
        for line in rcpart.replace("\r", "").splitlines():
            if line.startswith(tag + "=") and line.endswith("="):
                try:
                    rc = int(line[len(tag) + 1:-1])
                except ValueError:
                    pass
        out = self.expect(f"{tag}E", timeout)
        return rc, out.replace("\r", "").strip("\n")


def serve_dir(directory, addr, port):
    handler = lambda *a, **k: http.server.SimpleHTTPRequestHandler(*a, directory=directory, **k)
    httpd = http.server.ThreadingHTTPServer((addr, port), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd


def start_standin(standin, extra=""):
    logf = open("/tmp/etph-vm-standin.log", "a")
    p = subprocess.Popen(
        f"exec {standin}/bin/standin --listen 0.0.0.0:8888 --token {TOKEN} --require-tls "
        f"--device-name 'ETPH stand-in phone' --keepalive-secs 5 {extra}",
        shell=True, stdout=logf, stderr=subprocess.STDOUT)
    for _ in range(120):
        try:
            socket.create_connection(("127.0.0.1", 8888), 1).close()
            return p
        except OSError:
            time.sleep(0.5)
    try:
        print(open("/tmp/etph-vm-standin.log").read()[-2000:])
    except OSError:
        pass
    raise RuntimeError("standin did not start")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--release", required=True)
    ap.add_argument("--pkgs", required=True)
    ap.add_argument("--standin", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--keep", action="store_true")
    a = ap.parse_args()
    rel = resolve_release(a.release)
    apk = int(rel.split(".")[0]) >= 25
    os.makedirs(a.work, exist_ok=True)
    log(f"== OpenWrt {rel} x86_64 ({'apk' if apk else 'opkg'})")

    img = os.path.join(a.work, f"openwrt-{rel}.img")
    if not os.path.exists(img):
        base = f"{DL}/releases/{rel}/targets/x86/64/"
        name = f"openwrt-{rel}-x86-64-generic-ext4-combined.img.gz"
        gz = img + ".gz"
        sh(f"curl -fsSL --retry 3 -o {gz} {base}{name}")
        with gzip.open(gz) as src, open(img, "wb") as dst:
            try:
                shutil.copyfileobj(src, dst)
            except (EOFError, OSError):
                pass  # trailing garbage after the gzip stream (OpenWrt pads images)
        sh(f"qemu-img resize -f raw {img} 512M")
    # Every run boots a fresh copy-on-write overlay of the pristine image.
    disk = os.path.join(a.work, f"run-{rel}.qcow2")
    if os.path.exists(disk):
        os.unlink(disk)
    sh(f"qemu-img create -q -f qcow2 -b {img} -F raw {disk}")

    # LAN: tap + veth on a bridge; the "laptop" lives in netns etphlan.
    for c in ["ip netns del etphlan", "ip link del etphbr", "ip link del etphtap", "ip link del etphv0"]:
        sh(c, check_rc=False)
    sh("ip link add etphbr type bridge && ip link set etphbr up && ip addr add 192.168.1.2/24 dev etphbr")
    sh("ip tuntap add dev etphtap mode tap && ip link set etphtap master etphbr && ip link set etphtap up")
    sh("ip netns add etphlan && ip link add etphv0 type veth peer name etphv1 && "
       "ip link set etphv0 master etphbr && ip link set etphv0 up && ip link set etphv1 netns etphlan && "
       "ip netns exec etphlan ip link set lo up && ip netns exec etphlan ip link set etphv1 up")

    www = os.path.join(a.work, "www")
    shutil.rmtree(www, ignore_errors=True)
    shutil.copytree(a.pkgs, www)
    httpd = serve_dir(www, "192.168.1.2", 8000)
    standin = start_standin(a.standin)

    sock = os.path.join(a.work, "serial.sock")
    if os.path.exists(sock):
        os.unlink(sock)
    serial_log = os.path.join(a.work, f"serial-{rel}.log")
    qemu = subprocess.Popen([
        "qemu-system-x86_64", "-enable-kvm", "-m", "256", "-smp", "2", "-nographic", "-monitor", "none",
        "-drive", f"file={disk},format=qcow2,if=virtio",
        "-netdev", "tap,id=lan,ifname=etphtap,script=no,downscript=no",
        "-device", "virtio-net-pci,netdev=lan",
        "-netdev", "user,id=wan",
        "-device", "virtio-net-pci,netdev=wan",
        "-serial", f"unix:{sock},server,nowait",
    ], stdout=open(os.path.join(a.work, "qemu.log"), "w"), stderr=subprocess.STDOUT)
    try:
        con = Console(sock, serial_log)
        con.stage_dir = www
        con.expect("Please press Enter to activate this console", 180)
        time.sleep(3)
        con.send("\n")
        time.sleep(2)
        rc, _ = con.sh("ubus -t 120 wait_for network.interface.lan")
        # 25.12.5+ may come up without wan (config_generate before NIC modules).
        con.sh("uci -q get network.wan || { uci set network.wan=interface; uci set network.wan.device=eth1; "
               "uci set network.wan.proto=dhcp; uci commit network; /etc/init.d/network reload; }")
        rc, out = con.sh("for i in $(seq 60); do ip -4 route | grep -q '^default' && break; sleep 2; done; ip -4 route", 200)
        check("default via" in out, "VM booted with a WAN (QEMU user net) for package downloads", out)

        # kmod-tun (and adb for the USB leg) from the official feeds, while the WAN still routes.
        if apk:
            rc, out = con.sh("for i in 1 2 3; do apk update && break; sleep 10; done; apk add kmod-tun mwan3", 600)
        else:
            rc, out = con.sh("for i in 1 2 3; do opkg update && break; sleep 10; done; opkg install kmod-tun mwan3", 600)
        check(rc == 0, "kmod-tun and mwan3 installed from the OpenWrt feeds (no adb package: built-in ADB host)", out[-800:])
        # mwan3 starts with a config for wan/wan6 only; it gets switched on for the phone later.
        con.sh("/etc/init.d/mwan3 stop; /etc/init.d/mwan3 disable", 120)

        # Our packages over HTTP from the host bridge.
        rc, out = con.sh("cd /tmp && rm -f etph* luci-app-etph* && "
                         "wget -q -O - http://192.168.1.2:8000/index.txt", 60)
        files = [f for f in out.split() if f.endswith(".apk" if apk else ".ipk")
                 and ("_x86_64." in f or f.startswith("luci-app-etph"))]
        con.sh("cd /tmp && " + " && ".join(f"wget -q http://192.168.1.2:8000/{f}" for f in files), 120)
        if apk:
            rc, out = con.sh("cd /tmp && apk add --allow-untrusted " + " ".join(files), 300)
        else:
            rc, out = con.sh("cd /tmp && opkg install " + " ".join(files), 300)
        check(rc == 0, f"etph + luci-app-etph packages install ({', '.join(files)})", out[-1500:])
        rc, out = con.sh("etphonehome --version; ls -l /usr/sbin/etphonehome /lib/netifd/proto/etph.sh "
                         "/usr/libexec/etph-luci /www/luci-static/resources/view/etph/status.js")
        check(rc == 0 and "etphonehome" in out, "client, netifd handler and LuCI files in place", out)

        # The phone is the only internet from here on.
        con.sh("uci set network.wan.defaultroute=0; uci set network.wan.peerdns=0; "
               "uci -q delete network.wan6; uci commit network; /etc/init.d/network reload; sleep 5")
        rc, out = con.sh("ip -4 route; wget -q -T 8 -O /dev/null http://example.com && echo LEAK")
        check("default" not in out and "LEAK" not in out, "router has no internet before the phone uplink", out)

        # Set up through the LuCI backend, exactly what the page's "Set up" button runs.
        rc, out = con.sh(f"ETPH_PAIR_TOKEN={TOKEN} /usr/libexec/etph-luci setup phone wifi 10.0.2.2", 60)
        check('"ok": true' in out, "LuCI setup creates the phone uplink", out)
        time.sleep(15)  # first install: netifd restarts to load proto etph
        con.send("\n")
        rc, out = con.sh("for i in $(seq 60); do ifstatus phone | grep -q '\"up\": true' && break; sleep 2; done; ifstatus phone", 200)
        st = {}
        try:
            st = json.loads(out[out.index("{"):])
        except ValueError:
            pass
        if not isinstance(st, dict):
            st = {}
        check(st.get("up") is True, "netifd interface 'phone' (proto etph) is up", out[-1500:])
        check(st.get("l3_device") == "etph-phone", "its device is the tunnel etph-phone", str(st.get("l3_device")))
        routes = st.get("route", [])
        check(any(r.get("target") == "0.0.0.0" and r.get("nexthop") == "10.0.0.2" for r in routes),
              "netifd installed the default route via the phone", json.dumps(routes))
        check("8.8.8.8" in st.get("dns-server", []), "the phone's DNS server went to netifd/dnsmasq", str(st.get("dns-server")))
        check(st.get("data", {}).get("phone") == "ETPH stand-in phone", "phone name reached netifd (handshake device name)",
              json.dumps(st.get("data")))
        rc, out = con.sh("uci show firewall | grep -E \"network=.*phone\"")
        check(rc == 0, "uplink is in the wan firewall zone (masquerade)", out)
        rc, out = con.sh("ip route show default; ps w | grep -v grep | grep etphonehome")
        check("etph-phone" in out and "RouterTok3n" not in out, "client runs under netifd with the token kept out of ps", out)

        rc, out = con.sh("wget -q -T 20 -O - https://example.com | grep -o '<title>[^<]*'", 60)
        check("Example Domain" in out, "router: HTTPS through the phone (DNS via the phone too)", out)

        # The laptop behind the router.
        dh = "udhcpc -i etphv1 -n -q -t 10" if shutil.which("udhcpc") else "dhclient -1 -v etphv1"
        r = sh(f"ip netns exec etphlan {dh}", check_rc=False)
        r2 = sh("ip netns exec etphlan ip -4 addr show etphv1; ip netns exec etphlan ip route", check_rc=False)
        check("192.168.1." in r2.stdout and "default" in r2.stdout, "LAN laptop got DHCP from the router", r.stdout + r.stderr + r2.stdout)
        # Use the router's DNS explicitly (the netns shares the container's resolv.conf).
        r = sh("ip netns exec etphlan sh -c \"IP=\\$(nslookup example.com 192.168.1.1 2>/dev/null | awk '/^Address/ && !/#/ {print \\$2; exit}'); "
               "echo resolved=\\$IP; curl -s -m 30 --resolve example.com:443:\\$IP https://example.com\"", check_rc=False)
        check("Example Domain" in r.stdout, "LAN laptop: router DNS + HTTPS through router -> phone", (r.stdout + r.stderr)[-600:])
        r = sh("ip netns exec etphlan curl -s -o /dev/null -w '%{size_download} %{speed_download}' -m 60 "
               "'https://speed.cloudflare.com/__down?bytes=1048576'", check_rc=False)
        check(r.stdout.startswith("1048576"), "LAN laptop: 1 MiB download through the phone intact", r.stdout + r.stderr)

        rc, out = con.sh("sleep 3; cat /var/run/etph/phone.json; echo; echo TUNRX $(cat /sys/class/net/etph-phone/statistics/rx_bytes)")
        try:
            s = json.loads(out[out.index("{"):out.rindex("}") + 1])
        except ValueError:
            s = {}
        check(s.get("state") == "connected" and s.get("device") == "ETPH stand-in phone" and s.get("bytes_received", 0) > 1000000
              and s.get("encrypted") is True, "status file: connected, phone name, encrypted, byte counters",
              json.dumps({k: s.get(k) for k in ("state", "device", "encrypted", "bytes_received")}) + " " + out[:200])

        # mwan3 (failover / load balancing) sees the uplink like any WAN; it
        # tracks the phone's tunnel address, which the phone's relay answers.
        rc, out = con.sh("uci -q delete mwan3.wan; uci -q delete mwan3.wan6; uci -q delete mwan3.wanb; uci -q delete mwan3.wanb6; "
                         "uci set mwan3.phone=interface; uci set mwan3.phone.enabled=1; uci add_list mwan3.phone.track_ip=10.0.0.2; "
                         "uci set mwan3.phone.family=ipv4; uci set mwan3.phone_m1=member; uci set mwan3.phone_m1.interface=phone; "
                         "uci set mwan3.phone_m1.metric=1; uci set mwan3.phone_m1.weight=1; "
                         "uci set mwan3.phone_only=policy; uci add_list mwan3.phone_only.use_member=phone_m1; "
                         "for r in $(uci show mwan3 | sed -n 's/^mwan3[.]\\([^.]*\\)=rule$/\\1/p'); do uci set mwan3.$r.use_policy=phone_only; done; "
                         "uci commit mwan3; /etc/init.d/mwan3 enable; /etc/init.d/mwan3 start; "
                         "for i in $(seq 40); do mwan3 interfaces 2>/dev/null | grep -q 'interface phone is online' && break; sleep 2; done; "
                         "mwan3 interfaces", 300)
        check("interface phone is online" in out, "mwan3 tracks the phone uplink as online", out[-800:])
        rc, out = con.sh("wget -q -T 20 -O - https://example.com | grep -o '<title>[^<]*'", 60)
        check("Example Domain" in out, "router: HTTPS through the phone with mwan3 policy routing on", out)
        con.sh("/etc/init.d/mwan3 stop; /etc/init.d/mwan3 disable", 60)

        # LuCI: a real rpcd session runs the page's backend (ACL check).
        r = sh("ip netns exec etphlan curl -s -m 20 http://192.168.1.1/ubus -d "
               "'{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"call\",\"params\":[\"00000000000000000000000000000000\",\"session\",\"login\",{\"username\":\"root\",\"password\":\"\"}]}'",
               check_rc=False)
        sid = ""
        try:
            sid = json.loads(r.stdout)["result"][1]["ubus_rpc_session"]
        except Exception:
            pass
        check(bool(sid), "LuCI login (rpcd session)", r.stdout[-300:])
        r = sh("ip netns exec etphlan curl -s -m 30 http://192.168.1.1/ubus -d "
               f"'{{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"call\",\"params\":[\"{sid}\",\"file\",\"exec\",{{\"command\":\"/usr/libexec/etph-luci\",\"params\":[\"status\"]}}]}}'",
               check_rc=False)
        ok = False
        try:
            res = json.loads(r.stdout)["result"]
            st2 = json.loads(res[1]["stdout"])
            sess = json.loads(st2["interfaces"][0]["session"])
            ok = res[0] == 0 and sess["state"] == "connected" and "usb_phones" in st2 and bool(st2.get("adb_fingerprint"))
        except Exception as e:
            r.stdout += f" ({e})"
        check(ok, "LuCI page backend via rpcd ACL: status shows the connected phone", r.stdout[-800:])
        r = sh("ip netns exec etphlan curl -s -o /dev/null -w '%{http_code}' http://192.168.1.1/luci-static/resources/view/etph/status.js", check_rc=False)
        check(r.stdout == "200", "LuCI view is served", r.stdout)

        # The phone goes away -> interface down, the client keeps retrying.
        standin.terminate()
        standin.wait()
        rc, out = con.sh("for i in $(seq 45); do ifstatus phone | grep -q '\"up\": false' && break; sleep 2; done; "
                         "ifstatus phone | grep '\"up\"'; echo ROUTES; ip route show default; echo STATUS; cat /var/run/etph/phone.json", 120)
        routes = out.split("ROUTES")[-1].split("STATUS")[0]
        check('"up": false' in out and "etph-phone" not in routes, "phone gone: uplink down, default route removed", out)
        check('"retrying"' in out, "client keeps retrying (status file says so)", out)
        # It comes back by itself (backoff, then SIGUSR1 "Reconnect now" for the second time).
        standin = start_standin(a.standin)
        t0 = time.time()
        rc, out = con.sh("for i in $(seq 75); do ifstatus phone | grep -q '\"up\": true' && break; sleep 2; done; ifstatus phone | grep '\"up\"'", 200)
        check('"up": true' in out, f"phone back: uplink up again by itself ({int(time.time() - t0)}s)", out)
        rc, out = con.sh("wget -q -T 20 -O - https://example.com | grep -o '<title>[^<]*'", 60)
        check("Example Domain" in out, "internet through the phone again after the reconnect", out)
        standin.terminate()
        standin.wait()
        con.sh("for i in $(seq 45); do ifstatus phone | grep -q '\"up\": false' && break; sleep 2; done", 120)
        time.sleep(20)  # let the backoff grow past a few seconds
        standin = start_standin(a.standin)
        t0 = time.time()
        rc, out = con.sh("/usr/libexec/etph-luci retry phone; for i in $(seq 30); do ifstatus phone | grep -q '\"up\": true' && break; sleep 1; done; "
                         "ifstatus phone | grep '\"up\"'", 60)
        check('"up": true' in out and time.time() - t0 < 25, f"'Reconnect now' (SIGUSR1) skips the backoff ({int(time.time() - t0)}s)", out)

        # USB transport: the client's own ADB host, no adb package, no phone plugged in.
        rc, out = con.sh("for i in $(seq 120); do [ -s /etc/etph/.android/adbkey.pub ] && break; sleep 2; done; "
                         "ls -l /etc/etph/.android/; HOME=/etc/etph etphonehome adb-fingerprint; etphonehome usb-list --json; "
                         "command -v adb || echo NO-ADB-TOOL", 300)
        check("adbkey.pub" in out and out.count(":") >= 15, "router created its adb key at install and shows the fingerprint the phone will ask about", out)
        check("NO-ADB-TOOL" in out and "[]" in out, "USB needs no adb package; usb-list finds no phone in the VM", out)
        con.sh("uci set network.usbphone=interface; uci set network.usbphone.proto=etph; "
               "uci set network.usbphone.transport=usb; uci set network.usbphone.ipaddr=10.0.9.1/24; uci commit network; "
               "/etc/init.d/network reload; sleep 12")
        rc, out = con.sh("cat /var/run/etph/usbphone.json; ps w | grep -v grep | grep 'usb --native'", 60)
        check('"transport": "usb"' in out and "no phone with USB debugging" in out and '"retrying"' in out
              and "usb --native" in out,
              "USB uplink without a phone: built-in ADB host says to plug in a phone with USB debugging, keeps retrying", out)
        con.sh("ifdown usbphone; uci delete network.usbphone; uci commit network; /etc/init.d/network reload")

        # Unpair and remove.
        rc, out = con.sh("/usr/libexec/etph-luci unpair phone; uci -q get network.phone.token || echo NOTOKEN; ifstatus phone | grep '\"up\"'")
        check("NOTOKEN" in out and '"up": false' in out, "Unpair forgets the token and stops the uplink", out)
        rc, out = con.sh("/usr/libexec/etph-luci remove phone; uci -q get network.phone || echo GONE; uci show firewall | grep -c phone")
        check("GONE" in out, "Remove deletes the interface and its firewall zone entry", out)
        if apk:
            rc, out = con.sh("apk del luci-app-etph etph && ! test -e /usr/sbin/etphonehome && echo UNINSTALLED", 120)
        else:
            rc, out = con.sh("opkg remove luci-app-etph etph && ! test -e /usr/sbin/etphonehome && echo UNINSTALLED", 120)
        check("UNINSTALLED" in out, "packages uninstall cleanly", out)
        con.send("poweroff\n")
        time.sleep(5)
    except Exception as e:  # noqa: BLE001
        check(False, f"test aborted: {e}")
    finally:
        if qemu.poll() is None:
            qemu.kill()
        standin.terminate()
        httpd.shutdown()
        if not a.keep:
            for c in ["ip netns del etphlan", "ip link del etphbr", "ip link del etphtap"]:
                sh(c, check_rc=False)
    log(f"== OpenWrt {rel}: {len(FAILS)} failure(s)")
    for f in FAILS:
        log("  - " + f)
    sys.exit(1 if FAILS else 0)


if __name__ == "__main__":
    main()
