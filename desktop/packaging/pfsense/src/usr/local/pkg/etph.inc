<?php
/*
 * etph.inc
 *
 * ETPH (Errant Solutions): an Android phone as a pfSense uplink. The client
 * (package etphonehome) runs as a service and keeps the phone link as the
 * interface etph0; pfSense owns routes, gateways, firewall and DNS
 * (--no-routes) and is told about every link change through the up/down
 * hook (etph-hook.sh -> etph-linkchange.php: gateway monitor, routes, rules).
 * Licensed under the MIT License.
 */

require_once("config.inc");
require_once("globals.inc");
require_once("service-utils.inc");
require_once("util.inc");

define('ETPH_RCFILE', '/usr/local/etc/rc.d/etph.sh');
define('ETPH_TOKEN', '/usr/local/etc/etphonehome/token');
define('ETPH_STATUS', '/var/run/etph/etph0.json');
define('ETPH_HOOK', '/usr/local/pkg/etph-hook.sh');

function etph_settings() {
	$s = config_get_path('installedpackages/etph/config/0', []);
	return is_array($s) ? $s : [];
}

function etph_validate($post, &$input_errors) {
	if (empty($post['enable'])) {
		return;
	}
	$t = $post['transport'] ?? 'wifi';
	if (!in_array($t, ['wifi', 'usb', 'auto'])) {
		$input_errors[] = gettext("Choose a connection type.");
	}
	if ($t == 'wifi' && !is_ipaddr($post['host'] ?? '')) {
		$input_errors[] = gettext("Wi-Fi needs the phone's IP address.");
	}
	if ($t == 'auto' && !empty($post['host']) && !is_ipaddr($post['host'])) {
		$input_errors[] = gettext("The phone address must be an IP address.");
	}
	if (!is_port($post['port'] ?? '')) {
		$input_errors[] = gettext("The port must be a number between 1 and 65535.");
	}
	if ($t != 'usb' && strlen(trim($post['token'] ?? '')) == 0) {
		$input_errors[] = gettext("Wi-Fi needs the Auth Token from the app's Wi-Fi panel.");
	}
}

function etph_args($s) {
	$port = escapeshellarg($s['port'] ?: '8888');
	$common = "--no-routes --interface etph0 --reconnect --status-file " . ETPH_STATUS .
		" --up-script " . ETPH_HOOK . " --down-script " . ETPH_HOOK;
	switch ($s['transport'] ?? 'wifi') {
	case 'usb':
		return "--router usb --port {$port} {$common}";
	case 'auto':
		$host = empty($s['host']) ? '' : ' --host ' . escapeshellarg($s['host']);
		return "--router auto --wifi-port {$port}{$host} {$common}";
	default:
		return "--router wifi --host " . escapeshellarg($s['host']) . " --port {$port} {$common}";
	}
}

function etph_resync() {
	$s = etph_settings();
	if (empty($s['enable'])) {
		if (is_service_running('etph')) {
			stop_service('etph');
		}
		unlink_if_exists(ETPH_RCFILE);
		return;
	}
	safe_mkdir(dirname(ETPH_TOKEN), 0700);
	@file_put_contents(ETPH_TOKEN, trim($s['token'] ?? ''));
	@chmod(ETPH_TOKEN, 0600);
	safe_mkdir(dirname(ETPH_STATUS));
	$args = etph_args($s);
	$start = <<<EOD
	kldstat -q -m if_tun || kldload -n if_tuntap 2>/dev/null
	if [ -r /usr/local/etc/etphonehome/token ]; then
		ETPH_TOKEN=\$(cat /usr/local/etc/etphonehome/token); export ETPH_TOKEN
	fi
	export ETPHONEHOME_CONFIG=/usr/local/etc/etphonehome/config.toml
	/usr/sbin/daemon -f -r -R 5 -P /var/run/etph.pid -o /var/log/etphonehome.log -t etph /usr/local/bin/etphonehome {$args}
EOD;
	$stop = <<<EOD
	if [ -f /var/run/etph.pid ]; then
		kill \$(cat /var/run/etph.pid) 2>/dev/null
		i=0; while [ \$i -lt 10 ] && pgrep -F /var/run/etph.pid >/dev/null 2>&1; do sleep 1; i=\$((i+1)); done
		rm -f /var/run/etph.pid
	fi
EOD;
	write_rcfile(['file' => 'etph.sh', 'start' => $start, 'stop' => $stop]);
	if (is_service_running('etph')) {
		restart_service('etph');
	} else {
		start_service('etph');
	}
}

function etph_install() {
	safe_mkdir(dirname(ETPH_STATUS));
}

function etph_deinstall() {
	if (is_service_running('etph')) {
		stop_service('etph');
	}
	unlink_if_exists(ETPH_RCFILE);
	unlink_if_exists(ETPH_TOKEN);
}

/* The client's status file: state, phone, ETPH Pro, bytes, last error. */
function etph_status() {
	$d = json_decode(@file_get_contents(ETPH_STATUS), true);
	return is_array($d) ? $d : ['state' => (is_service_running('etph') ? 'starting' : 'stopped')];
}
