#!/usr/bin/env python3
"""ETPH on a real OPNsense (current release, nano image) VM: the os-etph
plugin installed with pkg, configured through its GUI page/API, the phone
(stand-in phone core on the Wi-Fi network em2) as the assignable interface
etph0 with its own gateway, in a gateway group behind the primary WAN, and a
LAN client that keeps internet when the WAN fails.

    opnsense-vm-test.py --pkgdir <dir with etphonehome-*-freebsd15-amd64.pkg,
                         os-etph-*.pkg> --standin <dir> --work <dir> [--release 26.7]

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
from opngui import OpnGui  # noqa: E402

TOKEN = "OpnSenseTok"
HERE = os.path.dirname(os.path.abspath(__file__))


def gateways(ssh):
    rc, out = ssh("pluginctl -r return_gateways_status 2>/dev/null || configctl interface gateways status")
    try:
        d = json.loads(out)
    except ValueError:
        return {}
    d = d.get("dpinger", d) if isinstance(d, dict) else d
    if isinstance(d, list):
        return {g.get("name"): g.get("status") for g in d}
    return {k: (v.get("status") if isinstance(v, dict) else v) for k, v in d.items()}


def if_bytes(ssh, ifname):
    """Bytes received on ifname (netstat's Ibytes column, found by header)."""
    rc, out = ssh(f"netstat -ibn -I {ifname}")
    lines = [l.split() for l in out.strip().splitlines() if l.strip()]
    try:
        col = lines[0].index("Ibytes")
        return max(int(l[col - len(lines[0])]) for l in lines[1:] if l[0] == ifname)
    except (ValueError, IndexError):
        return -1


def steps(a, ssh, standins):
    # ---- install: client (FreeBSD 15 ABI) + plugin -----------------------
    client = sorted(glob.glob(os.path.join(a.pkgdir, "etphonehome-*-freebsd15-amd64.pkg")))[-1]
    plugin = sorted(glob.glob(os.path.join(a.pkgdir, "os-etph-*.pkg")))[-1]
    ssh.put(client, "/tmp/etphonehome.pkg")
    ssh.put(plugin, "/tmp/os-etph.pkg")
    rc, out = ssh("pkg add /tmp/etphonehome.pkg && pkg add /tmp/os-etph.pkg && pkg info os-etph etphonehome")
    check(rc == 0 and "os-etph" in out, "pkg add etphonehome + os-etph", out)
    rc, out = ssh("pkg check -s etphonehome os-etph && configctl etph status")
    check(rc == 0 and "not running" in out, "checksums OK; configd knows the plugin's actions (etph status)", out)

    g = OpnGui()
    def form():
        try:
            b = g.page("/ui/etph/")
        except OSError as e:  # the web GUI restarts after the plugin install
            return False, str(e)
        return "etph.general.host" in b, b
    ok, page = wait_until(form, 90, 5)
    check(ok, "GUI: Interfaces > Phone uplink (ETPH) page renders its form", page[:300])
    menu = g.get("/api/core/menu/search/")
    check("Phone uplink (ETPH)" in menu, "GUI menu: the page is listed under Interfaces", menu[:300])

    # ---- assign the phone network and the phone itself (API, like the GUI)
    item = g.api("/api/interfaces/assignment/get_item")
    devs = item.get("interface", {}).get("if", {})
    check("etph0" in devs, "Interfaces: Assignments offers etph0 (ETPH phone uplink) before the phone is connected",
          json.dumps(devs)[:400])
    for dev, descr in (("em2", "PHONENET"), ("etph0", "PHONE")):
        r = g.api("/api/interfaces/assignment/add_item", {"interface": {"if": dev, "descr": descr}})
        check(r.get("result") == "saved", f"assigned {dev} as {descr}", r)
    r = g.api("/api/interfaces/assignment/reconfigure")
    check(r.get("status") == "ok", "assignments applied", r)
    ssh.put(os.path.join(HERE, "opnsense-assign.php"), "/tmp/assign.php")
    rc, out = ssh("php /tmp/assign.php interfaces && /usr/local/etc/rc.reload_all >/dev/null 2>&1; sleep 10; "
                  "ifconfig em2 | grep 'inet '", 300)
    check(rc == 0 and "198.51.100." in out, "PHONE: 10.0.0.1/24 + gateway ETPH_GW (10.0.0.2, priority 250), "
          "WAN_DHCP priority 10, gateway switching on; PHONENET em2 on the phone's Wi-Fi", out)

    # ---- the plugin: settings + service through the GUI API -------------
    s = start_standin(a.standin, TOKEN, os.path.join(a.work, "standin-opnsense.log"))
    standins.append(s)
    r = g.api("/api/etph/settings/set", {"etph": {"general": {
        "enabled": "1", "transport": "wifi", "host": "198.51.100.1", "port": "9301", "token": TOKEN}}})
    check(r.get("result") == "saved", "GUI API: settings saved", r)
    r = g.api("/api/etph/service/reconfigure")
    check(r.get("status") == "ok", "GUI API: Apply (template + service start)", r)
    ok, st = wait_until(lambda: (lambda d: (d.get("state") == "connected", d))(g.api("/api/etph/service/phone")), 90)
    check(ok and st.get("phone_pro") is True and st.get("device"),
          f"phone connected as etph0 (status page: {st.get('state')}, {st.get('device')}, ETPH Pro {st.get('phone_pro')})", st)
    r = g.api("/api/etph/service/status")
    check(r.get("status") == "running", "service status: running (Lobby/services widget)", r)
    rc, out = ssh("cat /etc/rc.conf.d/etphonehome; ls -l /usr/local/etc/etphonehome/token")
    check("--router wifi --host 198.51.100.1" in out and "--no-routes" in out,
          "template wrote the rc.conf.d (router mode, firewall owns routes/DNS)", out)
    ok, gws = wait_until(lambda: (lambda d: (d.get("ETPH_GW") == "none", d))(gateways(ssh)), 120, 5)
    check(ok, f"gateway ETPH_GW online (dpinger to the phone, 10.0.0.2): {gws}", gws)
    r = g.api("/api/routing/settings/search_gateway")
    check("ETPH_GW" in json.dumps(r), "System: Gateways lists ETPH_GW", json.dumps(r)[:600])
    # what Apply on System: Gateways: Group does: save, then re-arm the
    # gateway watcher with the new group
    rc, out = ssh("php /tmp/assign.php group && configctl interface routes configure", 300)
    # Firewall: Rules offers the new group as a gateway once configd's
    # cached gateway list has caught up (seconds): what a person retrying
    # the save would see.
    ok, out2 = wait_until(lambda: (lambda r: ("lan_rules_policy_routed=1" in r[1], r[1]))(
        ssh("php /tmp/assign.php rule 2>&1")), 120, 10)
    ssh("configctl filter reload")
    check(rc == 0 and "group=FAILOVER" in out and ok,
          "gateway group FAILOVER (WAN_DHCP tier 1, ETPH_GW tier 2); LAN rule routed through FAILOVER", out + out2)

    ok, gws = wait_until(lambda: (lambda d: (d.get("ETPH_GW") == "none" and d.get("WAN_DHCP") == "none", d))(
        gateways(ssh)), 120, 5)
    rc, out = ssh("pgrep -lf dpinger")
    check(ok and "10.0.0.2" in out and "192.0.2.1" in out,
          f"both gateways monitored by dpinger and online: {gws}", out)
    rc, out = ssh("ifconfig etph0")
    check("10.0.0.1 --> 10.0.0.2" in out, "etph0 up with 10.0.0.1 -> 10.0.0.2", out)

    # ---- a LAN client, primary WAN up ------------------------------------
    addr = lan_dhcp()
    check("192.168.1." in addr, "LAN client got an address from OPNsense", addr)
    p0 = if_bytes(ssh, "etph0")
    ok, out = wait_until(lambda: lan_curl(t=15), 60, 5)
    check(ok, "LAN client: HTTPS works through the primary WAN", out)
    rc, out = ssh("pfctl -sr | grep -n 'on em0' | grep -E 'route-to|to \\(em0\\)'")
    lines = out.strip().splitlines()
    check(len(lines) == 2 and "route-to" in lines[1] and "route-to" not in lines[0],
          "pf: LAN -> firewall (DNS) stays local, ahead of the policy-routed LAN rule", out)

    # ---- primary WAN fails ------------------------------------------------
    wan(False)
    ok, gws = wait_until(lambda: (lambda d: (d.get("WAN_DHCP") == "down", d))(gateways(ssh)), 180, 5)
    check(ok, f"WAN gateway marked down after the uplink died: {gws}", gws)
    ok, out = wait_until(lambda: lan_curl(t=15), 120, 5)
    p1 = if_bytes(ssh, "etph0")
    check(ok, "LAN client: HTTPS still works -- through the phone (failover)", out)
    check(p1 > p0, f"failover traffic crossed etph0 ({p0} -> {p1} bytes in)")
    ok, out = wait_until(lambda: (lambda o: ("etph0" in o, o))(ssh("route -n get default | grep interface")[1]), 60, 5)
    check(ok, "gateway switching: the firewall's own default route moved to the phone", out)
    rc, out = ssh("cat /var/run/etph/etph0.json")
    check('"state":"connected"' in out.replace(" ", ""), "plugin status still connected during failover", out)

    # ---- WAN back ------------------------------------------------------------
    wan(True)
    ok, gws = wait_until(lambda: (lambda d: (d.get("WAN_DHCP") == "none", d))(gateways(ssh)), 240, 5)
    check(ok, f"WAN gateway back online: {gws}", gws)
    ok, out = wait_until(lambda: lan_curl(t=15), 90, 5)
    check(ok, "LAN client: HTTPS after the WAN came back", out)
    ok, gws = wait_until(lambda: (lambda d: (d.get("ETPH_GW") == "none", d))(gateways(ssh)), 60, 5)
    rc, out = ssh("grep -c 'Tethering connection established' /var/log/etphonehome.log")
    check(ok and out.strip() == "1", f"one phone session through WAN failure and recovery, ETPH_GW online: {gws}, "
          f"sessions {out.strip()}", out)

    # ---- the phone goes away and comes back -----------------------------------
    standins.pop().terminate()
    # down, or gone from the status list with its interface
    ok, gws = wait_until(lambda: (lambda d: (d.get("ETPH_GW") != "none", d))(gateways(ssh)), 120, 5)
    check(ok, f"phone gone: ETPH_GW not online: {gws}", gws)
    standins.append(start_standin(a.standin, TOKEN, os.path.join(a.work, "standin-opnsense.log")))
    ok, st = wait_until(lambda: (lambda d: (d.get("state") == "connected", d))(g.api("/api/etph/service/phone")), 120, 5)
    ok2, gws = wait_until(lambda: (lambda d: (d.get("ETPH_GW") == "none", d))(gateways(ssh)), 120, 5)
    check(ok and ok2, f"phone back: reconnected by itself, ETPH_GW online again: {gws}", st)

    # ---- turn it off ---------------------------------------------------------
    r = g.api("/api/etph/settings/set", {"etph": {"general": {"enabled": "0"}}})
    g.api("/api/etph/service/reconfigure")
    time.sleep(5)
    rc, out = ssh("pgrep -f 'etphonehome --router' || echo stopped; grep -c 'Desktop requested disconnect' /dev/null")
    check("stopped" in out, "disabling in the GUI stops the client", out)
    check("Desktop requested disconnect" in open(os.path.join(a.work, "standin-opnsense.log")).read(),
          "the phone saw a clean disconnect")
    rc, out = ssh("pkg delete -y os-etph etphonehome && ls /usr/local/opnsense/mvc/app/controllers/OPNsense/Etph 2>&1")
    check("No such file" in out, "pkg delete removes the plugin and the client", out)


def attached(a):
    ssh = Ssh(os.path.join(a.work, "id_ed25519"))
    standins = []
    try:
        steps(a, ssh, standins)
    except Exception as e:  # noqa: BLE001
        check(False, f"aborted: {e}")
    finally:
        wan(True)
        for s in standins:
            s.terminate()
        log(f"OPNsense VM summary: {len(PASS)} passed, {len(FAIL)} failed")
    sys.exit(1 if FAIL else 0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pkgdir", required=True)
    ap.add_argument("--standin", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--release", default="26.7")
    ap.add_argument("--keep", action="store_true", help="debugging: leave the VM running at the end")
    ap.add_argument("--attach", action="store_true",
                    help="debugging: use the already running VM (root ssh on 192.168.1.1) and leave it running")
    a = ap.parse_args()
    os.makedirs(a.work, exist_ok=True)
    img = os.path.join(a.work, f"OPNsense-{a.release}-nano-amd64.img")
    if not os.path.exists(img):
        url = f"https://pkg.opnsense.org/releases/{a.release}/OPNsense-{a.release}-nano-amd64.img.bz2"
        log(f"downloading {url}")
        sh(f"curl -fsSL --retry 3 -o {img}.bz2 {url} && bunzip2 -f {img}.bz2", timeout=1800)
    disk = os.path.join(a.work, "opn-run.qcow2")
    if a.attach:
        return attached(a)
    if os.path.exists(disk):
        os.unlink(disk)
    sh(f"qemu-img create -q -f qcow2 -b {img} -F raw {disk}")
    key = os.path.join(a.work, "id_ed25519")
    if not os.path.exists(key):
        sh(f"ssh-keygen -q -t ed25519 -N '' -f {key}")
    setup_net(a.work)
    sock = os.path.join(a.work, "opn.sock")
    if os.path.exists(sock):
        os.unlink(sock)
    qemu = subprocess.Popen(qemu_cmd(disk, sock), stdout=open(os.path.join(a.work, "qemu.log"), "w"),
                            stderr=subprocess.STDOUT)
    standins = []
    ssh = Ssh(key)
    try:
        con = Console(sock, os.path.join(a.work, "serial-opnsense.log"))
        con.expect(r"login:", 600)
        check(True, f"OPNsense {a.release} booted (LAN em0, WAN em1 DHCP from the primary uplink)")
        con.send("root\n")
        con.expect(r"Password:", 30)
        con.send("opnsense\n")
        con.expect(r"Enter an option:", 120)
        con.send("8\n")
        time.sleep(3)
        con.send("/bin/sh\n")
        time.sleep(1)
        pub = open(key + ".pub").read().strip()
        con.sh(f"mkdir -p /root/.ssh && echo '{pub}' > /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys")
        # System: Settings: Administration: SSH on (LAN, root by key), the way
        # the GUI stores it, so service reloads during the test keep it up.
        b64 = base64.b64encode(open(os.path.join(HERE, "opnsense-ssh.php"), "rb").read()).decode()
        con.sh("rm -f /tmp/ssh.b64")
        for k in range(0, len(b64), 200):  # short lines: the tty's input line is limited
            con.sh(f"printf %s {b64[k:k + 200]} >> /tmp/ssh.b64")
        con.sh("b64decode -r < /tmp/ssh.b64 > /tmp/ssh.php")
        con.sh(f"php /tmp/ssh.php '{pub}' && configctl openssh restart")
        ok, _ = wait_until(lambda: (ssh("true")[0] == 0, None), 60)
        rc, ver = ssh("opnsense-version; uname -r")
        check(ok and "OPNsense" in ver, f"shell on the firewall ({ver.strip()})", ver)

        steps(a, ssh, standins)
    except Exception as e:  # noqa: BLE001
        check(False, f"aborted: {e}")
    finally:
        try:
            wan(True)
        except Exception:  # noqa: BLE001
            pass
        if not a.keep:
            ssh("shutdown -p now", 15)
            try:
                qemu.wait(60)
            except subprocess.TimeoutExpired:
                qemu.kill()
        for s in standins:
            s.terminate()
        log(f"OPNsense VM summary: {len(PASS)} passed, {len(FAIL)} failed")
        for f in FAIL:
            log(f"  failed: {f}")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
