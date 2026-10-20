//! Everything a front end (Linux CLI, Windows CLI, GUI) needs to turn a
//! profile or command line into a running tethering session: target
//! resolution, ADB port forwarding, TUN + route setup, auto-reconnect,
//! Bluetooth RFCOMM (Linux), mDNS discovery and the profile commands.
//!
//! This used to be copy-pasted into `linux/src/main.rs` and
//! `windows/src/main.rs` (and was missing from the GUI entirely), with no
//! tests. Keeping one implementation here means the CLI and GUI behave the
//! same and the logic is exercised by `cargo test`.

use crate::config::{Config, ConnectionType, Profile};
use crate::connection_service::{ConnectionService, ConnectionState};
use crate::filter::FilterConfig;
use crate::host::{self, HookInfo, HostIntegration};
use crate::protocol::capabilities;
use crate::session::{self, ConnectOptions, ForwardOptions, SessionEnd, Transport};
use anyhow::{bail, Context, Result};
use log::{error, info, warn};
use std::future::Future;
use std::net::SocketAddr;
use std::path::Path;
use std::process::Command;
use std::time::{Duration, Instant};
use tokio::sync::watch;

/// Default TCP port the Android app listens on.
pub const DEFAULT_PORT: u16 = 8888;
/// Address the desktop end of the TUN link gets (Android's VPN side is .2).
pub const TUN_ADDRESS: &str = "10.0.0.1";
pub const TUN_NETMASK: &str = "255.255.255.0";
pub const TUN_PEER: &str = "10.0.0.2";
/// mDNS service type registered by the Android app's NsdAdvertiser.
pub const MDNS_SERVICE_TYPE: &str = "_etphonehome._tcp.local.";

/// Where the session's bytes travel.
#[derive(Debug, Clone)]
pub enum Target {
    /// USB (via `adb forward`, `adb_port` set) or WiFi over TCP.
    Tcp {
        opts: ConnectOptions,
        adb_port: Option<u16>,
    },
    /// Bluetooth RFCOMM (SPP, channel 1).
    Bluetooth {
        address: String,
        token: Option<String>,
    },
    /// Router: a phone on USB (built-in ADB host) when one is plugged in,
    /// otherwise WiFi (if configured), decided again on every attempt.
    Auto {
        usb_port: u16,
        wifi: Option<ConnectOptions>,
    },
}

/// A fully-resolved description of one session.
#[derive(Debug, Clone)]
pub struct SessionPlan {
    pub target: Target,
    pub tun_name: String,
    pub filter: FilterConfig,
    /// Routes/DNS ownership, hook scripts, status file, TUN addresses.
    pub host: HostIntegration,
}

/// Resolve `host:port`, accepting IP literals and hostnames.
///
/// The old CLI did `format!("{}:{}", host, port).parse::<SocketAddr>()`,
/// which only accepts IP literals, so a profile host like `pixel.local`
/// failed with "invalid socket address syntax".
pub async fn resolve_addr(host: &str, port: u16) -> Result<SocketAddr> {
    if let Ok(ip) = host.parse::<std::net::IpAddr>() {
        return Ok(SocketAddr::new(ip, port));
    }
    let mut addrs = tokio::net::lookup_host((host, port))
        .await
        .context(format!("could not resolve host '{}'", host))?;
    addrs
        .find(|a| a.is_ipv4())
        .context(format!("host '{}' has no IPv4 address", host))
}

fn non_empty(s: &str) -> Option<String> {
    if s.is_empty() {
        None
    } else {
        Some(s.to_string())
    }
}

/// Build the session plan for a saved profile: picks the right token
/// (`auth.wifi_token` for WiFi, `auth.bluetooth_token` for Bluetooth -- the
/// Bluetooth token was never sent before, so Android always rejected it),
/// the TLS flag, the port and the profile's filter rules.
pub async fn plan_for_profile(
    config: &Config,
    profile: &Profile,
    tun_name: &str,
) -> Result<SessionPlan> {
    let target = match profile.connection_type {
        ConnectionType::Usb => {
            let port = profile.port.unwrap_or(DEFAULT_PORT);
            Target::Tcp {
                opts: ConnectOptions {
                    addr: SocketAddr::from(([127, 0, 0, 1], port)),
                    capabilities: capabilities::USB,
                    token: None,
                    tls: false,
                    timeout: Duration::from_secs(10),
                },
                adb_port: Some(port),
            }
        }
        ConnectionType::Wifi => {
            let host = profile
                .host
                .as_deref()
                .context(format!("WiFi profile '{}' has no host", profile.name))?;
            let addr = resolve_addr(host, profile.port.unwrap_or(DEFAULT_PORT)).await?;
            Target::Tcp {
                opts: ConnectOptions {
                    addr,
                    capabilities: capabilities::WIFI,
                    token: non_empty(&config.auth.wifi_token),
                    tls: config.auth.tls_enabled,
                    timeout: Duration::from_secs(10),
                },
                adb_port: None,
            }
        }
        ConnectionType::Bluetooth => Target::Bluetooth {
            address: profile.address.clone().context(format!(
                "Bluetooth profile '{}' has no address",
                profile.name
            ))?,
            token: non_empty(&config.auth.bluetooth_token),
        },
    };
    if let Some(problem) = profile.filter.wire_problem() {
        bail!("Profile '{}' can't be used: {}", profile.name, problem);
    }
    Ok(SessionPlan {
        target,
        tun_name: tun_name.to_string(),
        filter: profile.filter.clone(),
        host: HostIntegration::default(),
    })
}

/// `adb forward tcp:PORT tcp:PORT`: connections to this computer's
/// 127.0.0.1:PORT are carried over USB to the phone's 127.0.0.1:PORT, where
/// the app's USB-mode server listens (loopback-only on the phone).
///
/// This used to run `adb reverse`, which is the opposite direction (phone
/// port -> computer port): with the app already listening it fails with
/// "cannot bind listener: Address already in use", and if it ran first the
/// app could not bind its port. Either way USB tethering never connected.
pub fn setup_adb_forwarding(port: u16) -> Result<()> {
    setup_adb_forwarding_for(port, None)
}

/// The `adb` arguments for forwarding `port`, optionally to one phone
/// (`-s <serial>`) when several are plugged in.
pub fn adb_forward_args(port: u16, serial: Option<&str>) -> Vec<String> {
    let mut args = Vec::new();
    if let Some(s) = serial.filter(|s| !s.trim().is_empty()) {
        args.push("-s".to_string());
        args.push(s.trim().to_string());
    }
    args.push("forward".to_string());
    args.push(format!("tcp:{}", port));
    args.push(format!("tcp:{}", port));
    args
}

/// [`setup_adb_forwarding`] for one phone (`adb -s <serial>`).
pub fn setup_adb_forwarding_for(port: u16, serial: Option<&str>) -> Result<()> {
    info!("Setting up ADB port forwarding (tcp:{})...", port);
    let output = Command::new("adb")
        .args(adb_forward_args(port, serial))
        .output()
        .context("ADB not found. Please install Android Debug Bridge (adb)")?;
    if !output.status.success() {
        bail!(
            "ADB port forwarding failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    info!(
        "ADB port forwarding set up: this computer port {0} -> phone port {0}",
        port
    );
    Ok(())
}

/// Pull the WiFi IPv4 address out of `ip addr show wlan0` output.
pub fn parse_wlan_ip(ip_addr_output: &str) -> Option<String> {
    ip_addr_output.lines().find_map(|line| {
        let line = line.trim();
        let rest = line.strip_prefix("inet ")?;
        let cidr = rest.split_whitespace().next()?;
        let ip = cidr.split('/').next()?;
        if ip.starts_with("127.") {
            None
        } else {
            Some(ip.to_string())
        }
    })
}

/// Ask the USB-connected phone for its WiFi IP via ADB.
pub fn discover_ip_via_adb() -> Result<String> {
    let output = Command::new("adb")
        .args(["shell", "ip", "addr", "show", "wlan0"])
        .output()
        .context("ADB not found. Please install Android Debug Bridge (adb)")?;
    if !output.status.success() {
        bail!(
            "Failed to query the phone's WiFi interface: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    parse_wlan_ip(&String::from_utf8_lossy(&output.stdout)).context(
        "Could not find a WiFi IP address. Make sure the phone is connected via USB with USB \
         debugging enabled, and is connected to WiFi",
    )
}

/// A device found by mDNS.
#[derive(Debug, Clone)]
pub struct DiscoveredDevice {
    pub name: String,
    pub hostname: String,
    pub addresses: Vec<std::net::IpAddr>,
    pub port: u16,
}

/// Browse mDNS for the Android app's `_etphonehome._tcp` service.
pub async fn discover_mdns(timeout: Duration) -> Result<Vec<DiscoveredDevice>> {
    let mdns = mdns_sd::ServiceDaemon::new().context("Failed to create mDNS service daemon")?;
    let receiver = mdns
        .browse(MDNS_SERVICE_TYPE)
        .context("Failed to browse for services")?;
    let deadline = Instant::now() + timeout;
    let mut found: Vec<DiscoveredDevice> = Vec::new();
    loop {
        let left = deadline.saturating_duration_since(Instant::now());
        if left.is_zero() {
            break;
        }
        match tokio::time::timeout(left, receiver.recv_async()).await {
            Ok(Ok(mdns_sd::ServiceEvent::ServiceResolved(info))) => {
                let mut addresses: Vec<std::net::IpAddr> =
                    info.get_addresses().iter().copied().collect();
                addresses.sort();
                let dev = DiscoveredDevice {
                    name: info.get_fullname().to_string(),
                    hostname: info.get_hostname().to_string(),
                    addresses,
                    port: info.get_port(),
                };
                if !found.iter().any(|d| d.name == dev.name) {
                    info!("Found device: {}", dev.name);
                    found.push(dev);
                }
            }
            Ok(Ok(_)) => {}
            Ok(Err(_)) | Err(_) => break,
        }
    }
    // Wait for the daemon's goodbye: dropping its reply channel makes
    // mdns-sd log a spurious "failed to send response of shutdown" error.
    if let Ok(done) = mdns.shutdown() {
        let _ = done.recv_timeout(Duration::from_secs(1));
    }
    Ok(found)
}

/// The phones found by [`discover_mdns`] as a JSON array (`discover-wifi --json`).
pub fn discovered_json(found: &[DiscoveredDevice]) -> String {
    let list: Vec<serde_json::Value> = found
        .iter()
        .map(|d| {
            serde_json::json!({
                "name": d.name,
                "hostname": d.hostname,
                "addresses": d.addresses.iter().map(|a| a.to_string()).collect::<Vec<_>>(),
                "port": d.port,
            })
        })
        .collect();
    serde_json::Value::Array(list).to_string()
}

/// Point the default route at the TUN link (lower priority than the
/// existing default so we don't cut off the host's own uplink by surprise).
pub fn setup_routing(tun_name: &str) -> Result<()> {
    setup_routing_via(tun_name, TUN_PEER)
}

/// [`setup_routing`] with the TUN link's peer address.
pub fn setup_routing_via(tun_name: &str, peer: &str) -> Result<()> {
    info!("Setting up routing for {}...", tun_name);
    #[cfg(target_os = "linux")]
    {
        // No `sysctl net.ipv4.ip_forward=1` here: this computer only sends
        // its own traffic into the TUN device, which needs no forwarding.
        // Flipping it turned the user's machine into a router for every
        // network it is on, and it was never turned back off.
        let output = Command::new("ip")
            .args([
                "route", "add", "default", "via", peer, "dev", tun_name, "metric", "100",
            ])
            .output()
            .context("Failed to run `ip route` (is iproute2 installed?)")?;
        if !output.status.success() {
            warn!(
                "Failed to add default route via {} (may already exist): {}",
                tun_name,
                String::from_utf8_lossy(&output.stderr).trim()
            );
        }
    }
    #[cfg(windows)]
    {
        let output = Command::new("route")
            .args([
                "add", "0.0.0.0", "mask", "0.0.0.0", peer, "metric", "10",
            ])
            .output()
            .context("Failed to add route")?;
        if !output.status.success() {
            warn!("Failed to add default route (may already exist)");
        }
    }
    #[cfg(not(any(target_os = "linux", windows)))]
    {
        warn!("Routing setup not implemented for this platform");
    }
    Ok(())
}

/// Point name resolution at the DNS server the phone announced, for names
/// resolved through the tunnel. Without this a computer whose only uplink is
/// the phone keeps asking a DNS server it can no longer reach.
///
/// Linux: per-link systemd-resolved settings (`resolvectl dns/domain`), which
/// disappear with the TUN device; nothing global is touched. Windows: the
/// TUN adapter's DNS server (`netsh`). Elsewhere, or if that fails, a
/// warning says what to configure by hand.
pub fn configure_dns(tun_name: &str, dns: &str) {
    #[cfg(target_os = "linux")]
    {
        let ok = Command::new("resolvectl")
            .args(["dns", tun_name, dns])
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false);
        if ok {
            let _ = Command::new("resolvectl")
                .args(["domain", tun_name, "~."])
                .output();
            info!("DNS on {} set to {} (systemd-resolved)", tun_name, dns);
        } else {
            warn!(
                "Could not set DNS on {} (no systemd-resolved). If this computer has no other \
                 working DNS server, add `nameserver {}` to /etc/resolv.conf",
                tun_name, dns
            );
        }
    }
    #[cfg(windows)]
    {
        let name = format!("name={}", tun_name);
        let ok = Command::new("netsh")
            .args([
                "interface",
                "ipv4",
                "set",
                "dnsservers",
                &name,
                "static",
                dns,
                "primary",
            ])
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false);
        if ok {
            info!("DNS on {} set to {}", tun_name, dns);
        } else {
            warn!(
                "Could not set DNS on {} to {} (netsh failed)",
                tun_name, dns
            );
        }
    }
    #[cfg(not(any(target_os = "linux", windows)))]
    {
        warn!("Set your DNS server to {} by hand on this platform", dns);
    }
}

/// Create the TUN device (async, so reads and writes run independently).
pub fn create_tun(tun_name: &str) -> Result<tun::AsyncDevice> {
    create_tun_with(tun_name, &host::TunAddressing::default())
}

/// [`create_tun`] with explicit link addresses.
pub fn create_tun_with(tun_name: &str, addr: &host::TunAddressing) -> Result<tun::AsyncDevice> {
    crate::tun_interface::create_async(
        tun_name,
        &addr.local.to_string(),
        &addr.netmask().to_string(),
    )
    .context(format!(
        "Failed to create TUN interface '{}'. Run as root/Administrator (sudo etphonehome ...), \
         make sure the tun module is loaded (sudo modprobe tun) and /dev/net/tun exists; on \
         Windows wintun.dll must be next to the executable. If the interface already exists, \
         pick another name with --interface",
        tun_name
    ))
}

/// Open the transport for a plan (runs `adb forward` first for USB).
pub async fn open_transport(target: &Target) -> Result<Transport> {
    open_transport_with(target, &HostIntegration::default()).await
}

/// [`open_transport`] with the host's USB settings (adb serial, built-in
/// ADB host).
pub async fn open_transport_with(target: &Target, host: &HostIntegration) -> Result<Transport> {
    match target {
        Target::Tcp { opts, adb_port } => {
            if let Some(port) = adb_port {
                if host.usb_native || host.adb_tcp.is_some() {
                    return open_native_usb(*port, host).await;
                }
                setup_adb_forwarding_for(*port, host.adb_serial.as_deref())?;
            }
            session::connect(opts).await
        }
        Target::Bluetooth { address, token } => connect_bluetooth(address, token.clone()).await,
        Target::Auto { usb_port, wifi } => {
            if host.adb_tcp.is_some() || usb_phone_present(host.adb_serial.as_deref()) {
                info!("Phone on USB: using it");
                return open_native_usb(*usb_port, host).await;
            }
            match wifi {
                Some(w) => session::connect(w).await,
                None => bail!(
                    "no phone with USB debugging on the USB port (plug it in, unlock it, enable USB \
                     debugging), and no WiFi phone configured"
                ),
            }
        }
    }
}

/// Is a phone with USB debugging plugged in (Linux sysfs)?
pub fn usb_phone_present(serial: Option<&str>) -> bool {
    #[cfg(target_os = "linux")]
    {
        let devs = crate::adb::usb::find_devices(std::path::Path::new("/sys/bus/usb/devices"));
        crate::adb::usb::pick(&devs, serial).is_ok()
    }
    #[cfg(not(target_os = "linux"))]
    {
        let _ = serial;
        false
    }
}

fn this_host_name() -> String {
    let h = std::fs::read_to_string("/proc/sys/kernel/hostname")
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "router".into());
    format!("etph@{h}")
}

/// USB through the built-in ADB host: the phone's adb interface via usbfs
/// (or adbd over TCP with `adb_tcp`), authenticated with this device's adb
/// key, then a stream to the app's loopback port -- no adb tool or server.
async fn open_native_usb(port: u16, host: &HostIntegration) -> Result<Transport> {
    let serial = host.adb_serial.clone();
    let adb_tcp = host.adb_tcp.clone();
    let status = host.status.clone();
    let stream = tokio::task::spawn_blocking(move || -> Result<crate::adb::AdbStream> {
        let key = crate::adb::key::AdbKey::load_or_create(&crate::adb::key::default_dir(), &this_host_name())?;
        let link: std::sync::Arc<dyn crate::adb::Link> = match adb_tcp {
            Some(addr) => std::sync::Arc::new(crate::adb::TcpLink::connect(&addr, Duration::from_secs(10))?),
            None => {
                #[cfg(target_os = "linux")]
                {
                    let devs = crate::adb::usb::find_devices(std::path::Path::new("/sys/bus/usb/devices"));
                    let dev = crate::adb::usb::pick(&devs, serial.as_deref())?;
                    info!("Phone on USB: {} (serial {})", dev.product, dev.serial);
                    std::sync::Arc::new(crate::adb::usb::UsbLink::open(&dev).with_context(|| {
                        format!("cannot open {} (run as root)", dev.dev_node.display())
                    })?)
                }
                #[cfg(not(target_os = "linux"))]
                {
                    let _ = serial;
                    bail!("the built-in ADB host needs Linux usbfs; use the adb tool on this platform")
                }
            }
        };
        let observer: &dyn crate::adb::AuthObserver = match &status {
            Some(st) => &**st,
            None => &crate::adb::NoObserver,
        };
        crate::adb::open_stream(link, &key, observer, Duration::from_secs(120), &format!("tcp:{port}"))
    })
    .await
    .context("adb task failed")??;
    let model = stream.connected.model();
    let mut t = session::handshake_stream(stream.io, capabilities::USB, None, Duration::from_secs(30)).await?;
    if t.handshake.device_name.is_none() {
        t.handshake.device_name = model;
    }
    Ok(t)
}

/// The app's RFCOMM service UUID (`TetherService.BLUETOOTH_UUID` on the
/// phone). Android hands out RFCOMM channels dynamically (channel 6 on a
/// Galaxy Note20, not 1), so the desktop must not guess a channel: BlueZ
/// looks the service up over SDP when we ask it to connect this profile.
pub const BLUETOOTH_SERVICE_UUID: &str = "6b1c0f53-4e3a-4d7e-9c5b-455450484f4e";

/// Pick the phone among the paired devices whose name matched `wanted`.
/// `paired` lists every paired device, for the error message.
pub fn pick_bluetooth_device<A: std::fmt::Display + Copy>(
    wanted: &str,
    matches: &[A],
    paired: &[String],
) -> Result<A> {
    match matches {
        [one] => Ok(*one),
        [] if paired.is_empty() => bail!(
            "No paired Bluetooth device is called \"{wanted}\" (no device is paired at all). Pair the \
             phone first: bluetoothctl, then `scan on`, `pair <address>`, `trust <address>`"
        ),
        [] => bail!(
            "No paired Bluetooth device is called \"{wanted}\". Paired devices: {}. Use one of \
             these names or addresses",
            paired.join(", ")
        ),
        many => bail!(
            "Several paired Bluetooth devices are called \"{wanted}\" ({}); use the address instead",
            many.iter().map(|a| a.to_string()).collect::<Vec<_>>().join(", ")
        ),
    }
}

/// `--address` takes the phone's MAC address or its name as paired (the app
/// can't show its own MAC: Android returns 02:00:00:00:00:00 to apps).
#[cfg(all(target_os = "linux", feature = "bluetooth"))]
async fn resolve_bluetooth_device(
    adapter: &bluer::Adapter,
    wanted: &str,
) -> Result<bluer::Address> {
    if let Ok(addr) = wanted.parse::<bluer::Address>() {
        return Ok(addr);
    }
    let mut matches = Vec::new();
    let mut paired = Vec::new();
    for addr in adapter.device_addresses().await? {
        let dev = adapter.device(addr)?;
        if !dev.is_paired().await.unwrap_or(false) {
            continue;
        }
        let alias = dev.alias().await.unwrap_or_default();
        let name = dev.name().await.ok().flatten().unwrap_or_default();
        if alias.trim().eq_ignore_ascii_case(wanted.trim())
            || name.trim().eq_ignore_ascii_case(wanted.trim())
        {
            matches.push(addr);
        }
        paired.push(format!("\"{alias}\" ({addr})"));
    }
    pick_bluetooth_device(wanted, &matches, &paired)
}

/// An RFCOMM stream BlueZ connected for our client profile. The profile
/// registration lives as long as the stream (dropping it unregisters the
/// profile, and BlueZ would tear the connection down).
#[cfg(all(target_os = "linux", feature = "bluetooth"))]
struct ProfileStream {
    stream: bluer::rfcomm::Stream,
    _profile: Box<bluer::rfcomm::ProfileHandle>,
}

#[cfg(all(target_os = "linux", feature = "bluetooth"))]
impl tokio::io::AsyncRead for ProfileStream {
    fn poll_read(
        self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &mut tokio::io::ReadBuf<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::pin::Pin::new(&mut self.get_mut().stream).poll_read(cx, buf)
    }
}

#[cfg(all(target_os = "linux", feature = "bluetooth"))]
impl tokio::io::AsyncWrite for ProfileStream {
    fn poll_write(
        self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &[u8],
    ) -> std::task::Poll<std::io::Result<usize>> {
        std::pin::Pin::new(&mut self.get_mut().stream).poll_write(cx, buf)
    }
    fn poll_flush(
        self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::pin::Pin::new(&mut self.get_mut().stream).poll_flush(cx)
    }
    fn poll_shutdown(
        self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::pin::Pin::new(&mut self.get_mut().stream).poll_shutdown(cx)
    }
}

#[cfg(all(target_os = "linux", feature = "bluetooth"))]
async fn connect_bluetooth(address: &str, token: Option<String>) -> Result<Transport> {
    use futures::StreamExt;
    if token.is_none() {
        warn!(
            "No Bluetooth token given (--token or auth.bluetooth_token); the Android app \
             rejects unauthenticated Bluetooth handshakes"
        );
    }
    let session = bluer::Session::new()
        .await
        .context("Failed to create BlueZ session. Is the bluetooth service running?")?;
    let adapter = session
        .default_adapter()
        .await
        .context("No Bluetooth adapter found")?;
    info!("Using Bluetooth adapter {}", adapter.name());
    let bt_addr = resolve_bluetooth_device(&adapter, address).await?;
    let uuid: bluer::Uuid = BLUETOOTH_SERVICE_UUID.parse().expect("valid UUID");
    // BlueZ owns the RFCOMM socket: it finds the app's channel over SDP and
    // passes us the connected socket (which also works from a container
    // with its own network namespace, where AF_BLUETOOTH can't be opened).
    let profile = session
        .register_profile(bluer::rfcomm::Profile {
            uuid,
            name: Some("ETPhoneHome desktop".to_string()),
            role: Some(bluer::rfcomm::Role::Client),
            require_authentication: Some(true),
            require_authorization: Some(false),
            auto_connect: Some(false),
            ..Default::default()
        })
        .await
        .context("Failed to register the ETPhoneHome Bluetooth profile with BlueZ")?;
    let mut profile = Box::new(profile);
    let device = adapter.device(bt_addr)?;
    let hint = format!(
        "Pair and trust the phone first (bluetoothctl pair {0}; bluetoothctl trust {0}) and \
         start the app in Bluetooth mode",
        bt_addr
    );
    let connect = device.connect_profile(&uuid);
    tokio::pin!(connect);
    let mut connect_done = false;
    let stream = tokio::time::timeout(Duration::from_secs(30), async {
        loop {
            tokio::select! {
                r = &mut connect, if !connect_done => {
                    r.map_err(|e| anyhow::anyhow!("Failed to connect to Bluetooth device {bt_addr}: {e}. {hint}"))?;
                    connect_done = true;
                }
                req = profile.next() => {
                    let req = req.context("BlueZ dropped the Bluetooth profile")?;
                    return req.accept().map_err(anyhow::Error::from);
                }
            }
        }
    })
    .await
    .context(format!("Timed out connecting to Bluetooth device {bt_addr}"))??;
    info!("Bluetooth RFCOMM connection established with {}", bt_addr);
    session::handshake_stream(
        ProfileStream {
            stream,
            _profile: profile,
        },
        capabilities::BLUETOOTH,
        token,
        Duration::from_secs(30),
    )
    .await
}

#[cfg(not(all(target_os = "linux", feature = "bluetooth")))]
async fn connect_bluetooth(address: &str, _token: Option<String>) -> Result<Transport> {
    if cfg!(target_os = "linux") {
        bail!(
            "Bluetooth tethering to {} is not built into this client (the OpenWrt/router build \
             has no BlueZ support). Use USB or WiFi.",
            address
        )
    }
    bail!(
        "Bluetooth tethering to {} is only supported on Linux (BlueZ). Use USB or WiFi on \
         this platform.",
        address
    )
}

/// Short name of a plan's transport ("usb", "wifi", "bluetooth").
pub fn transport_name(target: &Target) -> &'static str {
    match target {
        Target::Tcp {
            adb_port: Some(_), ..
        } => "usb",
        Target::Tcp { .. } => "wifi",
        Target::Bluetooth { .. } => "bluetooth",
        Target::Auto { .. } => "auto",
    }
}

/// Where a plan connects to, for logs and the status file.
pub fn target_label(target: &Target) -> String {
    match target {
        Target::Tcp { opts, .. } => opts.addr.to_string(),
        Target::Bluetooth { address, .. } => address.clone(),
        Target::Auto { usb_port, wifi } => match wifi {
            Some(w) => format!("USB (port {usb_port}) or {}", w.addr),
            None => format!("USB (port {usb_port})"),
        },
    }
}

/// Run one session: connect, bring up TUN + routes, forward until done.
pub async fn run_session<F>(
    plan: &SessionPlan,
    service: Option<ConnectionService>,
    shutdown: F,
) -> Result<SessionEnd>
where
    F: Future<Output = ()> + Send,
{
    let host = &plan.host;
    let usb_now = match &plan.target {
        Target::Tcp { adb_port, .. } => adb_port.is_some(),
        Target::Auto { .. } => host.adb_tcp.is_some() || usb_phone_present(host.adb_serial.as_deref()),
        Target::Bluetooth { .. } => false,
    };
    let transport = open_transport_with(&plan.target, host).await?;
    let tun = create_tun_with(&plan.tun_name, &host.tun)?;
    let dns = (transport.handshake.dns != 0)
        .then(|| std::net::Ipv4Addr::from(transport.handshake.dns));
    let device = transport.handshake.device_name.clone();
    if host.manage_routes {
        if let Err(e) = setup_routing_via(&plan.tun_name, &host.tun.peer.to_string()) {
            warn!("Routing setup failed (connection may still work): {:#}", e);
        }
        if let Some(dns) = dns {
            configure_dns(&plan.tun_name, &dns.to_string());
        }
    }
    // USB is a cable: "encrypted" only describes TLS over WiFi.
    let encrypted = match &plan.target {
        Target::Tcp { opts, .. } => opts.tls,
        Target::Bluetooth { .. } => true,
        Target::Auto { wifi, .. } => !usb_now && wifi.as_ref().is_some_and(|w| w.tls),
    };
    let mut hook = HookInfo {
        ifname: plan.tun_name.clone(),
        transport: match &plan.target {
            Target::Auto { .. } if usb_now => "usb".to_string(),
            Target::Auto { .. } => "wifi".to_string(),
            t => transport_name(t).to_string(),
        },
        dns,
        device: device.clone(),
        reason: None,
    };
    if let Some(d) = &device {
        info!("Phone: {}", d);
    }
    info!("Tethering connection established on {}", plan.tun_name);
    if let Target::Tcp { opts, .. } = &plan.target {
        info!(
            "Transport security: {}",
            if opts.tls {
                "ENCRYPTED (cert-pinned TLS 1.3)"
            } else {
                "PLAINTEXT"
            }
        );
    }
    if let Some(script) = &host.up_script {
        host::run_hook(script, "up", &host.tun, &hook);
    }
    // The status file needs live byte counters; the forwarding loop
    // publishes them through a ConnectionService (the GUI's, or our own).
    let service = service.or_else(|| host.status.as_ref().map(|_| ConnectionService::new()));
    let status_pump = match (&host.status, &service) {
        (Some(st), Some(svc)) => {
            st.connected(device.clone(), encrypted);
            let st = st.clone();
            let svc = svc.clone();
            Some(tokio::spawn(async move {
                let mut tick = tokio::time::interval(host::STATUS_INTERVAL);
                loop {
                    tick.tick().await;
                    let s = svc.stats();
                    st.traffic(s.bytes_sent, s.bytes_received);
                }
            }))
        }
        _ => None,
    };
    let opts = ForwardOptions {
        filter: plan.filter.clone(),
        service: service.clone(),
        ..Default::default()
    };
    let result = session::forward(transport, tun, opts, shutdown).await;
    if let Some(p) = status_pump {
        p.abort();
    }
    // `forward` consumed the TUN device: it is gone by now, so the down
    // hook sees the same state as pppd's ip-down.
    hook.reason = Some(match &result {
        Ok((end, _)) => format!("{:?}", end),
        Err(e) => format!("{:#}", e),
    });
    if let Some(st) = &host.status {
        if let Ok((_, stats)) = &result {
            st.traffic(stats.bytes_sent, stats.bytes_received);
        }
        st.session_ended(hook.reason.as_deref().unwrap_or(""));
    }
    if let Some(script) = &host.down_script {
        host::run_hook(script, "down", &host.tun, &hook);
    }
    let (end, _) = result?;
    Ok(end)
}

/// Auto-reconnect policy. `max_retries == 0` means retry forever.
#[derive(Debug, Clone, Copy)]
pub struct RetryPolicy {
    pub reconnect: bool,
    pub max_retries: u32,
}

/// A shutdown signal that fires once (Ctrl-C, GUI Disconnect).
pub fn shutdown_channel() -> (watch::Sender<bool>, watch::Receiver<bool>) {
    watch::channel(false)
}

/// Resolves when `rx` has been set to `true` (or its sender is dropped).
pub async fn wait_shutdown(mut rx: watch::Receiver<bool>) {
    let _ = rx.wait_for(|v| *v).await;
}

/// Shutdown receiver wired to Ctrl-C and (Unix) SIGTERM, which is how
/// service managers stop the client (OpenWrt netifd/procd, systemd): the
/// phone still gets a Disconnect instead of waiting for its idle timeout.
pub fn ctrl_c_shutdown() -> watch::Receiver<bool> {
    let (tx, rx) = shutdown_channel();
    tokio::spawn(async move {
        #[cfg(unix)]
        let what = {
            use tokio::signal::unix::{signal, SignalKind};
            match signal(SignalKind::terminate()) {
                Ok(mut term) => tokio::select! {
                    r = tokio::signal::ctrl_c() => match r { Ok(()) => "Ctrl-C", Err(_) => return },
                    _ = term.recv() => "SIGTERM",
                },
                Err(_) => match tokio::signal::ctrl_c().await {
                    Ok(()) => "Ctrl-C",
                    Err(_) => return,
                },
            }
        };
        #[cfg(not(unix))]
        let what = match tokio::signal::ctrl_c().await {
            Ok(()) => "Ctrl-C",
            Err(_) => return,
        };
        info!("{} received, disconnecting...", what);
        let _ = tx.send(true);
    });
    rx
}

/// Errors no retry can fix: the phone speaks another protocol major
/// version, or rejected our token (or our plaintext connection). Retrying
/// these forever just hides the reason behind a reconnect loop.
pub fn is_permanent(e: &anyhow::Error) -> bool {
    use crate::protocol::{ErrorCode, ProtocolError};
    e.chain().any(|c| {
        matches!(
            c.downcast_ref::<ProtocolError>(),
            Some(ProtocolError::VersionMismatch { .. })
                | Some(ProtocolError::PeerError {
                    code: ErrorCode::ProtocolVersion | ErrorCode::AuthFailed,
                    ..
                })
        )
    })
}

/// Run `attempt` with auto-reconnect and exponential backoff (1, 2, 4 ...
/// capped at 60 s). Stops on shutdown, on a cancelled session, or when the
/// retry budget is used up (returning the last error, if the last attempt
/// failed). Each attempt receives the 1-based attempt number.
pub async fn run_with_retry<A, Fut>(
    policy: RetryPolicy,
    shutdown: watch::Receiver<bool>,
    service: Option<ConnectionService>,
    mut attempt: A,
) -> Result<()>
where
    A: FnMut(u32) -> Fut,
    Fut: Future<Output = Result<SessionEnd>>,
{
    // `n` numbers attempts for the UI; `failed` counts attempts since the
    // last session that actually came up. The retry budget and the backoff
    // use `failed`: a laptop whose WiFi blips five times over a day is not
    // out of retries, and its first reconnect waits 1 s, not 60 s. (Both
    // used the lifetime attempt count before.)
    let mut n: u32 = 0;
    let mut failed: u32 = 0;
    loop {
        n += 1;
        let fut = attempt(n);
        tokio::pin!(fut);
        let result = tokio::select! {
            r = &mut fut => r,
            _ = wait_shutdown(shutdown.clone()) => {
                // The attempt sees the same shutdown signal (the forwarding
                // loop uses it to send Disconnect to the phone). Give it a
                // moment to finish that instead of dropping it mid-send; only
                // a still-connecting attempt gets cut off.
                match tokio::time::timeout(Duration::from_secs(3), &mut fut).await {
                    Ok(r) => r,
                    Err(_) => Ok(SessionEnd::Cancelled),
                }
            }
        };
        let stopping = *shutdown.borrow();
        let retry = !stopping
            && match &result {
                Ok(end) => {
                    info!("Connection closed: {:?}", end);
                    failed = 0;
                    end.should_reconnect()
                }
                Err(e) if is_permanent(e) => {
                    error!("Connection failed: {:#}", e);
                    error!(
                        "Not retrying: this will not fix itself (update the app or this client so \
                         both speak the same protocol version, or use the token the app shows)"
                    );
                    false
                }
                Err(e) => {
                    error!("Connection failed: {:#}", e);
                    failed += 1;
                    true
                }
            };
        let budget_left = policy.max_retries == 0 || failed < policy.max_retries;
        if !(policy.reconnect && retry && budget_left) {
            if let Some(svc) = &service {
                svc.set_state(match &result {
                    Err(e) => ConnectionState::Error {
                        message: format!("{:#}", e),
                    },
                    Ok(_) => ConnectionState::Disconnected,
                });
            }
            return match result {
                Ok(_) => Ok(()),
                Err(e) if policy.reconnect => {
                    Err(e.context(format!("giving up after {} connection attempt(s)", n)))
                }
                Err(e) => Err(e),
            };
        }
        let delay = ConnectionService::calculate_retry_delay(failed);
        info!(
            "Reconnecting in {}s (attempt {} of {})...",
            delay.as_secs(),
            failed + 1,
            if policy.max_retries == 0 {
                "∞".to_string()
            } else {
                policy.max_retries.to_string()
            }
        );
        if let Some(svc) = &service {
            if let Some(profile) = svc.current_profile() {
                svc.set_state(ConnectionState::Reconnecting {
                    profile,
                    attempt: n + 1,
                    delay_secs: delay.as_secs(),
                });
            }
        }
        tokio::select! {
            _ = tokio::time::sleep(delay) => {}
            _ = host::retry_requested() => info!("Retrying now"),
            _ = wait_shutdown(shutdown.clone()) => {
                if let Some(svc) = &service { svc.set_state(ConnectionState::Disconnected); }
                return Ok(());
            }
        }
    }
}

/// Connect a saved profile with auto-reconnect, reporting state/stats to
/// `service` (used by the GUI; the CLI passes `None`).
pub async fn connect_profile(
    config: &Config,
    profile: &Profile,
    tun_name: &str,
    policy: RetryPolicy,
    shutdown: watch::Receiver<bool>,
    service: Option<ConnectionService>,
) -> Result<()> {
    let plan = plan_for_profile(config, profile, tun_name).await?;
    run_plan(&plan, policy, shutdown, service, Some(profile.clone())).await
}

/// Run a plan with auto-reconnect.
pub async fn run_plan(
    plan: &SessionPlan,
    policy: RetryPolicy,
    shutdown: watch::Receiver<bool>,
    service: Option<ConnectionService>,
    profile: Option<Profile>,
) -> Result<()> {
    let svc_outer = service.clone();
    let status = plan.host.status.clone();
    let result = run_with_retry(policy, shutdown.clone(), service, |n| {
        let svc = svc_outer.clone();
        let profile = profile.clone();
        let shutdown = shutdown.clone();
        let status = status.clone();
        async move {
            if let Some(st) = &status {
                st.connecting(n);
            }
            if let (Some(s), Some(p)) = (&svc, &profile) {
                s.set_state(ConnectionState::Connecting {
                    profile: p.clone(),
                    attempt: n,
                });
            }
            let svc2 = svc.clone();
            let profile2 = profile.clone();
            let connected = async move {
                if let (Some(s), Some(p)) = (&svc2, &profile2) {
                    s.set_state(ConnectionState::Connected {
                        profile: p.clone(),
                        started_at: Instant::now(),
                    });
                }
                wait_shutdown(shutdown).await
            };
            let r = run_session(plan, svc, connected).await;
            if let (Some(st), Err(e)) = (&status, &r) {
                st.failed(&format!("{:#}", e));
            }
            r
        }
    })
    .await;
    if let Some(st) = &status {
        match &result {
            Ok(()) => st.stopped(None),
            Err(e) => st.stopped(Some(&format!("{:#}", e))),
        }
    }
    result
}

// ---------------------------------------------------------------------------
// Profile commands
// ---------------------------------------------------------------------------

/// Parse a CLI connection type.
pub fn parse_connection_type(s: &str) -> Result<ConnectionType> {
    match s.to_lowercase().as_str() {
        "usb" => Ok(ConnectionType::Usb),
        "wifi" => Ok(ConnectionType::Wifi),
        "bluetooth" | "bt" => Ok(ConnectionType::Bluetooth),
        _ => bail!(
            "Invalid connection type '{}'. Must be: usb, wifi, or bluetooth",
            s
        ),
    }
}

/// Load the config at `path`, or defaults if the file doesn't exist yet.
pub fn load_config_at(path: &Path) -> Result<Config> {
    if path.exists() {
        Config::load(path)
    } else {
        Ok(Config::default())
    }
}

/// Create or update a profile in the config file at `path`.
///
/// Updating an existing profile keeps its filter rules (they can't be set
/// from the command line, and the old code silently reset them to the
/// default on every save). WiFi profiles need a host and Bluetooth profiles
/// an address; that used to be accepted and only fail at connect time.
#[allow(clippy::too_many_arguments)]
pub fn save_profile_at(
    path: &Path,
    name: &str,
    conn_type: &str,
    host: Option<String>,
    port: Option<u16>,
    address: Option<String>,
    description: Option<String>,
) -> Result<()> {
    if name.trim().is_empty() {
        bail!("Profile name must not be empty");
    }
    let connection_type = parse_connection_type(conn_type)?;
    match connection_type {
        ConnectionType::Wifi if host.is_none() => bail!("WiFi profiles need --host"),
        ConnectionType::Bluetooth if address.is_none() => {
            bail!("Bluetooth profiles need --address")
        }
        _ => {}
    }
    let mut config = load_config_at(path)?;
    let filter = config
        .get_profile(name)
        .map(|p| p.filter.clone())
        .unwrap_or_default();
    config.upsert_profile(Profile {
        name: name.to_string(),
        connection_type,
        description,
        port,
        host,
        address,
        filter,
    });
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent)?;
        }
    }
    config.save(path)
}

/// Delete a profile; errors if it doesn't exist.
pub fn delete_profile_at(path: &Path, name: &str) -> Result<()> {
    let mut config = load_config_at(path)?;
    if !config.remove_profile(name) {
        bail!("Profile '{}' not found in {}", name, path.display());
    }
    config.save(path)
}

/// Human-readable profile listing (printed to stdout by the CLIs).
pub fn format_profiles(config: &Config) -> String {
    use std::fmt::Write;
    let mut out = String::new();
    if config.profiles.is_empty() {
        out.push_str("No saved profiles found.\n");
        out.push_str(
            "Create one with: etphonehome save-profile <name> --type <usb|wifi|bluetooth> ...\n",
        );
        return out;
    }
    out.push_str("Saved connection profiles:\n\n");
    for p in &config.profiles {
        let kind = match p.connection_type {
            ConnectionType::Usb => "USB",
            ConnectionType::Wifi => "WiFi",
            ConnectionType::Bluetooth => "Bluetooth",
        };
        let _ = writeln!(out, "  {} ({})", p.name, kind);
        let _ = writeln!(
            out,
            "    {}",
            p.description.as_deref().unwrap_or("No description")
        );
        match p.connection_type {
            ConnectionType::Usb => {
                let _ = writeln!(out, "    Port: {}", p.port.unwrap_or(DEFAULT_PORT));
            }
            ConnectionType::Wifi => {
                if let Some(h) = &p.host {
                    let _ = writeln!(out, "    Host: {}:{}", h, p.port.unwrap_or(DEFAULT_PORT));
                }
            }
            ConnectionType::Bluetooth => {
                if let Some(a) = &p.address {
                    let _ = writeln!(out, "    Address: {}", a);
                }
            }
        }
        if p.filter.enabled {
            let _ = writeln!(
                out,
                "    Filter: {:?} mode, {} rule(s)",
                p.filter.mode,
                p.filter.rules.len()
            );
        }
        out.push('\n');
    }
    out.push_str("Connect with: sudo etphonehome connect <profile-name>\n");
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU32, Ordering};
    use std::sync::Arc;

    fn tmp(name: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!(
            "etph-runner-{}-{}-{}",
            name,
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&d).unwrap();
        d.join("sub").join("config.toml")
    }

    #[test]
    fn discovered_phones_as_json() {
        let j = discovered_json(&[DiscoveredDevice {
            name: "ET Phone Home._etphonehome._tcp.local.".into(),
            hostname: "pixel.local.".into(),
            addresses: vec!["192.168.8.153".parse().unwrap()],
            port: 8888,
        }]);
        let v: serde_json::Value = serde_json::from_str(&j).unwrap();
        assert_eq!(v[0]["addresses"][0], "192.168.8.153");
        assert_eq!(v[0]["port"], 8888);
        assert_eq!(discovered_json(&[]), "[]");
    }

    #[test]
    fn adb_forward_targets_one_phone_when_asked() {
        assert_eq!(
            adb_forward_args(8888, None),
            vec!["forward", "tcp:8888", "tcp:8888"]
        );
        assert_eq!(
            adb_forward_args(9000, Some(" R5CR70PMMQR ")),
            vec!["-s", "R5CR70PMMQR", "forward", "tcp:9000", "tcp:9000"]
        );
        assert_eq!(
            adb_forward_args(8888, Some("")),
            vec!["forward", "tcp:8888", "tcp:8888"]
        );
    }

    #[test]
    fn transport_names_for_status_and_hooks() {
        let opts = ConnectOptions {
            addr: "127.0.0.1:8888".parse().unwrap(),
            capabilities: capabilities::USB,
            token: None,
            tls: false,
            timeout: Duration::from_secs(1),
        };
        let usb = Target::Tcp {
            opts: opts.clone(),
            adb_port: Some(8888),
        };
        let wifi = Target::Tcp {
            opts,
            adb_port: None,
        };
        assert_eq!(transport_name(&usb), "usb");
        assert_eq!(transport_name(&wifi), "wifi");
        assert_eq!(target_label(&wifi), "127.0.0.1:8888");
        let bt = Target::Bluetooth {
            address: "Pixel".into(),
            token: None,
        };
        assert_eq!(transport_name(&bt), "bluetooth");
        assert_eq!(target_label(&bt), "Pixel");
    }

    #[test]
    fn bluetooth_name_picks_the_single_paired_match() {
        let paired = vec!["\"Galaxy\" (EC:7C:B6:77:D4:8F)".to_string()];
        assert_eq!(pick_bluetooth_device("Galaxy", &[7u8], &paired).unwrap(), 7);
    }

    #[test]
    fn bluetooth_name_without_match_lists_the_paired_devices() {
        let paired = vec!["\"Pixel\" (AA:BB:CC:DD:EE:FF)".to_string()];
        let e = pick_bluetooth_device::<u8>("Galaxy", &[], &paired)
            .unwrap_err()
            .to_string();
        assert!(
            e.contains("No paired Bluetooth device is called \"Galaxy\""),
            "{e}"
        );
        assert!(
            e.contains("Pixel") && e.contains("AA:BB:CC:DD:EE:FF"),
            "{e}"
        );
        let e = pick_bluetooth_device::<u8>("Galaxy", &[], &[])
            .unwrap_err()
            .to_string();
        assert!(
            e.contains("no device is paired at all") && e.contains("bluetoothctl"),
            "{e}"
        );
    }

    #[test]
    fn bluetooth_name_matching_two_devices_asks_for_the_address() {
        let e = pick_bluetooth_device("Galaxy", &[1u8, 2u8], &[])
            .unwrap_err()
            .to_string();
        assert!(
            e.contains("Several paired Bluetooth devices") && e.contains("1, 2"),
            "{e}"
        );
    }

    #[test]
    fn bluetooth_service_uuid_matches_the_app() {
        let kt = include_str!(
            "../../../android/app/src/main/java/com/etphonehome/service/TetherService.kt"
        );
        assert!(
            kt.contains(&format!("UUID.fromString(\"{BLUETOOTH_SERVICE_UUID}\")")),
            "the app must listen on the UUID the desktop connects to"
        );
    }

    #[test]
    fn parse_wlan_ip_extracts_first_non_loopback_ipv4() {
        let out = "32: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500\n    \
                   link/ether 02:00:00:00:00:00 brd ff:ff:ff:ff:ff:ff\n    \
                   inet 192.168.1.100/24 brd 192.168.1.255 scope global wlan0\n    \
                   inet6 fe80::1/64 scope link\n";
        assert_eq!(parse_wlan_ip(out).as_deref(), Some("192.168.1.100"));
        assert_eq!(parse_wlan_ip("    inet 127.0.0.1/8 scope host lo\n"), None);
        assert_eq!(parse_wlan_ip("Device \"wlan0\" does not exist.\n"), None);
    }

    #[test]
    fn connection_types_parse_case_insensitively() {
        assert_eq!(parse_connection_type("USB").unwrap(), ConnectionType::Usb);
        assert_eq!(parse_connection_type("WiFi").unwrap(), ConnectionType::Wifi);
        assert_eq!(
            parse_connection_type("bt").unwrap(),
            ConnectionType::Bluetooth
        );
        assert!(parse_connection_type("serial").is_err());
    }

    #[test]
    fn save_list_update_delete_profile_roundtrip() {
        let path = tmp("profiles");
        save_profile_at(
            &path,
            "home",
            "wifi",
            Some("192.168.1.50".into()),
            Some(9000),
            None,
            Some("Home".into()),
        )
        .unwrap();
        save_profile_at(&path, "cable", "usb", None, None, None, None).unwrap();

        // Give "home" a filter rule as the GUI would, then update it from the
        // CLI: the rule must survive.
        let mut cfg = Config::load(&path).unwrap();
        cfg.profiles[0].filter.enabled = true;
        cfg.save(&path).unwrap();
        save_profile_at(
            &path,
            "home",
            "wifi",
            Some("192.168.1.51".into()),
            None,
            None,
            None,
        )
        .unwrap();
        let cfg = Config::load(&path).unwrap();
        assert_eq!(cfg.profiles.len(), 2);
        let home = cfg.get_profile("home").unwrap();
        assert_eq!(home.host.as_deref(), Some("192.168.1.51"));
        assert!(home.filter.enabled, "CLI update wiped the filter config");

        let listing = format_profiles(&cfg);
        assert!(listing.contains("home (WiFi)"), "{}", listing);
        assert!(listing.contains("Host: 192.168.1.51:8888"), "{}", listing);
        assert!(listing.contains("cable (USB)"), "{}", listing);
        assert!(listing.contains("Filter: Blacklist mode"), "{}", listing);

        delete_profile_at(&path, "home").unwrap();
        assert!(delete_profile_at(&path, "home").is_err());
        let cfg = Config::load(&path).unwrap();
        assert_eq!(cfg.list_profiles(), vec!["cable".to_string()]);
    }

    #[test]
    fn save_profile_validates_required_fields() {
        let path = tmp("validate");
        assert!(save_profile_at(&path, "w", "wifi", None, None, None, None).is_err());
        assert!(save_profile_at(&path, "b", "bluetooth", None, None, None, None).is_err());
        assert!(save_profile_at(&path, "x", "carrier-pigeon", None, None, None, None).is_err());
        assert!(save_profile_at(&path, " ", "usb", None, None, None, None).is_err());
        assert!(!path.exists(), "nothing should have been written");
        // ...and with the field present they save (a Bluetooth phone may be
        // named instead of addressed).
        save_profile_at(
            &path,
            "b",
            "bluetooth",
            None,
            None,
            Some("Galaxy Note20".into()),
            None,
        )
        .expect("bluetooth profile with an address/name saves");
        save_profile_at(
            &path,
            "w",
            "wifi",
            Some("192.168.1.9".into()),
            None,
            None,
            None,
        )
        .expect("wifi profile with a host saves");
        let names = load_config_at(&path).unwrap().list_profiles();
        assert!(
            names.contains(&"b".to_string()) && names.contains(&"w".to_string()),
            "{names:?}"
        );
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn empty_profile_list_explains_how_to_create_one() {
        let s = format_profiles(&Config::default());
        assert!(s.contains("No saved profiles"));
        assert!(s.contains("--type"));
    }

    #[tokio::test]
    async fn plan_for_profile_picks_token_tls_and_port() {
        let mut cfg = Config::default();
        cfg.auth.wifi_token = "wtok".into();
        cfg.auth.bluetooth_token = "btok".into();
        cfg.auth.tls_enabled = true;
        let wifi = Profile {
            name: "w".into(),
            connection_type: ConnectionType::Wifi,
            description: None,
            port: Some(9999),
            host: Some("10.1.2.3".into()),
            address: None,
            filter: FilterConfig::default(),
        };
        let plan = plan_for_profile(&cfg, &wifi, "tun7").await.unwrap();
        assert_eq!(plan.tun_name, "tun7");
        match plan.target {
            Target::Tcp { opts, adb_port } => {
                assert_eq!(opts.addr, "10.1.2.3:9999".parse().unwrap());
                assert_eq!(opts.token.as_deref(), Some("wtok"));
                assert!(opts.tls);
                assert_eq!(opts.capabilities, capabilities::WIFI);
                assert_eq!(adb_port, None);
            }
            t => panic!("{:?}", t),
        }

        let usb = Profile {
            connection_type: ConnectionType::Usb,
            host: None,
            port: None,
            ..wifi.clone()
        };
        match plan_for_profile(&cfg, &usb, "etph0").await.unwrap().target {
            Target::Tcp { opts, adb_port } => {
                assert_eq!(opts.addr, "127.0.0.1:8888".parse().unwrap());
                assert_eq!(opts.token, None, "USB never sends a token");
                assert!(!opts.tls);
                assert_eq!(adb_port, Some(8888));
            }
            t => panic!("{:?}", t),
        }

        let bt = Profile {
            connection_type: ConnectionType::Bluetooth,
            address: Some("AA:BB:CC:DD:EE:FF".into()),
            ..wifi.clone()
        };
        match plan_for_profile(&cfg, &bt, "etph0").await.unwrap().target {
            Target::Bluetooth { address, token } => {
                assert_eq!(address, "AA:BB:CC:DD:EE:FF");
                assert_eq!(token.as_deref(), Some("btok"));
            }
            t => panic!("{:?}", t),
        }

        let no_host = Profile {
            host: None,
            ..wifi.clone()
        };
        assert!(plan_for_profile(&cfg, &no_host, "etph0").await.is_err());

        // A filter the phone can't take is refused before connecting, rather
        // than ending every session as soon as it starts.
        let mut big_filter = wifi.clone();
        big_filter.filter.enabled = true;
        big_filter.filter.rules = (0..=crate::filter::MAX_WIRE_RULES)
            .map(|i| crate::filter::FilterRule {
                id: format!("r{}", i),
                enabled: true,
                action: crate::filter::FilterAction::Block,
                protocol: None,
                dest_ip: Some("10.0.0.1".into()),
                dest_port: None,
                source_ip: None,
                source_port: None,
                description: None,
            })
            .collect();
        let err = plan_for_profile(&cfg, &big_filter, "etph0")
            .await
            .expect_err("1025 rules is more than the phone takes");
        let msg = format!("{:#}", err);
        assert!(msg.contains("Profile 'w'"), "{}", msg);
        assert!(msg.contains("at most 1024"), "{}", msg);
    }

    #[tokio::test]
    async fn resolve_addr_accepts_ips_and_hostnames() {
        assert_eq!(
            resolve_addr("192.168.4.4", 8888).await.unwrap(),
            "192.168.4.4:8888".parse().unwrap()
        );
        let a = resolve_addr("localhost", 1234).await.unwrap();
        assert!(a.ip().is_loopback());
        assert_eq!(a.port(), 1234);
        // Trailing dot = absolute name, so the resolver skips the resolv.conf
        // search list. Without it, a CI host with `search redweek.com` (whose
        // zone has a wildcard record) resolved `no-such-host.invalid` as
        // `no-such-host.invalid.redweek.com` and this assertion failed.
        // RFC 6761 guarantees `.invalid.` itself never resolves.
        assert!(resolve_addr("no-such-host.invalid.", 1).await.is_err());
    }

    #[tokio::test(start_paused = true)]
    async fn retry_backs_off_and_respects_max_retries() {
        let calls = Arc::new(AtomicU32::new(0));
        let (_tx, rx) = shutdown_channel();
        let c = Arc::clone(&calls);
        let start = tokio::time::Instant::now();
        let r = run_with_retry(
            RetryPolicy {
                reconnect: true,
                max_retries: 4,
            },
            rx,
            None,
            move |_| {
                let c = Arc::clone(&c);
                async move {
                    c.fetch_add(1, Ordering::SeqCst);
                    Err::<SessionEnd, _>(anyhow::anyhow!("refused"))
                }
            },
        )
        .await;
        assert_eq!(calls.load(Ordering::SeqCst), 4);
        let err = format!("{:#}", r.unwrap_err());
        assert!(err.contains("giving up after 4"), "{}", err);
        // Backoff 1 + 2 + 4 seconds between the four attempts.
        assert_eq!(start.elapsed().as_secs(), 7);
    }

    /// A version mismatch or a rejected token cannot be fixed by retrying:
    /// the client must stop with the reason, not loop forever.
    #[tokio::test(start_paused = true)]
    async fn permanent_errors_are_not_retried() {
        use crate::protocol::{ErrorCode, ProtocolError};
        let errors: Vec<fn() -> anyhow::Error> = vec![
            || {
                anyhow::Error::new(ProtocolError::VersionMismatch {
                    local_major: 1,
                    peer_major: 2,
                    peer_version: 0x0200,
                })
                .context("handshake with Android device failed")
            },
            || {
                anyhow::Error::new(ProtocolError::PeerError {
                    code: ErrorCode::AuthFailed,
                    message: "Authentication failed: invalid token".into(),
                })
                .context("handshake with Android device failed")
            },
            || {
                anyhow::Error::new(ProtocolError::PeerError {
                    code: ErrorCode::ProtocolVersion,
                    message: "Protocol version mismatch".into(),
                })
            },
        ];
        for make in errors {
            let calls = Arc::new(AtomicU32::new(0));
            let (_tx, rx) = shutdown_channel();
            let c = Arc::clone(&calls);
            let r = run_with_retry(
                RetryPolicy {
                    reconnect: true,
                    max_retries: 0,
                },
                rx,
                None,
                move |_| {
                    let c = Arc::clone(&c);
                    async move {
                        c.fetch_add(1, Ordering::SeqCst);
                        Err::<SessionEnd, _>(make())
                    }
                },
            )
            .await;
            assert_eq!(calls.load(Ordering::SeqCst), 1);
            assert!(r.is_err());
        }
        // Transient failures stay retryable.
        assert!(!is_permanent(&anyhow::anyhow!("connection refused")));
        assert!(!is_permanent(&anyhow::Error::new(
            ProtocolError::PeerError {
                code: ErrorCode::InternalError,
                message: "busy".into(),
            }
        )));
    }

    /// The retry budget counts failures since the last session that came
    /// up, not attempts over the client's lifetime, and the backoff starts
    /// again at 1 s after a session.
    #[tokio::test(start_paused = true)]
    async fn retry_budget_resets_after_a_session_came_up() {
        let calls = Arc::new(AtomicU32::new(0));
        let (_tx, rx) = shutdown_channel();
        let c = Arc::clone(&calls);
        let start = tokio::time::Instant::now();
        let r = run_with_retry(
            RetryPolicy {
                reconnect: true,
                max_retries: 2,
            },
            rx,
            None,
            move |n| {
                let c = Arc::clone(&c);
                async move {
                    c.fetch_add(1, Ordering::SeqCst);
                    match n {
                        // fail, session, fail, session, fail, fail -> give up
                        2 | 4 => Ok(SessionEnd::TransportClosed("wifi blip".into())),
                        _ => Err::<SessionEnd, _>(anyhow::anyhow!("refused")),
                    }
                }
            },
        )
        .await;
        assert_eq!(calls.load(Ordering::SeqCst), 6);
        assert!(r.is_err());
        // Delays: 1 (fail) + 1 (session) + 1 (fail) + 1 (session) + 1 (fail).
        assert_eq!(start.elapsed().as_secs(), 5);
    }

    #[tokio::test(start_paused = true)]
    async fn no_reconnect_means_single_attempt() {
        let calls = Arc::new(AtomicU32::new(0));
        let (_tx, rx) = shutdown_channel();
        let c = Arc::clone(&calls);
        let r = run_with_retry(
            RetryPolicy {
                reconnect: false,
                max_retries: 0,
            },
            rx,
            None,
            move |_| {
                let c = Arc::clone(&c);
                async move {
                    c.fetch_add(1, Ordering::SeqCst);
                    Ok(SessionEnd::TransportClosed("lost".into()))
                }
            },
        )
        .await;
        assert!(r.is_ok());
        assert_eq!(calls.load(Ordering::SeqCst), 1);
    }

    #[tokio::test(start_paused = true)]
    async fn reconnects_after_drop_then_stops_on_cancel() {
        let calls = Arc::new(AtomicU32::new(0));
        let (_tx, rx) = shutdown_channel();
        let c = Arc::clone(&calls);
        let svc = ConnectionService::new();
        let r = run_with_retry(
            RetryPolicy {
                reconnect: true,
                max_retries: 0,
            },
            rx,
            Some(svc.clone()),
            move |n| {
                let c = Arc::clone(&c);
                async move {
                    c.fetch_add(1, Ordering::SeqCst);
                    if n < 3 {
                        Ok(SessionEnd::PeerDisconnected("phone stopped".into()))
                    } else {
                        Ok(SessionEnd::Cancelled)
                    }
                }
            },
        )
        .await;
        assert!(r.is_ok());
        assert_eq!(calls.load(Ordering::SeqCst), 3);
        assert!(matches!(svc.state(), ConnectionState::Disconnected));
    }

    #[tokio::test(start_paused = true)]
    async fn shutdown_during_backoff_stops_retrying() {
        let calls = Arc::new(AtomicU32::new(0));
        let (tx, rx) = shutdown_channel();
        let c = Arc::clone(&calls);
        let task = tokio::spawn(run_with_retry(
            RetryPolicy {
                reconnect: true,
                max_retries: 0,
            },
            rx,
            None,
            move |_| {
                let c = Arc::clone(&c);
                async move {
                    c.fetch_add(1, Ordering::SeqCst);
                    Err::<SessionEnd, _>(anyhow::anyhow!("refused"))
                }
            },
        ));
        tokio::time::sleep(Duration::from_millis(1500)).await;
        tx.send(true).unwrap();
        assert!(task.await.unwrap().is_ok());
        assert_eq!(calls.load(Ordering::SeqCst), 2);
    }

    /// Ctrl-C while connected must let the session finish (it sends
    /// Disconnect to the phone) rather than dropping it mid-flight. This
    /// raced before: the retry loop and the session both watched the signal
    /// and the loop sometimes won, so the phone only saw the socket close.
    #[tokio::test(start_paused = true)]
    async fn shutdown_lets_running_session_finish_cleanly() {
        let finished = Arc::new(AtomicU32::new(0));
        let (tx, rx) = shutdown_channel();
        let f = Arc::clone(&finished);
        let rx2 = rx.clone();
        let task = tokio::spawn(run_with_retry(
            RetryPolicy {
                reconnect: true,
                max_retries: 0,
            },
            rx,
            None,
            move |_| {
                let f = Arc::clone(&f);
                let rx = rx2.clone();
                async move {
                    wait_shutdown(rx).await;
                    // e.g. sending Disconnect and flushing
                    tokio::time::sleep(Duration::from_millis(500)).await;
                    f.fetch_add(1, Ordering::SeqCst);
                    Ok(SessionEnd::TransportClosed("closed after goodbye".into()))
                }
            },
        ));
        tokio::time::sleep(Duration::from_secs(1)).await;
        tx.send(true).unwrap();
        assert!(task.await.unwrap().is_ok());
        assert_eq!(finished.load(Ordering::SeqCst), 1, "session was cut off");
    }

    #[tokio::test(start_paused = true)]
    async fn final_failure_is_reported_to_connection_service() {
        let (_tx, rx) = shutdown_channel();
        let svc = ConnectionService::new();
        let r = run_with_retry(
            RetryPolicy {
                reconnect: false,
                max_retries: 0,
            },
            rx,
            Some(svc.clone()),
            |_| async { Err::<SessionEnd, _>(anyhow::anyhow!("no route to phone")) },
        )
        .await;
        assert!(r.is_err());
        match svc.state() {
            ConnectionState::Error { message } => assert!(message.contains("no route")),
            s => panic!("{:?}", s),
        }
    }

    /// The Android app registers `_etphonehome._tcp` via NsdManager; stand
    /// in for it with a real mDNS responder and check discovery finds it.
    #[tokio::test]
    async fn mdns_discovery_finds_advertised_service() {
        let responder = mdns_sd::ServiceDaemon::new().unwrap();
        // A routable local address (mDNS isn't sent over loopback). No
        // packet is sent by this connect; it only selects the interface.
        let probe = std::net::UdpSocket::bind("0.0.0.0:0").unwrap();
        probe.connect("192.0.2.1:9").unwrap();
        let ip = probe.local_addr().unwrap().ip().to_string();
        let host = format!("etph-test-{}.local.", std::process::id());
        let info = mdns_sd::ServiceInfo::new(
            MDNS_SERVICE_TYPE,
            &format!("ET Phone Home {}", std::process::id()),
            &host,
            ip.as_str(),
            8888,
            None::<std::collections::HashMap<String, String>>,
        )
        .unwrap();
        responder.register(info).unwrap();
        let found = discover_mdns(Duration::from_secs(5)).await.unwrap();
        let _ = responder.shutdown();
        let me = found
            .iter()
            .find(|d| d.name.contains(&std::process::id().to_string()))
            .unwrap_or_else(|| panic!("advertised service not discovered: {:?}", found));
        assert_eq!(me.port, 8888);
        assert!(!me.addresses.is_empty());
    }
}
