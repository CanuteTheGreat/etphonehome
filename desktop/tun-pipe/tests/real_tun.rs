//! Root mode, real-kernel check of the root helper: the unit tests in lib.rs pump through in-memory
//! streams; this runs the real `tun-pipe` binary against a real tun device in a throw-away network
//! namespace (`unshare -rn`). The kernel's own `ping` sends an echo request to a made-up neighbour
//! on the tun subnet; the test reads that packet from the helper's framed stdout, answers it as the
//! neighbour would (framed back on stdin) and requires the ping to succeed. That proves both
//! directions of the helper, the ioctl attach and the framing against the real kernel.
//!
//! Skipped without unprivileged namespaces / `ip tuntap` / ping unless ETPH_KERNEL_E2E_REQUIRED=1
//! (then it fails instead of skipping).
#![cfg(target_os = "linux")]

use std::io::{BufRead, BufReader};
use std::process::{Command, Stdio};
use std::sync::mpsc;
use std::thread;
use std::time::Duration;
use tun_pipe::{read_frame, write_frame};

fn required() -> bool {
    std::env::var("ETPH_KERNEL_E2E_REQUIRED").as_deref() == Ok("1")
}

/// None = usable; Some(reason) = this machine cannot run the test.
fn unavailable() -> Option<String> {
    let probe_script = "command -v ping >/dev/null && ip tuntap add dev probe0 mode tun";
    let probe = Command::new("unshare")
        .args(["-rn", "sh", "-c", probe_script])
        .output();
    match probe {
        Ok(o) if o.status.success() => None,
        Ok(o) => Some(format!(
            "no usable netns/tuntap/ping: {}",
            String::from_utf8_lossy(&o.stderr).trim()
        )),
        Err(e) => Some(format!("cannot start unshare: {e}")),
    }
}

fn checksum(data: &[u8]) -> u16 {
    let mut sum: u32 = 0;
    for c in data.chunks(2) {
        sum += u16::from_be_bytes([c[0], *c.get(1).unwrap_or(&0)]) as u32;
    }
    while sum > 0xffff {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

/// The echo reply a neighbour would send for an IPv4 echo request, or None for any other packet.
fn echo_reply(req: &[u8]) -> Option<Vec<u8>> {
    if req.len() < 28 || req[0] >> 4 != 4 || req[9] != 1 {
        return None;
    }
    let ihl = ((req[0] & 0x0f) as usize) * 4;
    if req.len() < ihl + 8 || req[ihl] != 8 {
        return None;
    }
    let mut r = req.to_vec();
    let (src, dst) = (req[12..16].to_vec(), req[16..20].to_vec());
    r[12..16].copy_from_slice(&dst);
    r[16..20].copy_from_slice(&src);
    r[ihl] = 0; // echo reply
    r[ihl + 2] = 0;
    r[ihl + 3] = 0;
    let c = checksum(&r[ihl..]).to_be_bytes();
    r[ihl + 2] = c[0];
    r[ihl + 3] = c[1];
    r[10] = 0;
    r[11] = 0;
    let h = checksum(&r[..ihl]).to_be_bytes();
    r[10] = h[0];
    r[11] = h[1];
    Some(r)
}

#[test]
fn kernel_ping_through_the_real_helper_gets_its_answer() {
    if let Some(why) = unavailable() {
        assert!(!required(), "required but unavailable: {why}");
        eprintln!("skipped: {why}");
        return;
    }
    let helper = env!("CARGO_BIN_EXE_tun-pipe");
    // Inside the namespace: tun device (the app's TunDevicePlan does the same with `ip`), a ping at
    // a neighbour that does not exist, and the helper as the only thing that can answer it.
    let script = format!(
        "set -e; ip link set lo up; ip tuntap add dev etph0 mode tun; \
         ip addr add 10.0.0.2/30 dev etph0; ip link set etph0 up; \
         (ok=0; for i in 1 2 3 4 5 6 7 8; do if ping -c 1 -W 2 10.0.0.1 >/dev/null 2>&1; then ok=1; break; fi; sleep 1; done; \
          if [ $ok = 1 ]; then echo PING_OK >&2; else echo PING_FAIL >&2; fi) & \
         exec '{helper}' tun-pipe etph0"
    );
    let mut child = Command::new("unshare")
        .args(["-rn", "sh", "-c", &script])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn unshare");
    let mut to_helper = child.stdin.take().unwrap();
    let mut from_helper = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();

    let (ptx, prx) = mpsc::channel::<String>();
    thread::spawn(move || {
        for line in BufReader::new(stderr).lines().map_while(Result::ok) {
            let _ = ptx.send(line);
        }
    });

    // Packets the helper hands over (router solicitations etc. may come first): answer the echo.
    let (ftx, frx) = mpsc::channel::<Vec<u8>>();
    thread::spawn(move || {
        let mut buf = Vec::new();
        while let Ok(Some(n)) = read_frame(&mut from_helper, &mut buf) {
            if ftx.send(buf[..n].to_vec()).is_err() {
                break;
            }
        }
    });
    let mut seen = 0;
    let mut answered = false;
    while !answered {
        let pkt = match frx.recv_timeout(Duration::from_secs(20)) {
            Ok(p) => p,
            Err(_) => panic!("no echo request from the helper ({seen} other packets)"),
        };
        match echo_reply(&pkt) {
            Some(reply) => {
                assert_eq!(&pkt[16..20], &[10, 0, 0, 1], "wrong echo target");
                write_frame(&mut to_helper, &reply).expect("frame the reply into the helper");
                answered = true;
            }
            None => seen += 1,
        }
    }

    let mut log = Vec::new();
    let verdict = loop {
        match prx.recv_timeout(Duration::from_secs(20)) {
            Ok(l) if l == "PING_OK" || l == "PING_FAIL" => break l,
            Ok(l) => log.push(l),
            Err(_) => break "TIMEOUT".to_string(),
        }
    };
    // Closing the pipe is the app's normal stop: the helper must exit cleanly on its own.
    drop(to_helper);
    let mut waited = 0;
    let status = loop {
        if let Some(s) = child.try_wait().unwrap() {
            break s;
        }
        waited += 1;
        if waited > 100 {
            let _ = child.kill();
            panic!("helper did not exit after the app closed the pipe");
        }
        thread::sleep(Duration::from_millis(100));
    };
    assert_eq!(verdict, "PING_OK", "ping not answered; stderr: {log:?}");
    assert!(
        status.success(),
        "helper exit status {status:?}; stderr: {log:?}"
    );
}
