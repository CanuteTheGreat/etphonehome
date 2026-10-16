# Building ET Phone Home

This document provides instructions for building the ET Phone Home tethering solution.

## Prerequisites

### For Android App

1. **Android Studio** (latest version)
   - Download from: https://developer.android.com/studio

2. **Java Development Kit (JDK) 17+**
   - Included with Android Studio or download from: https://adoptium.net/

3. **Android SDK**
   - API Level 21 (minimum)
   - API Level 34 (target)
   - Install via Android Studio SDK Manager

### For Desktop Apps (Rust)

1. **Rust** (latest stable)
   ```bash
   curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
   ```

2. **Platform-specific requirements**

   **Linux:**
   ```bash
   # Ubuntu/Debian
   sudo apt-get install build-essential libssl-dev pkg-config

   # For Bluetooth support (v0.4.0+)
   sudo apt-get install libdbus-1-dev

   # Fedora/RHEL
   sudo dnf install gcc openssl-devel pkg-config

   # For Bluetooth support (v0.4.0+)
   sudo dnf install dbus-devel
   ```

   **Windows:**
   - Install Visual Studio Build Tools or Visual Studio Community
   - Install Rust for Windows: https://www.rust-lang.org/tools/install
   - Wintun driver: download it from https://www.wintun.net and put `wintun.dll` next to `etphonehome.exe` (the client uses Wintun, not TAP-Windows)

3. **Android Debug Bridge (ADB)** - For USB tethering
   ```bash
   # Ubuntu/Debian
   sudo apt-get install android-tools-adb

   # macOS
   brew install android-platform-tools

   # Windows
   # Download from: https://developer.android.com/studio/releases/platform-tools
   ```

## Building the Android App

### Command Line Build

1. Navigate to the Android directory:
   ```bash
   cd android
   ```

2. Build debug APK:
   ```bash
   ./gradlew assembleDebug
   ```

3. Build release APK (requires signing):
   ```bash
   ./gradlew assembleRelease
   ```

4. The APK will be located at:
   ```
   android/app/build/outputs/apk/debug/app-debug.apk
   # or
   android/app/build/outputs/apk/release/app-release.apk
   ```

### Android Studio Build

1. Open Android Studio
2. Select "Open an existing project"
3. Navigate to the `android` directory
4. Wait for Gradle sync to complete
5. Click "Build" → "Build Bundle(s) / APK(s)" → "Build APK(s)"

### Installing on Device

#### Via ADB (Recommended for Development)
```bash
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

#### Via Android Studio
1. Connect your Android device via USB
2. Enable USB debugging on your device
3. Click the "Run" button in Android Studio
4. Select your device from the list

## Building Desktop Apps

### Linux

1. Navigate to the desktop directory:
   ```bash
   cd desktop
   ```

2. Build the Linux binary:
   ```bash
   cargo build --release -p etphonehome-linux
   ```

3. The binary will be located at:
   ```
   desktop/target/release/etphonehome
   ```

4. (Optional) Install system-wide:
   ```bash
   sudo cp desktop/target/release/etphonehome /usr/local/bin/
   ```

### Windows

1. Open PowerShell or Command Prompt
2. Navigate to the desktop directory:
   ```cmd
   cd desktop
   ```

3. Build the Windows binary:
   ```cmd
   cargo build --release -p etphonehome-windows
   ```

4. The binary will be located at:
   ```
   desktop\target\release\etphonehome.exe
   ```

## Development Build

For faster development builds (without optimizations):

### Android
```bash
cd android
./gradlew assembleDebug
```

### Desktop (Linux/Windows)
```bash
cd desktop
cargo build -p etphonehome-linux  # or etphonehome-windows
```

Development builds are much faster but result in larger binaries and slower runtime performance.

## Dependencies (v0.4.0+)

ET Phone Home v0.4.0 introduces several new dependencies:

### Desktop (Rust)
- **mdns-sd** (0.11) - mDNS service discovery for automatic device finding
- **bluer** (0.17, Linux only) - BlueZ D-Bus integration for Bluetooth RFCOMM
  - Requires `libdbus-1-dev` system package
  - Features: `bluetoothd`, `rfcomm`
- **toml** - TOML configuration file parsing for connection profiles
- **dirs** - Cross-platform config directory paths

### Android (Kotlin)
- **Android NSD (NsdManager)** - Built-in mDNS support (no additional dependencies)
- **BluetoothManager API** - Built-in Bluetooth support (no additional dependencies)

### Build Notes

**Bluetooth on Linux:**
If you don't need Bluetooth support, you can skip the `libdbus-1-dev` requirement. The build will succeed but Bluetooth functionality will not be available.

**mDNS Discovery:**
The `mdns-sd` crate works on all platforms without additional dependencies.

**Connection Profiles:**
Profile support uses TOML parsing which is included automatically.

## Verifying Your Build

After building, verify the installation:

### Desktop Client
```bash
# Check version and available commands
./desktop/target/release/etphonehome --help

# Should show all commands including v0.4.0 additions:
# - usb, wifi, bluetooth
# - discover-ip, discover-wifi
# - save-profile, list-profiles, connect, delete-profile
# - setup-adb
```

### Android App
1. Install the APK on your device
2. Open the app
3. Verify all three connection modes are available: USB, WiFi, Bluetooth
4. In WiFi mode, check that IP and Auth Token are displayed
5. In Bluetooth mode, check that Address and Auth Token are displayed

## Troubleshooting

### Android Build Issues

**Problem:** Gradle sync fails
- **Solution:** Ensure you have the correct Android SDK installed via Android Studio SDK Manager

**Problem:** Build fails with "SDK location not found"
- **Solution:** Create `android/local.properties` with:
  ```
  sdk.dir=/path/to/Android/Sdk
  ```

### Rust Build Issues

**Problem:** "linker 'cc' not found"
- **Solution (Linux):** Install build-essential: `sudo apt-get install build-essential`
- **Solution (Windows):** Install Visual Studio Build Tools

**Problem:** TUN interface creation fails
- **Solution:** On Linux, install TUN/TAP kernel module: `sudo modprobe tun`
- **Solution:** On Windows, put `wintun.dll` (https://www.wintun.net) next to `etphonehome.exe`

**Problem:** "failed to run custom build command for `libdbus-sys`" (v0.4.0+)
- **Solution (Linux):** Install D-Bus development libraries:
  ```bash
  # Ubuntu/Debian
  sudo apt-get install libdbus-1-dev pkg-config

  # Fedora/RHEL
  sudo dnf install dbus-devel pkgconf-pkg-config
  ```
- **Note:** This is only required for Bluetooth support on Linux

**Problem:** Bluetooth features not working after build
- **Solution (Linux):** Ensure BlueZ is installed and running:
  ```bash
  sudo apt-get install bluez
  sudo systemctl status bluetooth
  ```
- **Solution (Windows):** Ensure Bluetooth is enabled in Windows Settings

**Problem:** `bluer` dependency errors (v0.4.0+)
- **Solution:** This is Linux-specific. If building on Windows, this is expected and won't affect the build
- **Verify:** Check that `bluer` is only in `desktop/linux/Cargo.toml`, not workspace dependencies

### ADB Issues

**Problem:** "adb: device unauthorized"
- **Solution:** Check your Android device for USB debugging authorization prompt

**Problem:** "adb: no devices found"
- **Solution:**
  1. Check USB cable connection
  2. Ensure USB debugging is enabled
  3. Try a different USB port
  4. Run `adb kill-server && adb start-server`

## Cross-Compilation

### Building for Different Targets

You can cross-compile the Rust binaries for different platforms:

```bash
# Install target
rustup target add x86_64-pc-windows-gnu

# Build for Windows from Linux
cargo build --release -p etphonehome-windows --target x86_64-pc-windows-gnu
```

Common targets:
- `x86_64-unknown-linux-gnu` - Linux (64-bit)
- `x86_64-pc-windows-gnu` - Windows (64-bit)
- `aarch64-linux-android` - Android ARM64 (for native components)

## Next Steps

After building, see [SETUP.md](SETUP.md) for instructions on setting up and using ET Phone Home.
