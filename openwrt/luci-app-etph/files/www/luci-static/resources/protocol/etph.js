'use strict';
'require form';
'require network';

// Network > Interfaces: "ETPH phone uplink" protocol (netifd proto etph,
// /lib/netifd/proto/etph.sh). The router's internet comes from a phone
// running the Errant Solutions ETPH app, over USB (adb) or Wi-Fi (TLS).
network.registerPatternVirtual(/^etph-.+$/);

return network.registerProtocol('etph', {
	getI18n: function() {
		return _('ETPH phone uplink');
	},

	getIfname: function() {
		return this._ubus('l3_device') || 'etph-%s'.format(this.sid).substring(0, 15);
	},

	getOpkgPackage: function() {
		return 'etph';
	},

	isFloating: function() {
		return true;
	},

	isVirtual: function() {
		return true;
	},

	getDevices: function() {
		return null;
	},

	containsDevice: function(ifname) {
		return (network.getIfnameOf(ifname) == this.getIfname());
	},

	renderFormOptions: function(s) {
		var o;

		o = s.taboption('general', form.ListValue, 'transport', _('Connection to the phone'),
			_('USB: enable USB debugging, start ETPH in USB mode, plug the phone into the router and tap Allow on the phone. Wi-Fi: start ETPH in WiFi mode and copy its address and token.'));
		o.value('usb', _('USB cable (adb)'));
		o.value('wifi', _('Wi-Fi (encrypted, pairing token)'));
		o.value('auto', _('USB when plugged in, else Wi-Fi'));
		o.default = 'usb';

		o = s.taboption('general', form.Value, 'host', _('Phone address'),
			_('Shown in the ETPH app in WiFi mode, e.g. 192.168.8.153'));
		o.depends('transport', 'wifi');
		o.depends('transport', 'auto');
		o.datatype = 'host';

		o = s.taboption('general', form.Value, 'token', _('Pairing token'),
			_('The token the ETPH app shows in WiFi mode'));
		o.depends('transport', 'wifi');
		o.depends('transport', 'auto');
		o.password = true;

		o = s.taboption('general', form.Value, 'serial', _('Phone serial'),
			_('Only needed when several phones are plugged in (see Network > Phone uplink)'));
		o.depends('transport', 'usb');
		o.depends('transport', 'auto');
		o.optional = true;

		o = s.taboption('advanced', form.Flag, 'tls', _('Encrypt (TLS 1.3)'),
			_('Turn off only if the app\'s "Require encryption" switch is off'));
		o.depends('transport', 'wifi');
		o.default = o.enabled;

		o = s.taboption('advanced', form.Value, 'port', _('Port'));
		o.datatype = 'port';
		o.placeholder = '8888';

		o = s.taboption('advanced', form.Value, 'ipaddr', _('Tunnel address'),
			_('This end of the link to the phone; pick a subnet your LAN does not use'));
		o.datatype = 'cidr4';
		o.placeholder = '10.0.0.1/24';

		o = s.taboption('advanced', form.Flag, 'defaultroute', _('Use default gateway'),
			_('If unchecked, no default route is configured'));
		o.default = o.enabled;

		o = s.taboption('advanced', form.Value, 'metric', _('Use gateway metric'));
		o.datatype = 'uinteger';
		o.placeholder = '0';

		o = s.taboption('advanced', form.Flag, 'peerdns', _('Use DNS server from the phone'));
		o.default = o.enabled;

		o = s.taboption('advanced', form.DynamicList, 'dns', _('Use custom DNS servers'));
		o.depends('peerdns', '0');
		o.datatype = 'ipaddr';
	}
});
