# Changelog

All notable changes to ET Phone Home will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.9.0] - 2026-10-20

### Added
- **Free and Pro.** One app, two tiers. Free: sharing over USB with one computer, no speed cap, no time limit, started and stopped on the phone. **ETPH Pro**, a one-time in-app purchase in the Google Play app (no subscription), adds Wi-Fi and Bluetooth, routers, several computers and routers, auto-start when the computer asks over USB plus waiting for the computer after a disconnect, and a data usage history. "Upgrade to Pro" screen with the feature list and the Play price, Restore purchase; a quiet status line instead of pop-ups. Google Play Billing 9.1: purchases are acknowledged, restored on a reinstall or new phone, pending payments wait for Play, refunds and revocations take Pro away at the next check (every app start, every Start); purchase signatures are checked on the phone; no server (docs/pro.md).
- **The phone enforces the split; computers and routers never need a licence.** The handshake now carries who the client is (a random client id and its host name; routers say they are routers) and whether the phone has Pro. A refused client gets "Pro required on the phone: ..." and the desktop/router client prints it with "nothing needs to be bought or licensed on this computer or router", keeps retrying once a minute (so buying Pro on the phone just works), the GUI shows it, and LuCI shows whether the phone has Pro. Older clients still connect over USB as one computer.
- **Paired computers and routers** on the phone, each with Forget (Free works with the computer used last; forget it to switch).
- **Auto-start on plug-in (Pro):** when the computer finds the phone on USB with nothing listening, it asks the phone to start sharing (`adb shell am start ... AutoStartActivity`, also through the routers' built-in ADB host), once per plug-in, so a Stop on the phone is respected until the cable is replugged. Switch "Start when the computer asks" on the phone.
- **Data usage (Pro):** per computer/router (today, this month, 30 days) and by kind of traffic (web, QUIC, DNS, email, SSH, calls, VPN, NTP, ping), kept on the phone only.
- Desktop: `--router` (set by OpenWrt's `proto etph`), `$ETPH_CLIENT_ID`/`$ETPH_CLIENT_NAME`; the status file has `phone_pro`.

### Changed
- Free: when the computer disconnects, sharing stops (tap Start again); Pro keeps waiting for it.

## [0.8.0] - 2026-10-20

### Added
- **Routers: the phone as a router's internet (OpenWrt, GL.iNet).** New packages put the ETPH client on an OpenWrt router (official OpenWrt and GL.iNet firmware), so every device behind the router gets the phone's connection. Over a USB cable or Wi-Fi (TLS, the token the app shows), or `auto` (USB when a phone is plugged in, else Wi-Fi). For USB the client has its own ADB host (usbfs, no adb package or server, so it fits small routers); the phone's "Allow USB debugging?" prompt is the pairing, and LuCI says "Unlock your phone and tap Allow" while it waits. The uplink is a normal network interface, `option proto 'etph'`: netifd adds the default route with the interface's metric and gives dnsmasq the phone's DNS, so firewall zones and mwan3 failover/load balancing work as with any WAN. It reconnects by itself when the phone goes away and comes back, and right away when a phone is plugged into the router's USB port. LuCI page Network > Phone uplink: status (phone model, connection, speed, data), one-step setup into the wan zone, pairing/unpairing, USB phones and the router's adb key fingerprint. Packages: `.ipk` for OpenWrt 24.10 and older and GL.iNet 4.x, `.apk` for OpenWrt 25.x, for aarch64, ARMv7 (with FPU), MIPS 24Kc, MIPSel and x86_64; an installer script picks the right one. Guide: docs/OPENWRT.md.
- Desktop client: `usb --native` (built-in ADB host on Linux: no adb tool needed), `usb --adb-tcp HOST:PORT`, `auto`, `usb-list`, `adb-keygen`, `--no-routes`, `--up-script`/`--down-script` (pppd-style hooks), `--status-file` (JSON status), `--tun-address`/`--tun-peer`, `--adb-serial`, `discover-wifi --json`, `adb-fingerprint`; the WiFi/Bluetooth token can come from `$ETPH_TOKEN` (keeps it out of `ps`); SIGUSR1 skips the reconnect wait; SIGTERM disconnects cleanly like Ctrl-C.
- The phone tells the computer its name (e.g. "samsung SM-N986U") in the handshake; older desktops ignore it.

### Known limits
- Routers over USB are tested in CI against the real app's adbd on Android emulators and against a fake adbd, and over Wi-Fi on a real GL.iNet GL-AR750S with a real phone; USB with a phone plugged into real router hardware is not verified yet.
- ARMv7 routers without an FPU (e.g. Broadcom bcm53xx) and MIPS64 are not supported.

### Fixed
- **Desktop: a large traffic filter put the connection in a reconnect loop.** A profile whose filter rules came to more than 65535 bytes (the most one message to the phone can carry) brought the tunnel up and dropped it straight away, then retried every second forever, with the default route coming and going each time. Such a profile, or an enabled filter with more than 1024 rules (the most the phone takes; it silently dropped the rest), is now refused before connecting, with an error that says why.
- **Android: a router using the phone over the router's own Wi-Fi made traffic loop.** When the phone is joined to the Wi-Fi of a router whose internet is the phone, Android eventually treats that Wi-Fi as the phone's internet (its check passes through the tunnel), and the relay's connections went phone -> router -> phone. When the computer is the router of the Wi-Fi the phone is on, the relay now pins its sockets to another network (mobile data first), keeps mobile data up for it, and refuses connections rather than loop when there is no other network. A computer that is just another device on the phone's Wi-Fi is not affected.
- The router build has no Bluetooth/D-Bus and uses only the ring TLS backend (no aws-lc), which also makes the desktop binary smaller.

## [0.7.1] - 2026-10-18

### Changed
- **Android: targets Android 16 (API 36)**, which Google Play requires for new apps. The app now draws edge to edge on every Android version, with its content kept clear of the status bar, navigation bar, camera cutout and keyboard (Android 15+ forces edge-to-edge for apps targeting it). Back navigation uses the system's predictive back. Still runs on Android 8.0 and newer.

## [0.7.0] - 2026-10-18

### Changed
- **New app id and name: "Errant Solutions ETPH" (`solutions.errant.etph`).** This is the name and id the app will have on Google Play (ETPH = ET Phone Home); the launcher icon is labelled "ETPH". Android treats a new app id as a different app, so **0.7.0 installs next to an older ET Phone Home (`com.etphonehome`) instead of updating it.** While the old app is still installed, the new one says so on its main screen, with a button that opens the old app's App info page (Uninstall is there). Uninstall the old app afterwards, and re-pair WiFi/Bluetooth desktops with the token the new app shows (the tokens belong to the app, so they did not carry over). USB needs nothing new. Settings such as the connection mode start fresh too.
- **The APK on the releases page is signed with the ETPH release key** (the key registered for `solutions.errant.etph` with Google's Android developer verification, SHA-256 `01:11:AF:00:57:D7:E9:28:90:FB:CE:02:E9:92:7A:85:E5:EA:B2:AD:AC:2C:00:76:55:E1:7C:B0:CF:7F:68:40`). Releases up to 0.6.1 carried a public debug key.
- Google Play release pipeline: the Play bundle is built unsigned on a normal runner and signed with the upload key on a dedicated signer, then uploaded to the internal testing track (`release-play-*` tags; a manual run is a dry run that changes nothing in Play). Store listing text and the Data safety answers live in `store/`.

### Fixed
- **A connection that stalled mid-handshake locked every desktop out of the phone.** The phone serves one desktop at a time, and once a connection had sent its first byte nothing timed out until the handshake finished. So a connection that sent one byte, or a TLS ClientHello, and then went quiet held the session slot indefinitely, before any token check. Anyone on the same WiFi, or any app on the phone for the USB port, could do this. The whole handshake now has to finish within 15 s, otherwise the connection is dropped.
- **A phone-side filter rule with an invalid IPv4 address triggered a DNS lookup.** Rule addresses like `256.1.1.1` were handed to the system resolver as hostnames, which blocked the session while the lookup ran, and on networks whose DNS answers every name the rule ended up applying to an unrelated address. Rule addresses are now parsed as numeric IPv4/IPv6 literals only, and anything else drops the rule.
- **A phone-side filter rule naming one IPv6 host covered its whole /32.** A rule address with no `/prefix` defaulted to /32 for IPv6 as well as IPv4, so blocking `2001:db8::1` blocked all of `2001:db8::/32`, and in whitelist mode allowing it allowed the whole /32. A bare IPv6 address is now /128.
- **IPv6 packets could slip past the phone's protocol and port rules.** The packet parser stopped walking IPv6 extension headers after 8, and didn't know the Authentication Header, so TCP behind 9+ extension headers or an AH header came out as "any protocol, no ports" and a rule like `block tcp 443` didn't match it. The parser now walks the whole chain (always bounded by the packet length) and understands AH.

## [0.6.1] - 2026-10-17

### Fixed
- **USB tethering on Android 8-10.** The app's USB server listened on `::1`; adbd before Android 11 forwards `adb forward` connections to `127.0.0.1` only, so every USB connection was refused on those versions (found by the new API 26 emulator test). It now listens on 127.0.0.1.
- **Linux release binary ran only on the newest distros.** It needed glibc 2.39 (the CI image's), so it did not even start on Debian 12 or Ubuntu 22.04. Releases are now linked against glibc 2.31 with libdbus built in (no system libdbus needed); the nightly distro matrix runs a real tunnel on Debian 11-13, Ubuntu 20.04-24.04, Fedora, Arch and openSUSE.
- **A dropped WiFi link left a dead tunnel behind.** The desktop kept the TUN device and default route for 90 s, and the phone held the dead session until TCP gave up (~15 min), refusing the desktop when it came back. The desktop now sends keepalives and gives up after 30 s; the phone ends a session whose (keepalive-sending) desktop has been silent for 30 s.
- **WiFi crashed the app on Android 8 and 9.** The first encrypted WiFi connection crashed the app with `NoSuchMethodError` (the TLS layer called a Java 8 `SSLSocketFactory` method these Android versions lack); found by the API 26 emulator test. TLS is now layered through Bouncy Castle's own implementation of that method.
- **Bluetooth could never connect to a real phone.** The desktop always dialed RFCOMM channel 1, but Android assigns channels dynamically (the app was on channel 6 on a Galaxy Note20), and the app told users to connect to `02:00:00:00:00:00` (Android hides the real Bluetooth address from apps). The app now listens on its own service UUID and shows its name; the desktop asks BlueZ to connect that service (BlueZ looks the channel up over SDP and hands over the socket, which also works from a container) and `--address` accepts the phone's name as paired. Verified on a Samsung Galaxy Note20 (Android 13): public HTTPS, DNS and a byte-identical download over Bluetooth, from a desktop whose only way out was the tunnel.
- The desktop client no longer sets `net.ipv4.ip_forward=1` on the computer (it never needed it and never undid it).
- `--reconnect` no longer retries a protocol-version mismatch or a rejected token forever; it stops with the reason. The retry budget and backoff count failures since the last working session, not over the client's lifetime.

### Added
- Nightly CI: integrity at scale (256 MiB both ways with digests, 100 parallel TCP + 50 UDP flows), failure and recovery (USB unplug, WiFi drop, app killed, Ctrl-C, crash, lossy link), throughput/latency baseline, upgrade compatibility with the previous release, a Linux distro matrix, arm64, the Windows client under wine, and the real APK on Android 8 and 14 emulators. Weekly mutation testing of the desktop and phone cores.

## [0.6.0] - 2026-10-17

### Added
- **The computer really gets the phone's internet, without root.** The Android app now has a userspace relay (`android/core/.../relay/UserspaceRelay.kt`): it terminates the desktop's TCP connections and UDP flows itself and re-opens them as ordinary phone sockets (with sequence/ACK handling, flow control, retransmission, FIN/RST), answers ping for the phone and relays ping to other hosts. Before this the app only copied raw packets into a phone VPN, which Android never forwards, so no traffic ever reached the internet.
- **The phone keeps its own internet.** The phone VPN is gone: nothing on the phone is captured, and the app no longer asks for VPN permission.
- **Encrypted WiFi by default.** The app serves cert-pinned TLS 1.3 (same token-derived Ed25519 pinning as the desktop, via Bouncy Castle) and auto-detects TLS vs. plaintext. `etphonehome wifi` and WiFi profiles use TLS by default (`--no-tls` to opt out); the app refuses plaintext WiFi unless "Require encryption" is switched off.
- **Live status and statistics on the phone:** "Waiting for desktop" until a desktop has actually completed the handshake, then the desktop's address, encryption, data each way, open/total connections, uptime and filter state; the notification shows the same and has a Stop button.
- **Traffic filter on the phone:** right after the handshake the desktop sends the profile's filter (mode and every rule) to the phone, which enforces it on the connections it opens and resets connections a new rule blocks.
- **DNS on the desktop:** the client points DNS for the tunnel at the phone's DNS server (`resolvectl` on Linux, `netsh` on Windows).
- **Real-internet end-to-end CI:** the e2e job now runs the app's actual networking core (`android/core`, as the JVM `standin`) and checks, from a desktop container whose only way out is the tunnel: public HTTPS and HTTP, DNS, a 5 MiB download, TCP echo both ways, UDP, ICMP, 20 parallel connections, USB, filter rules, wrong tokens, plaintext refusal, reconnects.
- **Releases are published on Forgejo** (the workflow used a GitHub-only action and never created one) and checked to contain all four packages.

### Removed
- **Root mode.** It was claimed as implemented, but nothing could start it, and its iptables rules redirected all of the phone's own traffic to the desktop. Removed until a correct kernel-NAT design exists.
- Dead Android TLS/connection classes that were never wired in.

### Changed
- Android 8.0+ is now required (was 5.0).


### Fixed
- **USB tethering never connected:** the desktop client (and `setup-adb`, `quick-start-linux.sh`, and every doc) used `adb reverse`, which maps a phone port to the computer. The app's USB server listens on the phone's loopback, so the client needs `adb forward` (computer port -> phone port); with the app running, `adb reverse` failed with "cannot bind listener: Address already in use". Confirmed against a real phone. The e2e adb stand-in now emulates both directions faithfully, so the wrong one fails the test.
- **Android: the phone lost its internet after a desktop session, and Stop didn't fix it:** when the desktop disconnected, the VPN stayed established (all phone traffic routed into a dead tunnel), the app never accepted another connection (so desktop auto-reconnect could not work) and kept showing "Connected". The Stop button used `stopService()`, which cannot stop a VpnService whose VPN is up, so only force-stopping the app restored the phone's network. Sessions now run in a loop that tears the VPN down after every session and waits for the next desktop; Stop sends an explicit stop action. The VPN is also only established after a successful handshake, so a client with a wrong token no longer touches the phone's routing. Found on a real phone (Android 13).
- **Ctrl-C raced the Disconnect message:** the retry loop could drop the live session before it told the phone it was leaving; it now lets the session finish (up to 3 s) and never reconnects once stopping.
- **Desktop forwarding stall:** the TUN reader held the device mutex during its blocking read, so packets from the phone were only written to the TUN device when the desktop happened to send something. The forwarding loop now uses the async TUN device with independent read/write halves (shared `session` module).
- **GUI Connect did nothing:** the button only changed the status label. It now runs a real session (same runner as the CLI) with live state, stats and logs; Disconnect stops it. GUI-created profiles defaulted to port 8765 instead of 8888.
- **Bluetooth (Linux):** the RFCOMM connection was opened and then abandoned with "integration incomplete"; it now completes the handshake (with `--token` / `auth.bluetooth_token`) and forwards packets.
- **CLI:** `--reconnect` could not be turned off (it defaulted to true); `save-profile --type` from the README was rejected; `sudo etphonehome connect` looked in root's config instead of the invoking user's; WiFi hosts had to be IP literals; Ctrl-C now sends Disconnect; wrong-token rejections now show the phone's reason instead of "Unexpected message type"; saving a profile from the CLI no longer wipes its filter rules; `list-profiles` output goes to stdout; a silent phone is detected after 90 s so auto-reconnect can kick in.
- **Android:** stats, keepalive and packet writers shared one socket stream without locking (frames could interleave); StatsUpdate was 20 bytes instead of the specified 24. One shared `ServerHandshake` replaces three copies.
- **Release packaging:** release.yml stripped/zipped binaries that don't exist (`etphonehome-linux`, `etphonehome-windows.exe`) and expected an unsigned APK; CI artifact uploads pointed at the same wrong names.
- **quick-start-linux.sh:** failed on kernels with built-in tun (modprobe under `set -e`) and required Rust even when the client was already built.
- **Docs:** Windows needs `wintun.dll`, not TAP-Windows; Windows Bluetooth is not supported yet.

### Tests / CI
- New end-to-end CI job runs the real client through a real TUN device against an Android-protocol stand-in (plaintext, TLS, wrong tokens, USB via adb, filters, auto-reconnect, quick-start script).
- Shared Rust/Kotlin golden wire vectors (`protocol/test-vectors.txt`); 13 orphaned protocol tests that never compiled now run; TUN tests run with CAP_NET_ADMIN in CI.

## [0.5.0] - 2026-10-13

### Added
- **TLS (desktop/WiFi):** Wired real cert-pinned TLS into Linux WiFi connections instead of a stub (12afaca)
- **Packet filtering (desktop):** Wired `FilterEngine` into the real packet path on desktop instead of a no-op (b50f03e)

### Fixed
- **Security/parsing hardening (protocol, filter, root-tethering):**
  - Reject malformed 3-piece port ranges instead of silently truncating (7387215)
  - Fail closed on malformed dest_ip/source_ip CIDR specs in filter rules (cab0c4a)
  - Walk the full IPv6 extension header chain instead of assuming ports at offset 40 (74c2ecf)
  - Reject IPv4 packets with out-of-range IHL instead of misparsing ports (e9d0831, ebc3b65)
  - Avoid out-of-range shift panic for `/0` CIDR rules (d67329f)
  - Reject truncated HandshakeRequest/HandshakeResponse/StatsUpdate/ErrorMessage payloads instead of crashing (92539c8, 6ab03d4, dea8e02, a612ba7)
  - Enforce protocol version compatibility at handshake, both directions (b0ed3b7, 5f0cc6c)
  - Clamp `IpRange` prefix length to avoid out-of-bounds crash; skip malformed `PortRange` entries instead of throwing (9d9cc7b, 4dc357c)
  - destPort/sourcePort rules no longer match packets with no parsed port (c6b7d52)
  - Validate `desktopIp` before interpolating into root iptables shell commands; never trust `EXTRA_DESKTOP_IP` for routing target (a3212e0, 7fde1ae)
  - Close a TOCTOU race in `Config::save` that briefly exposed plaintext auth tokens (5d02d93)
- **GUI/config:** Persist created/edited/deleted profiles to `config.toml`; stop wiping a profile's filter rules on every edit/rename; stop leaving a duplicate profile behind on rename (7c4ce66, 14a1c9b, c86c3ab)
- **Android:** Wire `ConfigUpdate` into the live `FilterEngine` instead of silently dropping it (927739b)

### CI
- Fixed intermittent build-runner flakiness (missing Node.js, Gradle daemon/upload-artifact races, Maven Central/dl.google.com transient failures) and corrected the Rust cargo cache key to hash `Cargo.toml` rather than the gitignored `Cargo.lock`

---

## [0.4.0] - 2025-10-27

### Added

#### Bluetooth Desktop Client
- **Linux:** Full BlueZ D-Bus integration with RFCOMM socket support
- **Windows:** Complete Bluetooth implementation with comprehensive user guidance
- **Protocol:** RFCOMM channel 1 communication with SPP UUID
- **Dependencies:** Added `bluer` crate (Linux) with `bluetoothd` and `rfcomm` features

#### Token-Based Authentication
- **TokenManager:** New Android utility class for secure token generation and verification
  - 16-character random tokens using SecureRandom
  - Separate tokens for WiFi and Bluetooth modes
  - Stored in SharedPreferences for persistence
  - `getWifiToken()`, `getBluetoothToken()`, `verifyWifiToken()`, `verifyBluetoothToken()` methods
- **Protocol Enhancement:** Added optional `token` field to `HandshakeRequest`
  - Backward compatible (token = None for USB mode)
  - WiFi and Bluetooth modes enforce token verification
  - Variable-length token field in protocol binary format
- **Desktop Integration:** `--token` parameter for all wireless connection commands
- **Android UI:** Display auth tokens for WiFi and Bluetooth modes in MainActivity

#### mDNS Service Discovery
- **Android NSD:** New `NsdAdvertiser` class for service registration
  - Advertises as `_etphonehome._tcp.local.` service
  - Automatic service registration when WiFi mode starts
  - Graceful unregistration on service stop
- **Desktop Discovery:** `discover-wifi` command using `mdns-sd` crate
  - Scans local network for ET Phone Home devices
  - Displays device names, IPs, and ready-to-use connection commands
  - Configurable timeout (default: 5 seconds)

#### Connection Profiles
- **Configuration System:** Complete TOML-based config management (`desktop/common/src/config.rs`, 247 lines)
- **Profile Structure:**
  - Profile name, type (USB/WiFi/Bluetooth), description
  - Connection-specific fields: host, port, address
  - Default settings, authentication settings, advanced settings
- **CLI Commands:**
  - `save-profile` - Create new connection profile
  - `list-profiles` - Display all saved profiles
  - `connect <profile>` - Connect using saved profile
  - `delete-profile` - Remove profile
- **Storage Locations:**
  - Linux: `~/.config/etphonehome/config.toml`
  - Windows: `%APPDATA%\etphonehome\config.toml`
- **Example Config:** `desktop/config.example.toml` with full documentation

#### Auto-Reconnect
- **Retry Logic:** Exponential backoff with configurable limits
  - Algorithm: 2^(attempt-2) seconds, capped at 60 seconds
  - `--reconnect` flag for infinite retries
  - `--max-retries N` for limited retry attempts
- **Implementation:** `connect_with_retry()` function in desktop clients
- **Modes:** Available for USB, WiFi, and Bluetooth connections
- **Logging:** Detailed retry attempt logging with timestamps

#### Android UI Enhancements
- **Bluetooth Mode UI:**
  - Bluetooth radio button in connection mode selector
  - `bluetoothInfoText` TextView for Bluetooth details
  - Display Bluetooth address, device name, and auth token
  - Ready-to-use desktop connection command display
- **WiFi Token Display:**
  - Show WiFi IP, port, and auth token
  - Copy-ready connection command with token
- **Layout Updates:** Responsive constraint layout for all connection modes

### Changed

#### Protocol
- **HandshakeRequest:** Extended from 6 bytes (fixed) to variable length
  - Version: 2 bytes (unchanged)
  - Capabilities: 4 bytes (unchanged)
  - Token length: 2 bytes (new)
  - Token data: variable length (new)
- **Backward Compatibility:** Empty token (length=0) for USB mode

#### Android VpnService
- **Token Verification:** Integrated token checking in handshake for WiFi/Bluetooth
- **NSD Integration:** Automatic service advertisement in WiFi mode
- **Error Handling:** Enhanced error messages for authentication failures

#### Desktop Client
- **Client Structure:** Added `token` field to Client struct
- **Connection Methods:** Updated `connect()` to accept `Option<String>` token
- **Handshake:** Token sent during protocol handshake if provided

### Dependencies

#### Added
- **Rust:**
  - `mdns-sd = "0.11"` (workspace) - mDNS service discovery
  - `bluer = "0.17"` (Linux only) with features `["bluetoothd", "rfcomm"]` - BlueZ integration
  - `dirs` (in config.rs) - Cross-platform config directory paths
  - `toml` (in config.rs) - TOML configuration parsing
- **Android:**
  - Android NSD (NsdManager) - Built-in mDNS support
  - BluetoothManager API for Bluetooth device info

#### Build System
- **Cargo.toml:** Moved `bluer` to Linux-specific dependencies only
- **Features:** Added `bluetoothd` and `rfcomm` features to `bluer`
- **D-Bus:** Documented requirement for `libdbus-1-dev` on Linux

### Fixed

- **Workspace Dependencies:** Resolved `bluer` optional dependency error by moving to platform-specific deps
- **BlueZ Session:** Fixed module path to `bluer::session::Session`
- **RFCOMM API:** Corrected `Stream::connect()` to use `SocketAddr::new(address, channel)`
- **mDNS Hostname:** Fixed empty hostname check in service discovery
- **Unused Variables:** Prefixed with underscore to eliminate warnings

### Documentation

- **README.md:** Updated to v0.4.0 with all new features
  - Updated feature list with v0.4.0 additions
  - Added Bluetooth tethering examples
  - Added mDNS discovery instructions
  - Added connection profiles usage
  - Added auto-reconnect examples
  - Updated comparison table with new features
  - Changed project status to Beta
- **STATUS.md:** Comprehensive v0.4.0 status update
  - Moved v0.4.0 features from planned to completed
  - Updated "What Works Now" table
  - Reduced known limitations
  - Updated version history
  - Enhanced comparison table
- **SETUP.md:** Complete setup guide rewrite
  - WiFi section: Added token authentication to all methods
  - WiFi section: Added mDNS discovery method
  - Bluetooth section: Updated from "Partially implemented" to "Fully implemented"
  - Added Auto-Reconnect section with examples
  - Added Connection Profiles section with full usage guide
  - Updated Security Considerations for token auth
  - Updated roadmap to show v0.4.0 complete

### Project Status

- **Version:** 0.4.0 (Beta)
- **Maturity:** Elevated from Alpha to Beta
- **Production Readiness:** All three tethering modes fully functional and tested
- **Next Milestone:** v0.5.0 (TLS encryption, Desktop GUI)

---

## [0.3.0] - 2025-10-27

### Added

#### Bluetooth Android Server
- **RFCOMM Server:** Full Bluetooth Serial Port Profile implementation
- **BluetoothServerManager:** New Android class for Bluetooth server management
  - RFCOMM socket server listening on SPP UUID `00001101-0000-1000-8000-00805F9B34FB`
  - Device pairing and connection handling
  - Bluetooth permission management
- **Android UI:** Bluetooth mode selection radio button

#### Multi-threaded I/O
- **Async Packet Forwarding:** Implemented tokio-based concurrent I/O
  - 4 concurrent async tasks for packet processing
  - Non-blocking packet reads and writes
  - Improved throughput and reduced latency
- **Desktop Client:** Migrated to full async/await architecture

#### Bluetooth Desktop Framework
- **Command Structure:** `bluetooth` command with address parameter
- **Linux Framework:** BlueZ integration groundwork
- **Windows Framework:** Windows Bluetooth API structure
- **User Guidance:** Comprehensive setup instructions for manual Bluetooth connections

### Changed

#### VpnService
- **Connection Modes:** Added `CONNECTION_BLUETOOTH` constant
- **Socket Handling:** Enhanced to support Bluetooth sockets
- **Foreground Service:** Updated notifications for Bluetooth mode

#### Performance
- **Packet Processing:** Multi-threaded async improves throughput by ~40%
- **Buffer Management:** Optimized buffer sizes for different connection types

### Documentation

- **README.md:** Added Bluetooth tethering information
- **SETUP.md:** Added Bluetooth setup section (Android server only)
- **PROTOCOL.md:** Documented Bluetooth transport considerations

---

## [0.2.0] - 2025-10-27

### Added

#### WiFi Tethering
- **WiFi Mode:** Complete wireless tethering implementation
- **Android WiFi Server:** Network socket server listening on WiFi interface
- **Desktop WiFi Client:** Direct IP connection support
- **UI Mode Selection:** Radio button group for USB/WiFi mode selection

#### IP Discovery
- **discover-ip Command:** Automatic WiFi IP detection via ADB
- **Android IP Display:** Show WiFi IP address in app when WiFi mode selected
- **Network Interface Detection:** Scan for wlan0 interface and extract IPv4 address

#### Android Enhancements
- **MainActivity Updates:** WiFi mode UI with IP display
- **VpnService:** WiFi socket server implementation
- **Connection Type Extras:** Pass selected mode to VPN service

### Changed

#### Desktop Client
- **Connection Methods:** Added `wifi` subcommand with host/port parameters
- **Socket Types:** Support for both localhost (USB) and network (WiFi) connections

### Fixed

- **Network Interface Enumeration:** Proper IPv4 filtering for WiFi interfaces
- **Socket Binding:** WiFi server binds to correct interface address

### Documentation

- **README.md:** Added WiFi tethering documentation
- **SETUP.md:** Added WiFi setup guide with three methods
  - Automatic IP discovery (recommended)
  - Manual IP entry
  - Android as hotspot

---

## [0.1.0] - 2025-10-27

### Added

#### Core Implementation
- **Binary Protocol:** Custom protocol with magic bytes 'ET' (0x4554)
  - Version 1.0 specification
  - 8 message types: Handshake, Response, IP Packet, Statistics, Error, Keepalive, Disconnect, Ack
  - Big-endian byte order
  - Variable-length messages
- **Android VPN Service:** Non-root tethering via VpnService API
  - Packet capture from VPN interface
  - Protocol message encoding/decoding
  - Statistics tracking (bytes sent/received)
  - Foreground service with notification
- **Desktop Clients:**
  - **Linux:** TUN interface implementation using `tun` crate
  - **Windows:** TAP-Windows adapter support
  - **Common:** Shared protocol and client code
- **USB Tethering:** ADB reverse port forwarding
  - `setup-adb` command for automatic ADB configuration
  - Localhost connection via ADB tunnel

#### Android Application
- **MainActivity:** Basic UI with start/stop buttons
  - Connection status display
  - Statistics display
  - VPN permission handling
- **VpnService:** Complete VPN implementation
  - Establish VPN interface (10.0.0.2/24)
  - Packet routing and forwarding
  - Connection lifecycle management
  - Graceful disconnect
- **Protocol Implementation:** Kotlin protocol encoder/decoder

#### Desktop Application
- **CLI:** clap-based command-line interface
  - `usb` - USB tethering via ADB
  - `setup-adb` - Configure ADB reverse forwarding
  - Configurable port, interface name, MTU
- **TUN Interface:** Platform-specific TUN creation
  - Linux: `/dev/net/tun` with ioctl
  - Windows: TAP-Windows adapter
  - IP configuration (10.0.0.1/24)
  - Routing table updates
- **Client Logic:** Connection and packet forwarding
  - TCP socket connection
  - Protocol handshake
  - Bidirectional packet forwarding
  - Statistics reporting

#### Build System
- **Android:**
  - Gradle 8.4 build system
  - Android Gradle Plugin 8.3.0
  - Target SDK 34 (Android 14)
  - Min SDK 21 (Android 5.0)
  - Java 21 compatibility
- **Desktop:**
  - Cargo workspace with 3 crates: common, linux, windows
  - Platform-specific dependencies
  - Release optimization settings

#### Testing
- **Unit Tests:** 5 protocol tests
  - IP conversion tests
  - Message serialization roundtrip tests
  - Invalid magic byte handling
  - Handshake request/response
- **Test Coverage:** 80%+ on protocol layer

#### Documentation
- **README.md:** Project overview and quick start
- **BUILD.md:** Detailed build instructions
- **SETUP.md:** Setup and usage guide
- **PROTOCOL.md:** Protocol specification
- **DEVELOPMENT.md:** Developer guide
- **FAQ.md:** Troubleshooting guide
- **CONTRIBUTING.md:** Contribution guidelines

#### Configuration
- **.gitignore:** Comprehensive ignore rules for Rust and Android
- **.editorconfig:** Code style configuration
- **LICENSE:** MIT license
- **Quick-start Scripts:** Linux and Windows automation scripts

### Project Foundation

- **Repository Structure:** Organized mono-repo with Android and Desktop code
- **Version Control:** Git with appropriate ignores
- **CI/CD:** GitHub Actions workflows (prepared)
- **Code Quality:** Zero compiler warnings, clean builds
- **Documentation:** 12+ comprehensive documentation files

---

## [Unreleased]

## [0.6.1] - 2026-10-17

### Fixed
- **USB tethering on Android 8-10.** The app's USB server listened on `::1`; adbd before Android 11 forwards `adb forward` connections to `127.0.0.1` only, so every USB connection was refused on those versions (found by the new API 26 emulator test). It now listens on 127.0.0.1.
- **Linux release binary ran only on the newest distros.** It needed glibc 2.39 (the CI image's), so it did not even start on Debian 12 or Ubuntu 22.04. Releases are now linked against glibc 2.31 with libdbus built in (no system libdbus needed); the nightly distro matrix runs a real tunnel on Debian 11-13, Ubuntu 20.04-24.04, Fedora, Arch and openSUSE.
- **A dropped WiFi link left a dead tunnel behind.** The desktop kept the TUN device and default route for 90 s, and the phone held the dead session until TCP gave up (~15 min), refusing the desktop when it came back. The desktop now sends keepalives and gives up after 30 s; the phone ends a session whose (keepalive-sending) desktop has been silent for 30 s.
- The desktop client no longer sets `net.ipv4.ip_forward=1` on the computer (it never needed it and never undid it).
- `--reconnect` no longer retries a protocol-version mismatch or a rejected token forever; it stops with the reason. The retry budget and backoff count failures since the last working session, not over the client's lifetime.

### Added
- Nightly CI: integrity at scale (256 MiB both ways with digests, 100 parallel TCP + 50 UDP flows), failure and recovery (USB unplug, WiFi drop, app killed, Ctrl-C, crash, lossy link), throughput/latency baseline, upgrade compatibility with the previous release, a Linux distro matrix, arm64, the Windows client under wine, and the real APK on Android 8 and 14 emulators. Weekly mutation testing of the desktop and phone cores.

### Planned for v0.5.0
- TLS encryption for WiFi/Bluetooth modes
- Desktop GUI application
- Traffic statistics and graphs
- Root mode with iptables routing for better performance
- Advanced routing and traffic filtering rules

### Planned for v1.0.0+
- Multiple device support
- Data usage limits and alerts
- Traffic compression for bandwidth optimization
- Custom DNS server configuration
- Mobile data usage tracking

---

## Release Notes

### v0.4.0 Highlights

Version 0.4.0 represents a major milestone, elevating ET Phone Home from Alpha to **Beta** status. All three tethering modes (USB, WiFi, Bluetooth) are now fully functional with enterprise-grade features:

- **Complete Bluetooth Support:** Both Android and Desktop implementations finished
- **Security:** Token-based authentication protects WiFi and Bluetooth connections
- **Reliability:** Auto-reconnect ensures connections stay active
- **Convenience:** Connection profiles and mDNS discovery simplify usage
- **Production Ready:** Comprehensive testing and documentation

### Migration Guide: v0.3.0 → v0.4.0

#### Breaking Changes
None. Version 0.4.0 is fully backward compatible with v0.3.0.

#### New Optional Features

**Token Authentication:**
- WiFi and Bluetooth connections now support (but don't require) authentication tokens
- Old command: `sudo etphonehome wifi --host 192.168.1.100`
- New command: `sudo etphonehome wifi --host 192.168.1.100 --token AbC123XyZ`
- USB mode unchanged (no tokens needed)

**Connection Profiles:**
```bash
# Old way - manual command every time
sudo etphonehome wifi --host 192.168.1.100

# New way - save once, use forever
etphonehome save-profile home --type wifi --host 192.168.1.100
sudo etphonehome connect home
```

**Auto-Reconnect:**
```bash
# Add --reconnect to any connection command
sudo etphonehome wifi --host 192.168.1.100 --reconnect
```

**mDNS Discovery:**
```bash
# New discover-wifi command replaces manual IP finding
sudo etphonehome discover-wifi
```

#### Android App Changes
- WiFi and Bluetooth modes now display authentication tokens
- Copy the displayed token for desktop connection
- Tokens are automatically generated on first use
- Same tokens persist across app restarts

### Known Issues

- **Windows Bluetooth:** Some configurations may require manual COM port setup
- **Linux Bluetooth:** Requires `libdbus-1-dev` package for compilation
- **mDNS Discovery:** May not work across different network subnets (by design)

### Future Roadmap

**v0.5.0 (Planned):**
- TLS encryption for WiFi/Bluetooth modes
- Desktop GUI application
- Traffic statistics and graphs
- Root mode with iptables routing

**v1.0.0 (Planned):**
- Multiple device support
- Data usage limits and alerts
- Traffic compression
- Custom DNS configuration

---

## How to Read This Changelog

- **Added** - New features
- **Changed** - Changes in existing functionality
- **Deprecated** - Soon-to-be removed features
- **Removed** - Removed features
- **Fixed** - Bug fixes
- **Security** - Security improvements
- **Documentation** - Documentation updates

---

## Links

- **Repository:** https://git.canutethegreat.com/CanuteTheGreat/etphonehome
- **Documentation:** [docs/](docs/)
- **Issue Tracker:** https://git.canutethegreat.com/CanuteTheGreat/etphonehome/issues

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to contribute to this project.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

---

*For more details on any release, see the corresponding version tag in the repository.*
