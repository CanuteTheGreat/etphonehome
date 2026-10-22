# ET Phone Home - Frequently Asked Questions

## General Questions

### What is ET Phone Home?

ET Phone Home is an open-source tethering solution that allows you to share your Android device's internet connection with your Windows or Linux computer. It's designed as a free, open-source alternative to commercial solutions like PDANet and EasyTether.

### How is this different from PDANet/EasyTether?

**Similarities:**
- USB tethering without root
- The computer's connections are re-opened by the phone app itself (a userspace relay, like EasyTether's NAT), so no root is needed
- Cross-platform desktop support

**Differences:**
- **Open Source:** Full source code available
- **Free:** No paid features or subscriptions
- **Modern:** Built with modern languages (Kotlin, Rust)
- **Extensible:** Easy to modify and contribute to

### Does it require root access?

No. The app relays the computer's traffic through ordinary app sockets on the phone, which needs no root and no VPN. The phone keeps its own internet while tethering. (An old, non-working root mode was removed in v0.6.0.)

### What platforms are supported?

**Android:** API Level 26+ (Android 8.0+)
**Desktop:**
- Linux (tested on Ubuntu, Debian, Fedora)
- Windows 10/11

### Is it safe to use?

Yes. The app needs no root and no VPN permission: it only opens network sockets like any app, and never touches the phone's own traffic. Being open source, you can review the code yourself.

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
- Token authentication, and TLS 1.3 encryption by default (v0.6.0+)
- mDNS auto-discovery (v0.4.0+)

**Bluetooth:**
- Lowest power consumption
- Wireless convenience
- Slowest speeds (typical Bluetooth limitations)
- Token authentication for security (v0.4.0+)
- RFCOMM; Linux desktop only (the Windows client has no Bluetooth)

### Can I use multiple connection methods simultaneously?

Not currently. You can only use one connection method at a time. Future versions may support this.

### Does USB tethering work on all Android phones?

USB tethering should work on most Android devices with API level 21+. However:
- USB debugging must be enabled
- Some phones may require specific USB drivers

## Setup and Configuration

### Do I need to install ADB?

Yes, for USB tethering. ADB (Android Debug Bridge) is required to set up port forwarding between your Android device and computer.

**Install ADB:**
- Linux: `sudo apt-get install android-tools-adb`
- Windows: Download Android Platform Tools
- macOS: `brew install android-platform-tools`

### What is "ADB port forwarding"?

`adb forward tcp:8888 tcp:8888` makes connections to port 8888 on your computer travel over the USB cable to port 8888 on the phone, where the app's USB-mode server listens (on the phone's loopback only). The desktop client sets this up for you; it is `adb forward`, not `adb reverse` (which points the other way and conflicts with the app's port).

### Does the app need VPN permission?

No (since v0.6.0). Earlier versions set up a phone VPN that captured the phone's own traffic, which cut the phone off and never got the computer online. The app now relays the computer's connections through ordinary sockets, so the phone keeps its internet.

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

Every connection is relayed by the app in user space (the phone's kernel doesn't forward the packets itself), which costs some CPU and latency compared with native tethering.

### How much battery does it use?

Tethering uses significant battery because:
- Mobile data is active
- The relay is running (the app holds a wake lock while tethering)
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
2. The app shows "Waiting for desktop" (WiFi: the token is right; the app requires TLS unless you turned "Require encryption" off)
3. ADB forwarding is set up: `adb forward --list`
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

The client sets DNS for the tunnel to the server the phone announces (8.8.8.8): `resolvectl` on Linux with systemd-resolved, `netsh` on Windows. Without systemd-resolved it prints a warning; then:

**Linux:**
```bash
sudo sh -c 'echo "nameserver 8.8.8.8" > /etc/resolv.conf'
```

**Windows:**
- Check the TUN adapter's DNS settings (should be 8.8.8.8)

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
- Only clients before 0.6.1 load the system libdbus; from 0.6.1 it is built in. Update the client, or install `libdbus-1-3` for an old one

**Windows:** the Windows client has no Bluetooth support; use USB or WiFi.

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

The computer's connections leave the phone from the app's own sockets, so they look like the phone's app traffic (the phone sets TTL and other packet fields), not like hotspot traffic. Carriers can still use other signals (traffic patterns, user-agents); nothing can promise to be undetectable.

### Is my data encrypted?

**USB:** the app's port is on the phone's loopback, reachable only through `adb forward` over the cable.
**WiFi:** yes, by default (v0.6.0+): TLS 1.3 where both sides derive an Ed25519 key from the pairing token and accept only that key. A wrong token fails the handshake; there is no plaintext fallback. The app refuses unencrypted WiFi connections unless you switch "Require encryption" off (then the desktop needs `--no-tls`).
**Bluetooth:** token-authenticated over a paired RFCOMM link (Bluetooth link encryption); no extra TLS layer.

### What data does the app collect?

None. The app:
- Doesn't collect analytics
- Doesn't send telemetry
- Doesn't track usage
- Is fully open source

### Can I use this on public WiFi?

USB is the safest. WiFi mode is encrypted and token-pinned by default, but on untrusted networks keep "Require encryption" on.

## Development

### Can I contribute?

Absolutely! Contributions are welcome:
1. Fork the repository
2. Make your changes
3. Submit a pull request

See [DEVELOPMENT.md](DEVELOPMENT.md) for details.

### What features are planned?

**Done (v0.6.0):**
- ✅ Real internet for the computer without root (userspace relay); the phone keeps its own internet
- ✅ USB, WiFi and Bluetooth tethering (Bluetooth: Android + Linux desktop only)
- ✅ WiFi encrypted by default (TLS 1.3 pinned to the pairing token)
- ✅ Live status and statistics on the phone; notification with Stop
- ✅ Traffic filtering on the desktop and on the phone (profile rules are sent to the phone)
- ✅ DNS set up on the desktop
- ✅ Auto-reconnect, token authentication, mDNS discovery, connection profiles
- ✅ Desktop GUI (Linux build)

**Not done:**
- Root mode (kernel NAT on rooted phones) - removed in v0.6.0 until it is done properly
- Bluetooth in the Windows client, macOS client, Windows GUI build
- IPv6 through the tunnel
- Token regeneration button (clear the app's data for now)
- Multi-device support, data usage limits, mobile data usage tracking

### How can I report bugs?

Create an issue on the [issue tracker](https://github.com/CanuteTheGreat/etphonehome/issues) with:
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
- Kernel forwarding (slightly better performance)
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
- Bluetooth support on Linux (EasyTether doesn't have this)
- All features in free version

**Advantages of EasyTether:**
- Proven reliability (longer track record)
- Extensive device support
- Commercial support

## Advanced Usage

### Can I use custom DNS servers?

The phone announces Google DNS (8.8.8.8) and the desktop client points DNS for the tunnel at it. A custom DNS server option is planned.

### Can I route only specific traffic?

Split tunneling is not currently supported but is on the roadmap.

### Can I tether multiple computers?

Not currently. Each tethering session supports one computer. Multi-device support is planned.

### Can I monitor bandwidth usage?

The phone shows the connected desktop, data each way, open and total connections, uptime and filter state, live. More detailed monitoring is planned.

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
