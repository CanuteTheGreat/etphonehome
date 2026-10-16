# ET Phone Home - Frequently Asked Questions

## General Questions

### What is ET Phone Home?

ET Phone Home is an open-source tethering solution that allows you to share your Android device's internet connection with your Windows or Linux computer. It's designed as a free, open-source alternative to commercial solutions like PDANet and EasyTether.

### How is this different from PDANet/EasyTether?

**Similarities:**
- USB tethering without root
- Uses VPN API for packet capture
- Cross-platform desktop support

**Differences:**
- **Open Source:** Full source code available
- **Free:** No paid features or subscriptions
- **Modern:** Built with modern languages (Kotlin, Rust)
- **Extensible:** Easy to modify and contribute to

### Does it require root access?

No! ET Phone Home works without root using Android's VPN API. However, root support is planned for better performance when available.

### What platforms are supported?

**Android:** API Level 21+ (Android 5.0+)
**Desktop:**
- Linux (tested on Ubuntu, Debian, Fedora)
- Windows 10/11

### Is it safe to use?

Yes, the app uses standard Android APIs and doesn't require any dangerous permissions beyond what's necessary for VPN functionality. Being open source, you can review the code yourself.

## Connection Methods

### Which connection method should I use?

**USB (Recommended):**
- Most reliable
- Fastest speeds
- Best battery life on phone
- Requires USB cable and ADB setup

**WiFi:**
- Wireless convenience
- No cable needed
- Slightly more latency
- Uses more battery
- Token authentication for security (v0.4.0+)
- mDNS auto-discovery (v0.4.0+)

**Bluetooth:**
- Lowest power consumption
- Wireless convenience
- Slowest speeds (typical Bluetooth limitations)
- Token authentication for security (v0.4.0+)
- Full RFCOMM implementation (v0.4.0+)

### Can I use multiple connection methods simultaneously?

Not currently. You can only use one connection method at a time. Future versions may support this.

### Does USB tethering work on all Android phones?

USB tethering should work on most Android devices with API level 21+. However:
- Some manufacturers may restrict VPN API usage
- USB debugging must be enabled
- Some phones may require specific USB drivers

## Setup and Configuration

### Do I need to install ADB?

Yes, for USB tethering. ADB (Android Debug Bridge) is required to set up port forwarding between your Android device and computer.

**Install ADB:**
- Linux: `sudo apt-get install android-tools-adb`
- Windows: Download Android Platform Tools
- macOS: `brew install android-platform-tools`

### What is "ADB reverse port forwarding"?

ADB reverse forwarding creates a tunnel that allows your Android device to connect to a service running on your computer as if it were local. This enables USB tethering without complex networking setup.

### Why do I need VPN permission?

The VPN permission allows the app to capture all network traffic from your Android device and route it through the tethering connection. This is how non-root tethering works on Android.

### Do I need admin/root on my computer?

Yes, creating TUN/TAP network interfaces requires administrator/root privileges:
- **Linux:** Run with `sudo`
- **Windows:** Run as Administrator

### Can I run it without admin rights?

Unfortunately, no. Creating virtual network interfaces is a privileged operation on all operating systems.

## v0.4.0 Features

### What is token authentication?

Token authentication (introduced in v0.4.0) secures WiFi and Bluetooth connections with randomly generated 16-character tokens.

**How it works:**
1. Android app generates a unique token for WiFi and Bluetooth
2. Token is displayed in the app when you select WiFi or Bluetooth mode
3. You provide this token when connecting from desktop: `--token AbC123XyZ`
4. Connection is rejected if token doesn't match

**Security notes:**
- USB mode doesn't need tokens (already secure via local connection)
- Tokens persist across app restarts
- Each connection mode has its own token
- Prevents unauthorized connections on WiFi/Bluetooth

### How do I find my connection token?

**On Android:**
1. Open ET Phone Home app
2. Select WiFi or Bluetooth mode
3. Token is displayed on screen along with connection command
4. Token looks like: `AbC123XyZ4567890`

**On Desktop:**
Use the `--token` parameter:
```bash
sudo etphonehome wifi --host 192.168.1.100 --token AbC123XyZ4567890
```

### What is mDNS discovery?

mDNS (Multicast DNS) discovery allows automatic device finding on your local network.

**Without mDNS (old way):**
- Find Android IP manually
- Type IP address in command

**With mDNS (v0.4.0+):**
```bash
sudo etphonehome discover-wifi
# Shows all ET Phone Home devices on network with IPs
```

The Android app automatically advertises itself when WiFi mode is active.

### What are connection profiles?

Connection profiles (v0.4.0+) let you save frequently used connections for quick access.

**Create a profile:**
```bash
etphonehome save-profile home-wifi \
  --type wifi \
  --host 192.168.1.100 \
  --description "Home WiFi"
```

**Use a profile:**
```bash
sudo etphonehome connect home-wifi
```

**Benefits:**
- No need to remember IPs or parameters
- Quickly switch between saved connections
- Store default settings
- Cross-platform config files (TOML)

### What is auto-reconnect?

Auto-reconnect (v0.4.0+) automatically retries if connection drops.

**Enable auto-reconnect:**
```bash
sudo etphonehome wifi --host 192.168.1.100 --token XXX --reconnect
```

**How it works:**
- Uses exponential backoff (2s, 4s, 8s, 16s, 32s, 60s...)
- Maximum wait capped at 60 seconds
- Retries forever by default
- Limit retries with `--max-retries 10`

**Use cases:**
- Unstable WiFi connections
- Mobile device moving between networks
- Background tethering that should stay active

### Can I regenerate my tokens?

Currently, tokens are automatically generated on first use and persist. Manual regeneration is not yet implemented but is planned for a future release.

**Workaround:**
Clear the app's data to generate new tokens (Settings → Apps → ET Phone Home → Clear Data).

## Performance

### What speeds can I expect?

Speeds depend on several factors:
- Your mobile data connection speed
- Connection method (USB is fastest)
- Phone CPU and USB controller
- Amount of other traffic on the phone

Typical results:
- **USB:** 80-95% of mobile connection speed
- **WiFi:** 70-85% of mobile connection speed

### Why is it slower than native tethering?

Using the VPN API adds some overhead compared to root-based iptables routing. However, the difference is usually minimal (5-15%).

### How much battery does it use?

Tethering uses significant battery because:
- Mobile data is active
- VPN service is running
- CPU is processing packets

**Tips to reduce battery usage:**
- Use USB connection (phone charges while tethering)
- Reduce screen brightness on phone
- Close unnecessary apps on phone

### Can I improve performance?

**Try these optimizations:**
1. Use USB instead of WiFi
2. Ensure phone has good signal strength
3. Close background apps on phone
4. Use a good quality USB cable (for USB tethering)
5. Reduce MTU if experiencing packet loss

## Troubleshooting

### "Failed to create TUN interface"

**Linux:**
```bash
# Load TUN module
sudo modprobe tun

# Check if loaded
lsmod | grep tun
```

**Windows:**
- Put `wintun.dll` (https://www.wintun.net) next to `etphonehome.exe`
- Run as Administrator

### "Failed to connect to Android device"

**Check these:**
1. Android app is running and tethering started
2. VPN permission granted on Android
3. ADB forwarding is set up: `adb reverse --list`
4. Firewall isn't blocking the connection
5. Android device is still connected (USB) or reachable (WiFi)

### "ADB device unauthorized"

**Solution:**
1. Check Android screen for authorization prompt
2. Accept USB debugging authorization
3. Check "Always allow from this computer"
4. Reconnect USB cable if needed

### Connection drops frequently

**Possible causes:**
1. **USB cable:** Try a different cable
2. **USB power saving:** Disable USB selective suspend
3. **Phone power saving:** Disable battery optimization for the app
4. **Signal:** Check mobile signal strength

### Internet works but DNS doesn't resolve

**Linux:**
```bash
# Check DNS settings
cat /etc/resolv.conf

# Manually set DNS
sudo sh -c 'echo "nameserver 8.8.8.8" > /etc/resolv.conf'
```

**Windows:**
- Check network adapter DNS settings
- Manually set to 8.8.8.8 (Google DNS)

### High latency/ping times

**Normal latency ranges:**
- Mobile network: 30-100ms base latency
- USB tethering adds: 5-15ms
- WiFi tethering adds: 10-25ms
- Bluetooth tethering adds: 20-40ms

**If latency is excessive:**
1. Check mobile signal strength
2. Try different connection method
3. Check for background apps using network
4. Test without tethering to isolate issue

### "Authentication failed: invalid token" (v0.4.0+)

**This error means the token doesn't match.**

**Solution:**
1. Check Android app for current token
2. Copy token exactly (case-sensitive, 16 characters)
3. Ensure you're using the correct token (WiFi vs Bluetooth)
4. Try regenerating tokens (clear app data if needed)

Example of correct usage:
```bash
# WiFi token from Android app: AbC123XyZ4567890
sudo etphonehome wifi --host 192.168.1.100 --token AbC123XyZ4567890
```

### mDNS discovery doesn't find my device (v0.4.0+)

**Possible causes:**
1. **Different subnets:** mDNS only works within the same subnet
2. **Firewall:** Some firewalls block mDNS (port 5353)
3. **WiFi mode not active:** Device must be in WiFi mode and tethering started
4. **Network configuration:** Some routers block multicast traffic

**Solutions:**
- Ensure both devices are on the same WiFi network
- Check firewall settings (allow UDP port 5353)
- Use manual IP discovery as fallback: `etphonehome discover-ip`
- Manually specify IP: `--host 192.168.1.100`

### Bluetooth connection fails (v0.4.0+)

**Linux (BlueZ) Issues:**

**Problem:** "Failed to connect to BlueZ"
- Install BlueZ: `sudo apt-get install bluez`
- Start Bluetooth service: `sudo systemctl start bluetooth`
- Check status: `sudo systemctl status bluetooth`

**Problem:** "Device not paired"
- Pair via bluetoothctl: `bluetoothctl pair AA:BB:CC:DD:EE:FF`
- Trust device: `bluetoothctl trust AA:BB:CC:DD:EE:FF`

**Problem:** "libdbus-1.so not found"
- Install D-Bus libraries: `sudo apt-get install libdbus-1-dev`
- Rebuild desktop client

**Windows Issues:**

**Problem:** "Bluetooth not available"
- Enable Bluetooth in Windows Settings
- Ensure Bluetooth adapter is working

**Problem:** "Device not paired"
- Pair via Settings → Devices → Bluetooth
- Ensure device shows as "Paired" not just "Connected"

### Connection profile not found (v0.4.0+)

**Problem:** "Profile 'name' not found"

**Solution:**
1. List profiles: `etphonehome list-profiles`
2. Check profile name (case-sensitive)
3. Create profile if needed: `etphonehome save-profile ...`

**Profile location:**
- Linux: `~/.config/etphonehome/config.toml`
- Windows: `%APPDATA%\etphonehome\config.toml`

### Auto-reconnect keeps retrying forever (v0.4.0+)

**This is the default behavior.**

**To limit retries:**
```bash
sudo etphonehome wifi --host 192.168.1.100 --reconnect --max-retries 5
```

**To stop reconnecting:**
Press `Ctrl+C` to terminate the client.

## Security and Privacy

### Can my carrier detect I'm tethering?

The app uses VPN API which may be detectable by carriers who analyze TTL (Time To Live) values or other packet characteristics. However:
- Most carriers don't actively monitor
- VPN API makes detection harder than traditional tethering
- Encryption isn't currently implemented but is planned

### Is my data encrypted?

**USB:** Data is local to USB connection (secure, no network exposure)
**WiFi:** Connection is authenticated with tokens (v0.4.0+) but not encrypted. TLS encryption planned for v0.5.0
**Bluetooth:** Connection is authenticated with tokens (v0.4.0+) but not encrypted. TLS encryption planned for v0.5.0

**Token Authentication vs. Encryption:**
- **Authentication:** Prevents unauthorized devices from connecting (implemented in v0.4.0)
- **Encryption:** Protects data in transit from eavesdropping (planned for v0.5.0)

For maximum security over WiFi/Bluetooth:
- Use only on trusted networks
- Use HTTPS for all web traffic
- Wait for TLS encryption in v0.5.0
- Use USB mode for maximum security

### What data does the app collect?

None. The app:
- Doesn't collect analytics
- Doesn't send telemetry
- Doesn't track usage
- Is fully open source

### Can I use this on public WiFi?

USB tethering is always safe. WiFi tethering on public networks should be avoided unless encryption is implemented.

## Development

### Can I contribute?

Absolutely! Contributions are welcome:
1. Fork the repository
2. Make your changes
3. Submit a pull request

See [DEVELOPMENT.md](DEVELOPMENT.md) for details.

### What features are planned?

**Completed (v0.5.0):**
- ✅ Bluetooth tethering (Android + Linux desktop; Windows desktop Bluetooth
  is not implemented — the client fails with a clear error and points you
  at WiFi mode instead)
- ✅ Auto-reconnect with exponential backoff
- ✅ Token authentication for WiFi/Bluetooth
- ✅ mDNS service discovery
- ✅ Connection profiles
- ✅ Desktop GUI application (Linux release build; Windows GUI release
  pending verification)
- ✅ Root mode (Android iptables routing)
- ✅ Traffic filtering, enforced on both Android and desktop clients

**Not yet done:**
- TLS encryption for WiFi/Bluetooth — the PSK/crypto code exists on both
  sides but isn't wired into the live connection yet, so traffic is still
  plaintext on the wire. This is the top engineering priority right now.
- Traffic statistics and graphs beyond basic bytes sent/received

**Long term (v1.0.0+):**
- Multi-device support
- Data usage limits and alerts
- Traffic compression
- Custom DNS configuration
- Mobile data usage tracking

### How can I report bugs?

Create an issue on GitHub with:
1. Description of the problem
2. Steps to reproduce
3. Your device/OS information
4. Logs if possible

### Can I use this commercially?

Check the LICENSE file for details. Generally:
- Free to use personally
- Check license for commercial use
- Attribution required
- No warranty provided

## Comparison with Other Solutions

### vs. Native Android Tethering

**Advantages of ET Phone Home:**
- Works when carrier blocks tethering
- More control over routing
- Open source

**Advantages of Native:**
- No VPN permission needed
- Slightly better performance
- No extra software required

### vs. PDANet

**Advantages of ET Phone Home:**
- Free and open source
- No paid features
- Modern codebase
- Token authentication for security (v0.4.0+)
- Auto-reconnect with exponential backoff (v0.4.0+)
- Connection profiles for quick access (v0.4.0+)
- mDNS auto-discovery (v0.4.0+)
- All three modes: USB, WiFi, Bluetooth (v0.4.0+)

**Advantages of PDANet:**
- More mature (longer development history)
- Commercial support
- Larger user base

### vs. EasyTether

**Advantages of ET Phone Home:**
- Open source
- Free (no paid version needed)
- Active development
- Modern feature set (token auth, auto-reconnect, profiles, mDNS)
- Bluetooth support (EasyTether doesn't have this)
- All features in free version

**Advantages of EasyTether:**
- Proven reliability (longer track record)
- Extensive device support
- Commercial support

## Advanced Usage

### Can I use custom DNS servers?

Currently, the app uses Google DNS (8.8.8.8). Custom DNS support is planned for a future release.

### Can I route only specific traffic?

Split tunneling is not currently supported but is on the roadmap.

### Can I tether multiple computers?

Not currently. Each tethering session supports one computer. Multi-device support is planned.

### Can I monitor bandwidth usage?

The app shows basic statistics (bytes sent/received). More detailed monitoring is planned.

### Can I set data limits?

Not currently. This feature is on the roadmap.

## Getting Help

### Where can I get support?

1. Check this FAQ
2. Read [SETUP.md](SETUP.md) for setup issues
3. Read [BUILD.md](BUILD.md) for build issues
4. Check existing GitHub issues
5. Create a new issue if needed

### How do I enable debug logging?

**Android:**
```bash
adb logcat -s ETPhoneHome:V
```

**Desktop (Linux):**
```bash
RUST_LOG=debug sudo etphonehome usb
```

**Desktop (Windows):**
```powershell
$env:RUST_LOG="debug"
.\etphonehome.exe usb
```

### The quick start script doesn't work

**Linux:**
```bash
# Make sure it's executable
chmod +x scripts/quick-start-linux.sh

# Run as root
sudo ./scripts/quick-start-linux.sh
```

**Windows:**
```powershell
# Run as Administrator
# Enable script execution if needed:
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
```

## Miscellaneous

### Why "ET Phone Home"?

It's a playful reference to the movie E.T., where the alien wants to "phone home." The app helps your computer "phone home" using your Android's connection.

### Is this project actively maintained?

Yes! The project is under active development. Check the repository for recent commits and releases.

### Can I donate/support the project?

ET Phone Home is built and maintained by volunteers. Money and hardware
donations directly fund development time, CI infrastructure, and test
hardware across the range of Android devices and desktop platforms this
project supports.

Currently, the best ways to support the project are:
1. Financial or hardware donations via [GitHub Sponsors](https://github.com/sponsors/CanuteTheGreat) or by opening an issue to discuss hardware donations
2. Star the repository
3. Report bugs
4. Contribute code
5. Spread the word

See the [project homepage](index.html#donors) for the full donor list and details.

### Will there be a macOS version?

macOS support is possible and may be added in the future, though it's not currently a priority.
