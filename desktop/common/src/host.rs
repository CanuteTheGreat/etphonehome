//! How a session plugs into the host's network stack.
//!
//! On a desktop the client owns its routes and DNS (`runner::setup_routing`,
//! `runner::configure_dns`). On a router the network daemon owns them
//! (OpenWrt's netifd adds the default route with the interface's metric,
//! hands the DNS server to dnsmasq and lets the firewall zone / mwan3 see a
//! normal WAN interface), so the client only creates the TUN device and
//! reports it through hook scripts, like pppd's ip-up/ip-down. A status file
//! lets a web UI (LuCI) show the session without talking to the process.

use anyhow::{bail, Context, Result};
use log::{info, warn};
use serde::Serialize;
use std::net::Ipv4Addr;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// The TUN link's addresses. The phone's relay answers on the peer address
/// and NATs everything else through its own sockets, so any private /30 or
/// larger works; routers pick one that does not clash with their LAN.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TunAddressing {
    pub local: Ipv4Addr,
    pub prefix: u8,
    pub peer: Ipv4Addr,
}

impl Default for TunAddressing {
    fn default() -> Self {
        Self {
            local: Ipv4Addr::new(10, 0, 0, 1),
            prefix: 24,
            peer: Ipv4Addr::new(10, 0, 0, 2),
        }
    }
}

impl TunAddressing {
    /// Parse `A.B.C.D/NN` (prefix optional, default /24) plus an optional
    /// peer; without one the peer is the next address in the subnet.
    pub fn parse(local: &str, peer: Option<&str>) -> Result<Self> {
        let (ip, prefix) = match local.split_once('/') {
            Some((ip, p)) => (
                ip,
                p.trim()
                    .parse::<u8>()
                    .with_context(|| format!("bad prefix length in '{local}'"))?,
            ),
            None => (local, 24),
        };
        let local: Ipv4Addr = ip
            .trim()
            .parse()
            .with_context(|| format!("'{ip}' is not an IPv4 address"))?;
        if !(8..=30).contains(&prefix) {
            bail!("TUN prefix /{prefix} must be between /8 and /30");
        }
        let mask = u32::MAX << (32 - prefix as u32);
        let peer = match peer {
            Some(p) if !p.trim().is_empty() => p
                .trim()
                .parse::<Ipv4Addr>()
                .with_context(|| format!("'{p}' is not an IPv4 address"))?,
            _ => {
                let next = u32::from(local).wrapping_add(1);
                if next & mask != u32::from(local) & mask || next & !mask == !mask {
                    Ipv4Addr::from(u32::from(local).wrapping_sub(1))
                } else {
                    Ipv4Addr::from(next)
                }
            }
        };
        if u32::from(peer) & mask != u32::from(local) & mask {
            bail!("TUN peer {peer} is not in {local}/{prefix}");
        }
        if peer == local {
            bail!("TUN peer and local address are both {local}");
        }
        Ok(Self {
            local,
            prefix,
            peer,
        })
    }

    pub fn netmask(&self) -> Ipv4Addr {
        Ipv4Addr::from(u32::MAX << (32 - self.prefix as u32))
    }
}

/// Everything about the host side of a session that is not the transport.
#[derive(Debug, Clone)]
pub struct HostIntegration {
    /// Add the default route and DNS ourselves (desktop). Off on routers,
    /// where the up-script hands them to netifd.
    pub manage_routes: bool,
    /// Run after the TUN device is up (env: see [`hook_env`]).
    pub up_script: Option<PathBuf>,
    /// Run after the session ended and the TUN device is gone.
    pub down_script: Option<PathBuf>,
    /// JSON status for UIs (LuCI), rewritten atomically.
    pub status: Option<Arc<StatusFile>>,
    pub tun: TunAddressing,
    /// `adb -s <serial>` when several phones are plugged in.
    pub adb_serial: Option<String>,
    /// USB through the built-in ADB host (usbfs) instead of the adb tool.
    pub usb_native: bool,
    /// Built-in ADB host over TCP to an adbd in tcpip mode (tests, `adb tcpip`).
    pub adb_tcp: Option<String>,
}

impl Default for HostIntegration {
    fn default() -> Self {
        Self {
            manage_routes: true,
            up_script: None,
            down_script: None,
            status: None,
            tun: TunAddressing::default(),
            adb_serial: None,
            usb_native: false,
            adb_tcp: None,
        }
    }
}

/// What a session looks like to a hook script.
#[derive(Debug, Clone, Default)]
pub struct HookInfo {
    pub ifname: String,
    pub transport: String,
    pub dns: Option<Ipv4Addr>,
    pub device: Option<String>,
    pub reason: Option<String>,
}

/// Environment handed to the up/down scripts (pppd ip-up style).
pub fn hook_env(tun: &TunAddressing, info: &HookInfo) -> Vec<(String, String)> {
    let mut env = vec![
        ("ETPH_IFNAME".to_string(), info.ifname.clone()),
        ("ETPH_LOCAL".to_string(), tun.local.to_string()),
        ("ETPH_PREFIX".to_string(), tun.prefix.to_string()),
        ("ETPH_NETMASK".to_string(), tun.netmask().to_string()),
        ("ETPH_PEER".to_string(), tun.peer.to_string()),
        ("ETPH_TRANSPORT".to_string(), info.transport.clone()),
    ];
    if let Some(dns) = info.dns {
        env.push(("ETPH_DNS".to_string(), dns.to_string()));
    }
    if let Some(d) = &info.device {
        env.push(("ETPH_DEVICE".to_string(), d.clone()));
    }
    if let Some(r) = &info.reason {
        env.push(("ETPH_REASON".to_string(), r.clone()));
    }
    env
}

/// Run a hook script with the session environment; its failure is logged,
/// never fatal (a broken hook must not kill the uplink or the retry loop).
pub fn run_hook(script: &Path, phase: &str, tun: &TunAddressing, info: &HookInfo) -> bool {
    let mut cmd = Command::new(script);
    cmd.arg(phase);
    for (k, v) in hook_env(tun, info) {
        cmd.env(k, v);
    }
    match cmd.output() {
        Ok(out) if out.status.success() => {
            info!("{} hook {} ran", phase, script.display());
            true
        }
        Ok(out) => {
            warn!(
                "{} hook {} failed ({}): {}",
                phase,
                script.display(),
                out.status,
                String::from_utf8_lossy(&out.stderr).trim()
            );
            false
        }
        Err(e) => {
            warn!("{} hook {} could not run: {}", phase, script.display(), e);
            false
        }
    }
}

// ---------------------------------------------------------------------------
// Retry now (SIGUSR1)
// ---------------------------------------------------------------------------

static RETRY_NOW: tokio::sync::Notify = tokio::sync::Notify::const_new();

/// Skip the current reconnect backoff (USB hotplug: the phone was just
/// plugged back in, so don't wait up to a minute for the next attempt).
pub fn retry_now() {
    RETRY_NOW.notify_one();
}

/// Resolves when [`retry_now`] was called.
pub async fn retry_requested() {
    RETRY_NOW.notified().await
}

/// `kill -USR1 <pid>` = [`retry_now`] (Unix).
pub fn install_retry_signal() {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{signal, SignalKind};
        match signal(SignalKind::user_defined1()) {
            Ok(mut s) => {
                tokio::spawn(async move {
                    while s.recv().await.is_some() {
                        info!("SIGUSR1: retrying now");
                        retry_now();
                    }
                });
            }
            Err(e) => warn!("cannot listen for SIGUSR1: {}", e),
        }
    }
}

// ---------------------------------------------------------------------------
// Status file
// ---------------------------------------------------------------------------

/// The session as a UI sees it (`/var/run/etph/<iface>.json` on routers).
#[derive(Debug, Clone, Serialize, Default, PartialEq)]
pub struct Status {
    /// connecting | authorizing (phone shows "Allow USB debugging?") |
    /// connected | retrying | stopped | failed
    pub state: String,
    pub transport: String,
    pub target: String,
    pub interface: String,
    /// The phone, as it introduced itself (e.g. "samsung SM-N986U").
    pub device: Option<String>,
    pub encrypted: bool,
    pub attempt: u32,
    /// Unix seconds the current session came up.
    pub connected_since: Option<u64>,
    pub last_error: Option<String>,
    pub last_end: Option<String>,
    pub bytes_sent: u64,
    pub bytes_received: u64,
    /// Bytes per second over the last update interval.
    pub rate_sent: u64,
    pub rate_received: u64,
    pub sessions: u32,
    pub pid: u32,
    pub updated: u64,
}

pub fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Previous traffic sample: when, bytes sent, bytes received.
type Sample = Option<(Instant, u64, u64)>;

/// Keeps [`Status`] and writes it to disk on every change.
#[derive(Debug)]
pub struct StatusFile {
    path: PathBuf,
    inner: Mutex<(Status, Sample)>,
}

impl StatusFile {
    pub fn new(path: PathBuf, transport: &str, target: &str, interface: &str) -> Arc<Self> {
        let s = Arc::new(Self {
            path,
            inner: Mutex::new((
                Status {
                    state: "connecting".into(),
                    transport: transport.into(),
                    target: target.into(),
                    interface: interface.into(),
                    pid: std::process::id(),
                    ..Default::default()
                },
                None,
            )),
        });
        s.update(|_| {});
        s
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn snapshot(&self) -> Status {
        self.inner.lock().unwrap().0.clone()
    }

    fn update(&self, f: impl FnOnce(&mut Status)) {
        let json = {
            let mut g = self.inner.lock().unwrap();
            f(&mut g.0);
            g.0.updated = unix_now();
            serde_json::to_string_pretty(&g.0).unwrap_or_default()
        };
        if let Err(e) = write_atomic(&self.path, json.as_bytes()) {
            warn!("cannot write status file {}: {}", self.path.display(), e);
        }
    }

    pub fn connecting(&self, attempt: u32) {
        self.update(|s| {
            s.state = "connecting".into();
            s.attempt = attempt;
        });
    }

    /// The phone is asking "Allow USB debugging?" for our key.
    pub fn authorizing(&self, fingerprint: &str) {
        self.update(|s| {
            s.state = "authorizing".into();
            s.last_error = Some(format!(
                "Unlock the phone and tap Allow on \"Allow USB debugging?\" (fingerprint {fingerprint})"
            ));
        });
    }

    pub fn connected(&self, device: Option<String>, encrypted: bool) {
        self.inner.lock().unwrap().1 = None;
        self.update(|s| {
            s.state = "connected".into();
            s.device = device;
            s.encrypted = encrypted;
            s.connected_since = Some(unix_now());
            s.last_error = None;
            s.bytes_sent = 0;
            s.bytes_received = 0;
            s.rate_sent = 0;
            s.rate_received = 0;
            s.sessions += 1;
        });
    }

    /// Byte counters of the running session; rates come from the change
    /// since the previous call.
    pub fn traffic(&self, sent: u64, received: u64) {
        let now = Instant::now();
        let (rs, rr) = {
            let mut g = self.inner.lock().unwrap();
            let rates = match g.1 {
                Some((t, ps, pr)) => {
                    let dt = now.duration_since(t).as_secs_f64();
                    if dt >= 0.5 {
                        Some((
                            (sent.saturating_sub(ps) as f64 / dt) as u64,
                            (received.saturating_sub(pr) as f64 / dt) as u64,
                        ))
                    } else {
                        None
                    }
                }
                None => Some((0, 0)),
            };
            match rates {
                Some(r) => {
                    g.1 = Some((now, sent, received));
                    r
                }
                None => (g.0.rate_sent, g.0.rate_received),
            }
        };
        self.update(|s| {
            s.bytes_sent = sent;
            s.bytes_received = received;
            s.rate_sent = rs;
            s.rate_received = rr;
        });
    }

    pub fn session_ended(&self, reason: &str) {
        self.update(|s| {
            s.state = "retrying".into();
            s.connected_since = None;
            s.last_end = Some(reason.into());
            s.rate_sent = 0;
            s.rate_received = 0;
        });
    }

    pub fn failed(&self, error: &str) {
        self.update(|s| {
            s.state = "retrying".into();
            s.connected_since = None;
            s.last_error = Some(error.into());
        });
    }

    /// The client is exiting (`failed` = it gave up with an error).
    pub fn stopped(&self, error: Option<&str>) {
        self.update(|s| {
            s.state = if error.is_some() { "failed" } else { "stopped" }.into();
            s.connected_since = None;
            if let Some(e) = error {
                s.last_error = Some(e.into());
            }
            s.rate_sent = 0;
            s.rate_received = 0;
        });
    }
}

impl crate::adb::AuthObserver for StatusFile {
    fn waiting_for_allow(&self, fingerprint: &str) {
        self.authorizing(fingerprint);
    }
}

fn write_atomic(path: &Path, data: &[u8]) -> std::io::Result<()> {
    if let Some(dir) = path.parent() {
        if !dir.as_os_str().is_empty() {
            std::fs::create_dir_all(dir)?;
        }
    }
    let tmp = path.with_extension(format!("tmp{}", std::process::id()));
    std::fs::write(&tmp, data)?;
    std::fs::rename(&tmp, path)
}

/// The fingerprint Android shows in its "Allow USB debugging?" dialog for
/// an adb public key (`adbkey.pub`: base64 blob + comment): MD5 of the
/// decoded blob as colon-separated upper-case hex. Routers have no screen,
/// so LuCI shows this to compare with the phone's prompt.
pub fn adb_key_fingerprint(pubkey: &str) -> Result<String> {
    let b64 = pubkey
        .split_whitespace()
        .next()
        .context("empty adb public key")?;
    let blob = base64_decode(b64).context("adb public key is not base64")?;
    Ok(md5(&blob)
        .iter()
        .map(|b| format!("{:02X}", b))
        .collect::<Vec<_>>()
        .join(":"))
}

fn base64_decode(s: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(s.len() * 3 / 4);
    let (mut acc, mut bits) = (0u32, 0u32);
    for c in s.bytes() {
        let v = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' => break,
            _ => return None,
        } as u32;
        acc = (acc << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1 << bits) - 1;
        }
    }
    Some(out)
}

/// MD5 (RFC 1321): only for the adb fingerprint, which Android defines
/// with it; never used for security here.
fn md5(input: &[u8]) -> [u8; 16] {
    const S: [u32; 64] = [
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5, 9, 14, 20, 5,
        9, 14, 20, 5, 9, 14, 20, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10,
        15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    ];
    let k: Vec<u32> = (0..64)
        .map(|i| ((i as f64 + 1.0).sin().abs() * 4294967296.0) as u32)
        .collect();
    let (mut a0, mut b0, mut c0, mut d0) =
        (0x67452301u32, 0xefcdab89u32, 0x98badcfeu32, 0x10325476u32);
    let mut msg = input.to_vec();
    let bit_len = (input.len() as u64).wrapping_mul(8);
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&bit_len.to_le_bytes());
    for chunk in msg.chunks(64) {
        let m: Vec<u32> = chunk
            .chunks(4)
            .map(|w| u32::from_le_bytes([w[0], w[1], w[2], w[3]]))
            .collect();
        let (mut a, mut b, mut c, mut d) = (a0, b0, c0, d0);
        for i in 0..64 {
            let (f, g) = match i / 16 {
                0 => ((b & c) | (!b & d), i),
                1 => ((d & b) | (!d & c), (5 * i + 1) % 16),
                2 => (b ^ c ^ d, (3 * i + 5) % 16),
                _ => (c ^ (b | !d), (7 * i) % 16),
            };
            let f = f.wrapping_add(a).wrapping_add(k[i]).wrapping_add(m[g]);
            a = d;
            d = c;
            c = b;
            b = b.wrapping_add(f.rotate_left(S[i]));
        }
        a0 = a0.wrapping_add(a);
        b0 = b0.wrapping_add(b);
        c0 = c0.wrapping_add(c);
        d0 = d0.wrapping_add(d);
    }
    let mut out = [0u8; 16];
    for (i, v) in [a0, b0, c0, d0].iter().enumerate() {
        out[i * 4..i * 4 + 4].copy_from_slice(&v.to_le_bytes());
    }
    out
}

/// How often the status file's traffic numbers are refreshed.
pub const STATUS_INTERVAL: Duration = Duration::from_secs(2);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tun_addressing_parses_and_picks_a_peer() {
        let d = TunAddressing::parse("10.0.0.1", None).unwrap();
        assert_eq!(d, TunAddressing::default());
        let t = TunAddressing::parse("172.31.255.1/30", None).unwrap();
        assert_eq!(t.peer, Ipv4Addr::new(172, 31, 255, 2));
        assert_eq!(t.netmask(), Ipv4Addr::new(255, 255, 255, 252));
        // .2 in a /30: next (.3) is broadcast, so the peer is .1
        let t = TunAddressing::parse("172.31.255.2/30", None).unwrap();
        assert_eq!(t.peer, Ipv4Addr::new(172, 31, 255, 1));
        let t = TunAddressing::parse("192.168.77.1/24", Some("192.168.77.9")).unwrap();
        assert_eq!(t.peer, Ipv4Addr::new(192, 168, 77, 9));
        assert!(TunAddressing::parse("10.0.0.1/24", Some("10.0.1.2")).is_err());
        assert!(TunAddressing::parse("10.0.0.1/31", None).is_err());
        assert!(TunAddressing::parse("10.0.0.1/24", Some("10.0.0.1")).is_err());
        assert!(TunAddressing::parse("router", None).is_err());
    }

    #[test]
    fn hook_env_carries_the_session() {
        let env = hook_env(
            &TunAddressing::default(),
            &HookInfo {
                ifname: "etph-wan".into(),
                transport: "usb".into(),
                dns: Some(Ipv4Addr::new(8, 8, 8, 8)),
                device: Some("samsung SM-N986U".into()),
                reason: None,
            },
        );
        let get = |k: &str| env.iter().find(|(a, _)| a == k).map(|(_, v)| v.as_str());
        assert_eq!(get("ETPH_IFNAME"), Some("etph-wan"));
        assert_eq!(get("ETPH_LOCAL"), Some("10.0.0.1"));
        assert_eq!(get("ETPH_PREFIX"), Some("24"));
        assert_eq!(get("ETPH_PEER"), Some("10.0.0.2"));
        assert_eq!(get("ETPH_DNS"), Some("8.8.8.8"));
        assert_eq!(get("ETPH_DEVICE"), Some("samsung SM-N986U"));
        assert_eq!(get("ETPH_REASON"), None);
    }

    #[cfg(unix)]
    #[test]
    fn hooks_run_with_the_environment_and_failures_are_not_fatal() {
        let dir = std::env::temp_dir().join(format!("etph-hook-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let out = dir.join("out");
        let script = dir.join("hook.sh");
        std::fs::write(
            &script,
            format!(
                "#!/bin/sh\necho \"$1 $ETPH_IFNAME $ETPH_PEER $ETPH_REASON\" >> {}\n",
                out.display()
            ),
        )
        .unwrap();
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o755)).unwrap();
        let info = HookInfo {
            ifname: "etph0".into(),
            transport: "wifi".into(),
            reason: Some("phone stopped".into()),
            ..Default::default()
        };
        assert!(run_hook(&script, "down", &TunAddressing::default(), &info));
        assert_eq!(
            std::fs::read_to_string(&out).unwrap(),
            "down etph0 10.0.0.2 phone stopped\n"
        );
        assert!(!run_hook(
            &dir.join("missing.sh"),
            "up",
            &TunAddressing::default(),
            &info
        ));
        std::fs::write(&script, "#!/bin/sh\nexit 3\n").unwrap();
        assert!(!run_hook(&script, "up", &TunAddressing::default(), &info));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn status_file_tracks_the_session() {
        let dir = std::env::temp_dir().join(format!("etph-status-{}", std::process::id()));
        let path = dir.join("wan.json");
        let st = StatusFile::new(path.clone(), "usb", "127.0.0.1:8888", "etph-wan");
        let read = || -> serde_json::Value {
            serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap()
        };
        assert_eq!(read()["state"], "connecting");
        st.connecting(2);
        assert_eq!(read()["attempt"], 2);
        st.connected(Some("Google Pixel 8".into()), false);
        let v = read();
        assert_eq!(v["state"], "connected");
        assert_eq!(v["device"], "Google Pixel 8");
        assert_eq!(v["sessions"], 1);
        assert!(v["connected_since"].as_u64().unwrap() > 0);
        st.traffic(1000, 5000);
        assert_eq!(read()["bytes_received"], 5000);
        st.session_ended("phone stopped tethering");
        let v = read();
        assert_eq!(v["state"], "retrying");
        assert_eq!(v["last_end"], "phone stopped tethering");
        assert!(v["connected_since"].is_null());
        st.authorizing("AA:BB");
        let v = read();
        assert_eq!(v["state"], "authorizing");
        assert!(v["last_error"].as_str().unwrap().contains("tap Allow"));
        st.failed("connection refused");
        assert_eq!(read()["last_error"], "connection refused");
        st.stopped(None);
        assert_eq!(read()["state"], "stopped");
        st.stopped(Some("token rejected"));
        assert_eq!(read()["state"], "failed");
        // No temp files left behind.
        let names: Vec<_> = std::fs::read_dir(&dir).unwrap().map(|e| e.unwrap().file_name()).collect();
        assert_eq!(names.len(), 1, "{names:?}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn status_rates_follow_the_counters() {
        let dir = std::env::temp_dir().join(format!("etph-rate-{}", std::process::id()));
        let st = StatusFile::new(dir.join("s.json"), "wifi", "h", "i");
        st.connected(None, true);
        st.traffic(0, 0);
        {
            // pretend the previous sample is 2 s old
            let mut g = st.inner.lock().unwrap();
            g.1 = Some((Instant::now() - Duration::from_secs(2), 0, 0));
        }
        st.traffic(2000, 8000);
        let s = st.snapshot();
        assert!((900..=1000).contains(&s.rate_sent), "{}", s.rate_sent);
        assert!((3600..=4000).contains(&s.rate_received), "{}", s.rate_received);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn md5_matches_rfc_1321_vectors() {
        let hex = |d: [u8; 16]| d.iter().map(|b| format!("{:02x}", b)).collect::<String>();
        assert_eq!(hex(md5(b"")), "d41d8cd98f00b204e9800998ecf8427e");
        assert_eq!(hex(md5(b"abc")), "900150983cd24fb0d6963f7d28e17f72");
        assert_eq!(
            hex(md5(b"12345678901234567890123456789012345678901234567890123456789012345678901234567890")),
            "57edf4a22be3c955ac49da2e2107b67a"
        );
    }

    #[test]
    fn adb_fingerprint_is_md5_of_the_key_blob() {
        // "abc" base64 = YWJj; Android shows MD5(blob) as AA:BB:...
        assert_eq!(
            adb_key_fingerprint("YWJj root@OpenWrt\n").unwrap(),
            "90:01:50:98:3C:D2:4F:B0:D6:96:3F:7D:28:E1:7F:72"
        );
        assert_eq!(base64_decode("SGVsbG8=").unwrap(), b"Hello");
        assert!(adb_key_fingerprint("").is_err());
        assert!(adb_key_fingerprint("not*base64 x").is_err());
    }

    #[tokio::test]
    async fn retry_now_wakes_a_waiter() {
        let w = tokio::spawn(retry_requested());
        tokio::task::yield_now().await;
        retry_now();
        tokio::time::timeout(Duration::from_secs(2), w)
            .await
            .expect("waiter woke")
            .unwrap();
    }
}
