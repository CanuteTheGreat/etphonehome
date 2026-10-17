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
}

/// A fully-resolved description of one session.
#[derive(Debug, Clone)]
pub struct SessionPlan {
    pub target: Target,
    pub tun_name: String,
    pub filter: FilterConfig,
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
    Ok(SessionPlan {
        target,
        tun_name: tun_name.to_string(),
        filter: profile.filter.clone(),
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
    info!("Setting up ADB port forwarding (tcp:{})...", port);
    let output = Command::new("adb")
        .args([
            "forward",
            &format!("tcp:{}", port),
            &format!("tcp:{}", port),
        ])
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
    let _ = mdns.shutdown();
    Ok(found)
}

/// Point the default route at the TUN link (lower priority than the
/// existing default so we don't cut off the host's own uplink by surprise).
pub fn setup_routing(tun_name: &str) -> Result<()> {
    info!("Setting up routing for {}...", tun_name);
    #[cfg(target_os = "linux")]
    {
        let _ = Command::new("sysctl")
            .args(["-w", "net.ipv4.ip_forward=1"])
            .output();
        let output = Command::new("ip")
            .args([
                "route", "add", "default", "via", TUN_PEER, "dev", tun_name, "metric", "100",
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
                "add", "0.0.0.0", "mask", "0.0.0.0", TUN_PEER, "metric", "10",
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
    crate::tun_interface::create_async(tun_name, TUN_ADDRESS, TUN_NETMASK).context(format!(
        "Failed to create TUN interface '{}'. Run as root/Administrator (sudo etphonehome ...), \
         make sure the tun module is loaded (sudo modprobe tun) and /dev/net/tun exists; on \
         Windows wintun.dll must be next to the executable. If the interface already exists, \
         pick another name with --interface",
        tun_name
    ))
}

/// Open the transport for a plan (runs `adb forward` first for USB).
pub async fn open_transport(target: &Target) -> Result<Transport> {
    match target {
        Target::Tcp { opts, adb_port } => {
            if let Some(port) = adb_port {
                setup_adb_forwarding(*port)?;
            }
            session::connect(opts).await
        }
        Target::Bluetooth { address, token } => connect_bluetooth(address, token.clone()).await,
    }
}

#[cfg(target_os = "linux")]
async fn connect_bluetooth(address: &str, token: Option<String>) -> Result<Transport> {
    let bt_addr: bluer::Address = address
        .parse()
        .context("Invalid Bluetooth address format (expected XX:XX:XX:XX:XX:XX)")?;
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
    let socket_addr = bluer::rfcomm::SocketAddr::new(bt_addr, 1);
    let stream = bluer::rfcomm::Stream::connect(socket_addr)
        .await
        .context(format!(
            "Failed to connect to Bluetooth device {0}. Pair and trust it first \
             (bluetoothctl pair {0}; bluetoothctl trust {0}) and start the app in Bluetooth mode",
            address
        ))?;
    info!("Bluetooth RFCOMM connection established");
    session::handshake_stream(
        stream,
        capabilities::BLUETOOTH,
        token,
        Duration::from_secs(30),
    )
    .await
}

#[cfg(not(target_os = "linux"))]
async fn connect_bluetooth(address: &str, _token: Option<String>) -> Result<Transport> {
    bail!(
        "Bluetooth tethering to {} is only supported on Linux (BlueZ). Use USB or WiFi on \
         this platform.",
        address
    )
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
    let transport = open_transport(&plan.target).await?;
    let tun = create_tun(&plan.tun_name)?;
    if let Err(e) = setup_routing(&plan.tun_name) {
        warn!("Routing setup failed (connection may still work): {:#}", e);
    }
    if transport.handshake.dns != 0 {
        configure_dns(
            &plan.tun_name,
            &crate::client::int_to_ip(transport.handshake.dns),
        );
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
    let opts = ForwardOptions {
        filter: plan.filter.clone(),
        service,
        ..Default::default()
    };
    let (end, _) = session::forward(transport, tun, opts, shutdown).await?;
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

/// Shutdown receiver wired to Ctrl-C.
pub fn ctrl_c_shutdown() -> watch::Receiver<bool> {
    let (tx, rx) = shutdown_channel();
    tokio::spawn(async move {
        if tokio::signal::ctrl_c().await.is_ok() {
            info!("Ctrl-C received, disconnecting...");
            let _ = tx.send(true);
        }
    });
    rx
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
    let mut n: u32 = 0;
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
                    end.should_reconnect()
                }
                Err(e) => {
                    error!("Connection failed: {:#}", e);
                    true
                }
            };
        let budget_left = policy.max_retries == 0 || n < policy.max_retries;
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
        let delay = ConnectionService::calculate_retry_delay(n);
        info!(
            "Reconnecting in {}s (attempt {} of {})...",
            delay.as_secs(),
            n + 1,
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
    run_with_retry(policy, shutdown.clone(), service, |n| {
        let svc = svc_outer.clone();
        let profile = profile.clone();
        let shutdown = shutdown.clone();
        async move {
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
            run_session(plan, svc, connected).await
        }
    })
    .await
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
