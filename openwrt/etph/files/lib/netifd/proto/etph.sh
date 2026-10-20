#!/bin/sh
# netifd protocol handler: `option proto 'etph'` = this router's internet
# comes from a phone running Errant Solutions ETPH (USB cable or Wi-Fi).
#
#   config interface 'phone'
#       option proto 'etph'
#       option transport 'usb'        # usb | wifi | auto (USB when plugged in, else Wi-Fi)
#       option serial ''              # usb: the phone's serial, when several are plugged in
#       option host '192.168.8.153'   # wifi: the phone's address
#       option token '...'            # wifi: pairing token shown in the app
#       option tls '1'                # wifi: cert-pinned TLS 1.3 (default)
#       option port '8888'
#       option ipaddr '10.0.0.1/24'   # tunnel address (pick one off your LAN)
#       option metric '10'            # standard netifd options work too:
#       option defaultroute '1'       #   metric, defaultroute, peerdns, dns
#
# The client keeps running and reconnects by itself (phone unplugged,
# app stopped, Wi-Fi gone); each time a session comes up or ends it reports
# to netifd through /lib/netifd/etph-hook (like pppd's ip-up/ip-down), so
# the interface goes up/down, netifd installs the default route with this
# interface's metric and gives dnsmasq the phone's DNS server, and the
# firewall zone and mwan3 treat it as any other WAN.

[ -n "$INCLUDE_ONLY" ] || {
	. /lib/functions.sh
	. ../netifd-proto.sh
	init_proto "$@"
}

proto_etph_init_config() {
	no_device=1
	available=1
	proto_config_add_string "transport"
	proto_config_add_string "host"
	proto_config_add_int "port"
	proto_config_add_string "token"
	proto_config_add_boolean "tls"
	proto_config_add_string "serial"
	proto_config_add_string "ipaddr"
	proto_config_add_string "peeraddr"
	proto_config_add_boolean "defaultroute"
	proto_config_add_boolean "peerdns"
	proto_config_add_array "dns"
	proto_config_add_string "loglevel"
}

proto_etph_ifname() {
	# Linux interface names are at most 15 characters.
	printf 'etph-%.10s' "$1"
}

proto_etph_setup() {
	local config="$1"
	local transport host port token tls serial ipaddr peeraddr defaultroute peerdns dns loglevel
	json_get_vars transport host port token tls serial ipaddr peeraddr defaultroute peerdns loglevel
	json_get_values dns dns

	local ifname
	ifname="$(proto_etph_ifname "$config")"
	[ -x /usr/sbin/etphonehome ] || {
		proto_notify_error "$config" NO_CLIENT
		proto_block_restart "$config"
		return 1
	}

	# --router: the phone counts this as a router (ETPH Pro on the phone).
	set -- /usr/sbin/etphonehome --router
	case "${transport:-usb}" in
	usb)
		# The client's built-in ADB host (usbfs): no adb package or server.
		set -- "$@" usb --native --interface "$ifname"
		[ -n "$port" ] && set -- "$@" --port "$port"
		[ -n "$serial" ] && set -- "$@" --adb-serial "$serial"
		;;
	auto)
		set -- "$@" auto --interface "$ifname"
		[ -n "$port" ] && set -- "$@" --port "$port" --wifi-port "$port"
		[ -n "$serial" ] && set -- "$@" --adb-serial "$serial"
		if [ -n "$host" ]; then
			set -- "$@" --host "$host"
			[ "${tls:-1}" = 0 ] && set -- "$@" --no-tls
			[ -n "$token" ] && proto_export "ETPH_TOKEN=$token"
		fi
		;;
	wifi)
		[ -n "$host" ] || {
			proto_notify_error "$config" MISSING_PHONE_ADDRESS
			proto_block_restart "$config"
			return 1
		}
		[ -n "$token" ] || {
			proto_notify_error "$config" MISSING_PAIRING_TOKEN
			proto_block_restart "$config"
			return 1
		}
		set -- "$@" wifi --host "$host" --interface "$ifname"
		[ -n "$port" ] && set -- "$@" --port "$port"
		[ "${tls:-1}" = 0 ] && set -- "$@" --no-tls
		# Via the environment, not argv: keeps the token out of `ps`.
		proto_export "ETPH_TOKEN=$token"
		;;
	*)
		proto_notify_error "$config" BAD_TRANSPORT
		proto_block_restart "$config"
		return 1
		;;
	esac

	set -- "$@" --reconnect --max-retries 0 --no-routes \
		--up-script /lib/netifd/etph-hook --down-script /lib/netifd/etph-hook \
		--status-file "/var/run/etph/$config.json"
	[ -n "$ipaddr" ] && set -- "$@" --tun-address "$ipaddr"
	[ -n "$peeraddr" ] && set -- "$@" --tun-peer "$peeraddr"

	mkdir -p /var/run/etph /etc/etph
	proto_export "INTERFACE=$config"
	proto_export "ETPH_DEFAULTROUTE=${defaultroute:-1}"
	proto_export "ETPH_PEERDNS=${peerdns:-1}"
	proto_export "ETPH_DNS_OVERRIDE=$dns"
	# The router's adb key (what the phone's "Always allow from this
	# computer" remembers) is $HOME/.android/adbkey: /etc/etph is kept by
	# sysupgrade (keep.d/etph).
	proto_export "HOME=/etc/etph"
	proto_export "RUST_LOG=${loglevel:-info}"
	proto_run_command "$config" "$@"
}

proto_etph_teardown() {
	local config="$1"
	# SIGTERM: the client tells the phone it is leaving, then exits.
	proto_kill_command "$config" 15
}

[ -n "$INCLUDE_ONLY" ] || {
	add_protocol etph
}
