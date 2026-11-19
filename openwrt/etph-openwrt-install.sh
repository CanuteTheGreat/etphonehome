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
API=${ETPH_API:-https://api.github.com/repos/CanuteTheGreat/etphonehome}
LUCI=1
TAG=

die() { echo "etph-install: $*" >&2; exit 1; }

# The tag and the architecture end up in file names and URLs: letters, digits and . + _ - only.
safe() { case "$1" in ''|*[!0-9A-Za-z.+_-]*) return 1 ;; esac; }

for a in "$@"; do
	case "$a" in
	--no-adb) ;; # accepted for old instructions; adb is not used any more
	--no-luci) LUCI=0 ;;
	v*) TAG=$a ;;
	*) echo "unknown argument $a" >&2; exit 2 ;;
	esac
done

. "${ETPH_OPENWRT_RELEASE:-/etc/openwrt_release}"
ARCH=$DISTRIB_ARCH
[ -n "$ARCH" ] || die "cannot tell this router's architecture (/etc/openwrt_release)"
safe "$ARCH" || die "unexpected architecture name: $ARCH"
if [ -z "$TAG" ]; then
	TAG=$(wget -q -O - "$API/releases/latest" | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p')
	[ -n "$TAG" ] || die "cannot find the latest release (no internet yet? pass the tag)"
fi
case "$TAG" in v[0-9]*) ;; *) die "not a release tag: $TAG" ;; esac
safe "$TAG" || die "not a release tag: $TAG"
VER=${TAG#v}-r1
DL=${ETPH_DOWNLOAD_BASE:-https://errant.solutions/download/etphonehome}/$TAG
cd "${ETPH_WORKDIR:-/tmp}"

# Packages are installed only after their SHA-256 matches the release's SHA256SUMS (which covers every
# release file). Nothing is installed when the sums are missing, the package is not listed, or a hash differs.
rm -f SHA256SUMS
wget -q -O SHA256SUMS "$DL/SHA256SUMS" || die "cannot download $DL/SHA256SUMS: refusing to install unverified packages"
[ -s SHA256SUMS ] || die "empty SHA256SUMS: refusing to install unverified packages"

listed() { awk -v n="$1" '$2 == n || $2 == "*" n { f = 1 } END { exit !f }' SHA256SUMS; }

verify() {
	local want got
	want=$(awk -v n="$1" '$2 == n || $2 == "*" n { print $1; exit }' SHA256SUMS)
	[ -n "$want" ] || { rm -f "$1"; die "$1 is not listed in the release's SHA256SUMS"; }
	got=$(sha256sum "$1" | awk '{ print $1 }')
	[ "$want" = "$got" ] || { rm -f "$1"; die "$1 does not match the release's SHA256SUMS (expected $want, got $got): not installing"; }
}

cleanup() { rm -f SHA256SUMS etph*.apk etph*.ipk luci-app-etph*; }
trap cleanup EXIT

if command -v apk >/dev/null 2>&1; then
	PKG=etph-${VER}_$ARCH.apk
	LUCIPKG=luci-app-etph-${VER}_noarch.apk
	apk update
	EXTRA=kmod-tun
	apk add $EXTRA
	wget -q -O "$PKG" "$DL/$PKG" || die "no ETPH package for $ARCH in $TAG (see $SITE)"
	verify "$PKG"
	FILES=$PKG
	if [ "$LUCI" = 1 ] && [ -e /www/luci-static ]; then
		if wget -q -O "$LUCIPKG" "$DL/$LUCIPKG" && listed "$LUCIPKG"; then
			verify "$LUCIPKG"
			FILES="$FILES $LUCIPKG"
		else
			rm -f "$LUCIPKG"
		fi
	fi
	# The .apk files are not signed by an apk key; their integrity is the SHA-256 check above.
	apk add --allow-untrusted $FILES
else
	PKG=etph_${VER}_$ARCH.ipk
	LUCIPKG=luci-app-etph_${VER}_all.ipk
	opkg update
	EXTRA=kmod-tun
	opkg install $EXTRA
	wget -q -O "$PKG" "$DL/$PKG" || die "no ETPH package for $ARCH in $TAG (see $SITE)"
	verify "$PKG"
	FILES=$PWD/$PKG
	if [ "$LUCI" = 1 ] && [ -e /www/luci-static ]; then
		if wget -q -O "$LUCIPKG" "$DL/$LUCIPKG" && listed "$LUCIPKG"; then
			verify "$LUCIPKG"
			FILES="$FILES $PWD/$LUCIPKG"
		else
			rm -f "$LUCIPKG"
		fi
	fi
	opkg install $FILES
fi
echo
echo "ETPH $TAG installed. Set up the phone uplink in LuCI: Network > Phone uplink"
echo "(GL.iNet: open http://<router>/cgi-bin/luci), or see $SITE/"
