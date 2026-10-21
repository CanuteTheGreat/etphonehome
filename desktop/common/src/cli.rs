//! The `etphonehome` command line, shared by the Linux and Windows binaries
//! (which used to carry two ~1100-line, slowly diverging copies of it -- the
//! Windows one had already lost `--tls`).

use crate::config::Config;
use crate::filter::FilterConfig;
use crate::host::{self, HostIntegration, StatusFile, TunAddressing};
use crate::protocol::capabilities;
use crate::runner::{self, RetryPolicy, SessionPlan, Target, DEFAULT_PORT};
use crate::session::ConnectOptions;
use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use log::{info, warn};
use std::net::SocketAddr;
use std::time::Duration;

#[derive(Parser, Debug)]
#[command(name = "etphonehome", version)]
#[command(about = "ET Phone Home - USB/WiFi/Bluetooth tethering client", long_about = None)]
pub struct Cli {
    /// Config file to use (default: $ETPHONEHOME_CONFIG, else the user's
    /// config dir -- under sudo, the invoking user's)
    #[arg(long, global = true)]
    pub config: Option<std::path::PathBuf>,

    /// This client is a router's uplink (OpenWrt `proto etph` sets it; also
    /// $ETPH_ROUTER=1). Routers need ETPH Pro on the phone, never a licence here.
    #[arg(long, global = true)]
    pub router: bool,

    #[command(subcommand)]
    pub command: Commands,
}

/// Shared by every connecting subcommand.
#[derive(clap::Args, Debug, Clone)]
pub struct RetryArgs {
    /// Reconnect automatically when the connection drops (`--reconnect`
    /// or `--reconnect=false`)
    #[arg(short, long, num_args = 0..=1, default_missing_value = "true")]
    pub reconnect: Option<bool>,

    /// Maximum connection attempts with --reconnect (0 = infinite)
    #[arg(long)]
    pub max_retries: Option<u32>,
}

/// How the session plugs into the host (routers: OpenWrt netifd), shared
/// by every connecting subcommand.
#[derive(clap::Args, Debug, Clone, Default)]
pub struct HostArgs {
    /// USB phones: `auto` (accessory link -- no USB debugging needed -- when
    /// the phone offers it, else ADB), `aoa`, or `adb`
    #[arg(
        long,
        value_name = "auto|aoa|adb",
        default_value = "auto",
        env = "ETPH_USB_LINK"
    )]
    pub usb_link: crate::runner::UsbLinkMode,
    /// Don't add a default route or DNS; leave that to --up-script (on
    /// OpenWrt netifd owns routes, DNS, firewall zones and mwan3)
    #[arg(long)]
    pub no_routes: bool,
    /// Run this after the TUN device is up: `<script> up`, with ETPH_IFNAME,
    /// ETPH_LOCAL, ETPH_PREFIX, ETPH_PEER, ETPH_DNS, ETPH_DEVICE, ETPH_TRANSPORT
    #[arg(long, value_name = "SCRIPT")]
    pub up_script: Option<std::path::PathBuf>,
    /// Run this after a session ended: `<script> down` (same variables plus ETPH_REASON)
    #[arg(long, value_name = "SCRIPT")]
    pub down_script: Option<std::path::PathBuf>,
    /// Keep a JSON status (state, phone, bytes, rates, last error) in this file
    #[arg(long, value_name = "FILE")]
    pub status_file: Option<std::path::PathBuf>,
    /// Address of this end of the TUN link, A.B.C.D[/prefix] (default 10.0.0.1/24)
    #[arg(long, value_name = "ADDR")]
    pub tun_address: Option<String>,
    /// The phone's end of the TUN link (default: the next address)
    #[arg(long, value_name = "ADDR")]
    pub tun_peer: Option<String>,
}

impl HostArgs {
    fn integration(
        &self,
        transport: &str,
        target: &str,
        interface: &str,
        adb_serial: Option<String>,
    ) -> Result<HostIntegration> {
        let tun = match &self.tun_address {
            Some(a) => TunAddressing::parse(a, self.tun_peer.as_deref())?,
            None if self.tun_peer.is_some() => {
                TunAddressing::parse("10.0.0.1/24", self.tun_peer.as_deref())?
            }
            None => TunAddressing::default(),
        };
        Ok(HostIntegration {
            manage_routes: !self.no_routes,
            up_script: self.up_script.clone(),
            down_script: self.down_script.clone(),
            status: self
                .status_file
                .clone()
                .map(|p| StatusFile::new(p, transport, target, interface)),
            tun,
            adb_serial,
            usb_native: false,
            usb_link: self.usb_link,
            adb_tcp: None,
        })
    }
}

#[derive(Subcommand, Debug)]
pub enum Commands {
    /// Connect via USB (sets up `adb forward` automatically)
    Usb {
        /// Port the Android app listens on
        #[arg(short, long, default_value_t = DEFAULT_PORT)]
        port: u16,
        /// TUN interface name
        #[arg(short, long, default_value = "etph0")]
        interface: String,
        /// Which phone, when several are plugged in (`adb devices` serial)
        #[arg(long)]
        adb_serial: Option<String>,
        /// Talk to the phone over USB with the built-in ADB host (Linux usbfs):
        /// no adb tool or adb server needed (routers)
        #[arg(long)]
        native: bool,
        /// Built-in ADB host over TCP to an adbd in `adb tcpip` mode (HOST:PORT)
        #[arg(long, value_name = "HOST:PORT")]
        adb_tcp: Option<String>,
        #[command(flatten)]
        retry: RetryArgs,
        #[command(flatten)]
        net: HostArgs,
    },

    /// Router mode: the phone on USB when one is plugged in (built-in ADB
    /// host), otherwise WiFi when --host is given; chosen on every attempt
    Auto {
        /// Port the app listens on in USB mode
        #[arg(short, long, default_value_t = DEFAULT_PORT)]
        port: u16,
        /// WiFi fallback: the phone's address
        #[arg(short = 'H', long)]
        host: Option<String>,
        /// WiFi fallback: the app's port
        #[arg(long, default_value_t = DEFAULT_PORT)]
        wifi_port: u16,
        /// WiFi fallback: pairing token (or $ETPH_TOKEN)
        #[arg(short = 't', long, env = "ETPH_TOKEN", hide_env_values = true)]
        token: Option<String>,
        /// WiFi fallback without TLS
        #[arg(long)]
        no_tls: bool,
        /// Which USB phone, when several are plugged in
        #[arg(long)]
        adb_serial: Option<String>,
        /// TUN interface name
        #[arg(short, long, default_value = "etph0")]
        interface: String,
        #[command(flatten)]
        retry: RetryArgs,
        #[command(flatten)]
        net: HostArgs,
    },

    /// Run the background service the menu-bar / tray app talks to (as root
    /// or LocalSystem: launchd, the Windows service, rc.d start it)
    Service {
        /// TUN interface name
        #[arg(short, long, default_value = "etph0")]
        interface: String,
        /// Control socket (Unix)
        #[arg(long, value_name = "PATH")]
        control: Option<std::path::PathBuf>,
        /// Started by the Windows service manager (set by the installer)
        #[arg(long, hide = true)]
        windows_service: bool,
    },

    /// Ask the running service: status | usb | connect PROFILE | disconnect
    Ctl {
        /// status, usb, connect, disconnect
        action: String,
        /// Profile name for `connect`
        profile: Option<String>,
        /// Print the service's JSON reply
        #[arg(long)]
        json: bool,
    },

    /// List phones with USB debugging on this computer's USB ports (sysfs;
    /// no adb needed)
    UsbList {
        /// JSON (for scripts and LuCI)
        #[arg(long)]
        json: bool,
    },

    /// Create this device's adb key now if it has none (key generation takes
    /// a while on router CPUs) and print its fingerprint
    AdbKeygen,

    /// Connect via WiFi
    Wifi {
        /// Android device IP address or hostname
        #[arg(short = 'H', long)]
        host: String,
        /// Port number
        #[arg(short, long, default_value_t = DEFAULT_PORT)]
        port: u16,
        /// TUN interface name
        #[arg(short, long, default_value = "etph0")]
        interface: String,
        /// Authentication token shown in the Android app (required by the app;
        /// also read from $ETPH_TOKEN, which keeps it out of `ps`)
        #[arg(short = 't', long, env = "ETPH_TOKEN", hide_env_values = true)]
        token: Option<String>,
        /// Cert-pinned TLS 1.3 keyed to the token (the default; kept so
        /// older scripts that pass it still work). Fails closed, never falls
        /// back to plaintext.
        #[arg(long, overrides_with = "no_tls")]
        tls: bool,
        /// Connect without encryption (the app only accepts this when its
        /// "Require encryption" switch is off)
        #[arg(long, overrides_with = "tls")]
        no_tls: bool,
        #[command(flatten)]
        retry: RetryArgs,
        #[command(flatten)]
        net: HostArgs,
    },

    /// Connect via Bluetooth RFCOMM (Linux/BlueZ)
    Bluetooth {
        /// The phone's Bluetooth address (MAC) or its name as paired (shown in the app)
        #[arg(short = 'a', long)]
        address: String,
        /// Authentication token shown in the Android app (or $ETPH_TOKEN)
        #[arg(short = 't', long, env = "ETPH_TOKEN", hide_env_values = true)]
        token: Option<String>,
        /// TUN interface name
        #[arg(short, long, default_value = "etph0")]
        interface: String,
        #[command(flatten)]
        retry: RetryArgs,
        #[command(flatten)]
        net: HostArgs,
    },

    /// Set up ADB port forwarding (computer port -> phone port)
    SetupAdb {
        #[arg(short, long, default_value_t = DEFAULT_PORT)]
        port: u16,
    },

    /// Discover the Android device's WiFi IP address via ADB
    DiscoverIp,

    /// Print the fingerprint the phone shows in "Allow USB debugging?" for
    /// this computer's adb key (routers have no screen to compare with)
    AdbFingerprint {
        /// adb public key (default: $HOME/.android/adbkey.pub)
        #[arg(long)]
        key: Option<std::path::PathBuf>,
    },

    /// Discover Android devices on WiFi via mDNS
    DiscoverWifi {
        /// Discovery timeout in seconds
        #[arg(short, long, default_value = "5")]
        timeout: u64,
        /// Print the phones found as JSON (for scripts and LuCI)
        #[arg(long)]
        json: bool,
    },

    /// Connect using a saved profile
    Connect {
        /// Profile name
        profile_name: String,
        /// Override TUN interface name
        #[arg(short, long)]
        interface: Option<String>,
        #[command(flatten)]
        retry: RetryArgs,
        #[command(flatten)]
        net: HostArgs,
    },

    /// List all saved profiles
    ListProfiles,

    /// Save (create or update) a connection profile
    SaveProfile {
        /// Profile name
        name: String,
        /// Connection type (usb, wifi, bluetooth)
        #[arg(short = 't', long = "type", visible_alias = "conn-type")]
        conn_type: String,
        /// Host (WiFi)
        #[arg(long)]
        host: Option<String>,
        /// Port
        #[arg(short, long)]
        port: Option<u16>,
        /// Bluetooth address (MAC) or the phone's name as paired
        #[arg(short = 'a', long)]
        address: Option<String>,
        /// Description
        #[arg(short, long)]
        description: Option<String>,
        /// The pairing token the app shows (stored as auth.wifi_token or
        /// auth.bluetooth_token; also read from $ETPH_TOKEN)
        #[arg(long, env = "ETPH_TOKEN", hide_env_values = true)]
        token: Option<String>,
    },

    /// Delete a saved profile
    DeleteProfile {
        /// Profile name
        name: String,
    },
}

/// Platform hooks the binaries provide.
pub struct Platform {
    pub name: &'static str,
    /// Whether we have the privileges needed to create a TUN device.
    pub privileged: fn() -> bool,
    pub elevate_hint: &'static str,
}

fn policy(retry: &RetryArgs, default_reconnect: bool, default_max: u32) -> RetryPolicy {
    RetryPolicy {
        reconnect: retry.reconnect.unwrap_or(default_reconnect),
        max_retries: retry.max_retries.unwrap_or(default_max),
    }
}

fn tcp_plan(
    addr: SocketAddr,
    caps: u32,
    token: Option<String>,
    tls: bool,
    adb_port: Option<u16>,
    interface: String,
) -> SessionPlan {
    SessionPlan {
        target: Target::Tcp {
            opts: ConnectOptions {
                addr,
                capabilities: caps,
                token,
                tls,
                timeout: Duration::from_secs(10),
            },
            adb_port,
        },
        tun_name: interface,
        // Ad-hoc connections have no saved filter; use a profile for rules.
        filter: FilterConfig::default(),
        host: HostIntegration::default(),
    }
}

fn warn_unprivileged(platform: &Platform) {
    if !(platform.privileged)() {
        warn!("Not running with administrator rights; TUN interface creation will likely fail.");
        warn!("{}", platform.elevate_hint);
    }
}

/// Parse the command line and run it.
pub async fn main(platform: Platform) -> Result<()> {
    run(Cli::parse(), platform).await
}

/// Run an already-parsed command line.
pub async fn run(cli: Cli, platform: Platform) -> Result<()> {
    if cli.router {
        crate::identity::set_router(true);
    }
    if let Some(path) = &cli.config {
        std::env::set_var("ETPHONEHOME_CONFIG", path);
    }
    let cfg_path = || Config::default_path();
    host::install_retry_signal();

    match cli.command {
        Commands::Usb {
            port,
            interface,
            adb_serial,
            native,
            adb_tcp,
            retry,
            net,
        } => {
            warn_unprivileged(&platform);
            let target = if native || adb_tcp.is_some() {
                format!("phone port {port} (built-in ADB host)")
            } else {
                format!("127.0.0.1:{port} (adb forward)")
            };
            let mut integration = net.integration("usb", &target, &interface, adb_serial)?;
            integration.usb_native = native;
            integration.adb_tcp = adb_tcp;
            let mut plan = tcp_plan(
                SocketAddr::from(([127, 0, 0, 1], port)),
                capabilities::USB,
                None,
                false,
                Some(port),
                interface,
            );
            plan.host = integration;
            runner::run_plan(
                &plan,
                policy(&retry, false, 0),
                runner::ctrl_c_shutdown(),
                None,
                None,
            )
            .await
        }
        Commands::Wifi {
            host: wifi_host,
            port,
            interface,
            token,
            tls,
            no_tls,
            retry,
            net,
        } => {
            // TLS unless --no-tls was the last word (`--tls` is the default).
            let tls = tls || !no_tls;
            warn_unprivileged(&platform);
            if token.is_none() {
                warn!(
                    "No --token given; the Android app rejects WiFi connections without its token"
                );
            }
            let integration =
                net.integration("wifi", &format!("{wifi_host}:{port}"), &interface, None)?;
            let addr = runner::resolve_addr(&wifi_host, port).await?;
            let mut plan = tcp_plan(addr, capabilities::WIFI, token, tls, None, interface);
            plan.host = integration;
            runner::run_plan(
                &plan,
                policy(&retry, false, 0),
                runner::ctrl_c_shutdown(),
                None,
                None,
            )
            .await
        }
        Commands::Bluetooth {
            address,
            token,
            interface,
            retry,
            net,
        } => {
            warn_unprivileged(&platform);
            let config = Config::load_or_default()?;
            let token = token.or_else(|| {
                (!config.auth.bluetooth_token.is_empty())
                    .then(|| config.auth.bluetooth_token.clone())
            });
            let integration = net.integration("bluetooth", &address, &interface, None)?;
            let plan = SessionPlan {
                target: Target::Bluetooth { address, token },
                tun_name: interface,
                filter: FilterConfig::default(),
                host: integration,
            };
            runner::run_plan(
                &plan,
                policy(&retry, false, 0),
                runner::ctrl_c_shutdown(),
                None,
                None,
            )
            .await
        }
        Commands::Auto {
            port,
            host: wifi_host,
            wifi_port,
            token,
            no_tls,
            adb_serial,
            interface,
            retry,
            net,
        } => {
            warn_unprivileged(&platform);
            let wifi = match &wifi_host {
                Some(h) => Some(crate::session::ConnectOptions {
                    addr: runner::resolve_addr(h, wifi_port).await?,
                    capabilities: capabilities::WIFI,
                    token: token.clone(),
                    tls: !no_tls,
                    timeout: Duration::from_secs(10),
                }),
                None => None,
            };
            let plan_target = runner::Target::Auto {
                usb_port: port,
                wifi,
            };
            let mut integration = net.integration(
                "auto",
                &runner::target_label(&plan_target),
                &interface,
                adb_serial,
            )?;
            integration.usb_native = true;
            let plan = SessionPlan {
                target: plan_target,
                tun_name: interface,
                filter: FilterConfig::default(),
                host: integration,
            };
            runner::run_plan(
                &plan,
                policy(&retry, false, 0),
                runner::ctrl_c_shutdown(),
                None,
                None,
            )
            .await
        }
        Commands::Service {
            interface,
            control,
            windows_service: _,
        } => {
            warn_unprivileged(&platform);
            #[allow(unused_mut)]
            let mut opts = crate::daemon::Options {
                interface,
                ..Default::default()
            };
            #[cfg(unix)]
            if let Some(c) = control {
                opts.control = c;
            }
            #[cfg(not(unix))]
            let _ = control;
            crate::daemon::run(opts, runner::ctrl_c_shutdown()).await
        }
        Commands::Ctl {
            action,
            profile,
            json,
        } => {
            use crate::daemon::Request;
            let req = match action.as_str() {
                "status" => Request {
                    cmd: "status".into(),
                    ..Default::default()
                },
                "usb" => Request {
                    cmd: "connect".into(),
                    mode: Some("usb".into()),
                    ..Default::default()
                },
                "connect" => Request {
                    cmd: "connect".into(),
                    profile: Some(profile.context("connect needs a profile name")?),
                    ..Default::default()
                },
                "disconnect" => Request {
                    cmd: "disconnect".into(),
                    ..Default::default()
                },
                other => anyhow::bail!(
                    "unknown action {other:?}: status, usb, connect PROFILE, disconnect"
                ),
            };
            #[cfg(unix)]
            let reply = crate::daemon::request(&req, &crate::daemon::default_control()).await?;
            #[cfg(windows)]
            let reply = crate::daemon::request(&req).await?;
            if json {
                println!("{}", serde_json::to_string(&reply)?);
            } else {
                println!("state: {}", reply.state);
                if let Some(s) = &reply.session {
                    println!("session: {s}");
                }
                if let Some(d) = &reply.device {
                    println!("phone: {d}");
                }
                if let Some(p) = reply.phone_pro {
                    println!("ETPH Pro on the phone: {}", if p { "yes" } else { "no" });
                }
                if let Some(e) = &reply.last_error {
                    println!("last error: {e}");
                }
                println!(
                    "sent {} bytes, received {} bytes",
                    reply.bytes_sent, reply.bytes_received
                );
                if !reply.profiles.is_empty() {
                    println!("profiles: {}", reply.profiles.join(", "));
                }
            }
            match reply.error {
                Some(e) => anyhow::bail!(e),
                None => Ok(()),
            }
        }
        Commands::UsbList { json } => {
            // Every phone on the USB ports: with USB debugging (adb) and/or
            // reachable over the accessory link (no USB debugging needed).
            #[cfg(target_os = "linux")]
            let phones: Vec<(String, String, String, bool, bool)> =
                crate::aoa::usb::scan(std::path::Path::new("/sys/bus/usb/devices"))
                    .into_iter()
                    .filter(|d| d.is_accessory() || d.maybe_phone())
                    .map(|d| {
                        (
                            d.serial.clone(),
                            d.name(),
                            d.port.clone(),
                            d.has_adb(),
                            d.is_accessory(),
                        )
                    })
                    .collect();
            #[cfg(all(not(target_os = "linux"), feature = "usb-libusb"))]
            let phones: Vec<(String, String, String, bool, bool)> = crate::adb::usb::find_devices()
                .into_iter()
                .map(|d| {
                    (
                        d.serial.clone(),
                        d.product.clone(),
                        d.sysfs.clone(),
                        true,
                        false,
                    )
                })
                .collect();
            #[cfg(not(any(target_os = "linux", feature = "usb-libusb")))]
            let phones: Vec<(String, String, String, bool, bool)> = Vec::new();
            if json {
                let list: Vec<serde_json::Value> = phones
                    .iter()
                    .map(|(serial, product, port, adb, acc)| {
                        serde_json::json!({"serial": serial, "product": product, "port": port,
                                           "adb": adb, "accessory_mode": acc})
                    })
                    .collect();
                println!("{}", serde_json::Value::Array(list));
            } else if phones.is_empty() {
                println!("No phone on the USB ports.");
            } else {
                for (serial, product, port, adb, acc) in &phones {
                    let how = match (adb, acc) {
                        (_, true) => "accessory mode",
                        (true, false) => "USB debugging on",
                        (false, false) => "USB debugging off (accessory link)",
                    };
                    println!("{serial}\t{product}\t(port {port}, {how})");
                }
            }
            Ok(())
        }
        Commands::AdbKeygen => {
            let dir = crate::adb::key::default_dir();
            let host = std::fs::read_to_string("/proc/sys/kernel/hostname")
                .map(|h| format!("etph@{}", h.trim()))
                .unwrap_or_else(|_| "etph@router".into());
            let key = crate::adb::key::AdbKey::load_or_create(&dir, &host)?;
            println!("{}", key.fingerprint());
            Ok(())
        }
        Commands::SetupAdb { port } => runner::setup_adb_forwarding(port),
        Commands::AdbFingerprint { key } => {
            let path = match key {
                Some(p) => p,
                None => std::path::PathBuf::from(std::env::var("HOME").unwrap_or_default())
                    .join(".android/adbkey.pub"),
            };
            let pubkey = std::fs::read_to_string(&path).with_context(|| {
                format!(
                    "cannot read {} (adb creates it the first time it runs)",
                    path.display()
                )
            })?;
            println!("{}", host::adb_key_fingerprint(&pubkey)?);
            Ok(())
        }
        Commands::DiscoverIp => {
            let ip = runner::discover_ip_via_adb()?;
            println!("Android WiFi IP: {}", ip);
            println!("To connect via WiFi, run:");
            println!(
                "  etphonehome wifi --host {} --port {} --token <token shown in the app>",
                ip, DEFAULT_PORT
            );
            Ok(())
        }
        Commands::DiscoverWifi { timeout, json } => {
            if json {
                let found = runner::discover_mdns(Duration::from_secs(timeout)).await?;
                println!("{}", runner::discovered_json(&found));
                return Ok(());
            }
            info!(
                "Scanning for ET Phone Home devices on WiFi ({}s)...",
                timeout
            );
            let found = runner::discover_mdns(Duration::from_secs(timeout)).await?;
            if found.is_empty() {
                println!("No ET Phone Home devices found on the network.");
                println!("Make sure the phone is on the same WiFi, the app is tethering in WiFi");
                println!("mode, and the firewall allows mDNS (UDP 5353).");
                return Ok(());
            }
            println!("Found {} device(s):", found.len());
            for d in &found {
                println!("  Device: {}", d.name);
                for a in &d.addresses {
                    println!("    Address: {}:{}", a, d.port);
                }
                if !d.hostname.is_empty() {
                    println!("    Hostname: {}", d.hostname);
                }
            }
            if let Some(a) = found[0].addresses.first() {
                println!();
                println!("To connect to the first device, run:");
                println!(
                    "  etphonehome wifi --host {} --port {} --token <token>",
                    a, found[0].port
                );
                println!("Or save it as a profile:");
                println!(
                    "  etphonehome save-profile my-phone --type wifi --host {} --port {}",
                    a, found[0].port
                );
            }
            Ok(())
        }
        Commands::Connect {
            profile_name,
            interface,
            retry,
            net,
        } => {
            let path = cfg_path()?;
            let config = runner::load_config_at(&path)?;
            let profile = config.get_profile(&profile_name).with_context(|| {
                format!(
                    "Profile '{}' not found in {} (see `etphonehome list-profiles`)",
                    profile_name,
                    path.display()
                )
            })?;
            warn_unprivileged(&platform);
            let tun = interface.unwrap_or_else(|| config.default.interface.clone());
            let mut plan = runner::plan_for_profile(&config, profile, &tun).await?;
            plan.host = net.integration(
                runner::transport_name(&plan.target),
                &runner::target_label(&plan.target),
                &tun,
                None,
            )?;
            runner::run_plan(
                &plan,
                policy(
                    &retry,
                    config.default.auto_reconnect,
                    config.default.max_retries,
                ),
                runner::ctrl_c_shutdown(),
                None,
                Some(profile.clone()),
            )
            .await
        }
        Commands::ListProfiles => {
            let path = cfg_path()?;
            print!(
                "{}",
                runner::format_profiles(&runner::load_config_at(&path)?)
            );
            Ok(())
        }
        Commands::SaveProfile {
            name,
            conn_type,
            host,
            port,
            address,
            description,
            token,
        } => {
            let path = cfg_path()?;
            runner::save_profile_at(&path, &name, &conn_type, host, port, address, description)?;
            if let Some(t) = token.filter(|t| !t.trim().is_empty()) {
                let mut cfg = Config::load(&path)?;
                if conn_type.eq_ignore_ascii_case("bluetooth") {
                    cfg.auth.bluetooth_token = t.trim().to_string();
                } else {
                    cfg.auth.wifi_token = t.trim().to_string();
                }
                cfg.save(&path)?;
            }
            println!("Profile '{}' saved to {}", name, path.display());
            Ok(())
        }
        Commands::DeleteProfile { name } => {
            let path = cfg_path()?;
            runner::delete_profile_at(&path, &name)?;
            println!("Profile '{}' deleted from {}", name, path.display());
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(args: &[&str]) -> Cli {
        Cli::try_parse_from(std::iter::once("etphonehome").chain(args.iter().copied())).unwrap()
    }

    #[test]
    fn cli_definition_is_consistent() {
        // Catches duplicate short flags etc. (the old wifi `--host` used `-h`,
        // colliding with `--help`).
        use clap::CommandFactory;
        Cli::command().debug_assert();
    }

    #[test]
    fn reconnect_is_opt_in_and_can_be_turned_off() {
        // The old `#[arg(short, long, default_value = "true")] reconnect: bool`
        // was always true: there was no way to turn auto-reconnect off.
        match parse(&["usb"]).command {
            Commands::Usb { retry, .. } => assert!(!policy(&retry, false, 0).reconnect),
            c => panic!("{:?}", c),
        }
        match parse(&["usb", "--reconnect", "--max-retries", "3"]).command {
            Commands::Usb { retry, .. } => {
                let p = policy(&retry, false, 0);
                assert!(p.reconnect);
                assert_eq!(p.max_retries, 3);
            }
            c => panic!("{:?}", c),
        }
        match parse(&["connect", "home", "--reconnect=false"]).command {
            Commands::Connect { retry, .. } => assert!(!policy(&retry, true, 0).reconnect),
            c => panic!("{:?}", c),
        }
        match parse(&["connect", "home"]).command {
            Commands::Connect { retry, .. } => {
                assert!(policy(&retry, true, 5).reconnect, "profile default applies");
                assert_eq!(policy(&retry, true, 5).max_retries, 5);
            }
            c => panic!("{:?}", c),
        }
    }

    #[test]
    fn readme_command_lines_parse() {
        // Every example in README.md / docs/SETUP.md must be accepted.
        parse(&["usb"]);
        parse(&["usb", "--port", "9999"]);
        parse(&["usb", "--interface", "mytun0"]);
        parse(&["usb", "--reconnect"]);
        parse(&["setup-adb"]);
        parse(&["discover-wifi"]);
        parse(&["discover-ip"]);
        parse(&[
            "wifi",
            "--host",
            "192.168.1.100",
            "--token",
            "AbC123XyZ4567890",
        ]);
        parse(&[
            "wifi",
            "--host",
            "192.168.1.100",
            "--token",
            "XXX",
            "--reconnect",
            "--max-retries",
            "10",
        ]);
        parse(&[
            "bluetooth",
            "--address",
            "AA:BB:CC:DD:EE:FF",
            "--token",
            "AbC123XyZ4567890",
        ]);
        parse(&[
            "save-profile",
            "my-home-wifi",
            "--type",
            "wifi",
            "--host",
            "192.168.1.100",
            "--description",
            "Home WiFi connection",
        ]);
        parse(&[
            "save-profile",
            "my-phone-bt",
            "--type",
            "bluetooth",
            "--address",
            "AA:BB:CC:DD:EE:FF",
        ]);
        // Old spelling still works.
        parse(&["save-profile", "x", "--conn-type", "usb"]);
        parse(&["save-profile", "x", "-t", "usb"]);
        parse(&["list-profiles"]);
        parse(&["connect", "my-home-wifi"]);
        parse(&["delete-profile", "my-home-wifi"]);
        parse(&["--config", "/tmp/x.toml", "list-profiles"]);
    }

    #[test]
    fn router_flags_parse_into_host_integration() {
        match parse(&[
            "wifi",
            "--host",
            "192.168.8.153",
            "-t",
            "tok",
            "--reconnect",
            "--no-routes",
            "--up-script",
            "/lib/netifd/etph-hook",
            "--down-script",
            "/lib/netifd/etph-hook",
            "--status-file",
            "/tmp/etph-cli-test-wan.json",
            "--tun-address",
            "172.31.255.1/30",
        ])
        .command
        {
            Commands::Wifi { net, .. } => {
                assert!(net.no_routes);
                let i = net
                    .integration("wifi", "192.168.8.153:8888", "etph-wan", None)
                    .unwrap();
                assert!(!i.manage_routes);
                assert_eq!(
                    i.up_script.as_deref(),
                    Some(std::path::Path::new("/lib/netifd/etph-hook"))
                );
                assert_eq!(i.tun.peer.to_string(), "172.31.255.2");
                assert!(i.status.is_some());
                let _ = std::fs::remove_file("/tmp/etph-cli-test-wan.json");
            }
            c => panic!("{:?}", c),
        }
        match parse(&["usb", "--adb-serial", "R5CR70PMMQR"]).command {
            Commands::Usb {
                adb_serial, net, ..
            } => {
                assert_eq!(adb_serial.as_deref(), Some("R5CR70PMMQR"));
                let i = net.integration("usb", "x", "etph0", adb_serial).unwrap();
                assert!(i.manage_routes, "desktops keep managing their routes");
                assert_eq!(i.adb_serial.as_deref(), Some("R5CR70PMMQR"));
                assert_eq!(i.tun, TunAddressing::default());
            }
            c => panic!("{:?}", c),
        }
        match parse(&[
            "usb",
            "--tun-address",
            "10.0.0.1/24",
            "--tun-peer",
            "10.9.0.2",
        ])
        .command
        {
            Commands::Usb { net, .. } => {
                assert!(net.integration("usb", "x", "etph0", None).is_err());
            }
            c => panic!("{:?}", c),
        }
        match parse(&["discover-wifi", "--json", "-t", "2"]).command {
            Commands::DiscoverWifi { json, timeout } => assert!(json && timeout == 2),
            c => panic!("{:?}", c),
        }
        parse(&[
            "connect",
            "phone",
            "--no-routes",
            "--status-file",
            "/tmp/x.json",
        ]);
        parse(&["bluetooth", "-a", "Pixel", "--no-routes"]);
        parse(&["adb-fingerprint", "--key", "/etc/etph/.android/adbkey.pub"]);
        parse(&["adb-keygen"]);
        parse(&["usb-list", "--json"]);
        match parse(&["usb", "--native", "--adb-tcp", "127.0.0.1:15556"]).command {
            Commands::Usb {
                native, adb_tcp, ..
            } => {
                assert!(native);
                assert_eq!(adb_tcp.as_deref(), Some("127.0.0.1:15556"));
            }
            c => panic!("{:?}", c),
        }
        match parse(&[
            "auto",
            "--host",
            "192.168.8.153",
            "--no-routes",
            "--reconnect",
        ])
        .command
        {
            Commands::Auto {
                host,
                port,
                wifi_port,
                no_tls,
                ..
            } => {
                assert_eq!(host.as_deref(), Some("192.168.8.153"));
                assert_eq!((port, wifi_port, no_tls), (8888, 8888, false));
            }
            c => panic!("{:?}", c),
        }
        parse(&["auto"]);
    }

    #[test]
    fn wifi_tls_flag_and_token() {
        match parse(&["wifi", "--host", "h", "-t", "tok", "--tls"]).command {
            Commands::Wifi { token, tls, .. } => {
                assert_eq!(token.as_deref(), Some("tok"));
                assert!(tls);
            }
            c => panic!("{:?}", c),
        }
    }

    #[test]
    fn wifi_is_encrypted_unless_no_tls() {
        match parse(&["wifi", "--host", "h", "-t", "tok"]).command {
            Commands::Wifi { tls, no_tls, .. } => assert!(tls || !no_tls),
            c => panic!("{:?}", c),
        }
        match parse(&["wifi", "--host", "h", "-t", "tok", "--no-tls"]).command {
            Commands::Wifi { tls, no_tls, .. } => assert!(!(tls || !no_tls)),
            c => panic!("{:?}", c),
        }
        match parse(&["wifi", "--host", "h", "--no-tls", "--tls"]).command {
            Commands::Wifi { tls, no_tls, .. } => assert!(tls || !no_tls),
            c => panic!("{:?}", c),
        }
    }
}
