#!/usr/bin/env python3
"""ETPH on a real FreeBSD VM against the phone core.

    vm-test.py --pkg <etphonehome-*.pkg> --standin <standin install dir>
               --work <dir> [--release 14.4-RELEASE]

Boots FreeBSD's official cloud-init VM image under KVM (QEMU user network;
the host is 10.0.2.2 from inside), installs our package with pkg(8), and
connects to the phone's networking core (android/core standin) running on
the host, the way a FreeBSD box (or a pfSense/OPNsense firewall) reaches a
phone over Wi-Fi:

* package: pkg add, pkg check -s (file checksums), rc.d script, usb-list
  through libusb;
* CLI: the VM's default route is deleted first, so the tunnel is its only
  way out: etph0 (tun(4) renamed), /1 routes, resolvconf DNS, HTTPS + DNS
  through the phone, clean teardown on SIGINT;
* service: rc.d `service etphonehome start`, `etphonehome ctl connect` to a
  saved profile, status (phone, ETPH Pro), disconnect;
* Pro: a phone without ETPH Pro refuses Wi-Fi with the phone's reason.

Needs /dev/kvm, qemu-system-x86_64, qemu-img, xorriso (or genisoimage),
ssh/scp, xz, curl.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import time

TOKEN = "FreeBSDVmTok"
PASS, FAIL = [], []
SSH_PORT = 2522


def log(msg):
    print(msg, flush=True)


def check(ok, what, detail=""):
    (PASS if ok else FAIL).append(what)
    log(("PASS: " if ok else "FAIL: ") + what)
    if not ok and detail:
        log("    " + detail.replace("\n", "\n    ")[-3000:])


def sh(cmd, check_rc=True, **kw):
    r = subprocess.run(cmd, shell=True, text=True, capture_output=True, **kw)
    if check_rc and r.returncode != 0:
        raise RuntimeError(f"{cmd}: {r.stdout}{r.stderr}")
    return r


class Vm:
    def __init__(self, key):
        self.key = key

    def ssh(self, cmd, timeout=120):
        full = ["ssh", "-i", self.key, "-p", str(SSH_PORT), "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=/dev/null", "-o", "LogLevel=ERROR", "-o", "ConnectTimeout=10",
                "root@127.0.0.1", cmd]
        try:
            r = subprocess.run(full, text=True, capture_output=True, timeout=timeout)
            return r.returncode, r.stdout + r.stderr
        except subprocess.TimeoutExpired as e:
            return 124, f"timeout: {e}"

    def scp(self, src, dst):
        sh(f"scp -i {self.key} -P {SSH_PORT} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null "
           f"-o LogLevel=ERROR {src} root@127.0.0.1:{dst}")


def start_standin(standin, port, extra, logpath):
    f = open(logpath, "a")
    p = subprocess.Popen(
        f"exec {standin}/bin/standin --listen 0.0.0.0:{port} --token {TOKEN} --require-tls "
        f"--stats-log --stats-secs 2 --device-name 'ETPH stand-in' {extra}",
        shell=True, stdout=f, stderr=subprocess.STDOUT)
    for _ in range(150):
        if "listening on" in open(logpath).read():
            return p
        time.sleep(0.2)
    raise RuntimeError("standin did not start: " + open(logpath).read()[-2000:])


def wait_for(vm, cmd, what, timeout=60):
    end = time.time() + timeout
    out = ""
    while time.time() < end:
        rc, out = vm.ssh(cmd, 30)
        if rc == 0:
            return True, out
        time.sleep(2)
    return False, out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pkg", required=True)
    ap.add_argument("--standin", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--release", default="14.4-RELEASE")
    a = ap.parse_args()
    os.makedirs(a.work, exist_ok=True)
    rel = a.release
    name = f"FreeBSD-{rel}-amd64-BASIC-CLOUDINIT-ufs.qcow2"
    img = os.path.join(a.work, name)
    if not os.path.exists(img):
        url = f"https://download.freebsd.org/releases/VM-IMAGES/{rel}/amd64/Latest/{name}.xz"
        log(f"downloading {url}")
        sh(f"curl -fsSL --retry 3 -o {img}.xz {url} && xz -d -f {img}.xz")
    disk = os.path.join(a.work, "run.qcow2")
    if os.path.exists(disk):
        os.unlink(disk)
    sh(f"qemu-img create -q -f qcow2 -b {img} -F qcow2 {disk} 8G")

    key = os.path.join(a.work, "id_ed25519")
    if not os.path.exists(key):
        sh(f"ssh-keygen -q -t ed25519 -N '' -f {key}")
    pub = open(key + ".pub").read().strip()
    cid = os.path.join(a.work, "cidata")
    shutil.rmtree(cid, ignore_errors=True)
    os.makedirs(cid)
    open(os.path.join(cid, "meta-data"), "w").write("instance-id: etph-ci\nlocal-hostname: etph-freebsd\n")
    open(os.path.join(cid, "user-data"), "w").write(f"""#!/bin/sh
mkdir -p /root/.ssh
echo '{pub}' > /root/.ssh/authorized_keys
chmod 700 /root/.ssh; chmod 600 /root/.ssh/authorized_keys
grep -q '^PermitRootLogin' /etc/ssh/sshd_config || echo 'PermitRootLogin prohibit-password' >> /etc/ssh/sshd_config
sysrc sshd_enable=YES
service sshd restart || service sshd start
""")
    iso = os.path.join(a.work, "cidata.iso")
    mk = "xorriso -as mkisofs" if shutil.which("xorriso") else "genisoimage"
    sh(f"{mk} -quiet -output {iso} -volid cidata -joliet -rock {cid}/user-data {cid}/meta-data")

    serial = os.path.join(a.work, "serial.log")
    qemu = subprocess.Popen([
        "qemu-system-x86_64", "-enable-kvm", "-m", "1024", "-smp", "2", "-display", "none",
        "-drive", f"file={disk},format=qcow2,if=virtio",
        "-cdrom", iso,
        "-netdev", f"user,id=n0,hostfwd=tcp:127.0.0.1:{SSH_PORT}-:22",
        "-device", "virtio-net-pci,netdev=n0",
        "-serial", f"file:{serial}",
    ], stdout=open(os.path.join(a.work, "qemu.log"), "w"), stderr=subprocess.STDOUT)
    vm = Vm(key)
    standins = []
    try:
        ok, out = wait_for(vm, "uname -a", "ssh", timeout=420)
        check(ok, "FreeBSD VM booted (cloud-init user-data, ssh)", open(serial, errors="replace").read()[-3000:])
        if not ok:
            return
        log("VM: " + out.strip())

        # pkg(8) itself is bootstrapped from the FreeBSD mirrors; our package
        # has no dependencies.
        rc, out = vm.ssh("env ASSUME_ALWAYS_YES=yes pkg bootstrap -f >/dev/null 2>&1; pkg -v", 600)
        check(rc == 0, "pkg(8) available", out)
        vm.scp(a.pkg, "/tmp/etphonehome.pkg")
        rc, out = vm.ssh("pkg add /tmp/etphonehome.pkg && pkg info etphonehome && pkg check -s etphonehome "
                         "&& pkg info -l etphonehome", 300)
        check(rc == 0 and "/usr/local/bin/etphonehome" in out, "pkg add + pkg check -s (checksums) of our package", out)
        rc, out = vm.ssh("etphonehome --version && test -x /usr/local/etc/rc.d/etphonehome && "
                         "service etphonehome rcvar")
        check(rc == 0 and "etphonehome" in out and "etphonehome_enable" in out,
              "client runs, rc.d script installed", out)
        rc, out = vm.ssh("etphonehome usb-list --json")
        check(rc == 0 and out.strip().startswith("["), "usb-list enumerates USB through FreeBSD's libusb (no phone here)", out)

        s1 = start_standin(a.standin, 9301, "", os.path.join(a.work, "standin-pro.log"))
        standins.append(s1)
        # The phone becomes the only way out.
        rc, out = vm.ssh("route -n delete default; fetch -T 5 -q -o /dev/null https://example.com && echo LEAK; true")
        check("LEAK" not in out, "without the phone the VM has no internet (default route removed)", out)

        rc, out = vm.ssh(f"env ETPH_TOKEN={TOKEN} daemon -o /tmp/cli.log -p /tmp/cli.pid "
                         f"etphonehome wifi --host 10.0.2.2 --port 9301 --interface etph0")
        ok, out = wait_for(vm, "grep -q 'Tethering connection established' /tmp/cli.log", "tunnel", 60)
        rc, logtxt = vm.ssh("cat /tmp/cli.log")
        check(ok and "TLS handshake complete" in logtxt, "Wi-Fi session up (cert-pinned TLS 1.3, handshake)", logtxt)
        rc, out = vm.ssh("ifconfig etph0")
        check(rc == 0 and "10.0.0.1 --> 10.0.0.2" in out, "tun(4) interface renamed etph0, 10.0.0.1 -> 10.0.0.2", out)
        rc, out = vm.ssh("route -n get 1.1.1.1; route -n get 200.1.1.1")
        check(out.count("interface: etph0") == 2, "0/1 + 128/1 routes send all traffic through etph0", out)
        rc, out = vm.ssh("cat /etc/resolv.conf")
        check("8.8.8.8" in out, "resolvconf(8) got the phone's DNS server", out)
        rc, out = vm.ssh("fetch -T 20 -o - https://example.com | head -c 2000", 60)
        check(rc == 0 and "Example Domain" in out, "HTTPS through the phone (example.com)", out)
        rc, out = vm.ssh("drill -Q @8.8.8.8 example.com A || host example.com 8.8.8.8", 60)
        check(rc == 0 and out.strip() != "", "DNS through the phone (8.8.8.8 over the tunnel)", out)
        rc, out = vm.ssh("fetch -q -T 60 -o /tmp/5m 'https://speed.cloudflare.com/__down?bytes=5000000' && "
                         "stat -f %z /tmp/5m", 120)
        check(rc == 0 and out.strip().endswith("5000000"), "5 MB download through the phone, intact size", out)
        rc, out = vm.ssh("kill -INT $(cat /tmp/cli.pid 2>/dev/null || pgrep -f 'etphonehome wifi'); sleep 4; "
                         "ifconfig etph0 2>&1; grep -c 8.8.8.8 /etc/resolv.conf; true")
        check("does not exist" in out and out.strip().endswith("0"),
              "Ctrl-C: etph0 destroyed and the resolvconf entry removed", out)
        time.sleep(1)
        check("Disconnect" in open(os.path.join(a.work, "standin-pro.log")).read()
              or "session over" in open(os.path.join(a.work, "standin-pro.log")).read(),
              "the phone saw the session end")

        # The control service, as the rc.d script runs it.
        rc, out = vm.ssh(f"etphonehome --config /usr/local/etc/etphonehome/config.toml save-profile ci -t wifi "
                         f"--host 10.0.2.2 --port 9301 --token {TOKEN} && sysrc etphonehome_enable=YES && "
                         f"service etphonehome start && sleep 2 && etphonehome ctl status --json", 60)
        check(rc == 0 and '"state":"idle"' in out and '"ci"' in out,
              "rc.d service started; ctl status lists the saved phone", out)
        rc, out = vm.ssh("etphonehome ctl connect ci", 60)
        ok, out2 = wait_for(vm, "etphonehome ctl status --json | grep -q '\"state\":\"connected\"'", "svc", 60)
        rc, st = vm.ssh("etphonehome ctl status --json")
        try:
            sj = json.loads(st.strip().splitlines()[-1])
        except (ValueError, IndexError):
            sj = {}
        check(ok and sj.get("phone_pro") is True and sj.get("device"),
              f"service connected to the saved phone ({sj.get('device')}, ETPH Pro: {sj.get('phone_pro')})", st)
        rc, out = vm.ssh("fetch -T 20 -o - https://example.com | head -c 2000", 60)
        check(rc == 0 and "Example Domain" in out, "HTTPS through the phone via the service", out)
        rc, out = vm.ssh("etphonehome ctl disconnect; sleep 3; etphonehome ctl status --json; ifconfig etph0 2>&1; true")
        check('"state":"idle"' in out and "does not exist" in out, "ctl disconnect ends the session", out)
        rc, out = vm.ssh("service etphonehome stop; sleep 1; pgrep -f 'etphonehome service' || echo stopped")
        check("stopped" in out, "service etphonehome stop", out)

        # A phone without ETPH Pro: Wi-Fi is part of Pro.
        s2 = start_standin(a.standin, 9302, "--free", os.path.join(a.work, "standin-free.log"))
        standins.append(s2)
        rc, out = vm.ssh(f"env ETPH_TOKEN={TOKEN} timeout 40 etphonehome wifi --host 10.0.2.2 --port 9302 "
                         f"--interface etph0 2>&1; true", 60)
        check("Pro" in out and "Tethering connection established" not in out,
              "a phone without ETPH Pro refuses Wi-Fi and the client says why", out)

        rc, out = vm.ssh("pkg delete -y etphonehome && test ! -e /usr/local/bin/etphonehome && echo gone")
        check("gone" in out, "pkg delete removes the client", out)
    finally:
        vm.ssh("shutdown -p now", 10)
        try:
            qemu.wait(30)
        except subprocess.TimeoutExpired:
            qemu.kill()
        for s in standins:
            s.terminate()
        log(f"freebsd VM summary: {len(PASS)} passed, {len(FAIL)} failed")
        for f in FAIL:
            log(f"  failed: {f}")
    sys.exit(1 if FAIL or not PASS else 0)


if __name__ == "__main__":
    main()
