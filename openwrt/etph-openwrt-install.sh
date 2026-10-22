#!/bin/sh
# Install Errant Solutions ETPH (phone as WAN) on OpenWrt / GL.iNet:
#
#   wget -O /tmp/etph-install.sh https://errant.solutions/download/etphonehome/<tag>/etph-openwrt-install.sh
#   sh /tmp/etph-install.sh [--no-luci] [<tag>]
#
# Picks the package for this router's architecture and package manager
# (opkg: OpenWrt <= 24.10 and GL.iNet 4.x; apk: OpenWrt 25.x), installs
# kmod-tun from the official feeds, then ETPH (USB phones need nothing
# else: the client has its own ADB host) and
# its LuCI page (Network > Phone uplink).
set -e
SITE=https://errant.solutions/etphonehome
API=https://api.github.com/repos/CanuteTheGreat/etphonehome
LUCI=1
TAG=
for a in "$@"; do
	case "$a" in
	--no-adb) ;; # accepted for old instructions; adb is not used any more
	--no-luci) LUCI=0 ;;
	v*) TAG=$a ;;
	*) echo "unknown argument $a" >&2; exit 2 ;;
	esac
done

. /etc/openwrt_release
ARCH=$DISTRIB_ARCH
[ -n "$ARCH" ] || { echo "cannot tell this router's architecture (/etc/openwrt_release)" >&2; exit 1; }
if [ -z "$TAG" ]; then
	TAG=$(wget -q -O - "$API/releases/latest" | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p')
	[ -n "$TAG" ] || { echo "cannot find the latest release (no internet yet? pass the tag)" >&2; exit 1; }
fi
VER=${TAG#v}-r1
DL=https://errant.solutions/download/etphonehome/$TAG
cd /tmp

if command -v apk >/dev/null 2>&1; then
	PKG=etph-${VER}_$ARCH.apk
	LUCIPKG=luci-app-etph-${VER}_noarch.apk
	apk update
	EXTRA=kmod-tun
	apk add $EXTRA
	wget -q -O "$PKG" "$DL/$PKG" || { echo "no ETPH package for $ARCH in $TAG (see $SITE)" >&2; exit 1; }
	FILES=$PKG
	if [ "$LUCI" = 1 ] && [ -e /www/luci-static ]; then
		wget -q -O "$LUCIPKG" "$DL/$LUCIPKG" && FILES="$FILES $LUCIPKG"
	fi
	apk add --allow-untrusted $FILES
else
	PKG=etph_${VER}_$ARCH.ipk
	LUCIPKG=luci-app-etph_${VER}_all.ipk
	opkg update
	EXTRA=kmod-tun
	opkg install $EXTRA
	wget -q -O "$PKG" "$DL/$PKG" || { echo "no ETPH package for $ARCH in $TAG (see $SITE)" >&2; exit 1; }
	FILES=/tmp/$PKG
	if [ "$LUCI" = 1 ] && [ -e /www/luci-static ]; then
		wget -q -O "$LUCIPKG" "$DL/$LUCIPKG" && FILES="$FILES /tmp/$LUCIPKG"
	fi
	opkg install $FILES
fi
rm -f /tmp/etph*.apk /tmp/etph*.ipk /tmp/luci-app-etph*
echo
echo "ETPH $TAG installed. Set up the phone uplink in LuCI: Network > Phone uplink"
echo "(GL.iNet: open http://<router>/cgi-bin/luci), or see $SITE/"
