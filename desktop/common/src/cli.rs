//! The `etphonehome` command line, shared by the Linux and Windows binaries
//! (which used to carry two ~1100-line, slowly diverging copies of it -- the
//! Windows one had already lost `--tls`).

use crate::config::Config;
use crate::filter::FilterConfig;
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
        #[command(flatten)]
        retry: RetryArgs,
    },

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
        /// Authentication token shown in the Android app (required by the app)
        #[arg(short = 't', long)]
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
    },

    /// Connect via Bluetooth RFCOMM (Linux/BlueZ)
    Bluetooth {
        /// The phone's Bluetooth address (MAC) or its name as paired (shown in the app)
        #[arg(short = 'a', long)]
        address: String,
        /// Authentication token shown in the Android app
        #[arg(short = 't', long)]
        token: Option<String>,
        /// TUN interface name
        #[arg(short, long, default_value = "etph0")]
        interface: String,
        #[command(flatten)]
        retry: RetryArgs,
    },

    /// Set up ADB port forwarding (computer port -> phone port)
    SetupAdb {
        #[arg(short, long, default_value_t = DEFAULT_PORT)]
        port: u16,
    },

    /// Discover the Android device's WiFi IP address via ADB
    DiscoverIp,

    /// Discover Android devices on WiFi via mDNS
    DiscoverWifi {
        /// Discovery timeout in seconds
        #[arg(short, long, default_value = "5")]
        timeout: u64,
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
    if let Some(path) = &cli.config {
        std::env::set_var("ETPHONEHOME_CONFIG", path);
    }
    let cfg_path = || Config::default_path();

    match cli.command {
        Commands::Usb {
            port,
            interface,
            retry,
        } => {
            warn_unprivileged(&platform);
            let plan = tcp_plan(
                SocketAddr::from(([127, 0, 0, 1], port)),
                capabilities::USB,
                None,
                false,
                Some(port),
                interface,
            );
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
            host,
            port,
            interface,
            token,
            tls,
            no_tls,
            retry,
        } => {
            // TLS unless --no-tls was the last word (`--tls` is the default).
            let tls = tls || !no_tls;
            warn_unprivileged(&platform);
            if token.is_none() {
                warn!(
                    "No --token given; the Android app rejects WiFi connections without its token"
                );
            }
            let addr = runner::resolve_addr(&host, port).await?;
            let plan = tcp_plan(addr, capabilities::WIFI, token, tls, None, interface);
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
        } => {
            warn_unprivileged(&platform);
            let config = Config::load_or_default()?;
            let token = token.or_else(|| {
                (!config.auth.bluetooth_token.is_empty())
                    .then(|| config.auth.bluetooth_token.clone())
            });
            let plan = SessionPlan {
                target: Target::Bluetooth { address, token },
                tun_name: interface,
                filter: FilterConfig::default(),
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
        Commands::SetupAdb { port } => runner::setup_adb_forwarding(port),
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
        Commands::DiscoverWifi { timeout } => {
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
            runner::connect_profile(
                &config,
                profile,
                &tun,
                policy(
                    &retry,
                    config.default.auto_reconnect,
                    config.default.max_retries,
                ),
                runner::ctrl_c_shutdown(),
                None,
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
        } => {
            let path = cfg_path()?;
            runner::save_profile_at(&path, &name, &conn_type, host, port, address, description)?;
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
