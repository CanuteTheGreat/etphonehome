# ET Phone Home Development Guide

This guide is for developers who want to contribute to or modify ET Phone Home.

## Architecture Overview

ET Phone Home consists of three main components:

1. **Android App** - Kotlin-based Android application
2. **Desktop Client** - Rust-based desktop applications for Windows and Linux
3. **Protocol** - Binary protocol for communication between Android and desktop

### Component Architecture

```
┌─────────────────────────────────────────────────┐
│                  Android App                     │
│  ┌──────────────────────────────────────────┐  │
│  │          VPN Service (Non-root)          │  │
│  │  - Captures all network traffic via VPN  │  │
│  │  - Forwards to desktop companion         │  │
│  └──────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────┐  │
│  │       Connection Services (USB/WiFi)     │  │
│  │  - USB: ADB reverse forwarding           │  │
│  │  - WiFi: Direct socket connection        │  │
│  │  - Bluetooth: RFCOMM (planned)           │  │
│  └──────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
                       ↕
              Protocol Messages
                       ↕
┌─────────────────────────────────────────────────┐
│               Desktop Client                     │
│  ┌──────────────────────────────────────────┐  │
│  │          TUN Interface Manager           │  │
│  │  - Creates virtual network interface     │  │
│  │  - Routes packets to/from Android        │  │
│  └──────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────┐  │
│  │         Protocol Handler                 │  │
│  │  - Handshake and message parsing         │  │
│  │  - Statistics tracking                   │  │
│  └──────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

## Project Structure

```
etphonehome/
├── android/                    # Android application
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/etphonehome/
│   │   │   │   ├── MainActivity.kt          # Main UI
│   │   │   │   ├── protocol/Protocol.kt     # Protocol implementation
│   │   │   │   └── service/
│   │   │   │       └── VpnService.kt        # VPN service
│   │   │   ├── res/                         # Resources
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle                     # App build config
│   ├── build.gradle                         # Project build config
│   └── settings.gradle
│
├── desktop/                    # Desktop applications
│   ├── common/                 # Shared Rust library
│   │   └── src/
│   │       ├── protocol.rs     # Protocol implementation
│   │       ├── client.rs       # Connection client
│   │       └── tun_interface.rs # TUN interface
│   ├── linux/                  # Linux-specific code
│   │   └── src/main.rs
│   ├── windows/                # Windows-specific code
│   │   └── src/main.rs
│   └── Cargo.toml              # Workspace config
│
├── protocol/                   # Protocol documentation
│   └── PROTOCOL.md
│
├── docs/                       # Documentation
│   ├── BUILD.md
│   ├── SETUP.md
│   └── DEVELOPMENT.md (this file)
│
└── README.md
```

## Development Setup

### Android Development

1. **Install Android Studio**
2. **Open the project:**
   ```bash
   cd android
   # Open in Android Studio
   ```

3. **Run on device/emulator:**
   - Click "Run" or use `Shift+F10`
   - Select your device

4. **View logs:**
   ```bash
   adb logcat -s ETPhoneHome:V
   ```

### Rust Development

1. **Install Rust and tools:**
   ```bash
   curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
   rustup component add rustfmt clippy
   ```

2. **Build and run:**
   ```bash
   cd desktop

   # Build common library
   cargo build -p etphonehome-common

   # Build and run Linux client
   cargo run -p etphonehome-linux -- usb

   # Run tests
   cargo test
   ```

3. **Format and lint:**
   ```bash
   cargo fmt
   cargo clippy
   ```

## Protocol Development

The protocol is defined in `protocol/PROTOCOL.md` and implemented in:
- Android: `android/app/src/main/java/com/etphonehome/protocol/Protocol.kt`
- Rust: `desktop/common/src/protocol.rs`

### Adding a New Message Type

1. **Update PROTOCOL.md:**
   ```markdown
   | 0x09 | NEW_MESSAGE | Bidirectional | Description |
   ```

2. **Update Android Protocol.kt:**
   ```kotlin
   const val MSG_NEW_MESSAGE: Byte = 0x09

   data class NewMessage(...) : Message() {
       override val type = Protocol.MSG_NEW_MESSAGE
       // Implementation
   }
   ```

3. **Update Rust protocol.rs:**
   ```rust
   #[repr(u8)]
   pub enum MessageType {
       // ...
       NewMessage = 0x09,
   }

   pub struct NewMessage {
       // Fields
   }
   ```

4. **Add tests:**
   ```rust
   #[test]
   fn test_new_message_roundtrip() {
       // Test implementation
   }
   ```

## Android VPN Service

The VPN service is the core of the Android app. It:

1. **Creates a VPN interface** using Android's VPN API
2. **Captures all network traffic** from the device
3. **Forwards packets** to the desktop client via the protocol
4. **Receives packets** from desktop and writes to VPN interface

### Key Files

- `VpnService.kt` - Main VPN service implementation
- `UsbConnectionService.kt` - USB connection handling (to be implemented)

### VPN Flow

```
App Network Request
    ↓
VPN Interface (captures packet)
    ↓
VpnService.forwardFromVpnToDesktop()
    ↓
Wrap in IpPacket message
    ↓
Send via connection (USB/WiFi)
    ↓
Desktop receives and writes to TUN
    ↓
Desktop OS routes to internet
```

## Desktop TUN Interface

The desktop client creates a TUN (Layer 3) virtual network interface.

### Key Files

- `tun_interface.rs` - TUN interface creation and management
- `client.rs` - Connection to Android device

### TUN Flow

```
Application makes network request
    ↓
OS routes through TUN interface (etph0)
    ↓
TunInterface.read_packet()
    ↓
Client.send_packet() to Android
    ↓
Android forwards via mobile internet
```

## Testing

### Unit Tests

**Rust:**
```bash
cd desktop
cargo test
```

**Android:**
```bash
cd android
./gradlew test
```

### Integration Testing

1. **Build both components:**
   ```bash
   # Android
   cd android && ./gradlew assembleDebug

   # Desktop
   cd desktop && cargo build
   ```

2. **Install Android app:**
   ```bash
   adb install android/app/build/outputs/apk/debug/app-debug.apk
   ```

3. **Set up ADB forwarding:**
   ```bash
   adb reverse tcp:8888 tcp:8888
   ```

4. **Start Android app** and begin tethering

5. **Run desktop client:**
   ```bash
   sudo desktop/target/debug/etphonehome usb
   ```

6. **Test connectivity:**
   ```bash
   ping -c 4 8.8.8.8
   curl http://example.com
   ```

### Manual Testing Checklist

- [ ] USB connection establishment
- [ ] WiFi connection establishment
- [ ] Handshake success
- [ ] Packet forwarding (desktop → Android)
- [ ] Packet forwarding (Android → desktop)
- [ ] Statistics updates
- [ ] Keepalive messages
- [ ] Graceful disconnect
- [ ] Reconnection after drop
- [ ] Multiple concurrent connections
- [ ] High traffic scenarios
- [ ] Low bandwidth scenarios

## Debugging

### Android Debugging

**View VPN service logs:**
```bash
adb logcat | grep -E "(VpnService|ETPhoneHome)"
```

**Check VPN interface:**
```bash
adb shell ip addr show tun0
```

**Monitor network traffic:**
```bash
adb shell tcpdump -i tun0 -n
```

### Desktop Debugging

**Enable debug logging:**
```bash
RUST_LOG=debug sudo ./etphonehome usb
```

**Check TUN interface:**
```bash
ip addr show etph0
ip route show
```

**Monitor TUN traffic:**
```bash
sudo tcpdump -i etph0 -n
```

### Protocol Debugging

Add debug logging to see all messages:

**Android:**
```kotlin
override fun sendMessage(message: Message) {
    Log.d("Protocol", "Sending: $message")
    // ...
}
```

**Rust:**
```rust
pub fn send_message(&mut self, msg: Message) -> Result<(), ProtocolError> {
    debug!("Sending message: {:?}", msg);
    // ...
}
```

## Common Development Tasks

### Adding a New Connection Type

1. **Define connection in Android:**
   ```kotlin
   class NewConnectionService : Service() {
       // Implementation
   }
   ```

2. **Add to AndroidManifest.xml**

3. **Update desktop client:**
   ```rust
   // Add connection handling
   ```

4. **Update protocol capabilities:**
   ```kotlin
   const val CAP_NEW_TYPE: Int = 1 shl 5
   ```

### Optimizing Performance

**Reduce packet overhead:**
- Batch small packets when possible
- Adjust MTU settings

**Improve latency:**
- Use TCP_NODELAY (already enabled)
- Optimize buffer sizes

**Memory usage:**
- Pool packet buffers
- Limit queue sizes

### Adding Root Support

1. **Check for root:**
   ```kotlin
   fun isRooted(): Boolean {
       return Shell.getShell().isRoot
   }
   ```

2. **Use iptables instead of VPN:**
   ```kotlin
   Shell.cmd("iptables -t nat -A POSTROUTING -o wlan0 -j MASQUERADE").exec()
   ```

3. **Update capabilities:**
   ```kotlin
   val capabilities = Protocol.CAP_USB or Protocol.CAP_ROOT
   ```

## Code Style

### Kotlin (Android)

Follow Kotlin official style guide:
- Use 4 spaces for indentation
- Use camelCase for variables and functions
- Use PascalCase for classes
- Document public APIs

### Rust (Desktop)

Follow Rust style guide:
- Use `cargo fmt` for formatting
- Use `cargo clippy` for linting
- Document public APIs with `///` comments
- Use Result/Option for error handling

## Contributing

1. **Fork the repository**
2. **Create a feature branch:**
   ```bash
   git checkout -b feature/my-new-feature
   ```

3. **Make your changes**

4. **Test thoroughly**

5. **Commit with clear messages:**
   ```bash
   git commit -m "Add: New connection type for Bluetooth"
   ```

6. **Push and create pull request**

## Roadmap and TODOs

Current priorities (re-verified against actual code/git history — see
STATUS.md for full detail and evidence):

- [x] USB connection implementation
- [x] WiFi tethering mode
- [x] Bluetooth tethering (Android + Linux desktop; Windows desktop
      Bluetooth is intentionally unimplemented, see `connect_bluetooth` in
      `desktop/windows/src/main.rs`)
- [x] Root mode support (Android iptables routing)
- [x] Desktop GUI (`desktop/gui`, iced-based; now built/packaged in CI and
      Linux releases)
- [ ] Connection encryption — PSK/TLS crypto code exists on both sides
      (`desktop/common/src/tls/`, `android/.../tls/`) but is not yet wired
      into the live handshake; traffic is still plaintext on the wire. Top
      priority.
- [x] Traffic filtering (enforced on Android and, as of this audit, desktop)
- [ ] Improve error handling
- [x] Comprehensive protocol/filter/config unit tests (26 passing in
      `etphonehome-common` alone)
- [ ] Performance optimizations
- [ ] Multi-device support

## Resources

- [Android VPN API](https://developer.android.com/guide/topics/connectivity/vpn)
- [Rust TUN crate](https://docs.rs/tun/)
- [Protocol Specification](../protocol/PROTOCOL.md)
- [Build Instructions](BUILD.md)
- [Setup Guide](SETUP.md)

## Getting Help

For development questions:
1. Check existing issues
2. Review protocol documentation
3. Look at similar implementations (PDANet, EasyTether)
4. Ask in discussions

## License

See LICENSE file for details.
