<?php

namespace OPNsense\Etph\Api;

use OPNsense\Base\ApiMutableModelControllerBase;

class SettingsController extends ApiMutableModelControllerBase
{
    protected static $internalModelName = 'etph';
    protected static $internalModelClass = 'OPNsense\Etph\Etph';
}
