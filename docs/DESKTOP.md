# ETPH on computers: Linux, Windows, FreeBSD (macOS coming)

The phone runs the ETPH app; the computer runs the ETPH client. Every
platform uses the same client core (`desktop/common`): the same protocol,
cert-pinned TLS 1.3 for Wi-Fi, the same built-in ADB host for USB (no
Android SDK or adb tool needed), the same traffic filter and profiles.

| | Linux | Windows 10/11 | macOS 11+ (coming in a 0.9.x update) | FreeBSD 14+ |
|---|---|---|---|---|
| Download | `etphonehome-linux-*.tar.gz`, GUI | `etph-windows-*.msi` (or the zip) | not released yet | `etphonehome-*-freebsd-amd64.pkg` / `-aarch64.pkg` |
| App | GUI (`etphonehome-gui`) | tray app (notification area) | menu-bar app | - (CLI + rc.d service) |
| Tunnel | TUN (`/dev/net/tun`) | Wintun (bundled, signed by WireGuard LLC) | utun (kernel control socket, no kext) | tun(4), renamed `etph0` |
| USB | usbfs (built in) | WinUSB through libusb (built in) | IOKit through libusb (built in) | base-system libusb |
| DNS | systemd-resolved per link | adapter DNS (netsh) | scutil resolver for the tunnel | resolvconf(8) |
| Bluetooth | yes (BlueZ) | no | no | no |
| CI | real tunnel + internet, 9 distros | real install + Wintun + internet (GitHub Windows Server) | real install + utun + internet on Apple Silicon and Intel | real FreeBSD 14 VM: pkg, tunnel, internet, rc.d service |

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

## macOS (coming in a 0.9.x update)

The macOS client is not in a release yet; these are the steps it will have.

1. Open `etph-macos-<version>.pkg` (Apple Silicon and Intel, macOS 11+).
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

    pkg add etphonehome-<version>-freebsd-amd64.pkg
    sysrc etphonehome_enable=YES && service etphonehome start
    etphonehome ctl usb                       # phone on USB
    etphonehome --config /usr/local/etc/etphonehome/config.toml \
      save-profile phone -t wifi --host 192.168.1.50 --token <token>
    etphonehome ctl connect phone             # phone over Wi-Fi (Pro)

The tunnel is a tun(4) interface renamed `etph0` (10.0.0.1 -> 10.0.0.2),
with two /1 routes through it and the phone's DNS added with resolvconf(8);
all of it is removed when the session ends. The CLI works directly too:
`etphonehome wifi --host ... --token ...` (as root).

pfSense CE and OPNsense (both FreeBSD based) get their own packages with a
web-UI page in a later 0.9.x update; until then they are not supported.

CI tests the package, the rc.d service and the tunnel on a real FreeBSD 14.4 VM.

## Linux

See the README (CLI, GUI, distro packages, Bluetooth) and
[OPENWRT.md](OPENWRT.md) for routers.
