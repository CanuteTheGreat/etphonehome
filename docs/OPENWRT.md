# ETPH on routers (OpenWrt and GL.iNet)

Use a phone running **Errant Solutions ETPH** as a router's internet
connection (its WAN). Every device behind the router (laptops, a TV, a
second phone) then uses the phone's connection, and to the phone it is all
traffic from one app: no hotspot, no phone VPN, the phone keeps its own
internet.

Works on **official OpenWrt** (24.10 and older with opkg, 25.x with apk) and
on **GL.iNet travel routers** (firmware 4.x is OpenWrt-based; it uses opkg).

## What you need

| | |
|---|---|
| Router | OpenWrt 23.05 or newer (or GL.iNet 4.x), ~4 MB free flash, ~8 MB free RAM |
| CPU | aarch64 (GL.iNet MT3000/MT6000/AX1800/AXT1800 and other ipq60xx/mt798x, RPi 3/4/5), ARMv7 with FPU (ipq40xx, ipq806x, mvebu, mt7623), MIPS 24Kc (ath79: GL-AR750S, AR300M, many TP-Link), MIPSel (ramips MT7621/MT76x8: GL-MT300N, MT1300), x86_64 |
| Phone | Android 8.0+, the ETPH app 0.8.0+ |
| USB | a USB host port on the router, a data cable, *USB debugging* on the phone (no adb package: the client has its own ADB host) |

Not supported: ARMv7 routers without an FPU (e.g. Broadcom bcm53xx), MIPS64,
routers with 8 MB of flash or less. Bluetooth is not offered on routers
(OpenWrt rarely has BlueZ).

## Install

On the router (SSH), with internet still working through another uplink
(cable, Wi-Fi or the phone's own hotspot):

```sh
wget -O /tmp/etph-install.sh https://git.canutethegreat.com/CanuteTheGreat/etphonehome/releases/download/v0.8.0/etph-openwrt-install.sh
sh /tmp/etph-install.sh            # --no-adb: Wi-Fi only; --no-luci: no web page
```

It installs `kmod-tun` from the official feeds, then the ETPH package for
your router's architecture and the LuCI page. Nothing else is needed for
USB: the client talks to the phone's USB debugging interface itself
(usbfs), so the adb package and an adb server are not used. Or by hand: pick
`etph_0.8.0-r1_<arch>.ipk` (opkg) / `etph-0.8.0-r1_<arch>.apk` (apk) and
`luci-app-etph` from the [release](https://git.canutethegreat.com/CanuteTheGreat/etphonehome/releases),
then `opkg install ./etph_*.ipk ./luci-app-etph_*.ipk` or
`apk add --allow-untrusted ./etph-*.apk ./luci-app-etph-*.apk`
(`<arch>` = `DISTRIB_ARCH` in `/etc/openwrt_release`).

Package sizes (installed binary): about 2.4-3.1 MB depending on the CPU;
the `.ipk`/`.apk` download is about 1.1-1.5 MB. The exact numbers for each
release are in `sizes.tsv` on the release page.

## Set it up (LuCI)

**Network > Phone uplink** (GL.iNet: open `http://192.168.8.1/cgi-bin/luci`;
GL's own web UI does not show this interface, LuCI does).

**USB cable**
1. On the phone: Settings > About phone > tap *Build number* 7 times;
   Settings > Developer options > *USB debugging* on.
2. Open ETPH, choose **USB**, tap **Start**.
3. LuCI: *Add a phone uplink*, Connection **USB cable** (or **USB when
   plugged in, else Wi-Fi**), **Set up**.
4. Plug the phone into the router and unlock it. The phone asks *Allow USB
   debugging?*: compare the fingerprint with the one the LuCI page shows,
   tick *Always allow from this computer*, tap **Allow**. That is the
   pairing; while it waits, LuCI says *Unlock your phone and tap Allow*.

Plugging the phone in starts the uplink right away (hotplug); unplugging
it takes the interface down, and with `auto` the router falls back to the
phone over Wi-Fi. The phone charges from the router's USB port while
plugged in (slowly on low-power ports). Android's own *USB tethering*
(RNDIS/NCM, which OpenWrt can use with `kmod-usb-net-rndis`) is a separate
feature and not needed for ETPH: leave it off. It is an alternative when a
carrier allows tethering, but then the traffic counts as hotspot traffic;
ETPH's traffic is the phone's own.

**Wi-Fi**
1. Join the phone to the router's Wi-Fi (or the router to the phone's
   hotspot).
2. Open ETPH, choose **WiFi**, tap **Start**. The app shows the phone's
   address and a pairing token.
3. LuCI: *Add a phone uplink*, Connection **Wi-Fi**, **Find phone** (or type
   the address), enter the token, **Set up**. The link is TLS 1.3 pinned to
   that token; *Unpair* forgets it.

The first setup asks to restart the router's network once so it loads the
new protocol (all links drop for a few seconds; a repeater re-joins its
upstream Wi-Fi). Say no and reboot later if that is a bad moment. The page then shows the phone's model, the
connection, current speed and data used, with *Reconnect now*, *Unpair*
and *Remove*.

Mobile data must be on: when the phone is on the router's own Wi-Fi, ETPH
sends the router's traffic out over mobile data (never back over that
Wi-Fi, which would loop).

## Set it up (UCI / shell)

```sh
uci set network.phone=interface
uci set network.phone.proto=etph
uci set network.phone.transport=usb          # or: wifi
# uci set network.phone.host=192.168.8.153   # wifi: the phone's address
# uci set network.phone.token=XXXXXXXXXXXXXXXX
uci set network.phone.metric=10
uci add_list firewall.@zone[1].network=phone # the wan zone: NAT for the LAN
uci commit; /etc/init.d/network restart; /etc/init.d/firewall reload
ifstatus phone
```

Options: `transport` (usb | wifi | auto: USB when a phone is plugged in,
otherwise Wi-Fi), `host`, `port` (8888), `token`, `tls` (1), `serial` (the
phone's USB serial when several are plugged in), `ipaddr` (tunnel
address, default `10.0.0.1/24`: change it if your LAN uses 10.0.0.0/24),
`peeraddr`, plus the usual `metric`, `defaultroute`, `peerdns`, `dns`.
Router-wide: `/etc/config/etph` (`keygen`: create the router's adb key at
boot). With `option auto '0'` a usb/auto uplink is started when a phone is
plugged in and stopped when it is unplugged. Logs: `logread -e etph`.
Status for scripts: `/var/run/etph/<interface>.json`.

## How it works

- `/lib/netifd/proto/etph.sh` starts the client (`etphonehome usb|wifi
  --no-routes ...`) under netifd. The client creates the `etph-<name>` TUN
  device and, each time a session with the phone comes up or ends, runs
  `/lib/netifd/etph-hook`, which reports the link to netifd like pppd's
  ip-up/ip-down. netifd owns the default route (with the interface metric),
  DNS (dnsmasq) and the firewall zone; mwan3 tracks it like any WAN.
- The client keeps running and reconnects by itself (backoff up to a minute);
  plugging a phone into USB (hotplug) or *Reconnect now* makes it retry
  immediately.
- USB: the client finds the phone's ADB interface in sysfs, claims it
  through usbfs, authenticates with the router's RSA key (created once at
  install, `/etc/etph/.android/adbkey`, the same format adb uses) and opens
  a stream to the app's port, exactly what `adb forward` would do, without
  adb. The key directory is kept across sysupgrade, so the phone stays
  paired. A keep-settings sysupgrade drops added packages: reinstall ETPH
  afterwards (the settings are kept).
- The phone's side is the same as for a computer: the app relays the
  router's connections through ordinary phone sockets.

## What is tested

- CI (`.forgejo/workflows/openwrt.yml`, every change and nightly): builds the
  five CPU families and both package formats, then boots real OpenWrt 24.10
  (opkg) and 25.12 (apk) x86_64 VMs with no internet except through the
  phone's networking core: install, LuCI setup, interface up with the
  phone's route and DNS, HTTPS from the router and from a LAN laptop behind
  it, mwan3 tracking, the LuCI page backend through a real LuCI session,
  automatic reconnect after the phone goes away, *Reconnect now*, the USB
  uplink without a phone (key, fingerprint, clear message), unpair,
  uninstall. The built-in ADB host's protocol (auth, Allow prompt, stream,
  flow control) is tested against a fake adbd in unit tests and against the
  real app's adbd on Android emulators (nightly, over adbd's TCP port).
- Real hardware: GL.iNet GL-AR750S (MIPS 24Kc, OpenWrt 25.12) over Wi-Fi to
  a real phone (Samsung, Android 13). USB on real router hardware: see the
  release notes for its status.
