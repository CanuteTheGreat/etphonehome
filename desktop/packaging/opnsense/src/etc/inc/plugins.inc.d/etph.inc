<?php

/*
 * ETPH (Errant Solutions): an Android phone as an OPNsense uplink.
 * Registers the service and the etph0 device, so the phone shows up under
 * Interfaces: Assignments and its gateway can join gateway groups.
 */

function etph_enabled()
{
    $mdl = new \OPNsense\Etph\Etph();
    return (string)$mdl->general->enabled == '1';
}

function etph_services()
{
    $services = [];
    if (etph_enabled()) {
        $services[] = [
            'description' => gettext('ETPH phone uplink'),
            'configd' => [
                'restart' => ['etph restart'],
                'start' => ['etph start'],
                'stop' => ['etph stop'],
            ],
            'name' => 'etph',
            'pidfile' => '/var/run/etphonehome.pid',
        ];
    }
    return $services;
}

function etph_devices()
{
    return [[
        'configurable' => false,
        'pattern' => '^etph',
        'type' => 'etph',
        'volatile' => true,
        'names' => [
            'etph0' => [
                'descr' => 'etph0 (ETPH phone uplink)',
                'ifdescr' => 'PHONE',
                'name' => 'etph0',
            ],
        ],
    ]];
}
