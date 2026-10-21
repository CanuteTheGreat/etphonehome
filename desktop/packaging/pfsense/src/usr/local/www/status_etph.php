<?php
/*
 * status_etph.php
 *
 * ETPH (Errant Solutions): the phone link's live state.
 * Licensed under the MIT License.
 */

##|+PRIV
##|*IDENT=page-interfaces-etph
##|*NAME=Interfaces: Phone uplink (ETPH)
##|*DESCR=Allow access to the 'Interfaces: Phone uplink (ETPH)' pages.
##|*MATCH=status_etph.php*
##|-PRIV

require_once("guiconfig.inc");
require_once("/usr/local/pkg/etph.inc");

$st = etph_status();
if (isset($_GET['json'])) {
	header("Content-Type: application/json");
	echo json_encode($st);
	exit;
}

$pgtitle = [gettext("Interfaces"), gettext("Phone uplink (ETPH)"), gettext("Status")];
$pglinks = ["", "pkg_edit.php?xml=etph.xml", "@self"];
include("head.inc");
$tab_array = [];
$tab_array[] = [gettext("Settings"), false, "pkg_edit.php?xml=etph.xml"];
$tab_array[] = [gettext("Status"), true, "status_etph.php"];
display_top_tabs($tab_array);

$rows = [
	gettext("State") => $st['state'] ?? '',
	gettext("Phone") => $st['device'] ?? '',
	gettext("ETPH Pro on the phone") => isset($st['phone_pro']) ? ($st['phone_pro'] ? gettext("yes") : gettext("no")) : '',
	gettext("Connection") => trim(($st['transport'] ?? '') . ' ' . ($st['target'] ?? '')) .
		(!empty($st['encrypted']) ? ' (' . gettext("encrypted") . ')' : ''),
	gettext("Interface") => $st['interface'] ?? 'etph0',
	gettext("Connected since") => !empty($st['connected_since']) ? date('Y-m-d H:i:s', $st['connected_since']) : '',
	gettext("Received") => isset($st['bytes_received']) ? format_bytes($st['bytes_received']) : '',
	gettext("Sent") => isset($st['bytes_sent']) ? format_bytes($st['bytes_sent']) : '',
	gettext("Last error") => $st['last_error'] ?? '',
];
?>
<div class="panel panel-default">
	<div class="panel-heading"><h2 class="panel-title"><?=gettext("Phone link")?></h2></div>
	<div class="panel-body">
		<table class="table table-striped table-condensed" id="etph-status">
<?php foreach ($rows as $k => $v): ?>
			<tr><th><?=htmlspecialchars($k)?></th><td><?=htmlspecialchars((string)$v)?></td></tr>
<?php endforeach; ?>
		</table>
	</div>
</div>
<?php include("foot.inc"); ?>
