# ET Phone Home

A modern, open-source tethering solution for Android with companion apps for Windows and Linux.

## Features

- **Multiple Connection Methods**
  - USB tethering (most reliable)
  - WiFi hotspot
  - Bluetooth tethering

- **Root Optional**
  - Works without root using Android VPN API
  - Enhanced features when root is available

- **Cross-Platform Desktop Support**
  - Windows companion app
  - Linux companion app
  - Built with Rust for performance and reliability

## Project Structure

```
etphonehome/
├── android/          # Android app
├── desktop/          # Rust-based desktop apps
│   ├── common/       # Shared Rust code
│   ├── windows/      # Windows-specific code
│   └── linux/        # Linux-specific code
├── protocol/         # Communication protocol definitions
└── docs/             # Documentation
```

## Getting Started

### Prerequisites

**For Android Development:**
- Android Studio (latest version)
- Android SDK (API level 21+, targeting API 34)
- Java Development Kit (JDK) 17+

**For Desktop Development:**
- Rust (latest stable)
- Cargo

**For USB Tethering:**
- Android Debug Bridge (ADB)
- USB drivers for your device

### Building the Android App

```bash
cd android
./gradlew assembleDebug
```

### Building the Desktop Apps

```bash
cd desktop
cargo build --release
```

## How It Works

### Non-Root Mode (VPN API)
The app creates a local VPN connection that captures all network traffic and forwards it through the tethering connection to the desktop companion app.

### Root Mode (Optional)
When root is available, the app can directly manipulate iptables for more efficient routing and better performance.

### Connection Methods

1. **USB**: Uses ADB reverse port forwarding or custom USB protocol
2. **WiFi**: Creates a WiFi hotspot or direct connection
3. **Bluetooth**: Standard Bluetooth PAN (Personal Area Network) profile

## License

[Choose your license]

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
