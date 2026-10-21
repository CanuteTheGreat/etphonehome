<?php

namespace OPNsense\Etph\Api;

use OPNsense\Base\ApiMutableServiceControllerBase;
use OPNsense\Core\Backend;

class ServiceController extends ApiMutableServiceControllerBase
{
    protected static $internalServiceClass = '\OPNsense\Etph\Etph';
    protected static $internalServiceTemplate = 'OPNsense/Etph';
    protected static $internalServiceEnabled = 'general.enabled';
    protected static $internalServiceName = 'etph';

    /**
     * The client's status file: state, phone, ETPH Pro, bytes, last error.
     */
    public function phoneAction()
    {
        $out = json_decode((new Backend())->configdRun('etph phone'), true);
        return is_array($out) ? $out : ['state' => 'stopped'];
    }
}
