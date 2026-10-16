# ET Phone Home - Desktop Clients

This directory contains the Rust-based desktop companion applications for ET Phone Home tethering.

## Structure

```
desktop/
├── common/       # Shared library
│   └── src/
│       ├── protocol.rs      # Protocol implementation
│       ├── client.rs        # TCP client for Android connection
│       ├── tun_interface.rs # TUN interface management
│       └── lib.rs           # Library exports
├── linux/        # Linux-specific client
│   └── src/
│       └── main.rs          # Linux main application
├── windows/      # Windows-specific client
│   └── src/
│       └── main.rs          # Windows main application
└── Cargo.toml    # Workspace configuration
```

## Building

### All Platforms

```bash
# Build everything
cargo build --release

# Build specific platform
cargo build --release -p etphonehome-linux
cargo build --release -p etphonehome-windows

# Run tests
cargo test

# Check for errors
cargo check --all
```

### Platform-Specific Notes

**Linux:**
```bash
# Install dependencies (Ubuntu/Debian)
sudo apt-get install build-essential libssl-dev pkg-config

# Build
cargo build --release -p etphonehome-linux

# Binary location
./target/release/etphonehome
```

**Windows:**
```powershell
# Install Rust from rustup.rs
# Then build
cargo build --release -p etphonehome-windows

# Binary location
.\target\release\etphonehome.exe
```

## Usage

### Quick Start

**USB Mode (Recommended):**
```bash
# Linux
sudo ./etphonehome usb

# Windows (as Administrator)
.\etphonehome.exe usb
```

### Command Line Options

```
etphonehome <COMMAND> [OPTIONS]

Commands:
  usb         Connect via USB
  wifi        Connect via WiFi
  setup-adb   Set up ADB reverse forwarding

Options:
  -h, --help     Show help
  -V, --version  Show version
```

### USB Mode

```bash
# Default (port 8888, interface etph0)
sudo etphonehome usb

# Custom port
sudo etphonehome usb --port 9999

# Custom interface name
sudo etphonehome usb --interface mytun
```

### WiFi Mode

```bash
# Connect to Android device
sudo etphonehome wifi --host 192.168.1.100 --port 8888

# With custom interface
sudo etphonehome wifi --host 192.168.1.100 --interface etph1
```

### ADB Setup

```bash
# Set up reverse port forwarding
sudo etphonehome setup-adb

# Custom port
sudo etphonehome setup-adb --port 9999
```

## Configuration

### Environment Variables

```bash
# Set log level
export RUST_LOG=debug

# Run with debug logging
RUST_LOG=debug sudo etphonehome usb
```

Log levels: `error`, `warn`, `info`, `debug`, `trace`

### Config File

(Future feature - not yet implemented)

```bash
# Create config
cp config.example.toml config.toml

# Edit config
nano config.toml

# Run with config
etphonehome --config config.toml usb
```

## Architecture

### Common Library

The `common` crate provides shared functionality:

- **Protocol** (`protocol.rs`):
  - Binary protocol implementation
  - Message serialization/deserialization
  - Type-safe message handling

- **Client** (`client.rs`):
  - TCP connection to Android
  - Handshake implementation
  - Packet send/receive

- **TUN Interface** (`tun_interface.rs`):
  - Virtual network interface creation
  - Packet read/write operations
  - Cross-platform TUN management

### Platform-Specific Binaries

**Linux** (`linux/`):
- Uses standard Linux TUN/TAP
- iptables integration
- systemd service support (future)

**Windows** (`windows/`):
- Uses the Wintun driver (`wintun.dll` next to the executable)
- Windows routing API
- Windows service support (future)

## Dependencies

Main dependencies:

| Crate | Purpose |
|-------|---------|
| `tun` | TUN/TAP interface |
| `tokio` | Async runtime (for future use) |
| `anyhow` | Error handling |
| `log` / `env_logger` | Logging |
| `clap` | CLI argument parsing |

## Development

### Running Tests

```bash
# All tests
cargo test

# Specific package
cargo test -p etphonehome-common

# With output
cargo test -- --nocapture

# Integration tests only
cargo test --test '*'
```

### Code Quality

```bash
# Format code
cargo fmt

# Lint
cargo clippy

# Check without building
cargo check --all
```

### Adding Features

1. Add to `common/src/` for shared code
2. Update `linux/src/main.rs` and `windows/src/main.rs` for platform-specific features
3. Add tests in module's `#[cfg(test)]` section
4. Update this README

## Troubleshooting

### "Failed to create TUN interface"

**Linux:**
```bash
# Load TUN module
sudo modprobe tun

# Verify
lsmod | grep tun
```

**Windows:**
- Put `wintun.dll` (https://www.wintun.net) next to `etphonehome.exe`
- Run as Administrator

### "Permission denied"

Always run with elevated privileges:
- Linux: `sudo`
- Windows: Run as Administrator

### "Connection refused"

1. Verify Android app is running
2. Check ADB forwarding: `adb reverse --list`
3. Check firewall settings

### Debug Logging

```bash
# Enable debug logs
RUST_LOG=debug sudo etphonehome usb 2>&1 | tee debug.log

# Even more verbose
RUST_LOG=trace sudo etphonehome usb
```

## Performance

Current implementation uses single-threaded packet forwarding. Future improvements:

- [ ] Multi-threaded packet processing
- [ ] Async I/O with tokio
- [ ] Zero-copy packet forwarding
- [ ] Connection pooling

## Future Features

- [ ] Configuration file support
- [ ] Auto-reconnect
- [ ] Statistics dashboard
- [ ] GUI application
- [ ] systemd/Windows service integration
- [ ] Multiple device support
- [ ] Traffic filtering/routing rules

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for development guidelines.

### Code Style

- Follow Rust standard style (`cargo fmt`)
- Pass clippy lints (`cargo clippy`)
- Add tests for new features
- Document public APIs

## License

MIT License - See [LICENSE](../LICENSE) for details.

## Resources

- [Rust Book](https://doc.rust-lang.org/book/)
- [tun-rs Documentation](https://docs.rs/tun/)
- [Tokio Documentation](https://tokio.rs/)
- [Project Documentation](../docs/)
