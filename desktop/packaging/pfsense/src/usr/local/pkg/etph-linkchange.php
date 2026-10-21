<?php
/*
 * etph-linkchange.php up|down <ifname>
 *
 * ETPH (Errant Solutions): the phone link came up or went away. Gateway
 * monitoring, routes and rules are re-applied the way rc.newwanip does it,
 * without its package restart. Licensed under the MIT License.
 */
require_once("config.inc");
require_once("functions.inc");
require_once("interfaces.inc");
require_once("gwlb.inc");
require_once("filter.inc");
require_once("shaper.inc");

$what = ($argv[1] ?? 'up') == 'down' ? 'down' : 'up';
$ifname = preg_replace('/[^a-z0-9]/', '', $argv[2] ?? 'etph0');
$lock = lock('etph-linkchange', LOCK_EX);
$friendly = convert_real_interface_to_friendly_interface_name($ifname);
log_error(sprintf("ETPH: phone link %s on %s%s", $what, $ifname, $friendly ? " ({$friendly})" : " (not assigned)"));
if ($friendly) {
	setup_gateways_monitor();
	system_routing_configure();
	filter_configure_sync();
}
unlock($lock);
