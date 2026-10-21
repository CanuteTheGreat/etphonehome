<?php
/* CI only: turn on OPNsense's own SSH service (System: Settings:
 * Administration) with a key for root, so it survives the service reloads
 * the test triggers. Usage: php opnsense-ssh.php '<ssh public key>' */
require_once 'config.inc';
require_once 'util.inc';

use OPNsense\Core\Config;

$cfg = Config::getInstance();
$x = $cfg->object();
if (!isset($x->system->ssh)) {
    $x->system->addChild('ssh');
}
$x->system->ssh->enabled = 'enabled';
$x->system->ssh->permitrootlogin = '1';
$x->system->ssh->interfaces = 'lan';
foreach ($x->system->user as $u) {
    if ((string)$u->name == 'root') {
        $u->authorizedkeys = base64_encode($argv[1] . "\n");
    }
}
$cfg->save();
echo "ssh enabled\n";
