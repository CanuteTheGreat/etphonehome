use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use etphonehome_common::{client::Client, protocol::capabilities, tun_interface::TunInterface, Config, ConnectionType};
use log::{error, info, warn};
use std::net::SocketAddr;
use std::process::Command;
use std::time::Duration;

#[derive(Parser)]
#[command(name = "etphonehome")]
#[command(about = "ET Phone Home - USB/WiFi/Bluetooth tethering client for Linux", long_about = None)]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Connect via USB (requires ADB)
    Usb {
        /// ADB port (default: 8888)
        #[arg(short, long, default_value = "8888")]
        port: u16,

        /// TUN interface name
        #[arg(short, long, default_value = "etph0")]
        interface: String,

        /// Enable auto-reconnect on disconnect
        #[arg(short, long, default_value = "true")]
        reconnect: bool,

        /// Maximum reconnection attempts (0 = infinite)
        #[arg(long, default_value = "0")]
        max_retries: u32,
    },

    /// Connect via WiFi
    Wifi {
        /// Android device IP address
        #[arg(short, long)]
        host: String,

        /// Port number
        #[arg(short, long, default_value = "8888")]
        port: u16,

        /// TUN interface name
        #[arg(short, long, default_value = "etph0")]
        interface: String,

        /// Authentication token (optional, for enhanced security)
        #[arg(short = 't', long)]
        token: Option<String>,

        /// Enable auto-reconnect on disconnect
        #[arg(short, long, default_value = "true")]
        reconnect: bool,

        /// Maximum reconnection attempts (0 = infinite)
        #[arg(long, default_value = "0")]
        max_retries: u32,
    },

    /// Set up ADB reverse port forwarding
    SetupAdb {
        /// ADB port (default: 8888)
        #[arg(short, long, default_value = "8888")]
        port: u16,
    },

    /// Discover Android device's WiFi IP address via ADB
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

        /// Override auto-reconnect setting
        #[arg(short, long)]
        reconnect: Option<bool>,

        /// Override maximum reconnection attempts
        #[arg(long)]
        max_retries: Option<u32>,
    },

    /// List all saved profiles
    ListProfiles,

    /// Save current connection as a profile
    SaveProfile {
        /// Profile name
        name: String,

        /// Connection type (usb, wifi, bluetooth)
        #[arg(short = 't', long)]
        conn_type: String,

        /// Host (for WiFi)
        #[arg(long)]
        host: Option<String>,

        /// Port
        #[arg(short, long)]
        port: Option<u16>,

        /// Bluetooth address
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

    /// Connect via Bluetooth
    Bluetooth {
        /// Android device Bluetooth address (MAC)
        #[arg(short = 'a', long)]
        address: String,

        /// TUN interface name
        #[arg(short, long, default_value = "etph0")]
        interface: String,
    },
}

#[tokio::main]
async fn main() -> Result<()> {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    let cli = Cli::parse();

    match cli.command {
        Commands::Usb { port, interface, reconnect, max_retries } => {
            setup_adb_forwarding(port)?;
            let addr = format!("127.0.0.1:{}", port).parse()?;

            if reconnect {
                connect_with_retry(addr, &interface, capabilities::USB, max_retries, None).await
            } else {
                connect_and_run(addr, &interface, capabilities::USB, None).await
            }
        }
        Commands::Wifi {
            host,
            port,
            interface,
            token,
            reconnect,
            max_retries,
        } => {
            let addr: SocketAddr = format!("{}:{}", host, port)
                .parse()
                .context("Invalid address")?;

            if reconnect {
                connect_with_retry(addr, &interface, capabilities::WIFI, max_retries, token).await
            } else {
                connect_and_run(addr, &interface, capabilities::WIFI, token).await
            }
        }
        Commands::SetupAdb { port } => setup_adb_forwarding(port),
        Commands::DiscoverIp => discover_android_ip(),
        Commands::DiscoverWifi { timeout } => discover_wifi_devices(timeout).await,
        Commands::Connect { profile_name, interface, reconnect, max_retries } => {
            connect_with_profile(&profile_name, interface, reconnect, max_retries).await
        }
        Commands::ListProfiles => list_profiles(),
        Commands::SaveProfile { name, conn_type, host, port, address, description } => {
            save_profile(&name, &conn_type, host, port, address, description)
        }
        Commands::DeleteProfile { name } => delete_profile(&name),
        Commands::Bluetooth { address, interface } => {
            connect_bluetooth(&address, &interface).await
        }
    }
}

fn discover_android_ip() -> Result<()> {
    info!("Discovering Android device WiFi IP address...");

    // Check if ADB is available
    let adb_check = Command::new("adb")
        .arg("version")
        .output()
        .context("ADB not found. Please install Android Debug Bridge (adb)")?;

    if !adb_check.status.success() {
        anyhow::bail!("ADB is not working properly");
    }

    // Get WiFi IP address from Android device
    let output = Command::new("adb")
        .args(["shell", "ip", "addr", "show", "wlan0"])
        .output()
        .context("Failed to query Android WiFi interface")?;

    if !output.status.success() {
        anyhow::bail!("Failed to get WiFi IP from Android device");
    }

    let output_str = String::from_utf8_lossy(&output.stdout);

    // Parse IP address from output
    // Looking for line like: inet 192.168.1.100/24 brd 192.168.1.255 scope global wlan0
    for line in output_str.lines() {
        if line.trim().starts_with("inet ") && !line.contains("127.0.0.1") {
            if let Some(ip_part) = line.trim().split_whitespace().nth(1) {
                // Remove CIDR notation (e.g., /24)
                let ip = ip_part.split('/').next().unwrap_or(ip_part);
                info!("✓ Android WiFi IP: {}", ip);
                info!("To connect via WiFi, run:");
                info!("  sudo etphonehome wifi --host {} --port 8888", ip);
                return Ok(());
            }
        }
    }

    anyhow::bail!(
        "Could not find WiFi IP address. Make sure:\n\
         1. Android device is connected via USB\n\
         2. USB debugging is enabled\n\
         3. Device is connected to WiFi"
    )
}

fn setup_adb_forwarding(port: u16) -> Result<()> {
    info!("Setting up ADB reverse port forwarding...");

    // Check if ADB is available
    let adb_check = Command::new("adb")
        .arg("version")
        .output()
        .context("ADB not found. Please install Android Debug Bridge (adb)")?;

    if !adb_check.status.success() {
        anyhow::bail!("ADB is not working properly");
    }

    // Set up reverse port forwarding
    let output = Command::new("adb")
        .args(["reverse", &format!("tcp:{}", port), &format!("tcp:{}", port)])
        .output()
        .context("Failed to set up ADB reverse forwarding")?;

    if !output.status.success() {
        let error = String::from_utf8_lossy(&output.stderr);
        anyhow::bail!("ADB reverse forwarding failed: {}", error);
    }

    info!("ADB reverse forwarding set up successfully");
    info!("Port {} on Android will forward to port {} on this computer", port, port);

    Ok(())
}

async fn connect_with_retry(
    addr: SocketAddr,
    tun_name: &str,
    capabilities: u32,
    max_retries: u32,
    token: Option<String>,
) -> Result<()> {
    let mut attempt = 0;
    let infinite_retries = max_retries == 0;

    loop {
        attempt += 1;

        if !infinite_retries && attempt > max_retries {
            error!("Maximum reconnection attempts ({}) reached. Giving up.", max_retries);
            anyhow::bail!("Failed to establish stable connection after {} attempts", max_retries);
        }

        if attempt > 1 {
            // Exponential backoff: 2^(attempt-1) seconds, capped at 60 seconds
            let delay_secs = std::cmp::min(2u64.pow(attempt - 2), 60);
            info!("Reconnection attempt {} of {}...",
                  attempt,
                  if infinite_retries { "∞".to_string() } else { max_retries.to_string() });
            info!("Waiting {} seconds before retry...", delay_secs);
            tokio::time::sleep(Duration::from_secs(delay_secs)).await;
        }

        match connect_and_run(addr, tun_name, capabilities, token.clone()).await {
            Ok(_) => {
                // Connection ended normally
                info!("Connection closed normally.");

                if infinite_retries || attempt < max_retries {
                    info!("Auto-reconnect is enabled. Attempting to reconnect...");
                    continue;
                } else {
                    return Ok(());
                }
            }
            Err(e) => {
                error!("Connection failed: {:#}", e);

                if infinite_retries || attempt < max_retries {
                    warn!("Will attempt to reconnect...");
                    continue;
                } else {
                    return Err(e);
                }
            }
        }
    }
}

async fn connect_and_run(addr: SocketAddr, tun_name: &str, capabilities: u32, token: Option<String>) -> Result<()> {
    info!("Starting ET Phone Home client...");

    // Check if running as root
    if !is_root() {
        warn!("Not running as root. TUN interface creation may fail.");
        warn!("Please run with sudo: sudo etphonehome ...");
    }

    // Connect to Android device (synchronous)
    let mut client = Client::connect(addr, capabilities, token)
        .context(format!(
            "Failed to connect to Android device at {}\n\
             \n\
             Troubleshooting:\n\
             - Ensure Android app is running and tethering is started\n\
             - For USB mode: Verify ADB forwarding with 'adb reverse --list'\n\
             - For WiFi mode: Check firewall settings and WiFi connection\n\
             - Check Android logs: adb logcat -s ETPhoneHome:V",
            addr
        ))?;

    // Perform handshake
    let _handshake = client.handshake()
        .context(
            "Handshake with Android device failed\n\
             \n\
             Possible causes:\n\
             - Protocol version mismatch (update both Android and desktop apps)\n\
             - Connection interrupted during handshake\n\
             - Android app crashed or stopped\n\
             \n\
             Try restarting both the Android app and desktop client."
        )?;

    // Create TUN interface
    let tun = TunInterface::create(tun_name, "10.0.0.1", "255.255.255.0")
        .context(format!(
            "Failed to create TUN interface '{}'\n\
             \n\
             Troubleshooting:\n\
             - Ensure you're running with sudo: sudo etphonehome ...\n\
             - Load TUN module: sudo modprobe tun\n\
             - Check if TUN device exists: ls -l /dev/net/tun\n\
             - Verify permissions: sudo chmod 666 /dev/net/tun\n\
             \n\
             If the interface already exists, try a different name with --interface",
            tun_name
        ))?;

    // Set up routing
    setup_routing(tun_name).context(format!(
        "Failed to set up routing for interface '{}'\n\
         \n\
         This is usually not critical - the connection may still work.\n\
         You can manually add routes if needed:\n\
         sudo ip route add default via 10.0.0.2 dev {} metric 100",
        tun_name, tun_name
    ))?;

    info!("Tethering connection established!");
    info!("You can now use your Android device's internet connection.");
    info!("Starting packet forwarding (multi-threaded with async I/O)...");

    // Convert TCP stream to async
    let tcp_stream = client.into_stream();
    tcp_stream.set_nonblocking(true).context("Failed to set non-blocking")?;
    let async_stream = tokio::net::TcpStream::from_std(tcp_stream)
        .context("Failed to convert to async stream")?;

    // Create channels for packet forwarding
    let (tun_to_android_tx, mut tun_to_android_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(100);
    let (android_to_tun_tx, mut android_to_tun_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(100);

    // Split the TCP stream for concurrent read/write (consumes async_stream)
    let (mut tcp_read, mut tcp_write) = tokio::io::split(async_stream);

    // Share TUN interface between read and write tasks
    let tun = std::sync::Arc::new(std::sync::Mutex::new(tun));

    // Task 1: Read from TUN and send to channel
    let tun_read_handle = {
        let tun = std::sync::Arc::clone(&tun);
        tokio::task::spawn(async move {
            loop {
                let packet = {
                    let mut tun_guard = tun.lock().unwrap();
                    tun_guard.read_packet()
                };

                match packet {
                    Ok(pkt) => {
                        if tun_to_android_tx.send(pkt).await.is_err() {
                            break;
                        }
                    }
                    Err(_) => {
                        // No packet, yield to other tasks
                        tokio::time::sleep(tokio::time::Duration::from_micros(100)).await;
                    }
                }
            }
            info!("TUN read task stopped");
        })
    };

    // Task 2: Read from channel and send to Android
    let android_write_handle = tokio::task::spawn(async move {
        use tokio::io::AsyncWriteExt;
        while let Some(packet) = tun_to_android_rx.recv().await {
            // Wrap packet in protocol message
            let msg = etphonehome_common::protocol::Message::IpPacket(
                etphonehome_common::protocol::IpPacket::new(packet)
            );

            // Serialize to bytes
            let mut buffer = Vec::new();
            if let Err(e) = msg.write_to(&mut buffer) {
                error!("Failed to serialize message: {:?}", e);
                break;
            }

            if let Err(e) = tcp_write.write_all(&buffer).await {
                error!("Failed to write to Android: {}", e);
                break;
            }
        }
        info!("Android write task stopped");
    });

    // Task 3: Read from Android and send to channel
    let android_read_handle = tokio::task::spawn(async move {
        use tokio::io::AsyncReadExt;
        use etphonehome_common::protocol::{MAGIC, Message};

        loop {
            // Read header (5 bytes)
            let mut header = [0u8; 5];
            if let Err(e) = tcp_read.read_exact(&mut header).await {
                error!("Failed to read header: {}", e);
                break;
            }

            // Parse header
            let magic = ((header[0] as u16) << 8) | (header[1] as u16);
            if magic != MAGIC {
                error!("Invalid magic bytes");
                break;
            }

            let length = ((header[3] as u16) << 8) | (header[4] as u16);

            // Read payload
            let mut payload = vec![0u8; length as usize];
            if let Err(e) = tcp_read.read_exact(&mut payload).await {
                error!("Failed to read payload: {}", e);
                break;
            }

            // Create a cursor and read message
            let mut full_msg = header.to_vec();
            full_msg.extend_from_slice(&payload);
            let mut cursor = std::io::Cursor::new(full_msg);

            match Message::read_from(&mut cursor) {
                Ok(Message::IpPacket(pkt)) => {
                    if android_to_tun_tx.send(pkt.data).await.is_err() {
                        break;
                    }
                }
                Ok(Message::StatsUpdate(stats)) => {
                    log::debug!(
                        "Stats: sent={} recv={} uptime={}s",
                        stats.bytes_sent, stats.bytes_received, stats.uptime_seconds
                    );
                }
                Ok(Message::Disconnect(disc)) => {
                    info!("Android disconnected: {}", disc.reason);
                    break;
                }
                Ok(_) => {
                    // Other message types (keepalive, etc.)
                }
                Err(e) => {
                    error!("Protocol error: {:?}", e);
                    break;
                }
            }
        }
        info!("Android read task stopped");
    });

    // Task 4: Read from channel and write to TUN
    let tun_write_handle = {
        let tun = std::sync::Arc::clone(&tun);
        tokio::task::spawn(async move {
            while let Some(packet) = android_to_tun_rx.recv().await {
                let result = {
                    let mut tun_guard = tun.lock().unwrap();
                    tun_guard.write_packet(&packet)
                };

                if let Err(e) = result {
                    error!("Failed to write to TUN: {}", e);
                    break;
                }
            }
            info!("TUN write task stopped");
        })
    };

    // Wait for any task to complete (usually means error or disconnect)
    tokio::select! {
        _ = tun_read_handle => {},
        _ = android_write_handle => {},
        _ = android_read_handle => {},
        _ = tun_write_handle => {},
    }

    info!("Connection closed");

    Ok(())
}

fn setup_routing(tun_name: &str) -> Result<()> {
    info!("Setting up routing for {}...", tun_name);

    // Enable IP forwarding
    let _ = Command::new("sysctl")
        .args(["-w", "net.ipv4.ip_forward=1"])
        .output();

    // Add default route through TUN interface
    let output = Command::new("ip")
        .args(["route", "add", "default", "via", "10.0.0.2", "dev", tun_name, "metric", "100"])
        .output()
        .context("Failed to add route")?;

    if !output.status.success() {
        warn!("Failed to add default route (may already exist)");
    }

    info!("Routing configured");

    Ok(())
}

async fn connect_with_profile(
    profile_name: &str,
    interface_override: Option<String>,
    reconnect_override: Option<bool>,
    max_retries_override: Option<u32>,
) -> Result<()> {
    info!("Loading profile '{}'...", profile_name);

    let config = Config::load_or_default()?;
    let profile = config.get_profile(profile_name)
        .context(format!("Profile '{}' not found", profile_name))?;

    let interface = interface_override.as_deref()
        .unwrap_or(&config.default.interface);
    let reconnect = reconnect_override.unwrap_or(config.default.auto_reconnect);
    let max_retries = max_retries_override.unwrap_or(config.default.max_retries);

    match profile.connection_type {
        ConnectionType::Usb => {
            let port = profile.port.unwrap_or(8888);
            setup_adb_forwarding(port)?;
            let addr = format!("127.0.0.1:{}", port).parse()?;

            // USB doesn't use tokens
            if reconnect {
                connect_with_retry(addr, interface, capabilities::USB, max_retries, None).await
            } else {
                connect_and_run(addr, interface, capabilities::USB, None).await
            }
        }
        ConnectionType::Wifi => {
            let host = profile.host.as_ref()
                .context("WiFi profile missing host")?;
            let port = profile.port.unwrap_or(8888);
            let addr: SocketAddr = format!("{}:{}", host, port).parse()?;

            // Use wifi_token from config if available
            let token = if !config.auth.wifi_token.is_empty() {
                Some(config.auth.wifi_token.clone())
            } else {
                None
            };

            if reconnect {
                connect_with_retry(addr, interface, capabilities::WIFI, max_retries, token).await
            } else {
                connect_and_run(addr, interface, capabilities::WIFI, token).await
            }
        }
        ConnectionType::Bluetooth => {
            let address = profile.address.as_ref()
                .context("Bluetooth profile missing address")?;
            connect_bluetooth(address, interface).await
        }
    }
}

fn list_profiles() -> Result<()> {
    let config = Config::load_or_default()?;

    if config.profiles.is_empty() {
        info!("No saved profiles found.");
        info!("Create a profile with: etphonehome save-profile <name> --conn-type <type> ...");
        return Ok(());
    }

    info!("Saved connection profiles:");
    info!("");

    for profile in &config.profiles {
        let desc = profile.description.as_deref().unwrap_or("No description");
        info!("  {} ({})", profile.name, profile.connection_type_string());
        info!("    {}", desc);
        match profile.connection_type {
            ConnectionType::Usb => {
                info!("    Port: {}", profile.port.unwrap_or(8888));
            }
            ConnectionType::Wifi => {
                if let Some(ref host) = profile.host {
                    info!("    Host: {}:{}", host, profile.port.unwrap_or(8888));
                }
            }
            ConnectionType::Bluetooth => {
                if let Some(ref addr) = profile.address {
                    info!("    Address: {}", addr);
                }
            }
        }
        info!("");
    }

    info!("Connect with: sudo etphonehome connect <profile-name>");

    Ok(())
}

fn save_profile(
    name: &str,
    conn_type: &str,
    host: Option<String>,
    port: Option<u16>,
    address: Option<String>,
    description: Option<String>,
) -> Result<()> {
    let connection_type = match conn_type.to_lowercase().as_str() {
        "usb" => ConnectionType::Usb,
        "wifi" => ConnectionType::Wifi,
        "bluetooth" | "bt" => ConnectionType::Bluetooth,
        _ => anyhow::bail!("Invalid connection type '{}'. Must be: usb, wifi, or bluetooth", conn_type),
    };

    let profile = etphonehome_common::Profile {
        name: name.to_string(),
        connection_type,
        description,
        port,
        host,
        address,
        filter: etphonehome_common::FilterConfig::default(),
    };

    let mut config = Config::load_or_default()?;
    config.upsert_profile(profile);

    // Save to default location
    if let Ok(path) = Config::default_path() {
        // Create parent directory if it doesn't exist
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        config.save(&path)?;
        info!("Profile '{}' saved to {}", name, path.display());
    } else {
        config.save("config.toml")?;
        info!("Profile '{}' saved to config.toml", name);
    }

    Ok(())
}

fn delete_profile(name: &str) -> Result<()> {
    let mut config = Config::load_or_default()?;

    if config.remove_profile(name) {
        if let Ok(path) = Config::default_path() {
            config.save(&path)?;
            info!("Profile '{}' deleted from {}", name, path.display());
        } else {
            config.save("config.toml")?;
            info!("Profile '{}' deleted from config.toml", name);
        }
    } else {
        warn!("Profile '{}' not found", name);
    }

    Ok(())
}

fn is_root() -> bool {
    unsafe { libc::geteuid() == 0 }
}

// Helper trait for ConnectionType
trait ConnectionTypeExt {
    fn connection_type_string(&self) -> String;
}

impl ConnectionTypeExt for etphonehome_common::Profile {
    fn connection_type_string(&self) -> String {
        match self.connection_type {
            ConnectionType::Usb => "USB".to_string(),
            ConnectionType::Wifi => "WiFi".to_string(),
            ConnectionType::Bluetooth => "Bluetooth".to_string(),
        }
    }
}

async fn connect_bluetooth(address: &str, tun_name: &str) -> Result<()> {
    info!("Connecting via Bluetooth to {}...", address);

    // Check if running as root
    if !is_root() {
        warn!("Not running as root. TUN interface creation may fail.");
        warn!("Please run with sudo: sudo etphonehome ...");
    }

    // Parse Bluetooth address
    let bt_addr: bluer::Address = address.parse()
        .context("Invalid Bluetooth address format (expected XX:XX:XX:XX:XX:XX)")?;

    info!("Initializing Bluetooth adapter...");
    let session = bluer::Session::new().await
        .context("Failed to create BlueZ session. Is bluetooth service running?")?;

    let adapter_names = session.adapter_names().await
        .context("Failed to get Bluetooth adapters")?;

    let adapter_name = adapter_names.first()
        .context("No Bluetooth adapter found")?;

    info!("Using Bluetooth adapter: {}", adapter_name);

    info!("Connecting to device {}...", bt_addr);

    // Connect to RFCOMM socket on channel 1 (SPP typically uses channel 1)
    // SPP UUID: 00001101-0000-1000-8000-00805F9B34FB
    let socket_addr = bluer::rfcomm::SocketAddr::new(bt_addr, 1);
    let stream = bluer::rfcomm::Stream::connect(socket_addr).await
        .context(format!(
            "Failed to connect to Bluetooth device\n\
             \n\
             Troubleshooting:\n\
             - Ensure device is paired: bluetoothctl pair {}\n\
             - Ensure device is trusted: bluetoothctl trust {}\n\
             - Ensure Android app is running in Bluetooth mode\n\
             - Check Bluetooth is enabled: bluetoothctl show\n\
             - The SPP service should be on RFCOMM channel 1",
            address, address
        ))?;

    info!("Bluetooth RFCOMM connection established!");
    info!("Note: Bluetooth connection established, but full integration pending");
    info!("The RFCOMM stream is connected, but needs adapter work for Client::connect");

    anyhow::bail!(
        "Bluetooth RFCOMM connection successful, but Client integration incomplete.\n\
         \n\
         The connection to {} was established successfully.\n\
         Full packet forwarding integration requires refactoring Client to support\n\
         generic stream types beyond TCP sockets.\n\
         \n\
         This is partially implemented and will be completed in the next update.",
        address
    );
}

async fn discover_wifi_devices(timeout: u64) -> Result<()> {
    info!("Scanning for ET Phone Home devices on WiFi ({}s timeout)...", timeout);

    let mdns = mdns_sd::ServiceDaemon::new()
        .context("Failed to create mDNS service daemon")?;

    let service_type = "_etphonehome._tcp.local.";

    // Browse for services
    let receiver = mdns.browse(service_type)
        .context("Failed to browse for services")?;

    let mut found_devices = Vec::new();
    let start_time = std::time::Instant::now();

    // Collect results for the specified timeout
    while start_time.elapsed().as_secs() < timeout {
        // Check for events with a small timeout to allow periodic checking
        if let Ok(event) = tokio::time::timeout(
            std::time::Duration::from_millis(100),
            tokio::task::spawn_blocking({
                let receiver = receiver.clone();
                move || receiver.recv_timeout(std::time::Duration::from_millis(100))
            })
        ).await {
            if let Ok(Ok(event)) = event {
                match event {
                    mdns_sd::ServiceEvent::ServiceResolved(info) => {
                        info!("Found device: {}", info.get_fullname());
                        found_devices.push(info);
                    }
                    _ => {}
                }
            }
        }
    }

    // Shutdown the mDNS daemon
    mdns.shutdown()
        .context("Failed to shutdown mDNS daemon")?;

    if found_devices.is_empty() {
        info!("");
        info!("No ET Phone Home devices found on the network.");
        info!("");
        info!("Make sure:");
        info!("  1. Android device is on the same WiFi network");
        info!("  2. ET Phone Home app is running in WiFi mode");
        info!("  3. Firewall allows mDNS traffic (UDP port 5353)");
        return Ok(());
    }

    info!("");
    info!("Found {} device(s):", found_devices.len());
    info!("");

    for device in &found_devices {
        info!("  Device: {}", device.get_fullname());

        // Get all addresses
        let addresses = device.get_addresses();
        if !addresses.is_empty() {
            for addr in addresses {
                info!("    Address: {}:{}", addr, device.get_port());
            }
        }

        let hostname = device.get_hostname();
        if !hostname.is_empty() {
            info!("    Hostname: {}", hostname);
        }

        info!("");
    }

    // Provide connection instructions
    if let Some(first_device) = found_devices.first() {
        if let Some(addr) = first_device.get_addresses().iter().next() {
            let port = first_device.get_port();
            info!("To connect to the first device, run:");
            info!("  sudo etphonehome wifi --host {} --port {}", addr, port);
            info!("");
            info!("Or save as a profile:");
            info!("  etphonehome save-profile my-phone -t wifi --host {} --port {}", addr, port);
            info!("  sudo etphonehome connect my-phone");
        }
    }

    Ok(())
}
