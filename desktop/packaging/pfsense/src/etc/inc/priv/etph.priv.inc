<?php
global $priv_list;

$priv_list['page-interfaces-etph'] = array();
$priv_list['page-interfaces-etph']['name'] = "WebCfg - Interfaces: Phone uplink (ETPH)";
$priv_list['page-interfaces-etph']['descr'] = "Allow access to the ETPH phone uplink settings and status.";
$priv_list['page-interfaces-etph']['match'] = array();
$priv_list['page-interfaces-etph']['match'][] = "pkg_edit.php?xml=etph.xml*";
$priv_list['page-interfaces-etph']['match'][] = "status_etph.php*";
