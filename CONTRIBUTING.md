# Contributing to ET Phone Home

First off, thank you for considering contributing to ET Phone Home! It's people like you that make ET Phone Home such a great tool.

## Code of Conduct

This project and everyone participating in it is governed by our Code of Conduct. By participating, you are expected to uphold this code. Please be respectful and constructive in all interactions.

## How Can I Contribute?

### Reporting Bugs

Before creating bug reports, please check the existing issues as you might find out that you don't need to create one. When you are creating a bug report, please include as many details as possible:

**Bug Report Template:**

```markdown
**Describe the bug**
A clear and concise description of what the bug is.

**To Reproduce**
Steps to reproduce the behavior:
1. Go to '...'
2. Click on '....'
3. See error

**Expected behavior**
What you expected to happen.

**Screenshots/Logs**
If applicable, add screenshots or log output.

**Environment:**
 - OS: [e.g. Windows 11, Ubuntu 22.04]
 - Android Version: [e.g. Android 13]
 - Device: [e.g. Pixel 7]
 - App Version: [e.g. 0.1.0]

**Additional context**
Any other context about the problem.
```

### Suggesting Enhancements

Enhancement suggestions are tracked as GitHub issues. When creating an enhancement suggestion, please include:

- **Use a clear and descriptive title**
- **Provide a detailed description** of the suggested enhancement
- **Explain why this enhancement would be useful** to most users
- **List some other applications where this enhancement exists**, if applicable

### Pull Requests

1. **Fork the repo** and create your branch from `main`
2. **Make your changes**
   - If you've added code, add tests
   - If you've changed APIs, update the documentation
   - Ensure the test suite passes
   - Make sure your code follows the existing style
3. **Write clear commit messages**
4. **Submit a pull request**

## Development Process

### Setting Up Development Environment

See [DEVELOPMENT.md](docs/DEVELOPMENT.md) for detailed setup instructions.

**Quick Start:**

```bash
# Clone your fork
git clone https://github.com/YOUR-USERNAME/etphonehome.git
cd etphonehome

# Build Android app
cd android
./gradlew assembleDebug

# Build desktop client
cd ../desktop
cargo build
```

### Project Structure

```
etphonehome/
├── android/          # Android app (Kotlin)
├── desktop/          # Desktop apps (Rust)
├── protocol/         # Protocol specification
└── docs/             # Documentation
```

### Coding Standards

#### Kotlin (Android)

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use 4 spaces for indentation
- Maximum line length: 120 characters
- Document public APIs with KDoc

**Example:**

```kotlin
/**
 * Manages tethering connections
 *
 * @param context Application context
 */
class ConnectionManager(private val context: Context) {
    /**
     * Start a new tethering connection
     *
     * @param config Connection configuration
     * @throws IllegalStateException if connection already active
     */
    fun startConnection(config: ConnectionConfig) {
        // Implementation
    }
}
```

#### Rust (Desktop)

- Follow [Rust Style Guide](https://doc.rust-lang.org/1.0.0/style/README.html)
- Use `cargo fmt` before committing
- Use `cargo clippy` and fix all warnings
- Document public APIs with `///` doc comments

**Example:**

```rust
/// Manages the TUN interface for packet routing
pub struct TunInterface {
    device: tun::platform::Device,
}

impl TunInterface {
    /// Create a new TUN interface
    ///
    /// # Arguments
    ///
    /// * `name` - Interface name (e.g., "etph0")
    /// * `address` - IP address to assign
    ///
    /// # Errors
    ///
    /// Returns error if interface creation fails
    pub fn create(name: &str, address: &str) -> Result<Self> {
        // Implementation
    }
}
```

### Testing

#### Android

```bash
cd android
./gradlew test
```

#### Rust

```bash
cd desktop
cargo test
```

### Commit Messages

Use clear and meaningful commit messages:

```
Add: Feature description (for new features)
Fix: Bug description (for bug fixes)
Update: Change description (for updates)
Refactor: Refactoring description
Docs: Documentation changes
Test: Test changes
```

**Examples:**

```
Add: WiFi hotspot connection mode
Fix: VPN service crash on disconnect
Update: Protocol version to 1.1
Refactor: Extract connection logic to separate class
Docs: Add WiFi setup instructions
Test: Add unit tests for protocol parsing
```

### Branch Naming

Use descriptive branch names:

- `feature/description` - New features
- `fix/description` - Bug fixes
- `docs/description` - Documentation changes
- `refactor/description` - Code refactoring

**Examples:**

```
feature/wifi-tethering
fix/connection-timeout
docs/setup-guide
refactor/vpn-service
```

## Areas to Contribute

### High Priority

- [ ] WiFi tethering implementation
- [ ] Bluetooth tethering
- [ ] Root mode support
- [ ] Connection stability improvements
- [ ] Comprehensive testing

### Medium Priority

- [ ] Desktop GUI
- [ ] Auto-reconnect functionality
- [ ] Traffic statistics and monitoring
- [ ] Data usage limits
- [ ] Connection profiles

### Documentation

- [ ] Video tutorials
- [ ] More troubleshooting guides
- [ ] Platform-specific guides
- [ ] Translation to other languages

### Testing

- [ ] Test on more Android devices
- [ ] Test on different Linux distributions
- [ ] Performance benchmarking
- [ ] Edge case testing

## Protocol Changes

If you're proposing changes to the protocol:

1. **Update protocol specification** in `protocol/PROTOCOL.md`
2. **Maintain backwards compatibility** when possible
3. **Update version numbers** appropriately
4. **Update both implementations** (Android and Rust)
5. **Add tests** for the new protocol features

## Questions?

Feel free to:

- Open an issue with your question
- Start a discussion on GitHub Discussions
- Check existing documentation in `docs/`

## Recognition

Contributors will be recognized in:

- The project README
- Release notes
- A CONTRIBUTORS file (coming soon)

Thank you for contributing to ET Phone Home!
