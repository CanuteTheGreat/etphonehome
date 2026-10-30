# ETPH on computers and firewalls: Linux, Windows, macOS, FreeBSD, OPNsense, pfSense

The phone runs the ETPH app; the computer runs the ETPH client. Every
platform uses the same client core (`desktop/common`): the same protocol,
cert-pinned TLS 1.3 for Wi-Fi, the same built-in ADB host for USB (no
Android SDK or adb tool needed), the same traffic filter and profiles.

| | Linux | Windows 10/11 | macOS 15+ (every version Apple patches) | FreeBSD 14 / 15 |
|---|---|---|---|---|
| Download | `etphonehome-linux-*.tar.gz`, GUI | `etph-windows-*.msi` (or the zip) | `etph-macos-*.pkg` (Apple silicon + Intel) | `etphonehome-*-freebsd14-amd64.pkg` / `-freebsd15-` (also `-aarch64`) |
| App | GUI (`etphonehome-gui`) | tray app (notification area) | menu-bar app | - (CLI + rc.d service) |
| Tunnel | TUN (`/dev/net/tun`) | Wintun (bundled, signed by WireGuard LLC) | utun (kernel control socket, no kext) | tun(4), renamed `etph0` |
| USB | usbfs (built in) | WinUSB through libusb (built in) | IOKit through libusb (built in) | base-system libusb |
| DNS | systemd-resolved per link | adapter DNS (netsh) | scutil resolver for the tunnel | resolvconf(8) |
| Bluetooth | yes (BlueZ) | no | no | no |
| CI | real tunnel + internet, 9 distros | real install + Wintun + internet (GitHub Windows Server) | real install + utun + internet on macOS 27 and 15 (Apple silicon) and 26 (Intel) (GitHub macOS) | real FreeBSD 14 VM: pkg, tunnel, internet, rc.d service |

Firewalls: [OPNsense](#opnsense) (plugin `os-etph`) and [pfSense CE](#pfsense-ce)
(package `pfSense-pkg-etph`), each with a page under Interfaces, a service,
and the phone as an assignable interface for gateway groups (WAN failover).

Free and Pro are decided by the phone (the client never needs a licence):
USB to one computer is free; Wi-Fi, more than one computer and routers /
firewalls need ETPH Pro on the phone. The apps show "ETPH Pro on the phone:
yes/no" from the phone's handshake, and a refused computer prints the phone's
reason.

## Windows

1. Run `etph-windows-<version>.msi`. It installs to `C:\Program Files\ETPH`:
   `etphonehome.exe` (CLI; Start menu > ETPH > "ETPH command prompt"),
   `etph-tray.exe` (started at login), `wintun.dll`, and the **ETPH tethering service** (runs as
   LocalSystem; the tray app asks it to connect, so no UAC prompt per
   connection).
2. Phone: Developer options > USB debugging on, ETPH in USB mode, plug in.
3. Tray icon > **Connect over USB**; tap **Allow** on the phone the first
   time. The icon turns green when connected.

USB talks to the phone through WinUSB directly. Phones whose adb interface
announces the Microsoft OS descriptor (Android 10+ on most phones) get
WinUSB automatically; otherwise install the phone maker's USB driver (or
Google's USB driver) once.

Wi-Fi (ETPH Pro): from an Administrator prompt, save the phone for the
service, then pick it in the tray menu under **Connect to a saved phone**:

    "C:\Program Files\ETPH\etphonehome.exe" --config "%ProgramData%\ETPH\config.toml" save-profile phone -t wifi --host 192.168.1.50 --token <token from the app>

`etphonehome ctl status` shows the service's session from any prompt. The
service log is `%ProgramData%\ETPH\service.log`. winget: each release has
`etph-winget-<version>.zip` (manifests for the Windows Package Manager;
`winget install --manifest <unzipped folder>`).

## macOS

1. Open `etph-macos-<version>.pkg` (Apple silicon and Intel). Support follows Apple: every macOS version Apple still security-patches (15 and newer today), dropped when Apple stops; the installer refuses older macOS with a message.
2. The package installs `/Applications/ETPH.app` (menu bar, starts at login),
   `/usr/local/bin/etphonehome` and a launchd service that runs as root and
   owns the tunnel (`/Library/LaunchDaemons/solutions.errant.etph.plist`).
3. Menu bar > **ETPH** icon > **Connect over USB** (or a saved phone).

**First launch (Gatekeeper):** the package is not notarized (Errant
Solutions has no Apple Developer ID yet; the binaries are ad-hoc signed,
which Apple Silicon requires). macOS says it "cannot verify" the package:

- Finder: Control-click the .pkg > **Open** > **Open**; or System Settings >
  Privacy & Security > **Open Anyway**; or
- Terminal: `sudo installer -pkg ~/Downloads/etph-macos-<version>.pkg -target /`

When a Developer ID is added to the release signer, the same build script
signs with it and notarizes (`APPLE_DEVELOPER_ID_APP`,
`APPLE_DEVELOPER_ID_INSTALLER`, `NOTARIZE=1`).

Wi-Fi (ETPH Pro): `sudo etphonehome --config "/Library/Application Support/ETPH/config.toml" save-profile phone -t wifi --host 192.168.1.50 --token <token>`.
CLI without the app: `sudo etphonehome usb` / `sudo etphonehome wifi --host ... --token ...`.
macOS names tunnel interfaces itself (`utunN`); DNS for the tunnel is set
through `scutil` and removed when the session ends. Uninstall:
`sudo etph-uninstall`.

## FreeBSD

    pkg add etphonehome-<version>-freebsd14-amd64.pkg   # FreeBSD 15: -freebsd15-
    sysrc etphonehome_enable=YES && service etphonehome start
    etphonehome ctl usb                       # phone on USB
    etphonehome --config /usr/local/etc/etphonehome/config.toml \
      save-profile phone -t wifi --host 192.168.1.50 --token <token>
    etphonehome ctl connect phone             # phone over Wi-Fi (Pro)

The tunnel is a tun(4) interface renamed `etph0` (10.0.0.1 -> 10.0.0.2),
with two /1 routes through it and the phone's DNS added with resolvconf(8);
all of it is removed when the session ends. The CLI works directly too:
`etphonehome wifi --host ... --token ...` (as root).

pkg(8) refuses a package built for another FreeBSD major version, so each
release has one per major (same binary, built against 14.x). CI tests the
package, the rc.d service and the tunnel on a real FreeBSD 14.4 VM.

## Firewalls: OPNsense and pfSense CE

The phone becomes an interface of the firewall (`etph0`), with its own
gateway: use it as a backup WAN in a gateway group, or as the only uplink.
The firewall keeps control of routes, DNS and rules (the client runs with
`--no-routes`). Routers and firewalls need ETPH Pro on the phone. The phone
connects over Wi-Fi: it is on a network the firewall reaches (a Wi-Fi access
point on an OPT port, or the LAN). The settings also offer USB (phone
plugged into the firewall, through FreeBSD's libusb); that path is not yet
tested on a firewall, Wi-Fi is.

Both are tested in CI on real VMs (CI desktop-os.yml,
`firewall-vms`): install with `pkg add`, the GUI page, the service, the phone
assigned as an interface with its gateway, a gateway group "WAN first, then
the phone" as the LAN's gateway, and a LAN client that keeps HTTPS when the
primary WAN goes down (traffic counted on `etph0`), comes back when the WAN
returns, survives the phone going away and coming back, and the packages
uninstall cleanly.

### OPNsense

Tested on OPNsense 26.7 (FreeBSD 15.1).

1. Upload `etphonehome-<version>-freebsd15-amd64.pkg` and
   `os-etph-<version>.pkg` (e.g. with scp) and install them in that order
   from a shell: `pkg add etphonehome-...pkg && pkg add os-etph-...pkg`.
2. **Interfaces > Phone uplink (ETPH)**: Enable, connection (Wi-Fi / USB /
   automatic), the phone's address and the Auth Token from the app's Wi-Fi
   panel; **Apply**. The page shows the phone, ETPH Pro and traffic; the
   service appears under System > Diagnostics > Services.
3. **Interfaces > Assignments**: add `etph0 (ETPH phone uplink)` (offered even
   while the phone is away), enable it, IPv4 Static `10.0.0.1/24` with an
   upstream gateway `10.0.0.2` (the phone).
4. **System > Gateways**: give the WAN gateway a lower priority number than the
   phone's, keep monitoring on, and (optional) a group "WAN tier 1, phone
   tier 2" (trigger: member down) for the LAN rule's gateway. System > Settings
   > General > **Gateway switching** moves the firewall's own default route
   (and with it DNS) to the phone when the WAN is down.

### pfSense CE

Tested on pfSense CE 2.7.2 (FreeBSD 14.0), the newest CE release with an
offline installer (2.8 installs only through the online Netgate Installer).

1. Upload `etphonehome-<version>-freebsd14-amd64.pkg` and
   `pfSense-pkg-etph-<version>.pkg`, then from Diagnostics > Command Prompt or
   a shell: `pkg add etphonehome-...pkg && pkg add pfSense-pkg-etph-...pkg`.
2. **Interfaces > Phone uplink (ETPH)**: Enable, connection, the phone's
   address and Auth Token; **Save** starts the service (Status > Services).
   The **Status** tab shows the phone, ETPH Pro and traffic.
3. **Interfaces > Assignments**: once the phone is connected, add `etph0`,
   enable it with IPv4 Configuration Type **None** (the ETPH client addresses
   the point-to-point link itself, like an OpenVPN interface).
4. **System > Routing > Gateways**: add a gateway on that interface, address
   `10.0.0.2` (monitored). **Gateway Groups**: WAN tier 1, the phone tier 2,
   trigger "Member down"; set it as **Default gateway IPv4** (System >
   Routing) and/or as the gateway of the LAN rule.

Uninstall: `pkg delete pfSense-pkg-etph etphonehome` (the menu entry and
service go with it); on OPNsense `pkg delete os-etph etphonehome`.

## Linux

See the README (CLI, GUI, distro packages, Bluetooth) and
[OPENWRT.md](OPENWRT.md) for routers.
