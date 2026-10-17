# ET Phone Home

A modern, open-source tethering solution for Android with companion apps for Windows and Linux - an alternative to PDANet and EasyTether.

Your computer uses the phone's internet over USB, WiFi or Bluetooth. No root, no VPN on the phone, and the phone keeps its own internet the whole time.

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

### Current Features (v0.6.0)

- ✅ **Real internet for the computer, no root** - the app relays the computer's TCP, UDP (DNS included) and ping traffic through ordinary phone sockets (a userspace relay). Verified in CI against public HTTPS and on a real phone (Samsung, Android 13)
- ✅ **The phone keeps its internet** - no phone VPN is used, so the phone's own apps are never affected
- ✅ **USB Tethering** - via `adb forward`, fastest and most reliable
- ✅ **WiFi Tethering** - token-authenticated and **encrypted by default** (TLS 1.3, certificate pinned to the pairing token, fails closed)
- ✅ **Bluetooth Tethering** - RFCOMM, Linux (BlueZ) desktop only; the Windows client has no Bluetooth
- ✅ **Cross-Platform desktop** - Windows and Linux CLI, Linux GUI
- ✅ **DNS set up for you** - the desktop points DNS for the tunnel at the phone's DNS server (systemd-resolved on Linux, `netsh` on Windows)
- ✅ **Live statistics on the phone** - connected desktop, data each way, open connections, uptime; the status only says "connected" once a desktop really is
- ✅ **Traffic filter** - a connection profile's allow/block rules are applied on the computer and sent to the phone, which enforces them on the connections it opens
- ✅ **Auto-Reconnect** - exponential backoff retry
- ✅ **mDNS Discovery**, **IP Discovery** (ADB), **Connection Profiles**
- ✅ **Open Protocol** - documented binary protocol, shared test vectors between the Rust and Kotlin sides

### Planned Features

- 🔲 Root mode (kernel NAT on rooted phones). It is **not** in the app: an earlier, unreachable and wrongly designed implementation was removed in v0.6.0
- 🔲 Bluetooth in the Windows client
- 🔲 macOS client
- 🔲 IPv6 through the tunnel (IPv4 only today)
- 🔲 Multi-device support
- 🔲 Mobile data usage tracking

## 📋 Requirements

### Android Device
- Android 8.0+ (API Level 26+)
- USB debugging enabled (for USB tethering)
- No root and no VPN permission needed

### Desktop Computer

**Linux:**
- Root/sudo access
- TUN kernel module (usually built-in)
- ADB (Android Debug Bridge)
- The release binary needs glibc 2.35 or newer; it is tested nightly with a real tunnel on Debian 12/13, Ubuntu 22.04/24.04, Fedora, Arch and openSUSE Tumbleweed, on x86_64 and arm64 (older distros: build from source)

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
│       │   ├── MainActivity.kt           # Main UI (status + live statistics)
│       │   └── service/TetherService.kt  # Foreground service: USB/WiFi/Bluetooth listeners
│       └── res/                          # Android resources
├── android/core/         # Platform-independent core (plain Kotlin/JVM)
│   ├── src/main/kotlin/com/etphonehome/
│   │   ├── relay/        # Userspace TCP/UDP/ICMP relay
│   │   ├── session/      # Handshake + per-desktop session, TLS/plaintext detection
│   │   ├── tls/          # Cert-pinned TLS 1.3 server (Bouncy Castle)
│   │   ├── protocol/     # Wire protocol
│   │   └── filter/       # Traffic filter
│   └── standin/          # The same core as a JVM program (CI end-to-end test)
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
- Android SDK API 26-34

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

### Userspace relay (no root, no phone VPN)

```
┌───────────────────────┐                          ┌──────────────────┐
│  Desktop              │                          │  Android phone   │
│                       │  IP packets in protocol  │                  │
│  apps → TUN (etph0) ──┼──── messages over ──────→│  relay: one real │──→ phone's
│        default route  │  USB / WiFi(TLS) / BT    │  socket per flow │    own network
│                    ←──┼──────────────────────────┼──  (TCP/UDP/ICMP)│←── (mobile/WiFi)
└───────────────────────┘                          └──────────────────┘
```

1. **Desktop:** a TUN interface (`etph0`, 10.0.0.1) becomes a default route; DNS for it points at the DNS server the phone announces
2. **Transport:** the desktop's IP packets travel to the phone over USB (`adb forward`), WiFi (TLS 1.3) or Bluetooth
3. **Phone:** the relay terminates each TCP connection and UDP flow itself and opens an ordinary socket to the same destination, exactly like any app would; replies are turned back into IP packets for the desktop
4. **The phone's own traffic is untouched** - nothing is captured, so the phone keeps its internet

### Connection Methods

**USB (Recommended - Fastest):**
- Uses ADB port forwarding: `adb forward tcp:8888 tcp:8888`
- Android app listens on localhost:8888
- Desktop connects to localhost:8888 (tunneled via ADB)
- Most reliable and fastest
- Charges device while tethering

**WiFi (Wireless Convenience):**
- Android creates server socket on WiFi interface
- Desktop connects directly to Android's WiFi IP
- No cables required
- Slightly higher latency (+10-25ms)
- Token authentication, and TLS 1.3 by default (pinned to the token; the app refuses plaintext unless you switch "Require encryption" off, then use `--no-tls`)
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
file; a profile's `[profiles.filter]` rules are applied to outgoing traffic on the
computer and sent to the phone, which enforces them on the connections it relays.

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
adb forward --list

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

- **USB mode:** the app's port is bound to the phone's loopback, reachable only through `adb forward` over the cable
- **WiFi mode:** token authentication plus TLS 1.3 by default; both sides derive an Ed25519 key from the pairing token and pin it, so a wrong token fails the handshake and there is no plaintext fallback
- **Bluetooth mode:** token authentication over a paired (link-encrypted) RFCOMM connection
- **No phone VPN:** the phone's own traffic is never captured or inspected
- **Open Source:** All code is auditable under MIT license
- **No telemetry:** No data collection or tracking

See [FAQ.md](docs/FAQ.md) for more security information.

## 🆚 Comparison

|Feature|ET Phone Home|PDANet|EasyTether|Native Tethering|
|-------|-------------|------|----------|----------------|
|Price|Free|Freemium|Paid|Free|
|Open Source|✅|❌|❌|N/A|
|No Root|✅|✅|✅|✅|
|USB|✅|✅|✅|✅|
|WiFi|✅|✅|✅|✅|
|Bluetooth|✅ (Linux desktop)|✅|❌|✅|
|Encrypted WiFi link|✅ TLS 1.3|❌|❌|WPA|
|Token Auth|✅|❌|❌|N/A|
|Auto-Reconnect|✅|Unknown|Unknown|N/A|
|Connection Profiles|✅|Unknown|Unknown|N/A|
|mDNS Discovery|✅|❌|❌|N/A|
|Traffic leaves from phone app sockets (not the hotspot path)|✅|✅|✅|❌|
|Phone keeps its own internet|✅|✅|✅|✅|
|Desktop GUI|✅ (Linux)|✅|✅|N/A|
|macOS client|❌|✅|✅|✅|

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

**Note:** Current version is v0.6.0 (Beta). Downloads (Linux/Windows CLI, Linux GUI, Android APK): [Releases](https://git.canutethegreat.com/CanuteTheGreat/etphonehome/releases). See the roadmap in [FAQ.md](docs/FAQ.md) for planned features.
