'use strict';
'require view';
'require fs';
'require ui';
'require poll';

// Network > Phone uplink: status of every `proto etph` interface (phone,
// transport, throughput), USB pairing state, Wi-Fi pairing, and a one-step
// setup that creates the interface in the WAN firewall zone.

var HELPER = '/usr/libexec/etph-luci';

function call(args, env) {
	return fs.exec(HELPER, args, env).then(function(r) {
		try {
			return JSON.parse(r.stdout || 'null');
		} catch (e) {
			return { ok: false, error: (r.stderr || r.stdout || String(e)).trim() };
		}
	});
}

function fmtBytes(n) {
	n = +n || 0;
	var u = [ 'B', 'KiB', 'MiB', 'GiB', 'TiB' ], i = 0;
	while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
	return (i ? n.toFixed(1) : n) + ' ' + u[i];
}

function fmtRate(n) {
	return fmtBytes(n) + '/s';
}

function fmtSince(t) {
	if (!t) return '-';
	var s = Math.max(0, Math.floor(Date.now() / 1000) - t);
	var h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60);
	return (h ? h + 'h ' : '') + m + 'm ' + (s % 60) + 's';
}

function stateLabel(sess, nif) {
	if (!sess) return nif && nif.up ? _('Connected') : _('Not running');
	switch (sess.state) {
	case 'connected': return _('Connected');
	case 'connecting': return _('Connecting to the phone (attempt %d)').format(sess.attempt || 1);
	case 'authorizing': return _('Unlock your phone and tap Allow ("Allow USB debugging?")');
	case 'retrying': return _('Waiting for the phone, retrying');
	case 'failed': return _('Stopped: %s').format(sess.last_error || '');
	case 'stopped': return _('Stopped');
	}
	return sess.state;
}

function notify(res) {
	if (res && res.ok)
		ui.addNotification(null, E('p', res.message || _('Done')), 'info');
	else
		ui.addNotification(null, E('p', (res && res.error) || _('Failed')), 'danger');
}

return view.extend({
	load: function() {
		return call([ 'status' ]);
	},

	renderUplink: function(u) {
		var sess = null, nif = null;
		try { sess = u.session ? JSON.parse(u.session) : null; } catch (e) {}
		try { nif = u.netifd ? JSON.parse(u.netifd) : null; } catch (e) {}
		var up = sess && sess.state == 'connected';
		var rows = [
			[ _('Status'), E('strong', { 'id': 'etph-state-' + u.name, 'style': up ? 'color:green' : '' }, stateLabel(sess, nif)) ],
			[ _('Phone'), (sess && sess.device) || '-' ],
			[ _('ETPH Pro on the phone'), !sess || sess.phone_pro == null ? '-' : (sess.phone_pro ? _('yes')
				: _('no (free version: routers need ETPH Pro, bought once in the ETPH app on the phone; nothing to buy for the router)')) ],
			[ _('Connection'), (sess && sess.state == 'connected' && sess.encrypted) || u.transport == 'wifi'
				? _('Wi-Fi to %s, %s').format(u.host || '?', sess && sess.encrypted ? _('encrypted') : _('not encrypted'))
				: (u.transport == 'auto' ? _('USB cable when plugged in, else Wi-Fi to %s').format(u.host || '-') : _('USB cable')) ],
			[ _('Connected for'), up ? fmtSince(sess.connected_since) : '-' ],
			[ _('Download / upload now'), up ? fmtRate(sess.rate_received) + ' / ' + fmtRate(sess.rate_sent) : '-' ],
			[ _('Data this session'), sess ? fmtBytes(sess.bytes_received) + ' / ' + fmtBytes(sess.bytes_sent) : '-' ],
			[ _('Firewall zone'), u.zone || E('em', _('none: add the interface to the wan zone so LAN devices get internet')) ],
			[ _('Router address on the link'), (nif && nif['ipv4-address'] && nif['ipv4-address'][0])
				? nif['ipv4-address'][0].address + '/' + nif['ipv4-address'][0].mask : '-' ]
		];
		if (sess && sess.last_error && !up)
			rows.push([ (sess.last_error.indexOf('Pro required on the phone') >= 0) ? _('ETPH Pro needed on the phone') : _('Last error'),
				E('code', sess.last_error) ]);
		if (sess && sess.last_end && !up)
			rows.push([ _('Last session ended'), sess.last_end ]);

		var buttons = [
			E('button', { 'class': 'cbi-button cbi-button-action', 'click': ui.createHandlerFn(this, function() {
				return call([ 'retry', u.name ]).then(notify);
			}) }, _('Reconnect now')),
			' ',
			E('a', { 'class': 'cbi-button', 'href': L.url('admin/network/network') }, _('Edit interface')),
			' '
		];
		if (u.transport == 'wifi') {
			buttons.push(E('button', { 'class': 'cbi-button cbi-button-negative', 'click': ui.createHandlerFn(this, function() {
				if (!confirm(_('Forget the pairing token? The router stops using this phone until you pair again.')))
					return;
				return call([ 'unpair', u.name ]).then(notify).then(function() { location.reload(); });
			}) }, _('Unpair')));
			if (!u.paired) {
				var tok = E('input', { 'type': 'password', 'class': 'cbi-input-text', 'placeholder': _('token from the app') });
				buttons.push(' ', tok, ' ', E('button', { 'class': 'cbi-button cbi-button-positive', 'click': ui.createHandlerFn(this, function() {
					return call([ 'settoken', u.name ], { ETPH_PAIR_TOKEN: tok.value }).then(notify).then(function() { location.reload(); });
				}) }, _('Pair')));
			}
		}
		buttons.push(' ', E('button', { 'class': 'cbi-button cbi-button-remove', 'click': ui.createHandlerFn(this, function() {
			if (!confirm(_('Remove the phone uplink "%s"?').format(u.name)))
				return;
			return call([ 'remove', u.name ]).then(notify).then(function() { location.reload(); });
		}) }, _('Remove')));

		return E('div', { 'class': 'cbi-section' }, [
			E('h3', _('Uplink "%s"').format(u.name)),
			E('table', { 'class': 'table' }, rows.map(function(r) {
				return E('tr', { 'class': 'tr' }, [ E('td', { 'class': 'td left', 'width': '33%' }, r[0]), E('td', { 'class': 'td left' }, r[1]) ]);
			})),
			E('div', { 'class': 'right' }, buttons)
		]);
	},

	renderUsb: function(st) {
		var body = [];
		var phones = [];
		try { phones = JSON.parse(st.usb_phones || '[]'); } catch (e) {}
		body.push(E('p', phones.length ? _('Phones with USB debugging on this router\'s USB port:')
			: _('No phone with USB debugging on the USB port. On the phone: Settings > About phone > tap Build number 7 times; Settings > Developer options > USB debugging on. Open ETPH, choose USB, tap Start, then plug it in.')));
		if (phones.length)
			body.push(E('ul', phones.map(function(d) {
				return E('li', [ E('strong', d.product || d.serial), ' (', d.serial, ')' ]);
			})));
		if (st.adb_fingerprint)
			body.push(E('p', [ _('The first time, the phone asks "Allow USB debugging?": unlock it, check that it shows this router\'s key fingerprint, tick "Always allow from this computer" and tap Allow.'), E('br'), E('code', st.adb_fingerprint) ]));
		else if (st.adb_key_pending)
			body.push(E('p', [ _('The router is creating its USB (adb) key; this takes up to a minute or two on small routers. '),
				E('button', { 'class': 'cbi-button', 'click': ui.createHandlerFn(this, function() {
					return call([ 'keygen' ]).then(notify);
				}) }, _('Create it now')) ]));
		body.push(E('p', { 'class': 'cbi-value-description' }, _('The phone charges from the router while plugged in; a low-power router port may charge it slowly. Android\'s own "USB tethering" is a different feature and not needed (leave it off).')));
		return E('div', { 'class': 'cbi-section' }, [ E('h3', _('USB')), E('div', {}, body) ]);
	},

	renderSetup: function(st) {
		var name = E('input', { 'class': 'cbi-input-text', 'value': 'phone', 'size': 12 });
		var transport = E('select', { 'class': 'cbi-input-select' }, [
			E('option', { 'value': 'usb' }, _('USB cable')),
			E('option', { 'value': 'wifi' }, _('Wi-Fi')),
			E('option', { 'value': 'auto' }, _('USB when plugged in, else Wi-Fi'))
		]);
		var host = E('input', { 'class': 'cbi-input-text', 'placeholder': '192.168.8.153' });
		var token = E('input', { 'class': 'cbi-input-text', 'type': 'password', 'placeholder': _('token from the app') });
		var found = E('span');
		var discover = E('button', { 'class': 'cbi-button', 'click': ui.createHandlerFn(this, function() {
			found.textContent = _('Searching...');
			return call([ 'discover' ]).then(function(list) {
				list = Array.isArray(list) ? list : [];
				if (!list.length) { found.textContent = _('No phone found (start ETPH in WiFi mode on a phone on this network).'); return; }
				var a = (list[0].addresses || [])[0];
				if (a) host.value = a;
				found.textContent = _('Found: %s').format(list.map(function(d) { return (d.addresses || []).join(', '); }).join('; '));
			});
		}) }, _('Find phone'));
		var row = function(label, el) {
			return E('div', { 'class': 'cbi-value' }, [ E('label', { 'class': 'cbi-value-title' }, label), E('div', { 'class': 'cbi-value-field' }, el) ]);
		};
		return E('div', { 'class': 'cbi-section' }, [
			E('h3', _('Add a phone uplink')),
			E('p', _('Creates a network interface using the phone as internet, in the wan firewall zone, so every device on this router gets the phone\'s connection. On the phone: open ETPH, pick USB or WiFi and tap Start.')),
			row(_('Interface name'), name),
			row(_('Connection'), transport),
			row(_('Phone address (Wi-Fi)'), [ host, ' ', discover, ' ', found ]),
			row(_('Pairing token (Wi-Fi)'), token),
			E('div', { 'class': 'right' }, E('button', { 'class': 'cbi-button cbi-button-positive', 'click': ui.createHandlerFn(this, function() {
				return call([ 'setup', name.value, transport.value, host.value ], { ETPH_PAIR_TOKEN: token.value })
					.then(function(res) {
						notify(res);
						if (res && res.ok && res.needs_restart) {
							if (!confirm(_('Restart the router\'s network now to start the phone uplink? All connections drop for a few seconds (a repeater re-joins its upstream Wi-Fi). You can also reboot later.')))
								return;
							return call([ 'netrestart' ]).then(function() {
								ui.addNotification(null, E('p', _('Network restarting; reload this page in a few seconds.')), 'info');
							});
						}
						location.reload();
					});
			}) }, _('Set up')))
		]);
	},

	render: function(st) {
		st = st || {};
		var self = this;
		var m = E('div', {}, [
			E('h2', _('Phone uplink (Errant Solutions ETPH)')),
			E('div', { 'class': 'cbi-map-descr' }, _('Use a phone running the ETPH app as this router\'s internet connection, over a USB cable or Wi-Fi. Client %s.').format(st.version || '?'))
		]);
		(st.interfaces || []).forEach(function(u) { m.appendChild(self.renderUplink(u)); });
		m.appendChild(this.renderUsb(st));
		m.appendChild(this.renderSetup(st));
		if (st.mwan3_installed)
			m.appendChild(E('p', _('mwan3 is installed: add the phone interface under Network > MultiWAN Manager to use it for failover or load balancing.')));

		poll.add(function() {
			return call([ 'status' ]).then(function(n) {
				(n && n.interfaces || []).forEach(function(u) {
					var el = document.getElementById('etph-state-' + u.name);
					if (!el) return;
					var sess = null;
					try { sess = u.session ? JSON.parse(u.session) : null; } catch (e) {}
					el.textContent = stateLabel(sess, null);
				});
			});
		}, 5);
		return m;
	},

	handleSave: null,
	handleSaveApply: null,
	handleReset: null
});
