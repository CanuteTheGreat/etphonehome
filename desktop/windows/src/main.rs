use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use etphonehome_common::{
    client::Client, protocol::capabilities, tun_interface::TunInterface, Config, ConnectionType,
};
use log::{error, info, warn};
use std::net::SocketAddr;
use std::process::Command;
use std::time::Duration;

#[derive(Parser)]
#[command(name = "etphonehome")]
#[command(about = "ET Phone Home - USB/WiFi/Bluetooth tethering client for Windows", long_about = None)]
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

    /// Connect via Bluetooth
    Bluetooth {
        /// Android device Bluetooth address (MAC)
        #[arg(short = 'a', long)]
        address: String,

        /// TUN interface name
        #[arg(short, long, default_value = "etph0")]
        interface: String,
    },

    /// Connect using a saved profile
    Connect {
        profile_name: String,

        #[arg(short, long)]
        interface: Option<String>,

        #[arg(short, long)]
        reconnect: Option<bool>,

        #[arg(long)]
        max_retries: Option<u32>,
    },

    /// List all saved profiles
    ListProfiles,

    /// Save current connection as a profile
    SaveProfile {
        name: String,

        #[arg(short = 't', long)]
        conn_type: String,

        #[arg(long)]
        host: Option<String>,

        #[arg(short, long)]
        port: Option<u16>,

        #[arg(short = 'a', long)]
        address: Option<String>,

        #[arg(short, long)]
        description: Option<String>,
    },

    /// Delete a saved profile
    DeleteProfile { name: String },
}

#[tokio::main]
async fn main() -> Result<()> {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    check_admin_privileges();

    let cli = Cli::parse();

    match cli.command {
        Commands::Usb {
            port,
            interface,
            reconnect,
            max_retries,
        } => {
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
        Commands::Bluetooth { address, interface } => connect_bluetooth(&address, &interface).await,
        Commands::Connect {
            profile_name,
            interface,
            reconnect,
            max_retries,
        } => connect_with_profile(&profile_name, interface, reconnect, max_retries).await,
        Commands::ListProfiles => list_profiles(),
        Commands::SaveProfile {
            name,
            conn_type,
            host,
            port,
            address,
            description,
        } => save_profile(&name, &conn_type, host, port, address, description),
        Commands::DeleteProfile { name } => delete_profile(&name),
    }
}

fn check_admin_privileges() {
    #[cfg(windows)]
    {
        if !is_elevated() {
            warn!("Not running as Administrator. TUN interface creation may fail.");
            warn!("Please run as Administrator.");
        }
    }
}

#[cfg(windows)]
fn is_elevated() -> bool {
    use std::mem;
    use winapi::um::handleapi::CloseHandle;
    use winapi::um::processthreadsapi::{GetCurrentProcess, OpenProcessToken};
    use winapi::um::securitybaseapi::GetTokenInformation;
    use winapi::um::winnt::{TokenElevation, TOKEN_ELEVATION, TOKEN_QUERY};

    unsafe {
        let mut token = mem::zeroed();
        if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) == 0 {
            return false;
        }

        let mut elevation: TOKEN_ELEVATION = mem::zeroed();
        let mut size = mem::size_of::<TOKEN_ELEVATION>() as u32;

        let info_ok = GetTokenInformation(
            token,
            TokenElevation,
            &mut elevation as *mut _ as *mut _,
            size,
            &mut size,
        ) != 0;

        // OpenProcessToken succeeded above, so `token` is a real kernel handle
        // that must be closed regardless of which path we take out of here —
        // it was previously being leaked on every call to this function.
        CloseHandle(token);

        if !info_ok {
            return false;
        }

        elevation.TokenIsElevated != 0
    }
}

#[cfg(not(windows))]
fn _is_elevated() -> bool {
    false
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
            if let Some(ip_part) = line.split_whitespace().nth(1) {
                // Remove CIDR notation (e.g., /24)
                let ip = ip_part.split('/').next().unwrap_or(ip_part);
                info!("✓ Android WiFi IP: {}", ip);
                info!("To connect via WiFi, run:");
                info!("  etphonehome.exe wifi --host {} --port 8888", ip);
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
        .args([
            "reverse",
            &format!("tcp:{}", port),
            &format!("tcp:{}", port),
        ])
        .output()
        .context("Failed to set up ADB reverse forwarding")?;

    if !output.status.success() {
        let error = String::from_utf8_lossy(&output.stderr);
        anyhow::bail!("ADB reverse forwarding failed: {}", error);
    }

    info!("ADB reverse forwarding set up successfully");
    info!(
        "Port {} on Android will forward to port {} on this computer",
        port, port
    );

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
            error!(
                "Maximum reconnection attempts ({}) reached. Giving up.",
                max_retries
            );
            anyhow::bail!(
                "Failed to establish stable connection after {} attempts",
                max_retries
            );
        }

        if attempt > 1 {
            // Exponential backoff: 2^(attempt-1) seconds, capped at 60 seconds
            let delay_secs = std::cmp::min(2u64.pow(attempt - 2), 60);
            info!(
                "Reconnection attempt {} of {}...",
                attempt,
                if infinite_retries {
                    "∞".to_string()
                } else {
                    max_retries.to_string()
                }
            );
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

async fn connect_and_run(
    addr: SocketAddr,
    tun_name: &str,
    capabilities: u32,
    token: Option<String>,
) -> Result<()> {
    info!("Starting ET Phone Home client...");

    // Connect to Android device (synchronous)
    let mut client = Client::connect(addr, capabilities, token).context(format!(
        "Failed to connect to Android device at {}\n\
             \n\
             Troubleshooting:\n\
             - Ensure Android app is running and tethering is started\n\
             - For USB mode: Verify ADB forwarding with 'adb reverse --list'\n\
             - For WiFi mode: Check Windows Firewall settings\n\
             - Verify Android and desktop are on the same WiFi network\n\
             - Check Android logs: adb logcat -s ETPhoneHome:V",
        addr
    ))?;

    // Perform handshake
    let _handshake = client.handshake().context(
        "Handshake with Android device failed\n\
             \n\
             Possible causes:\n\
             - Protocol version mismatch (update both Android and desktop apps)\n\
             - Connection interrupted during handshake\n\
             - Android app crashed or stopped\n\
             \n\
             Try restarting both the Android app and desktop client.",
    )?;

    // Create TUN interface
    let tun = TunInterface::create(tun_name, "10.0.0.1", "255.255.255.0").context(format!(
        "Failed to create TUN interface '{}'\n\
             \n\
             Troubleshooting:\n\
             - Ensure you're running as Administrator\n\
             - Install TAP-Windows adapter (comes with OpenVPN)\n\
             - Download from: https://openvpn.net/community-downloads/\n\
             - Check installed adapters: Control Panel → Network Connections\n\
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
         route add 0.0.0.0 mask 0.0.0.0 10.0.0.2 metric 10",
        tun_name
    ))?;

    info!("Tethering connection established!");
    info!("You can now use your Android device's internet connection.");
    info!("Starting packet forwarding (multi-threaded with async I/O)...");

    // Convert TCP stream to async
    let tcp_stream = client.into_stream();
    tcp_stream
        .set_nonblocking(true)
        .context("Failed to set non-blocking")?;
    let async_stream =
        tokio::net::TcpStream::from_std(tcp_stream).context("Failed to convert to async stream")?;

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
                let tun = std::sync::Arc::clone(&tun);
                // TUN reads are blocking syscalls; run them on a dedicated
                // blocking thread so a slow/idle device can never stall this
                // async runtime's worker threads (which also drive the TCP
                // read/write tasks for the Android side).
                let packet = tokio::task::spawn_blocking(move || {
                    let mut tun_guard = tun.lock().unwrap();
                    tun_guard.read_packet()
                })
                .await;

                match packet {
                    Ok(Ok(pkt)) => {
                        if tun_to_android_tx.send(pkt).await.is_err() {
                            break;
                        }
                    }
                    Ok(Err(e)) => {
                        // TUN reads are blocking with no timeout, so an Err here
                        // means a real device failure (fd closed/removed, EBADF,
                        // ENXIO), not "nothing to read yet" -- there is no such
                        // state for a blocking read(2). Treating it as transient
                        // and looping forever previously spun a blocking-pool
                        // thread indefinitely (~10k iterations/sec) without ever
                        // surfacing the failure or tearing down the connection.
                        error!("TUN device read failed, stopping: {:?}", e);
                        break;
                    }
                    Err(e) => {
                        error!("TUN read blocking task panicked: {:?}", e);
                        break;
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
                etphonehome_common::protocol::IpPacket::new(packet),
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
        use etphonehome_common::protocol::{Message, MAGIC};
        use tokio::io::AsyncReadExt;

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
                        stats.bytes_sent,
                        stats.bytes_received,
                        stats.uptime_seconds
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
                let tun = std::sync::Arc::clone(&tun);
                // Same rationale as the read side: run the blocking device
                // write off the async runtime's worker threads.
                let result = tokio::task::spawn_blocking(move || {
                    let mut tun_guard = tun.lock().unwrap();
                    tun_guard.write_packet(&packet)
                })
                .await;

                match result {
                    Ok(Err(e)) => {
                        error!("Failed to write to TUN: {}", e);
                        break;
                    }
                    Err(e) => {
                        error!("TUN write blocking task panicked: {:?}", e);
                        break;
                    }
                    Ok(Ok(())) => {}
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

    #[cfg(windows)]
    {
        // Add default route through TUN interface with lower metric
        let output = Command::new("route")
            .args([
                "add", "0.0.0.0", "mask", "0.0.0.0", "10.0.0.2", "metric", "10",
            ])
            .output()
            .context("Failed to add route")?;

        if !output.status.success() {
            warn!("Failed to add default route (may already exist)");
        }
    }

    #[cfg(not(windows))]
    {
        warn!("Routing setup not implemented for this platform");
    }

    info!("Routing configured");

    Ok(())
}

async fn connect_bluetooth(address: &str, _tun_name: &str) -> Result<()> {
    info!("Connecting via Bluetooth to {}...", address);

    // Parse Bluetooth address (format: XX:XX:XX:XX:XX:XX)
    let addr_parts: Vec<&str> = address.split(':').collect();
    if addr_parts.len() != 6 {
        anyhow::bail!("Invalid Bluetooth address format. Expected XX:XX:XX:XX:XX:XX");
    }

    let mut bt_addr: u64 = 0;
    for (i, part) in addr_parts.iter().enumerate() {
        let byte = u8::from_str_radix(part, 16)
            .context(format!("Invalid hex byte in Bluetooth address: {}", part))?;
        bt_addr |= (byte as u64) << (40 - i * 8);
    }

    info!("Parsed Bluetooth address: {:#014x}", bt_addr);
    info!("");
    info!("Windows Bluetooth RFCOMM support is limited in Rust.");
    info!("For Bluetooth connectivity on Windows, you have two options:");
    info!("");
    info!("Option 1: Manual COM port setup (Recommended)");
    info!("  1. Pair with Android device:");
    info!("     - Settings > Devices > Bluetooth & other devices");
    info!("     - Add Bluetooth device and pair with {}", address);
    info!("  2. Find the COM port in Device Manager:");
    info!("     - Device Manager > Bluetooth > Standard Serial over Bluetooth");
    info!("     - Note the COM port (e.g., COM4)");
    info!("  3. Use a serial terminal or custom app to connect");
    info!("");
    info!("Option 2: Use native Windows Bluetooth APIs (Requires C++/C# wrapper)");
    info!("  - Windows.Devices.Bluetooth namespace");
    info!("  - RfcommDeviceService for SPP connections");
    info!("  - This would require a native Windows component");
    info!("");

    anyhow::bail!(
        "Bluetooth RFCOMM on Windows requires native API integration.\n\
         \n\
         The Rust winapi crate has limited Bluetooth socket support.\n\
         For production use, consider:\n\
         \n\
         1. Using WiFi mode (fully supported with mDNS discovery)\n\
         2. Creating a C++/C# wrapper for Windows.Devices.Bluetooth\n\
         3. Using the manual COM port method described above\n\
         \n\
         Linux has full Bluetooth support via BlueZ.\n\
         \n\
         Target device: {}\n\
         Parsed address: {:#014x}",
        address,
        bt_addr
    );
}

// Profile management functions

async fn connect_with_profile(
    profile_name: &str,
    interface_override: Option<String>,
    reconnect_override: Option<bool>,
    max_retries_override: Option<u32>,
) -> Result<()> {
    info!("Loading profile '{}'...", profile_name);

    let config = Config::load_or_default()?;
    let profile = config
        .get_profile(profile_name)
        .context(format!("Profile '{}' not found", profile_name))?;

    info!("Using profile: {}", profile.name);
    if let Some(desc) = &profile.description {
        info!("Description: {}", desc);
    }

    // Get settings with overrides
    let interface = interface_override
        .as_deref()
        .unwrap_or(&config.default.interface);
    let reconnect = reconnect_override.unwrap_or(config.default.auto_reconnect);
    let max_retries = max_retries_override.unwrap_or(config.default.max_retries);

    match profile.connection_type {
        ConnectionType::Usb => {
            let port = profile.port.unwrap_or(8888);
            info!("Connecting via USB on port {}...", port);

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
            let host = profile
                .host
                .as_ref()
                .context("WiFi profile missing 'host' field")?;
            let port = profile.port.unwrap_or(8888);

            info!("Connecting via WiFi to {}:{}...", host, port);
            let addr: SocketAddr = format!("{}:{}", host, port)
                .parse()
                .context("Invalid address")?;

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
            let address = profile
                .address
                .as_ref()
                .context("Bluetooth profile missing 'address' field")?;

            info!("Connecting via Bluetooth to {}...", address);
            connect_bluetooth(address, interface).await
        }
    }
}

fn list_profiles() -> Result<()> {
    let config = Config::load_or_default()?;

    if config.profiles.is_empty() {
        info!("No saved profiles found.");
        info!("Use 'save-profile' command to create a new profile.");
        return Ok(());
    }

    info!("Saved profiles:");
    info!("");

    for profile in &config.profiles {
        info!("  Name: {}", profile.name);
        info!("  Type: {}", profile.connection_type_string());

        if let Some(desc) = &profile.description {
            info!("  Description: {}", desc);
        }

        match profile.connection_type {
            ConnectionType::Usb => {
                if let Some(port) = profile.port {
                    info!("  Port: {}", port);
                }
            }
            ConnectionType::Wifi => {
                if let Some(host) = &profile.host {
                    info!("  Host: {}", host);
                }
                if let Some(port) = profile.port {
                    info!("  Port: {}", port);
                }
            }
            ConnectionType::Bluetooth => {
                if let Some(address) = &profile.address {
                    info!("  Address: {}", address);
                }
            }
        }

        info!("");
    }

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
        _ => {
            anyhow::bail!(
                "Invalid connection type: '{}'\n\
                 Valid types: usb, wifi, bluetooth",
                conn_type
            );
        }
    };

    // Validate required fields
    match connection_type {
        ConnectionType::Wifi => {
            if host.is_none() {
                anyhow::bail!("WiFi profiles require --host parameter");
            }
        }
        ConnectionType::Bluetooth => {
            if address.is_none() {
                anyhow::bail!("Bluetooth profiles require --address parameter");
            }
        }
        ConnectionType::Usb => {
            // USB only needs optional port
        }
    }

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
    let is_update = config.get_profile(name).is_some();

    config.upsert_profile(profile);

    let config_path = Config::default_path()?;

    // Create parent directory if it doesn't exist
    if let Some(parent) = config_path.parent() {
        std::fs::create_dir_all(parent).context("Failed to create config directory")?;
    }

    config.save(&config_path)?;

    if is_update {
        info!("Updated profile '{}' in {}", name, config_path.display());
    } else {
        info!("Saved profile '{}' to {}", name, config_path.display());
    }

    Ok(())
}

fn delete_profile(name: &str) -> Result<()> {
    let mut config = Config::load_or_default()?;

    if config.remove_profile(name) {
        let config_path = Config::default_path()?;
        config.save(&config_path)?;
        info!("Deleted profile '{}' from {}", name, config_path.display());
        Ok(())
    } else {
        anyhow::bail!("Profile '{}' not found", name);
    }
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

async fn discover_wifi_devices(timeout: u64) -> Result<()> {
    info!(
        "Scanning for ET Phone Home devices on WiFi ({}s timeout)...",
        timeout
    );

    let mdns = mdns_sd::ServiceDaemon::new().context("Failed to create mDNS service daemon")?;

    let service_type = "_etphonehome._tcp.local.";

    // Browse for services
    let receiver = mdns
        .browse(service_type)
        .context("Failed to browse for services")?;

    let mut found_devices = Vec::new();
    let start_time = std::time::Instant::now();

    // Collect results for the specified timeout
    while start_time.elapsed().as_secs() < timeout {
        // Check for events with a small timeout to allow periodic checking
        if let Ok(Ok(Ok(mdns_sd::ServiceEvent::ServiceResolved(info)))) = tokio::time::timeout(
            std::time::Duration::from_millis(100),
            tokio::task::spawn_blocking({
                let receiver = receiver.clone();
                move || receiver.recv_timeout(std::time::Duration::from_millis(100))
            }),
        )
        .await
        {
            info!("Found device: {}", info.get_fullname());
            found_devices.push(info);
        }
    }

    // Shutdown the mDNS daemon
    mdns.shutdown().context("Failed to shutdown mDNS daemon")?;

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
            info!("  etphonehome wifi --host {} --port {}", addr, port);
            info!("");
            info!("Or save as a profile:");
            info!(
                "  etphonehome save-profile my-phone -t wifi --host {} --port {}",
                addr, port
            );
            info!("  etphonehome connect my-phone");
        }
    }

    Ok(())
}
