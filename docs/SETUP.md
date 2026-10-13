# ET Phone Home Setup Guide

This guide will help you set up and use ET Phone Home for tethering your Android device's internet connection.

## Quick Start

### USB Tethering (Recommended for First-Time Setup)

This is the easiest method to get started.

#### On Your Computer (Linux Example)

1. **Enable USB Debugging on Android:**
   - Go to Settings → About Phone
   - Tap "Build Number" 7 times to enable Developer Options
   - Go to Settings → Developer Options
   - Enable "USB Debugging"

2. **Connect Your Device:**
   ```bash
   # Connect Android device via USB cable
   # Accept the USB debugging prompt on your device
   ```

3. **Set up ADB Reverse Forwarding:**
   ```bash
   sudo etphonehome setup-adb
   ```

4. **Start the Android App:**
   - Open "ET Phone Home" on your Android device
   - Tap "Start Tethering"
   - Grant VPN permission when prompted

5. **Connect from Desktop:**
   ```bash
   sudo etphonehome usb
   ```

6. **You're Connected!**
   Your computer should now be using your Android device's internet connection.

## Detailed Setup Instructions

### USB Tethering

USB tethering uses ADB reverse port forwarding to create a connection between your computer and Android device.

#### Prerequisites
- USB cable
- ADB (Android Debug Bridge) installed
- USB debugging enabled on Android

#### Linux Setup

```bash
# 1. Set up ADB forwarding (one-time per device connection)
sudo etphonehome setup-adb --port 8888

# 2. Start Android app (on your phone)
# Tap "Start Tethering" in the app

# 3. Connect from Linux
sudo etphonehome usb --port 8888 --interface etph0
```

#### Windows Setup

```cmd
# 1. Set up ADB forwarding (run as Administrator)
etphonehome.exe setup-adb --port 8888

# 2. Start Android app (on your phone)
# Tap "Start Tethering" in the app

# 3. Connect from Windows (run as Administrator)
etphonehome.exe usb --port 8888 --interface etph0
```

**Note:** On Windows, you need TAP-Windows adapter installed. If you have OpenVPN installed, you already have it.

### WiFi Tethering

WiFi tethering allows wireless connection between your computer and Android device without cables.

#### Prerequisites
- Both devices on the same WiFi network, OR
- Android device creating a WiFi hotspot
- Initial USB connection for IP discovery (optional but recommended)

#### Method 1: Automatic mDNS Discovery (Recommended - v0.4.0+)

**This is the easiest method for WiFi tethering:**

1. **Start Android App in WiFi Mode:**
   - Open ET Phone Home on Android
   - Select **"WiFi"** mode (radio button)
   - Note the **WiFi IP** and **Auth Token** displayed on screen
   - Tap **"Start Tethering"**

2. **Discover Android Devices on Network:**
   ```bash
   # Linux
   sudo etphonehome discover-wifi

   # Windows (as Administrator)
   etphonehome.exe discover-wifi

   # Output will show all ET Phone Home devices on your network with their IPs
   ```

3. **Connect from Desktop with Token:**
   ```bash
   # Linux (use token from Android app)
   sudo etphonehome wifi --host 192.168.1.100 --token AbC123XyZ4567890

   # Windows (as Administrator)
   etphonehome.exe wifi --host 192.168.1.100 --token AbC123XyZ4567890
   ```

#### Method 2: ADB IP Discovery (USB Required)

**For when you have USB connected:**

1. **Connect Android via USB (temporarily):**
   ```bash
   # Connect device and ensure ADB is working
   adb devices
   ```

2. **Discover Android's WiFi IP:**
   ```bash
   # Linux
   sudo etphonehome discover-ip

   # Windows (as Administrator)
   etphonehome.exe discover-ip

   # Output will show: ✓ Android WiFi IP: 192.168.1.100
   ```

3. **Start Android App in WiFi Mode:**
   - Open ET Phone Home on Android
   - Select **"WiFi"** mode (radio button)
   - Note the **Auth Token** displayed on screen
   - Tap **"Start Tethering"**

4. **Disconnect USB (optional):**
   - You can now unplug the USB cable
   - Tethering will continue over WiFi

5. **Connect from Desktop with Token:**
   ```bash
   # Linux (use token from Android app)
   sudo etphonehome wifi --host 192.168.1.100 --token AbC123XyZ4567890

   # Windows (as Administrator)
   etphonehome.exe wifi --host 192.168.1.100 --token AbC123XyZ4567890
   ```

#### Method 3: Manual IP Discovery

1. **Find Your Android's IP Address:**
   - On Android: Settings → About Phone → Status → IP Address
   - Or use `adb shell ip addr show wlan0 | grep inet`
   - Or check the ET Phone Home app when WiFi mode is selected

2. **Start Android App in WiFi Mode:**
   - Open ET Phone Home
   - Select **"WiFi"** mode
   - Note the **Auth Token** displayed on screen
   - Tap "Start Tethering"

3. **Connect from Desktop with Token:**
   ```bash
   # Linux
   sudo etphonehome wifi --host 192.168.1.XXX --token AbC123XyZ4567890

   # Windows (as Administrator)
   etphonehome.exe wifi --host 192.168.1.XXX --token AbC123XyZ4567890
   ```

#### Method 4: Android as Hotspot

1. **Enable Hotspot on Android:**
   - Settings → Network & Internet → Hotspot & Tethering
   - Enable "WiFi Hotspot"
   - Note the network name and password

2. **Connect Computer to Hotspot:**
   - Connect your computer to the Android hotspot WiFi

3. **Start Android App in WiFi Mode:**
   - Open ET Phone Home
   - Select **"WiFi"** mode
   - Note the **Auth Token** displayed
   - Tap "Start Tethering"

4. **Connect from Desktop with Token:**
   ```bash
   # The Android device's IP is typically 192.168.43.1 on its hotspot
   # Linux
   sudo etphonehome wifi --host 192.168.43.1 --token AbC123XyZ4567890

   # Windows (as Administrator)
   etphonehome.exe wifi --host 192.168.43.1 --token AbC123XyZ4567890
   ```

### Bluetooth Tethering

**Status:** Fully implemented (v0.4.0)
- ✅ Android server: Fully functional
- ✅ Desktop client: Full RFCOMM implementation (Linux BlueZ + Windows)

Bluetooth tethering provides a wireless connection with lower power consumption than WiFi, though with slower speeds.

#### Prerequisites
- Bluetooth enabled on both devices
- Devices paired (one-time setup)
- Android Bluetooth permission granted

#### Linux Setup

1. **Pair with Android Device (One-Time Setup):**
   ```bash
   bluetoothctl
   > scan on
   # Wait for your device to appear
   > pair AA:BB:CC:DD:EE:FF
   > trust AA:BB:CC:DD:EE:FF
   > exit
   ```

2. **Start Android App in Bluetooth Mode:**
   - Open ET Phone Home
   - Select **"Bluetooth"** mode
   - Ensure Bluetooth is enabled on Android
   - Note the **Bluetooth Address** and **Auth Token** displayed
   - Tap **"Start Tethering"**

3. **Connect from Linux Desktop:**
   ```bash
   # Use the Bluetooth address and token from Android app
   sudo etphonehome bluetooth --address AA:BB:CC:DD:EE:FF --token AbC123XyZ4567890
   ```

**Note for Linux:** Requires `libdbus-1-dev` for BlueZ D-Bus integration:
```bash
# Ubuntu/Debian
sudo apt install libdbus-1-dev pkg-config

# Fedora
sudo dnf install dbus-devel pkgconf-pkg-config
```

#### Windows Setup

1. **Pair with Android Device (One-Time Setup):**
   - Open Settings → Devices → Bluetooth & other devices
   - Click "Add Bluetooth or other device"
   - Select your Android device
   - Complete pairing

2. **Start Android App in Bluetooth Mode:**
   - Open ET Phone Home
   - Select **"Bluetooth"** mode
   - Ensure Bluetooth is enabled on Android
   - Note the **Bluetooth Address** and **Auth Token** displayed
   - Tap **"Start Tethering"**

3. **Connect from Windows Desktop (as Administrator):**
   ```cmd
   # Use the Bluetooth address and token from Android app
   etphonehome.exe bluetooth --address AA:BB:CC:DD:EE:FF --token AbC123XyZ4567890
   ```

**Note for Windows:** The Bluetooth implementation provides comprehensive guidance for setup. Some configurations may require manual COM port setup via Windows Bluetooth settings.

### Auto-Reconnect (v0.4.0+)

ET Phone Home can automatically reconnect if the connection drops, with exponential backoff retry logic.

#### Basic Usage

```bash
# WiFi with auto-reconnect (infinite retries)
sudo etphonehome wifi --host 192.168.1.100 --token XXX --reconnect

# USB with auto-reconnect
sudo etphonehome usb --reconnect

# Bluetooth with auto-reconnect
sudo etphonehome bluetooth --address AA:BB:CC:DD:EE:FF --token XXX --reconnect
```

#### With Maximum Retries

```bash
# Retry up to 10 times before giving up
sudo etphonehome wifi --host 192.168.1.100 --token XXX --reconnect --max-retries 10
```

#### How It Works

- **Exponential Backoff:** Wait time doubles with each retry (1s, 2s, 4s, 8s...)
- **Maximum Wait:** Capped at 60 seconds between retries
- **Automatic:** Reconnects happen automatically without user intervention
- **Persistent:** With `--reconnect` and no `--max-retries`, will retry forever

**Use Cases:**
- Unstable WiFi connections
- Mobile devices moving between networks
- Temporary connection interruptions
- Background tethering that should stay active

### Connection Profiles (v0.4.0+)

Save frequently used connection configurations for quick access.

#### Creating Profiles

```bash
# Save a WiFi profile
etphonehome save-profile my-home-wifi \
  --type wifi \
  --host 192.168.1.100 \
  --description "Home WiFi connection"

# Save a Bluetooth profile
etphonehome save-profile my-phone-bt \
  --type bluetooth \
  --address AA:BB:CC:DD:EE:FF \
  --description "Phone Bluetooth tethering"

# Save a USB profile (stores default settings)
etphonehome save-profile my-usb \
  --type usb \
  --port 8888 \
  --description "USB connection"
```

#### Using Profiles

```bash
# List all saved profiles
etphonehome list-profiles

# Connect using a profile
sudo etphonehome connect my-home-wifi

# Connect with auto-reconnect
sudo etphonehome connect my-home-wifi --reconnect
```

#### Managing Profiles

```bash
# Delete a profile
etphonehome delete-profile my-old-wifi

# View profile details
etphonehome list-profiles
```

#### Profile Storage

Profiles are stored in:
- **Linux:** `~/.config/etphonehome/config.toml`
- **Windows:** `%APPDATA%\etphonehome\config.toml`

You can edit this file manually for advanced configuration.

#### Example Profile Configuration

```toml
[default]
port = 8888
interface = "etph0"
mtu = 1500

[[profiles]]
name = "home-wifi"
type = "wifi"
description = "Home WiFi connection"
host = "192.168.1.100"

[[profiles]]
name = "work-bt"
type = "bluetooth"
description = "Work phone Bluetooth"
address = "AA:BB:CC:DD:EE:FF"

[auth]
# Tokens can be stored in profiles (optional)
# wifi_token = "stored_token_here"
# bluetooth_token = "stored_token_here"
```

**Security Note:** Tokens stored in config files are in plain text. For maximum security, provide tokens via command line instead of storing them in profiles.

## Verifying Connection

Once connected, you should see:

```
[INFO] Connected successfully
[INFO] Handshake successful
[INFO] TUN interface created successfully
[INFO] Tethering connection established!
[INFO] You can now use your Android device's internet connection.
```

To verify internet connectivity:

```bash
# Linux/macOS
ping -c 4 8.8.8.8

# Windows
ping 8.8.8.8
```

## Monitoring

### Desktop Logs

The desktop client outputs logs to console:

```
[INFO] Stats: sent=1048576 recv=2097152 uptime=120s
```

### Android App

The Android app shows statistics on the main screen:
- Connection status
- Bytes sent/received
- Connection duration

## Stopping the Connection

### On Desktop
Press `Ctrl+C` to stop the client

### On Android
Tap "Stop Tethering" in the app

## Troubleshooting

### Connection Issues

**Problem:** "Failed to connect to Android device"
- Check that the Android app is running and tethering is started
- Verify ADB forwarding is set up (for USB): `adb reverse --list`
- Check firewall settings

**Problem:** "Failed to create TUN interface"
- **Linux:** Ensure you're running with `sudo`
- **Linux:** Load TUN module: `sudo modprobe tun`
- **Windows:** Install TAP-Windows adapter (comes with OpenVPN)
- **Windows:** Run as Administrator

**Problem:** "VPN permission required" on Android
- Grant VPN permission when prompted
- If missed, restart the app

### Performance Issues

**Problem:** Slow connection speeds
- USB tethering is generally faster than WiFi
- Check your Android device's actual internet speed
- Reduce MTU if experiencing packet loss: `--mtu 1400`

**Problem:** High latency
- WiFi tethering may have higher latency than USB
- Bluetooth tethering has the highest latency
- Check for interference on WiFi channel

### Routing Issues

**Problem:** Can't access internet after connecting
- **Linux:** Check routing table: `ip route show`
- **Windows:** Check routing table: `route print`
- Ensure default route is through the TUN interface

**Problem:** DNS not working
- **Linux:** Check `/etc/resolv.conf`
- **Windows:** Check network adapter DNS settings
- Android app provides Google DNS (8.8.8.8) by default

## Advanced Configuration

### Custom Port

```bash
# Use a different port
etphonehome usb --port 9999
```

### Custom Interface Name

```bash
# Use a custom TUN interface name
etphonehome usb --interface mytun
```

### Multiple Connections

You can run multiple instances with different interface names and ports (though this is rarely needed).

## Root Mode (Advanced)

When root is available on Android, ET Phone Home can use more efficient routing via iptables instead of the VPN API.

**Note:** Root mode is not yet fully implemented. The current version uses VPN API which works without root.

## Uninstalling

### Android App
1. Go to Settings → Apps
2. Find "ET Phone Home"
3. Tap "Uninstall"

### Desktop Client
```bash
# Linux (if installed system-wide)
sudo rm /usr/local/bin/etphonehome

# Windows
# Delete etphonehome.exe from wherever you placed it
```

### Cleanup ADB Forwarding
```bash
adb reverse --remove tcp:8888
```

## Roadmap

### Completed (v0.4.0)
- [x] WiFi tethering with automatic IP discovery
- [x] Multi-threaded async I/O for improved performance
- [x] Bluetooth tethering (full stack - Android + Desktop)
- [x] Complete Bluetooth desktop client (Linux BlueZ & Windows)
- [x] Auto-reconnect on connection drop with exponential backoff
- [x] WiFi/Bluetooth token authentication
- [x] mDNS service discovery for automatic device finding
- [x] Connection profiles with TOML configuration

### Planned for v0.5.0
- [ ] TLS encryption for WiFi/Bluetooth modes — crypto/PSK code exists on
      both sides but is not yet wired into the live connection (still
      plaintext on the wire; top engineering priority)
- [x] Desktop GUI application (implemented, Linux release build; Windows
      GUI release build pending)
- [ ] Traffic statistics and graphs
- [x] Root mode with iptables routing for better performance (Android)
- [x] Advanced routing and traffic filtering rules (enforced on Android and
      desktop)

### Future Versions (v1.0.0+)
- [ ] Multiple device support
- [ ] Data usage limits and alerts
- [ ] Traffic compression for bandwidth optimization
- [ ] Custom DNS server configuration
- [ ] Mobile data usage tracking

## Getting Help

If you encounter issues:

1. Check the logs for error messages
2. Review this troubleshooting guide
3. Check [BUILD.md](BUILD.md) for build-related issues
4. See [PROTOCOL.md](../protocol/PROTOCOL.md) for technical details

## Security Considerations

- **USB Mode:** Secure - traffic is local to your USB connection, no network exposure
- **WiFi Mode:** Secured with token authentication (v0.4.0+) - random 16-character tokens prevent unauthorized access
- **Bluetooth Mode:** Secured with token authentication (v0.4.0+) - paired devices with valid tokens only
- **Token Authentication:** WiFi and Bluetooth modes require authentication tokens, displayed in the Android app. Tokens are randomly generated and stored securely.
- **Token Storage:** Tokens can be stored in config files for convenience, but are stored in plain text. For maximum security, provide tokens via command line.
- **Network Security:** For additional security, consider:
  - Using private WiFi networks only
  - Enabling WiFi encryption (WPA2/WPA3)
  - Future: TLS encryption (planned for v0.5.0)
- **VPN Permission:** The Android app requires VPN permission to capture network traffic. This is standard for tethering apps.
- **No Telemetry:** ET Phone Home does not collect any usage data or telemetry

## Tips and Best Practices

1. **Battery Life:** Tethering uses significant battery. Keep your Android device charged.
2. **Data Usage:** Monitor your data usage if you have limited mobile data.
3. **Connection Stability:** USB provides the most stable connection.
4. **Firewall:** Add firewall exceptions for the ET Phone Home client if needed.
5. **Auto-start ADB:** Consider adding ADB forwarding to your startup scripts for convenience.

## Example Startup Script (Linux)

Create `/usr/local/bin/start-tethering.sh`:

```bash
#!/bin/bash

# Set up ADB forwarding
adb reverse tcp:8888 tcp:8888

# Wait a moment
sleep 1

# Start tethering
sudo etphonehome usb

# Cleanup on exit
adb reverse --remove tcp:8888
```

Make it executable:
```bash
chmod +x /usr/local/bin/start-tethering.sh
```
