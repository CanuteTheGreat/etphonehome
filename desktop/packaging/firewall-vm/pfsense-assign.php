<?php
/* CI only: what an admin does in the pfSense GUI after installing
 * pfSense-pkg-etph and assigning etph0 (Interfaces > Assignments).
 *
 *   php pfsense-assign.php ssh '<public key>'
 *     System > Advanced > Admin Access: Secure Shell on, the key on admin
 *     (uid 0, so root's authorized_keys); setup wizard skipped.
 *
 *   php pfsense-assign.php phonenet
 *     Interfaces > Assignments: em2 as [PHONENET], DHCP (the phone's Wi-Fi
 *     network).
 *
 *   php pfsense-assign.php interfaces
 *     Interfaces > [PHONE] (etph0): enabled, IPv4 type None (the client
 *     addresses the link); System > Routing > Gateways: ETPH_GW on PHONE,
 *     10.0.0.2, monitored.
 *
 *   php pfsense-assign.php group
 *     System > Routing > Gateway Groups: FAILOVER = WAN_DHCP tier 1,
 *     ETPH_GW tier 2, trigger "Member down"; Default gateway IPv4 =
 *     FAILOVER (the firewall's own traffic and DNS follow it); Firewall >
 *     Rules > LAN: the default allow rule's gateway = FAILOVER.
 */
require_once("config.inc");
require_once("functions.inc");
require_once("filter.inc");
require_once("shaper.inc");
require_once("interfaces.inc");
require_once("gwlb.inc");
require_once("auth.inc");

$phase = $argv[1] ?? '';

function find_if($dev) {
	foreach (config_get_path('interfaces', []) as $k => $n) {
		if (($n['if'] ?? '') == $dev) {
			return $k;
		}
	}
	return null;
}

if ($phase == 'ssh') {
	config_set_path('system/ssh/enable', 'enabled');
	$users = config_get_path('system/user', []);
	foreach ($users as $i => $u) {
		if ($u['name'] == 'admin') {
			$users[$i]['authorizedkeys'] = base64_encode($argv[2] . "\n");
			config_set_path('system/user', $users);
			local_user_set($users[$i]);
		}
	}
	/* the admin finished (skipped) the first-time setup wizard */
	config_del_path('trigger_initial_wizard');
	write_config("CI: ssh");
	echo "ssh=on\n";
} elseif ($phase == 'phonenet') {
	/* the next free optN for em2 */
	for ($i = 1; config_get_path("interfaces/opt{$i}") !== null; $i++);
	$net = "opt{$i}";
	config_set_path("interfaces/{$net}", ['if' => 'em2', 'descr' => 'PHONENET', 'enable' => '', 'ipaddr' => 'dhcp']);
	write_config("CI: phone network");
	interface_configure($net, true);
	filter_configure_sync();
	echo "net={$net}\n";
} elseif ($phase == 'interfaces') {
	$phone = find_if('etph0');
	if ($phone === null) {
		fwrite(STDERR, "etph0 not assigned\n");
		exit(1);
	}
	config_set_path("interfaces/{$phone}/descr", 'PHONE');
	config_set_path("interfaces/{$phone}/enable", '');
	/* IPv4 Configuration Type "None", like an OpenVPN interface: the client
	 * addresses the point-to-point link itself (10.0.0.1 -> 10.0.0.2). */
	$gws = config_get_path('gateways/gateway_item', []);
	$gws[] = ['interface' => $phone, 'gateway' => '10.0.0.2', 'name' => 'ETPH_GW', 'weight' => '1',
		'ipprotocol' => 'inet', 'monitor' => '10.0.0.2', 'descr' => 'ETPH phone'];
	config_set_path('gateways/gateway_item', $gws);
	write_config("CI: phone interfaces");
	interface_configure($phone, true);
	system_routing_configure();
	setup_gateways_monitor();
	filter_configure_sync();
	echo "phone={$phone}\n";
} elseif ($phase == 'group') {
	$groups = config_get_path('gateways/gateway_group', []);
	$groups[] = ['name' => 'FAILOVER', 'item' => ['WAN_DHCP|1|address', 'ETPH_GW|2|address'],
		'trigger' => 'down', 'descr' => 'WAN, then the phone'];
	config_set_path('gateways/gateway_group', $groups);
	config_set_path('gateways/defaultgw4', 'FAILOVER');
	$hit = 0;
	$rules = config_get_path('filter/rule', []);
	foreach ($rules as $i => $r) {
		if (($r['descr'] ?? '') == 'Default allow LAN to any rule') {
			$rules[$i]['gateway'] = 'FAILOVER';
			$hit++;
		}
	}
	config_set_path('filter/rule', $rules);
	write_config("CI: failover group");
	system_routing_configure();
	setup_gateways_monitor();
	filter_configure_sync();
	echo "group=FAILOVER lan_rules_policy_routed={$hit}\n";
} elseif ($phase == 'status') {
	echo json_encode(return_gateways_status(true)) . "\n";
} else {
	fwrite(STDERR, "usage: pfsense-assign.php ssh KEY | phonenet | interfaces | group | status\n");
	exit(2);
}
