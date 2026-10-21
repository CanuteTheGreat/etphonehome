#!/usr/bin/env python3
"""ETPH on a real pfSense CE VM: pfSense-pkg-etph + the client installed with
pkg, configured on its GUI page (Interfaces > Phone uplink (ETPH)), the phone
(stand-in phone core on the Wi-Fi network em2) assigned as etph0 under
Interfaces > Assignments, its gateway in a gateway group behind the primary
WAN (also the default gateway), and a LAN client that keeps internet when the
WAN fails.

    pfsense-vm-test.py --pkgdir <dir with etphonehome-*-freebsd14-amd64.pkg,
                        pfSense-pkg-etph-*.pkg> --standin <dir> --work <dir>

pfSense CE 2.7.2 is the newest CE release with an offline installer (2.8
ships only the online Netgate Installer): the memstick-serial image is
installed unattended over the serial console once and cached in --work.
Needs root (taps, bridge, netns, NAT), /dev/kvm, qemu, dnsmasq, dhclient.
"""
import argparse
import base64
import glob
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fwlib import (FAIL, PASS, Console, Ssh, check, lan_curl, lan_dhcp, log, qemu_cmd, setup_net, sh,  # noqa: E402
                   start_standin, wait_until, wan)
from pfgui import PfGui  # noqa: E402

TOKEN = "PfSenseTok"
HERE = os.path.dirname(os.path.abspath(__file__))
RELEASE = "2.7.2"
IMG_URL = f"https://atxfiles.netgate.com/mirror/downloads/pfSense-CE-memstick-serial-{RELEASE}-RELEASE-amd64.img.gz"
UP = ("online", "none")


def install(work):
    """The pfSense installer, driven over the serial console (defaults:
    Auto (ZFS), stripe on the one disk)."""
    base = os.path.join(work, f"pfsense-{RELEASE}-installed.qcow2")
    if os.path.exists(base):
        return base
    img = os.path.join(work, f"pfSense-CE-memstick-serial-{RELEASE}-RELEASE-amd64.img")
    if not os.path.exists(img):
        log(f"downloading {IMG_URL}")
        sh(f"curl -fsSL --retry 3 -o {img}.gz {IMG_URL} && gunzip -f {img}.gz", timeout=1800)
    tmp = base + ".tmp"
    sh(f"rm -f {tmp} && qemu-img create -q -f qcow2 {tmp} 8G")
    sock = os.path.join(work, "pfinst.sock")
    if os.path.exists(sock):
        os.unlink(sock)
    q = subprocess.Popen(["qemu-system-x86_64", "-enable-kvm", "-m", "2048", "-smp", "2", "-display", "none",
                          "-drive", f"file={tmp},format=qcow2,if=none,id=hd", "-device", "virtio-blk-pci,drive=hd,bootindex=1",
                          "-drive", f"file={img},format=raw,if=none,id=stick,snapshot=on",
                          "-device", "virtio-blk-pci,drive=stick,bootindex=0",
                          "-nic", "none", "-serial", f"unix:{sock},server,nowait"],
                         stdout=open(os.path.join(work, "qemu-install.log"), "w"), stderr=subprocess.STDOUT)
    try:
        con = Console(sock, os.path.join(work, "serial-pfsense-install.log"))
        steps = [(r"Console type \[vt100\]:", "vt100\r", 600),
                 (r"Accept", "\r", 120),
                 (r"Install pfSense", "\r", 60),
                 (r"How would you like to partition", "\r", 60),
                 (r"Proceed with Installation", "\r", 120),
                 (r"Select Virtual Device type", "\r", 60),
                 (r"VirtIO Block Device", " ", 60),
                 (None, "\r", 0),  # OK
                 (r"Last Chance", "y", 60)]
        for pattern, keys, timeout in steps:
            if pattern:
                con.expect(pattern, timeout)
            time.sleep(3)  # dialog draws the whole screen before it reads keys
            con.send(keys)
        con.expect(r"Installation of pfSense complete|Rebooting", 1200)
        time.sleep(5)
    finally:
        q.kill()
        q.wait()
    os.rename(tmp, base)
    check(True, f"pfSense CE {RELEASE} installed from the memstick-serial image (Auto ZFS)")
    return base


def gateways(ssh):
    rc, out = ssh("php /tmp/assign.php status")
    try:
        d = json.loads(out.strip().splitlines()[-1])
    except (ValueError, IndexError):
        return {}
    return {k: v.get("status") for k, v in d.items()} if isinstance(d, dict) else {}


def if_bytes(ssh, ifname):
    """Bytes received on ifname (netstat's Ibytes column, found by header)."""
    rc, out = ssh(f"netstat -ibn -I {ifname}")
    lines = [l.split() for l in out.strip().splitlines() if l.strip()]
    try:
        col = lines[0].index("Ibytes")
        return max(int(l[col - len(lines[0])]) for l in lines[1:] if l[0] == ifname)
    except (ValueError, IndexError):
        return -1


def phase(ssh, name, marker, timeout=300):
    """Run a pfsense-assign.php phase detached: interface/filter reloads
    can drop the ssh session that started them."""
    ssh(f"rm -f /tmp/{name}.out; (nohup php /tmp/assign.php {name} > /tmp/{name}.out 2>&1 &)")
    ok, out = wait_until(lambda: (lambda o: (marker in o or "Fatal error" in o, o))(
        ssh(f"cat /tmp/{name}.out")[1]), timeout, 5)
    return ok and marker in out, out


def status(g):
    try:
        return json.loads(g.get("/status_etph.php?json=1"))
    except ValueError:
        return {}


def steps(a, ssh, standins):
    rc, ver = ssh("cat /etc/version; freebsd-version")
    check(rc == 0 and RELEASE in ver, f"shell on the firewall (pfSense {ver.split()[0] if ver else '?'}, FreeBSD "
          f"{ver.split()[-1] if ver else '?'})", ver)

    # ---- install: client (FreeBSD 14 ABI) + package -------------------------
    client = sorted(glob.glob(os.path.join(a.pkgdir, "etphonehome-*-freebsd14-amd64.pkg")))[-1]
    package = sorted(glob.glob(os.path.join(a.pkgdir, "pfSense-pkg-etph-*.pkg")))[-1]
    ssh.put(client, "/tmp/etphonehome.pkg")
    ssh.put(package, "/tmp/pfSense-pkg-etph.pkg")
    rc, out = ssh("pkg add /tmp/etphonehome.pkg && pkg add /tmp/pfSense-pkg-etph.pkg && pkg info pfSense-pkg-etph", 300)
    check(rc == 0 and "pfSense-pkg-etph" in out, "pkg add etphonehome + pfSense-pkg-etph", out)
    rc, out = ssh("pkg check -s etphonehome pfSense-pkg-etph && /usr/local/bin/etphonehome --version && "
                  "grep -c '<name>etph</name>' /conf/config.xml")
    check(rc == 0 and "etphonehome" in out.lower(), "checksums OK, the client runs on pfSense's FreeBSD; "
          "the package is registered in config.xml", out)

    g = PfGui()
    page = g.get("/pkg_edit.php?xml=etph.xml")
    check('name="host"' in page and 'name="transport"' in page and 'name="token"' in page,
          "GUI: Interfaces > Phone uplink (ETPH) page renders its form", page[-600:])
    check("pkg_edit.php?xml=etph.xml" in g.get("/interfaces_assign.php") and "Phone uplink (ETPH)" in page,
          "GUI menu: the page is listed under Interfaces")

    # ---- the network the phone is on (PHONENET, em2, DHCP) ------------------
    ok, out = phase(ssh, "phonenet", "net=opt")
    ok2, addr = wait_until(lambda: (lambda o: ("198.51.100." in o, o))(ssh("ifconfig em2 | grep 'inet '")[1]), 90, 5)
    check(ok and ok2, "PHONENET (em2) on the phone's Wi-Fi network", out + addr)

    steps_phone(a, ssh, standins)


def steps_phone(a, ssh, standins):
    g = PfGui()
    # ---- the package: settings + service through the GUI --------------------
    s = start_standin(a.standin, TOKEN, os.path.join(a.work, "standin-pfsense.log"))
    standins.append(s)
    page = g.post("/pkg_edit.php?xml=etph.xml", {"enable": "on", "transport": "wifi", "host": "198.51.100.1",
                                                 "port": "9301", "token": TOKEN, "xml": "etph.xml", "id": "0",
                                                 "submit": "Save"})
    rc, out = ssh("grep -A7 '<etph>' /conf/config.xml | grep -E 'enable|host|transport'; ls /usr/local/etc/rc.d/etph.sh")
    check("198.51.100.1" in out and "etph.sh" in out and "No such" not in out,
          "GUI: settings saved, service written and started", out + page[-600:])
    ok, st = wait_until(lambda: (lambda d: (d.get("state") == "connected", d))(status(g)), 90)
    check(ok and st.get("phone_pro") is True and st.get("device"),
          f"phone connected as etph0 (Status tab: {st.get('state')}, {st.get('device')}, ETPH Pro {st.get('phone_pro')})",
          st)
    page = g.get("/status_etph.php")
    check("ETPH stand-in" in page and "etph-status" in page, "GUI: Status tab shows the phone")
    rc, out = ssh("pgrep -lf 'etphonehome --router wifi'; cat /usr/local/etc/rc.d/etph.sh | grep -c no-routes; "
                  "ls -l /usr/local/etc/etphonehome/token")
    check("--router wifi" in out and "-rw-------" in out,
          "service runs in router mode (firewall owns routes/DNS), token file 0600", out)
    page = g.get("/status_services.php")
    check("etph" in page and "ETPH phone uplink" in page, "Status > Services lists the ETPH service")

    # ---- Interfaces > Assignments: etph0 ------------------------------------
    page = g.get("/interfaces_assign.php")
    check("etph0" in page, "Interfaces > Assignments offers etph0", page[-800:])
    page = g.post("/interfaces_assign.php", {"if_add": "etph0", "add": "add"})
    rc, out = ssh("grep -B1 -A1 '<if>etph0</if>' /conf/config.xml")
    check("etph0" in out, "assigned etph0 through the GUI", out or page[-800:])
    ok, out = phase(ssh, "interfaces", "phone=opt")
    check(ok, "PHONE enabled (IPv4 None, the link is addressed by the client), gateway ETPH_GW (10.0.0.2, monitored)", out)
    ok, gws = wait_until(lambda: (lambda d: (d.get("ETPH_GW") in UP and d.get("WAN_DHCP") in UP, d))(gateways(ssh)),
                         120, 5)
    check(ok, f"gateways online (dpinger to the phone and to the WAN gateway): {gws}", gws)
    ok, out = phase(ssh, "group", "lan_rules_policy_routed=1")
    check(ok,
          "gateway group FAILOVER (WAN_DHCP tier 1, ETPH_GW tier 2) = default gateway; LAN rule routed through it", out)
    rc, out = ssh("ifconfig etph0")
    check("10.0.0.1 --> 10.0.0.2" in out, "etph0 up with 10.0.0.1 -> 10.0.0.2", out)

    # ---- a LAN client, primary WAN up ----------------------------------------
    addr = lan_dhcp()
    check("192.168.1." in addr, "LAN client got an address from pfSense", addr)
    p0 = if_bytes(ssh, "etph0")
    ok, out = wait_until(lambda: lan_curl(t=15), 60, 5)
    check(ok, "LAN client: HTTPS works through the primary WAN", out)

    # ---- primary WAN fails ----------------------------------------------------
    wan(False)
    ok, gws = wait_until(lambda: (lambda d: (d.get("WAN_DHCP") not in (None,) + UP, d))(gateways(ssh)), 180, 5)
    check(ok, f"WAN gateway marked down after the uplink died: {gws}", gws)
    ok, out = wait_until(lambda: lan_curl(t=15), 120, 5)
    p1 = if_bytes(ssh, "etph0")
    check(ok, "LAN client: HTTPS still works -- through the phone (failover)", out)
    check(p1 > p0, f"failover traffic crossed etph0 ({p0} -> {p1} bytes in)")
    ok, out = wait_until(lambda: (lambda o: ("etph0" in o, o))(ssh("route -n get default | grep interface")[1]), 60, 5)
    check(ok, "the firewall's own default route (gateway group default) moved to the phone", out)
    st = status(g)
    check(st.get("state") == "connected", "package status still connected during failover", st)

    # ---- WAN back ---------------------------------------------------------------
    wan(True)
    ok, gws = wait_until(lambda: (lambda d: (d.get("WAN_DHCP") in UP, d))(gateways(ssh)), 240, 5)
    check(ok, f"WAN gateway back online: {gws}", gws)
    ok, out = wait_until(lambda: lan_curl(t=15), 90, 5)
    check(ok, "LAN client: HTTPS after the WAN came back", out)
    ok, gws = wait_until(lambda: (lambda d: (d.get("ETPH_GW") in UP, d))(gateways(ssh)), 60, 5)
    rc, out = ssh("grep -c 'Tethering connection established' /var/log/etphonehome.log")
    check(ok and out.strip() == "1", f"one phone session through WAN failure and recovery, ETPH_GW online: {gws}, "
          f"sessions {out.strip()}", out)

    # ---- the phone goes away and comes back -----------------------------------
    standins.pop().terminate()
    # down, or gone from the status list with its interface
    ok, gws = wait_until(lambda: (lambda d: (d.get("ETPH_GW") not in UP, d))(gateways(ssh)), 120, 5)
    check(ok, f"phone gone: ETPH_GW not online: {gws}", gws)
    standins.append(start_standin(a.standin, TOKEN, os.path.join(a.work, "standin-pfsense.log")))
    ok, st = wait_until(lambda: (lambda d: (d.get("state") == "connected", d))(status(g)), 120, 5)
    ok2, gws = wait_until(lambda: (lambda d: (d.get("ETPH_GW") in UP, d))(gateways(ssh)), 120, 5)
    check(ok and ok2, f"phone back: reconnected by itself, ETPH_GW online again: {gws}", st)

    # ---- turn it off --------------------------------------------------------------
    g.post("/pkg_edit.php?xml=etph.xml", {"transport": "wifi", "host": "198.51.100.1", "port": "9301",
                                          "token": TOKEN, "xml": "etph.xml", "id": "0", "submit": "Save"})
    ok, out = wait_until(lambda: (lambda o: ("stopped" in o, o))(
        ssh("pgrep -f 'etphonehome --router' || echo stopped")[1]), 30, 2)
    check(ok, "disabling in the GUI stops the client", out)
    check("Desktop requested disconnect" in open(os.path.join(a.work, "standin-pfsense.log")).read(),
          "the phone saw a clean disconnect")
    rc, out = ssh("pkg delete -y pfSense-pkg-etph etphonehome; ls /usr/local/pkg/etph.xml /usr/local/bin/etphonehome "
                  "2>&1; grep -c '<name>etph</name>' /conf/config.xml", 300)
    check(out.count("No such file") == 2, "pkg delete removes the package (and its menu entry) and the client", out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pkgdir", required=True)
    ap.add_argument("--standin", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--keep", action="store_true", help="debugging: leave the VM running at the end")
    ap.add_argument("--attach", action="store_true", help="debugging: the running VM, from the phone setup on")
    a = ap.parse_args()
    if a.attach:
        standins = []
        try:
            steps_phone(a, Ssh(os.path.join(a.work, "id_ed25519")), standins)
        except Exception as e:  # noqa: BLE001
            check(False, f"aborted: {e}")
        for s in standins:
            s.terminate()
        log(f"pfSense VM summary: {len(PASS)} passed, {len(FAIL)} failed")
        sys.exit(1 if FAIL else 0)
    os.makedirs(a.work, exist_ok=True)
    key = os.path.join(a.work, "id_ed25519")
    if not os.path.exists(key):
        sh(f"ssh-keygen -q -t ed25519 -N '' -f {key}")
    standins = []
    ssh = Ssh(key)
    qemu = None
    try:
        base = install(a.work)
        disk = os.path.join(a.work, "pf-run.qcow2")
        if os.path.exists(disk):
            os.unlink(disk)
        sh(f"qemu-img create -q -f qcow2 -b {base} -F qcow2 {disk}")
        setup_net(a.work)
        sock = os.path.join(a.work, "pf.sock")
        if os.path.exists(sock):
            os.unlink(sock)
        qemu = subprocess.Popen(qemu_cmd(disk, sock, wan_first=True),
                                stdout=open(os.path.join(a.work, "qemu-pf.log"), "w"), stderr=subprocess.STDOUT)
        con = Console(sock, os.path.join(a.work, "serial-pfsense.log"))
        con.expect(r"Enter an option:", 900)
        check(True, f"pfSense {RELEASE} booted (WAN em0 DHCP from the primary uplink, LAN em1)")
        con.send("8\r")
        con.expect(r"\]/root", 120)
        time.sleep(3)
        # System > Advanced > Admin Access: SSH on with a key, stored the way
        # the GUI stores it (the script is typed in short lines: tty limit).
        b64 = base64.b64encode(open(os.path.join(HERE, "pfsense-assign.php"), "rb").read()).decode()
        # FreeBSD 14.0 (pfSense 2.7.2) stalls the network stack for ~3 minutes
        # when the routing table grows past the small-table lookup algorithm
        # and the kernel switches it (log: "fib_algo ... switching algo to
        # radix4_lockless"); a kernel quirk, not ETPH: start on the final one.
        con.sh("sysctl net.route.algo.inet.algo=radix4_lockless")
        con.sh("rm -f /tmp/assign.b64")
        for k in range(0, len(b64), 200):
            con.sh(f"printf %s {b64[k:k + 200]} >> /tmp/assign.b64")
        con.sh("b64decode -r < /tmp/assign.b64 > /tmp/assign.php")
        pub = open(key + ".pub").read().strip()
        con.sh(f"php /tmp/assign.php ssh '{pub}'", 120)
        con.sh("/usr/local/sbin/pfSctl -c 'service restart sshd'", 120)
        ok, _ = wait_until(lambda: (ssh("true")[0] == 0, None), 90)
        check(ok, "SSH (key) enabled the way System > Advanced > Admin Access stores it")
        steps(a, ssh, standins)
    except Exception as e:  # noqa: BLE001
        check(False, f"aborted: {e}")
    finally:
        try:
            wan(True)
        except Exception:  # noqa: BLE001
            pass
        if qemu and not a.keep:
            ssh("shutdown -p now", 15)
            try:
                qemu.wait(90)
            except subprocess.TimeoutExpired:
                qemu.kill()
        for s in standins:
            s.terminate()
        log(f"pfSense VM summary: {len(PASS)} passed, {len(FAIL)} failed")
        for f in FAIL:
            log(f"  failed: {f}")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
