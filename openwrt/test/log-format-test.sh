#!/bin/sh
# Asserts that every router-side log line we write is structured key=value with a level, and that
# the client log level knob (UCI option `loglevel` -> RUST_LOG) is wired. Run: sh openwrt/test/log-format-test.sh
set -eu
cd "$(dirname "$0")/.."
fail=0
n=0
for f in etph/files/etc/hotplug.d/usb/30-etph etph/files/lib/netifd/etph-hook; do
	while IFS= read -r line; do
		n=$((n + 1))
		for key in 'level=' 'source=' 'event='; do
			case "$line" in
			*"$key"*) ;;
			*) echo "FAIL $f: logger line without $key: $line"; fail=1 ;;
			esac
		done
		case "$line" in *'-t etph'*) ;; *) echo "FAIL $f: logger line without -t etph: $line"; fail=1 ;; esac
	done <<EOF
$(grep 'logger ' "$f")
EOF
done
[ "$n" -ge 4 ] || { echo "FAIL: expected at least 4 logger lines, found $n"; fail=1; }
grep -q 'proto_export "RUST_LOG=${loglevel:-info}"' etph/files/lib/netifd/proto/etph.sh || { echo "FAIL: loglevel not exported as RUST_LOG"; fail=1; }
grep -q 'proto_config_add_string "loglevel"' etph/files/lib/netifd/proto/etph.sh || { echo "FAIL: loglevel option not declared"; fail=1; }
# Run the hotplug/hook lines through a stub logger to prove the emitted text parses as key=value pairs.
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/logger" <<'EOS'
#!/bin/sh
# last argument is the message
for a; do m=$a; done
echo "$m" >> "$LOGGER_OUT"
EOS
chmod +x "$tmp/logger"
export LOGGER_OUT="$tmp/out"
: > "$LOGGER_OUT"
for f in etph/files/lib/netifd/etph-hook; do
	grep 'logger ' "$f" | sed 's/^[[:space:]]*//' | while IFS= read -r l; do
		INTERFACE=phone ETPH_IFNAME=etph-phone ETPH_DEVICE="Pixel 7" ETPH_TRANSPORT=usb ETPH_REASON="phone gone" PATH="$tmp:$PATH" sh -c "$l"
	done
done
cat "$LOGGER_OUT"
grep -q '^level=INFO source=netifd event=uplink_up interface=phone ifname=etph-phone phone="Pixel 7" transport=usb$' "$LOGGER_OUT" || { echo "FAIL: uplink_up line"; fail=1; }
grep -q '^level=INFO source=netifd event=uplink_down interface=phone reason="phone gone"$' "$LOGGER_OUT" || { echo "FAIL: uplink_down line"; fail=1; }
[ "$fail" = 0 ] && echo "log-format-test: OK"
exit "$fail"
