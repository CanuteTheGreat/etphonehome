# ET Phone Home - Quick Start Guide

Get up and running with ET Phone Home in 5 minutes!

## Prerequisites Checklist

### Android Device
- [ ] Android 5.0 or higher
- [ ] USB cable
- [ ] USB debugging enabled
- [ ] Developer options enabled

### Desktop Computer
- [ ] Linux or Windows
- [ ] Root/Administrator access
- [ ] ADB installed
- [ ] (Linux) Build tools installed
- [ ] (Windows) `wintun.dll` next to `etphonehome.exe` (https://www.wintun.net)

## Step 1: Enable USB Debugging

### On Your Android Device:

1. Go to **Settings → About Phone**
2. Tap **Build Number** 7 times (you'll see "You are now a developer!")
3. Go back to **Settings → Developer Options**
4. Enable **USB Debugging**
5. Connect USB cable
6. Accept the "Allow USB debugging?" prompt

**Verify:**
```bash
adb devices
# Should show your device
```

## Step 2: Build the Project

### Option A: Quick Start Script (Recommended)

**Linux:**
```bash
cd /path/to/etphonehome
sudo ./scripts/quick-start-linux.sh
```

**Windows (PowerShell as Administrator):**
```powershell
cd C:\path\to\etphonehome
.\scripts\quick-start-windows.ps1
```

The script will:
- Check dependencies
- Build everything
- Install Android app
- Set up ADB forwarding
- Guide you through connection

### Option B: Manual Build

#### Build Android App

```bash
cd android
./gradlew assembleDebug

# Install on device
adb install app/build/outputs/apk/debug/app-debug.apk
```

#### Build Desktop Client

**Linux:**
```bash
cd desktop
cargo build --release -p etphonehome-linux

# Binary at: target/release/etphonehome
```

**Windows:**
```cmd
cd desktop
cargo build --release -p etphonehome-windows

# Binary at: target\release\etphonehome.exe
```

## Step 3: Set Up Connection

### 1. Configure ADB Forwarding

```bash
adb forward tcp:8888 tcp:8888

# Verify
adb forward --list
# Should show: tcp:8888 -> tcp:8888
```

### 2. Start Android App

1. Open **ET Phone Home** on your Android device
2. Tap **"Start Tethering"**
3. When prompted, tap **"OK"** to grant VPN permission
4. Wait for status to show **"Connected"**

### 3. Start Desktop Client

**Linux:**
```bash
sudo ./desktop/target/release/etphonehome usb
```

**Windows (as Administrator):**
```cmd
.\desktop\target\release\etphonehome.exe usb
```

## Step 4: Verify Connection

You should see output like:

```
[INFO] Connecting to Android device at 127.0.0.1:8888...
[INFO] Connected successfully
[INFO] Performing handshake...
[INFO] Handshake successful
[INFO] TUN interface created successfully
[INFO] Tethering connection established!
[INFO] You can now use your Android device's internet connection.
```

**Test your connection:**

```bash
# Test DNS
nslookup google.com

# Test ping
ping -c 4 8.8.8.8

# Test HTTP
curl http://example.com
```

## Troubleshooting

### "adb: command not found"

**Install ADB:**

```bash
# Ubuntu/Debian
sudo apt-get install android-tools-adb

# macOS
brew install android-platform-tools

# Windows
# Download from: https://developer.android.com/studio/releases/platform-tools
# Add to PATH
```

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
- Restart computer

### "Connection refused"

1. **Check Android app is running:**
   ```bash
   adb shell ps | grep etphonehome
   ```

2. **Check ADB forwarding:**
   ```bash
   adb forward --list
   ```

3. **Restart everything:**
   - Stop desktop client (Ctrl+C)
   - Stop Android app
   - Run `adb forward tcp:8888 tcp:8888` again
   - Start Android app
   - Start desktop client

### "Device unauthorized"

1. Disconnect USB cable
2. Run `adb kill-server`
3. Reconnect USB
4. Accept authorization prompt on Android
5. Check "Always allow from this computer"

### "VPN permission required"

1. Stop the app completely
2. Go to Settings → Apps → ET Phone Home → Permissions
3. Ensure all required permissions are granted
4. Start app again

## Performance Tips

### Optimal Settings

**For Best Speed:**
- Use USB connection
- Keep Android app in foreground
- Disable battery optimization for the app

**For Battery Life:**
- Still use USB (device charges while tethering)
- Lower screen brightness on Android
- Close unnecessary apps on Android

**For Stability:**
- Use a good quality USB cable
- Disable "USB debugging" timeout if available
- Keep computer plugged in

### Monitoring

**Check connection status:**
```bash
# On Android: Watch the app UI for:
- Connection status
- Bytes sent/received
- Duration

# On desktop: Watch logs for:
[INFO] Stats: sent=... recv=... uptime=...
```

## Stopping

### To Stop Tethering:

1. **On Desktop:** Press `Ctrl+C`
2. **On Android:** Tap "Stop Tethering"
3. **Clean up:**
   ```bash
   adb forward --remove tcp:8888
   ```

## Next Steps

### Learn More

- **[Full Setup Guide](docs/SETUP.md)** - Detailed setup instructions
- **[Testing Guide](docs/TESTING.md)** - How to test and benchmark
- **[FAQ](docs/FAQ.md)** - Common questions and answers
- **[Development Guide](docs/DEVELOPMENT.md)** - For developers

### WiFi Mode

**Method 1: Automatic IP Discovery (Recommended)**

```bash
# Discover Android WiFi IP (requires USB/ADB initially)
sudo etphonehome discover-ip

# Start Android app, select "WiFi" mode, tap "Start Tethering"

# Connect from desktop
sudo etphonehome wifi --host 192.168.1.100 --port 8888
```

**Method 2: Manual Setup**

```bash
# Find Android IP manually
adb shell ip addr show wlan0 | grep inet

# Or check Android Settings → About → Status → IP Address

# Start Android app, select "WiFi" mode, tap "Start Tethering"

# Connect from desktop
sudo etphonehome wifi --host <android-ip> --port 8888
```

### Bluetooth Mode (Partial Implementation)

```bash
# Android: Fully implemented - select "Bluetooth" mode
# Desktop: Framework in place, needs RFCOMM client implementation

# When desktop implementation is complete:
sudo etphonehome bluetooth --address AA:BB:CC:DD:EE:FF
```

### Advanced Usage

**Custom configuration:**
```bash
# Copy example config
cp config.example.toml config.toml

# Edit settings
nano config.toml

# Run with config (future feature)
sudo etphonehome --config config.toml usb
```

**Debug mode:**
```bash
# Enable debug logging
RUST_LOG=debug sudo etphonehome usb 2>&1 | tee debug.log
```

## Getting Help

**If something doesn't work:**

1. Check [FAQ.md](docs/FAQ.md)
2. Check [SETUP.md](docs/SETUP.md)
3. Enable debug logging:
   ```bash
   RUST_LOG=debug sudo etphonehome usb
   ```
4. Check Android logs:
   ```bash
   adb logcat -s ETPhoneHome:V
   ```
5. Create an issue on GitHub with:
   - Your OS and version
   - Android version
   - Error messages
   - Logs

## Success!

If you see "Tethering connection established!" and can ping 8.8.8.8, you're all set!

Your computer is now using your Android device's internet connection via USB tethering.

Enjoy! 🎉

---

## Common Commands Reference

```bash
# Check ADB connection
adb devices

# Set up port forwarding (USB mode)
adb forward tcp:8888 tcp:8888

# Check forwarding
adb forward --list

# Discover Android WiFi IP (WiFi mode)
sudo etphonehome discover-ip

# Install Android app
adb install app-debug.apk

# View Android logs
adb logcat -s ETPhoneHome:V

# Start desktop client - USB (Linux)
sudo etphonehome usb

# Start desktop client - WiFi (Linux)
sudo etphonehome wifi --host 192.168.1.100

# Start desktop client - USB (Windows)
.\etphonehome.exe usb

# Start desktop client - WiFi (Windows)
.\etphonehome.exe wifi --host 192.168.1.100

# Test connection
ping 8.8.8.8

# Stop forwarding
adb forward --remove tcp:8888
```

## Quick Reference Card

| Task | Command |
|------|---------|
| Enable USB debugging | Settings → Developer Options |
| Check device connection | `adb devices` |
| Setup port forwarding (USB) | `adb forward tcp:8888 tcp:8888` |
| Discover WiFi IP | `sudo etphonehome discover-ip` |
| Build Android | `./gradlew assembleDebug` |
| Build Desktop (Linux) | `cargo build --release` |
| Start tethering (USB) | Select USB, tap "Start" |
| Start tethering (WiFi) | Select WiFi, tap "Start" |
| Start desktop client (USB) | `sudo etphonehome usb` |
| Start desktop client (WiFi) | `sudo etphonehome wifi --host <IP>` |
| Check connection | `ping 8.8.8.8` |
| View logs | `adb logcat -s ETPhoneHome:V` |
| Stop tethering | Tap "Stop" in app |

For more details, see the [full documentation](docs/).
