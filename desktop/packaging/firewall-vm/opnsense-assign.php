<?php
/* CI only: what an admin does in the OPNsense GUI after installing os-etph.
 *
 *   php opnsense-assign.php interfaces   (before the phone is connected)
 *     Interfaces: [PHONE] etph0 static 10.0.0.1/24 with gateway ETPH_GW
 *     (10.0.0.2, priority 250), [PHONENET] em2 DHCP (the phone's Wi-Fi);
 *     System: Gateways: WAN_DHCP priority 10; System: Settings: General:
 *     "Gateway switching" on, so the firewall's own default route (and with
 *     it Unbound DNS for the LAN) moves to the phone when the WAN dies.
 *
 *   php opnsense-assign.php group        (once the phone is up)
 *     System: Gateways: Group FAILOVER = WAN_DHCP tier 1, ETPH_GW tier 2
 *     (the GUI only offers gateways `configctl interface gateways list`
 *     knows, so this comes after the phone link exists)
 *
 *   php opnsense-assign.php rule
 *     Firewall: Rules: LAN default allow rule routed through FAILOVER, after
 *     a rule that keeps LAN -> LAN address (DNS) local.
 *
 * Gateways get monitoring on explicitly (new gateways default to
 * "Disable Gateway Monitoring", and an unmonitored gateway never fails over).
 */
require_once 'config.inc';
require_once 'util.inc';

use OPNsense\Core\Config;

$cfg = Config::getInstance();
$x = $cfg->object();
$phase = $argv[1] ?? 'interfaces';

if ($phase == 'interfaces') {
    $phone = $net = null;
    foreach ($x->interfaces->children() as $k => $n) {
        if ((string)$n->if == 'etph0') {
            $phone = $k;
        } elseif ((string)$n->if == 'em2') {
            $net = $k;
        }
    }
    if ($phone === null || $net === null) {
        fwrite(STDERR, "etph0/em2 not assigned\n");
        exit(1);
    }
    $x->interfaces->$phone->enable = '1';
    $x->interfaces->$phone->ipaddr = '10.0.0.1';
    $x->interfaces->$phone->subnet = '24';
    $x->interfaces->$phone->gateway = 'ETPH_GW';
    $x->interfaces->$net->enable = '1';
    $x->interfaces->$net->ipaddr = 'dhcp';
    $x->system->gw_switch_default = '1';
    $cfg->save();

    $gw = new OPNsense\Routing\Gateways();
    $n = $gw->gateway_item->Add();
    $n->setNodes(['name' => 'WAN_DHCP', 'interface' => 'wan', 'ipprotocol' => 'inet', 'gateway' => 'dynamic',
        'defaultgw' => '1', 'monitor_disable' => '0', 'priority' => '10', 'weight' => '1',
        'descr' => 'primary uplink']);
    $n = $gw->gateway_item->Add();
    $n->setNodes(['name' => 'ETPH_GW', 'interface' => $phone, 'ipprotocol' => 'inet', 'gateway' => '10.0.0.2',
        'defaultgw' => '1', 'monitor_disable' => '0', 'monitor' => '10.0.0.2', 'priority' => '250', 'weight' => '1',
        'descr' => 'ETPH phone']);
    $gw->serializeToConfig();
    $cfg->save();
    echo "phone=$phone net=$net\n";
} elseif ($phase == 'group') {
    $gg = new OPNsense\Routing\GatewayGroups();
    $g = $gg->gateway_group->Add();
    $g->setNodes(['name' => 'FAILOVER', 'item' => 'WAN_DHCP', 'item2' => 'ETPH_GW', 'trigger' => 'down',
        'descr' => 'WAN, then the phone']);
    $gg->serializeToConfig();
    $cfg->save();
    echo "group=FAILOVER\n";
} else {
    /* a fresh process: the rule's gateway list is read from the saved config */
    $f = new OPNsense\Firewall\Filter();
    $hit = 0;
    $seq = 1;
    foreach ($f->rules->rule->iterateItems() as $r) {
        if ((string)$r->description == 'Default allow LAN to any rule') {
            $r->gateway = 'FAILOVER';
            $seq = max(1, (int)(string)$r->sequence);
            $r->sequence = (string)($seq + 100);  /* the new rule goes first */
            $hit++;
        }
    }
    /* Policy routing sends everything that matches out of a gateway, the
     * firewall's own DNS (Unbound on the LAN address) included: let LAN
     * traffic to the LAN address through first, without a gateway. */
    $r = $f->rules->rule->Add();
    $r->setNodes(['enabled' => '1', 'action' => 'pass', 'quick' => '1', 'interface' => 'lan', 'direction' => 'in',
        'ipprotocol' => 'inet', 'protocol' => 'any', 'source_net' => 'lan', 'destination_net' => 'lanip',
        'sequence' => (string)$seq, 'description' => 'LAN to this firewall (DNS) without policy routing']);
    $f->serializeToConfig();
    $cfg->save();
    echo "lan_rules_policy_routed=$hit\n";
}
