#!/bin/sh
# Tests for openwrt/etph-openwrt-install.sh with stub wget/apk/opkg: the installer must install a
# package only when its SHA-256 matches the release's SHA256SUMS, and must refuse an unsafe tag or
# architecture. No network, no router needed (POSIX sh + sha256sum).
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
INSTALLER=$HERE/../etph-openwrt-install.sh
T=$(mktemp -d "${TMPDIR:-/tmp}/etph-inst.XXXXXX")
trap 'rm -rf "$T"' EXIT
fails=0
ok() { echo "ok   - $1"; }
bad() { echo "FAIL - $1"; fails=$((fails + 1)); }

mkdir -p "$T/bin" "$T/site/v9.9.9"
# Stub wget: serves $T/site/<tag>/<file> for the download base, "latest" JSON for the API.
cat > "$T/bin/wget" <<'EOS'
#!/bin/sh
out=; url=
while [ $# -gt 0 ]; do
	case "$1" in -q) ;; -O) out=$2; shift ;; *) url=$1 ;; esac
	shift
done
case "$url" in
*/releases/latest) printf '{"tag_name": "%s"}\n' "$STUB_LATEST"; exit 0 ;;
esac
f=${url#"$ETPH_DOWNLOAD_BASE"/}
[ -f "$STUB_SITE/$f" ] || exit 1
if [ "$out" = - ]; then cat "$STUB_SITE/$f"; else cp "$STUB_SITE/$f" "$out"; fi
EOS
cat > "$T/bin/opkg" <<'EOS'
#!/bin/sh
echo "$0 $*" >> "$STUB_LOG"
exit 0
EOS
# apk variant: a second bin dir that has an apk (the installer picks apk when it exists).
mkdir -p "$T/apkbin"
sed 's/opkg/apk/' "$T/bin/opkg" > "$T/apkbin/apk"
cp "$T/bin/wget" "$T/apkbin/wget"
chmod +x "$T/bin/"* "$T/apkbin/"*
printf 'DISTRIB_ARCH="mipsel_24kc"\n' > "$T/openwrt_release"

# Package files + a matching SHA256SUMS.
S=$T/site/v9.9.9
printf 'package-bytes' > "$S/etph_9.9.9-r1_mipsel_24kc.ipk"
printf 'luci-bytes' > "$S/luci-app-etph_9.9.9-r1_all.ipk"
(cd "$S" && sha256sum etph_9.9.9-r1_mipsel_24kc.ipk luci-app-etph_9.9.9-r1_all.ipk > SHA256SUMS)

run() { # run <args...>: fresh work dir and log; sets rc, out
	rm -rf "$T/work"; mkdir -p "$T/work/www"; : > "$T/log"
	out=$(env PATH="$T/bin:$PATH" STUB_SITE="$T/site" STUB_LOG="$T/log" STUB_LATEST="${LATEST:-v9.9.9}" \
		ETPH_DOWNLOAD_BASE=https://dl.invalid/etph ETPH_OPENWRT_RELEASE="$T/openwrt_release" \
		ETPH_WORKDIR="$T/work" sh "$INSTALLER" "$@" 2>&1)
	rc=$?
}
installed() { grep -q '^.*opkg install /' "$T/log"; }

run v9.9.9 --no-luci
if [ $rc -eq 0 ] && installed; then ok "verified package is installed"; else bad "verified package is installed (rc=$rc: $out)"; fi
grep -q 'etph_9.9.9-r1_mipsel_24kc.ipk' "$T/log" || bad "opkg got the package path"

LATEST=v9.9.9 run
if [ $rc -eq 0 ] && installed; then ok "latest-release lookup works"; else bad "latest-release lookup works (rc=$rc: $out)"; fi

# A tampered package must not be installed.
printf 'evil-bytes' > "$S/etph_9.9.9-r1_mipsel_24kc.ipk"
run v9.9.9 --no-luci
if [ $rc -ne 0 ] && ! installed && echo "$out" | grep -q 'does not match'; then ok "tampered package is refused"; else bad "tampered package is refused (rc=$rc: $out)"; fi
printf 'package-bytes' > "$S/etph_9.9.9-r1_mipsel_24kc.ipk"

# Missing SHA256SUMS: fail closed.
mv "$S/SHA256SUMS" "$T/SHA256SUMS.hold"
run v9.9.9 --no-luci
if [ $rc -ne 0 ] && ! installed && echo "$out" | grep -q 'SHA256SUMS'; then ok "missing SHA256SUMS is refused"; else bad "missing SHA256SUMS is refused (rc=$rc: $out)"; fi
mv "$T/SHA256SUMS.hold" "$S/SHA256SUMS"

# Package not listed in SHA256SUMS: refused.
(cd "$S" && grep luci SHA256SUMS > S2; mv S2 SHA256SUMS)
run v9.9.9 --no-luci
if [ $rc -ne 0 ] && ! installed && echo "$out" | grep -q 'not listed'; then ok "unlisted package is refused"; else bad "unlisted package is refused (rc=$rc: $out)"; fi
(cd "$S" && sha256sum etph_9.9.9-r1_mipsel_24kc.ipk luci-app-etph_9.9.9-r1_all.ipk > SHA256SUMS)

# LuCI package with a bad hash is refused (when LuCI is present on the router).
printf 'tampered-luci' > "$S/luci-app-etph_9.9.9-r1_all.ipk"
run v9.9.9
# /www/luci-static is a real path check; only meaningful when it exists on this machine.
if [ -e /www/luci-static ]; then
	if [ $rc -ne 0 ] && ! installed; then ok "tampered LuCI package is refused"; else bad "tampered LuCI package is refused"; fi
fi

# apk variant (OpenWrt 25.x): the verified .apk is installed, a tampered one is not.
printf 'apk-bytes' > "$S/etph-9.9.9-r1_mipsel_24kc.apk"
printf 'luci-apk' > "$S/luci-app-etph-9.9.9-r1_noarch.apk"
(cd "$S" && sha256sum etph-9.9.9-r1_mipsel_24kc.apk luci-app-etph-9.9.9-r1_noarch.apk > SHA256SUMS)
rm -rf "$T/work"; mkdir -p "$T/work"; : > "$T/log"
out=$(env PATH="$T/apkbin:$PATH" STUB_SITE="$T/site" STUB_LOG="$T/log" STUB_LATEST=v9.9.9 ETPH_DOWNLOAD_BASE=https://dl.invalid/etph \
	ETPH_OPENWRT_RELEASE="$T/openwrt_release" ETPH_WORKDIR="$T/work" sh "$INSTALLER" v9.9.9 --no-luci 2>&1); rc=$?
if [ $rc -eq 0 ] && grep -q 'apk add --allow-untrusted etph-9.9.9-r1_mipsel_24kc.apk' "$T/log"; then ok "apk: verified package is installed"; else bad "apk: verified package is installed (rc=$rc: $out)"; fi
printf 'evil' > "$S/etph-9.9.9-r1_mipsel_24kc.apk"
: > "$T/log"
out=$(env PATH="$T/apkbin:$PATH" STUB_SITE="$T/site" STUB_LOG="$T/log" STUB_LATEST=v9.9.9 ETPH_DOWNLOAD_BASE=https://dl.invalid/etph \
	ETPH_OPENWRT_RELEASE="$T/openwrt_release" ETPH_WORKDIR="$T/work" sh "$INSTALLER" v9.9.9 --no-luci 2>&1); rc=$?
if [ $rc -ne 0 ] && ! grep -q 'apk add --allow' "$T/log"; then ok "apk: tampered package is refused"; else bad "apk: tampered package is refused (rc=$rc: $out)"; fi
(cd "$S" && sha256sum etph_9.9.9-r1_mipsel_24kc.ipk luci-app-etph_9.9.9-r1_all.ipk > SHA256SUMS)

# Unsafe tags are refused before any download.
for t in 'v1/../../x' 'v1;id' 'v1 2' 'vx'; do
	run "$t"
	if [ $rc -ne 0 ] && [ ! -s "$T/log" ]; then ok "unsafe tag refused: $t"; else bad "unsafe tag refused: $t (rc=$rc: $out)"; fi
done
LATEST='v1/../../x' run
if [ $rc -ne 0 ] && echo "$out" | grep -q 'not a release tag'; then ok "unsafe tag from the API is refused"; else bad "unsafe tag from the API is refused (rc=$rc: $out)"; fi

# Unsafe architecture name is refused.
printf 'DISTRIB_ARCH="mips/../x"\n' > "$T/openwrt_release"
run v9.9.9 --no-luci
if [ $rc -ne 0 ] && ! installed; then ok "unsafe architecture refused"; else bad "unsafe architecture refused (rc=$rc: $out)"; fi

[ $fails -eq 0 ] && echo "all installer tests passed" || { echo "$fails installer test(s) failed"; exit 1; }
