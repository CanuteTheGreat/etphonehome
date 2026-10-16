# ET Phone Home

A modern, open-source tethering solution for Android with companion apps for Windows and Linux - an alternative to PDANet and EasyTether.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20Linux%20%7C%20Windows-lightgrey.svg)](https://git.canutethegreat.com/CanuteTheGreat/etphonehome)

## 🚀 Quick Start

**Linux:**
```bash
sudo ./scripts/quick-start-linux.sh
```

**Windows (PowerShell as Administrator):**
```powershell
.\scripts\quick-start-windows.ps1
```

See the [Setup Guide](docs/SETUP.md) for detailed instructions.

## ✨ Features

### Current Features (v0.5.0)

- ✅ **USB Tethering** - Fast and reliable connection via USB cable
- ✅ **WiFi Tethering** - Wireless connection over local network
- ✅ **Bluetooth Tethering** - RFCOMM on Linux (BlueZ); not yet available in the Windows client
- ✅ **No Root Required** - Uses Android VPN API
- ✅ **Cross-Platform** - Windows and Linux desktop clients
- ✅ **Multi-threaded I/O** - Async packet forwarding for better performance
- ✅ **Open Protocol** - Well-documented binary protocol
- ✅ **Real-time Statistics** - Monitor connection speed and data usage
- ✅ **Modern Tech Stack** - Kotlin (Android) + Rust (Desktop)
- ✅ **Auto-Reconnect** - Exponential backoff retry logic for reliable connections
- ✅ **Token Authentication** - Secure WiFi and Bluetooth connections with random tokens
- ✅ **mDNS Discovery** - Automatic device discovery on local network
- ✅ **Connection Profiles** - Save and reuse connection configurations
- ✅ **IP Discovery** - Automatic WiFi IP detection via ADB or mDNS

### Planned Features

- ✅ Root mode for improved performance (Android root tethering — implemented)
- ✅ Desktop GUI application (implemented; Linux release build, Windows GUI release pending)
- ✅ Connection encryption for WiFi — `etphonehome wifi --tls` uses TLS 1.3 with a certificate pinned to the pairing token and fails closed (USB and Bluetooth stay plaintext)
- 🔲 Multi-device support
- 🔲 Mobile data usage tracking
- 🔲 Custom DNS servers

## 📋 Requirements

### Android Device
- Android 5.0+ (API Level 21+)
- USB debugging enabled (for USB tethering)
- VPN permission (granted through the app)

### Desktop Computer

**Linux:**
- Root/sudo access
- TUN kernel module (usually built-in)
- ADB (Android Debug Bridge)

**Windows:**
- Administrator privileges
- Wintun driver: download it from https://www.wintun.net and put `wintun.dll` next to `etphonehome.exe` (the client uses Wintun, not TAP-Windows)
- ADB (Android Debug Bridge)

## 🏗️ Project Structure

```
etphonehome/
├── android/              # Android application (Kotlin)
│   └── app/src/main/
│       ├── java/com/etphonehome/
│       │   ├── MainActivity.kt           # Main UI
│       │   ├── protocol/Protocol.kt      # Protocol implementation
│       │   └── service/VpnService.kt     # VPN service
│       └── res/                          # Android resources
│
├── desktop/              # Desktop applications (Rust)
│   ├── common/           # Shared library
│   │   └── src/
│   │       ├── protocol.rs      # Protocol implementation
│   │       ├── client.rs        # Connection client
│   │       └── tun_interface.rs # TUN interface manager
│   ├── linux/            # Linux-specific client
│   └── windows/          # Windows-specific client
│
├── protocol/             # Protocol specification
│   └── PROTOCOL.md       # Binary protocol documentation
│
├── docs/                 # Documentation
│   ├── BUILD.md          # Build instructions
│   ├── SETUP.md          # Setup and usage guide
│   ├── DEVELOPMENT.md    # Developer guide
│   └── FAQ.md            # Frequently asked questions
│
└── scripts/              # Helper scripts
    ├── quick-start-linux.sh      # Linux quick start
    └── quick-start-windows.ps1   # Windows quick start
```

## 🔧 Building from Source

### Prerequisites

**For Android:**
- Android Studio (latest)
- JDK 17+
- Android SDK API 21-34

**For Desktop:**
- Rust (latest stable)
- Platform build tools (see [BUILD.md](docs/BUILD.md))

### Build Android App

```bash
cd android
./gradlew assembleDebug

# Install on device
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Build Desktop Client

**Linux:**
```bash
cd desktop
cargo build --release -p etphonehome-linux
sudo cp target/release/etphonehome /usr/local/bin/
```

**Windows:**
```cmd
cd desktop
cargo build --release -p etphonehome-windows
```

See [BUILD.md](docs/BUILD.md) for detailed build instructions.

## 📖 How It Works

ET Phone Home uses different approaches based on what's available:

### VPN Mode (Default - No Root)

```
┌─────────────┐                           ┌──────────────┐
│   Android   │                           │   Desktop    │
│   Device    │                           │   Computer   │
│             │                           │              │
│  ┌────────┐ │                           │  ┌────────┐  │
│  │ VPN    │ │  ←── Protocol Messages ──→  │  TUN   │  │
│  │ Service│ │  (via USB/WiFi/Bluetooth)   │  iface │  │
│  └────────┘ │                           │  └────────┘  │
│      ↕      │                           │      ↕       │
│  Mobile     │                           │   Internet   │
│  Internet   │                           │   Apps       │
└─────────────┘                           └──────────────┘
```

1. **Android:** VPN service captures all network traffic
2. **Transport:** Traffic is sent via USB/WiFi/Bluetooth to desktop
3. **Desktop:** TUN interface receives traffic and routes to internet
4. **Return:** Response packets follow reverse path

### Connection Methods

**USB (Recommended - Fastest):**
- Uses ADB reverse port forwarding: `adb reverse tcp:8888 tcp:8888`
- Android app listens on localhost:8888
- Desktop connects to localhost:8888 (tunneled via ADB)
- Most reliable and fastest
- Charges device while tethering

**WiFi (Wireless Convenience):**
- Android creates server socket on WiFi interface
- Desktop connects directly to Android's WiFi IP
- No cables required
- Slightly higher latency (+10-25ms)
- Token authentication for security
- Use `discover-wifi` command for automatic discovery

**Bluetooth (Low Power):**
- RFCOMM (SPP, channel 1) via BlueZ on Linux
- The Windows client does not support Bluetooth yet (use USB or WiFi there)
- Token authentication for security
- Lower power consumption than WiFi
- Slowest connection method (typical Bluetooth speeds)

## 🎯 Usage Examples

### Basic USB Tethering

**On Android:**
1. Enable USB debugging
2. Connect to computer via USB
3. Open ET Phone Home app
4. Tap "Start Tethering"

**On Desktop:**
```bash
# Set up ADB forwarding
sudo etphonehome setup-adb

# Connect
sudo etphonehome usb
```

### WiFi Tethering

**Method 1: Automatic Discovery (Recommended)**

**On Android:**
1. Open ET Phone Home app
2. Select "WiFi" mode
3. Tap "Start Tethering"
4. Note the WiFi IP and Auth Token displayed

**On Desktop:**
```bash
# Discover Android devices on local network
sudo etphonehome discover-wifi

# Connect to discovered device (use token from Android app)
sudo etphonehome wifi --host 192.168.1.100 --token AbC123XyZ4567890
```

**Method 2: ADB Discovery (USB connected)**

```bash
# Discover Android WiFi IP via ADB
sudo etphonehome discover-ip
# Output: ✓ Android WiFi IP: 192.168.1.100

# Connect using discovered IP and token from Android app
sudo etphonehome wifi --host 192.168.1.100 --token AbC123XyZ4567890
```

**Method 3: Manual IP Entry**

Find Android's WiFi IP in Settings → About → Status, then:
```bash
sudo etphonehome wifi --host 192.168.1.100 --token AbC123XyZ4567890
```

### Bluetooth Tethering

**On Android:**
1. Open ET Phone Home app
2. Select "Bluetooth" mode
3. Tap "Start Tethering"
4. Note the Bluetooth Address and Auth Token displayed

**On Desktop (Linux):**
```bash
# Pair device first (one-time setup)
bluetoothctl
# > pair XX:XX:XX:XX:XX:XX
# > trust XX:XX:XX:XX:XX:XX
# > exit

# Connect to Bluetooth device
sudo etphonehome bluetooth --address XX:XX:XX:XX:XX:XX --token AbC123XyZ4567890
```

**On Desktop (Windows):** Bluetooth is not supported by the Windows client yet; use USB or WiFi.

### Connection Profiles

Save frequently used connections for quick access:

```bash
# Save a WiFi profile
etphonehome save-profile my-home-wifi --type wifi --host 192.168.1.100 --description "Home WiFi connection"

# Save a Bluetooth profile
etphonehome save-profile my-phone-bt --type bluetooth --address XX:XX:XX:XX:XX:XX

# List all profiles
etphonehome list-profiles

# Connect using a profile
sudo etphonehome connect my-home-wifi

# Delete a profile
etphonehome delete-profile my-home-wifi
```

Profiles live in `~/.config/etphonehome/config.toml`. `sudo etphonehome connect ...`
reads the invoking user's file (not root's), so profiles saved without sudo are found.
Use `--config <file>` or `ETPHONEHOME_CONFIG=<file>` to pick another file. WiFi
profiles use `auth.wifi_token` and Bluetooth profiles `auth.bluetooth_token` from that
file; a profile's `[profiles.filter]` rules are applied to outgoing traffic.

### Auto-Reconnect

Enable automatic reconnection on connection drops:

```bash
# WiFi with auto-reconnect (infinite retries)
sudo etphonehome wifi --host 192.168.1.100 --token XXX --reconnect

# With maximum retry limit
sudo etphonehome wifi --host 192.168.1.100 --token XXX --reconnect --max-retries 10

# USB with auto-reconnect
sudo etphonehome usb --reconnect
```

Auto-reconnect is off unless `--reconnect` is given for `usb`/`wifi`/`bluetooth`;
`connect <profile>` follows the profile file's `default.auto_reconnect` and accepts
`--reconnect=false` to override it. Backoff is 1, 2, 4 ... 60 seconds. Ctrl-C sends a
Disconnect to the phone and exits.

### Custom Configuration

```bash
# Custom port
etphonehome usb --port 9999

# Custom interface name
etphonehome usb --interface mytun0
```

See [SETUP.md](docs/SETUP.md) for more usage examples.

## 🐛 Troubleshooting

**Connection fails:**
```bash
# Check ADB connection
adb devices

# Check ADB forwarding
adb reverse --list

# Check logs
adb logcat -s ETPhoneHome:V
```

**TUN interface issues (Linux):**
```bash
# Load TUN module
sudo modprobe tun

# Check if loaded
lsmod | grep tun
```

**Windows TUN adapter:**
- The client uses the Wintun driver: put `wintun.dll` (https://www.wintun.net) next to `etphonehome.exe`
- Run as Administrator

See [FAQ.md](docs/FAQ.md) for more troubleshooting help.

## 🤝 Contributing

Contributions are welcome! Please see [DEVELOPMENT.md](docs/DEVELOPMENT.md) for:
- Development setup
- Architecture overview
- Coding standards
- How to contribute

## 📚 Documentation

- **[BUILD.md](docs/BUILD.md)** - Building from source
- **[SETUP.md](docs/SETUP.md)** - Setup and usage guide
- **[DEVELOPMENT.md](docs/DEVELOPMENT.md)** - Development guide
- **[FAQ.md](docs/FAQ.md)** - Frequently asked questions
- **[PROTOCOL.md](protocol/PROTOCOL.md)** - Protocol specification

## 🔒 Security

- **USB mode:** Traffic is local to USB connection (secure)
- **VPN API:** Standard Android VPN implementation
- **Open Source:** All code is auditable under MIT license
- **No telemetry:** No data collection or tracking
- **Encryption:** WiFi encryption planned for future release

See [FAQ.md](docs/FAQ.md) for more security information.

## 🆚 Comparison

|Feature|ET Phone Home|PDANet|EasyTether|Native Tethering|
|-------|-------------|------|----------|----------------|
|Price|Free|Freemium|Paid|Free|
|Open Source|✅|❌|❌|N/A|
|No Root|✅|✅|✅|✅|
|USB|✅|✅|✅|✅|
|WiFi|✅|✅|✅|✅|
|Bluetooth|✅|✅|❌|✅|
|Token Auth|✅|❌|❌|N/A|
|Auto-Reconnect|✅|Unknown|Unknown|N/A|
|Connection Profiles|✅|Unknown|Unknown|N/A|
|mDNS Discovery|✅|❌|❌|N/A|
|Bypass Carrier|✅|✅|✅|❌|
|Multi-threaded I/O|✅|Unknown|Unknown|N/A|
|Desktop GUI|✅|✅|✅|N/A|

## 📄 License

This project is licensed under the [MIT License](LICENSE).

You are free to use, modify, and distribute this software for any purpose, including commercial use. See the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Inspired by PDANet and EasyTether
- Built with [Rust](https://www.rust-lang.org/) and [Kotlin](https://kotlinlang.org/)
- Uses [tun-rs](https://github.com/meh/rust-tun) for TUN interface

## 📞 Support

- 📖 [Documentation](docs/)
- 🐛 [Issue Tracker](https://git.canutethegreat.com/CanuteTheGreat/etphonehome/issues)

---

**Note:** Current version is v0.4.0 (Beta). All three tethering modes (USB, WiFi, Bluetooth) are fully functional with token authentication, auto-reconnect, and connection profiles. See the roadmap in [FAQ.md](docs/FAQ.md) for planned features.
