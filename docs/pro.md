# ETPH Free and Pro

One app (`solutions.errant.etph`, Google Play "Errant Solutions ETPH"), two tiers, decided by
the owner on 2026-10-20:

| | Free | ETPH Pro |
|---|---|---|
| USB to one computer, no speed cap, no time limit | yes | yes |
| Wi-Fi (TLS 1.3) and Bluetooth | - | yes |
| Routers (OpenWrt / GL.iNet uplink, `proto etph`) | - | yes |
| Several computers and routers | one computer | yes |
| Auto-start when the computer asks over USB; keeps waiting after a disconnect | start/stop by hand | yes |
| Data usage per computer/router and by kind of traffic | live numbers only | yes |

Pro is a one-time (non-consumable) Google Play in-app product, id `etph_pro_lifetime`.

## Where the split is enforced: the phone

Everything Pro is enforced in the app, never on computers or routers:

- `android/core/.../pro/ProGate.kt` decides (pure Kotlin, `ProGateTest`):
  transports (Wi-Fi/Bluetooth need Pro), admission at the handshake (routers need Pro; Free
  admits one computer, the one used most recently, so a refunded phone keeps working with the
  computer it was last used with), auto-start and keep-listening-after-a-session.
- `TetherService` refuses to start Wi-Fi/Bluetooth without Pro, runs `ProGate.admit` after the
  token check of every handshake, and on Free stops sharing when the computer leaves.
- The handshake carries the entitlement both ways (protocol, backward compatible):
  - request: the client appends a client id (random, kept next to its config; on routers
    `/etc/etph/.config/etphonehome/client-id`) and its host name; routers set `CAP_ROUTER`
    (`etphonehome --router`, passed by `proto etph`);
  - response: `CAP_PRO` (bit 8) when the phone has Pro;
  - refusal: `ERR_PRO_REQUIRED` (0x06) with a message starting "Pro required on the phone: ".
    Clients print it plus "nothing needs to be bought or licensed on this computer or router",
    keep retrying once a minute (buying Pro on the phone, or forgetting the other computer there,
    fixes it without touching the client), the GUI shows it, and LuCI shows "ETPH Pro needed on
    the phone" and whether the phone has Pro.
- Auto-start: Android doesn't let an app start a foreground service from a "USB connected"
  broadcast, so the computer starts it: when it finds the phone on USB but nothing listening, it
  runs `adb shell am start -n solutions.errant.etph/com.etphonehome.AutoStartActivity` (the
  built-in ADB host on routers does the same through the adb `shell:` service), once per plug-in
  (so a Stop tapped on the phone is respected until the cable is replugged). The phone decides:
  Free refuses, Pro starts USB sharing if "Start when the computer asks" is on.

## Purchases: Play Billing, verified on the phone, no backend

`android/app/.../billing/`: Play Billing Library 9.1 (`PlayBillingGateway`, a thin wrapper)
behind the `BillingGateway` seam; every decision is in `core/.../pro/Entitlement.kt`
(`EntitlementEngine`, tested with a fake gateway in `EntitlementEngineTest`):

- purchase flow -> Play's purchase update -> verified -> Pro; acknowledged right away (Play
  refunds purchases that aren't acknowledged within 3 days);
- pending purchases (cash/bank payment not made yet) stay Free with a note until Play confirms;
- restore: Play is asked on every app start, every Start, every return to the app and on
  "Restore purchase", so a reinstall or a new phone with the same Google account gets Pro back;
  Family Library sharing works the same way if it is enabled for the product in Play Console;
- refunds, chargebacks and revocations: the purchase disappears from Play's answer and the app
  goes back to Free on the next check;
- offline: the last purchase Play confirmed is kept (re-verified on load) and keeps Pro for 30
  days without Play, so a phone that is itself the uplink doesn't lose Pro.

Local verification: Play signs each purchase's JSON with the app's RSA licence key;
`PurchaseVerifier` checks that signature with the public key compiled in from
`android/app/play-license-key.txt` (Play Console > Monetize with Play > Monetization setup >
Licensing; it is public, so it is committed), then the package name, product id and state.
While that file is missing the app can't check signatures and trusts Play's answer as-is
(`publish-play.yml` warns about it).

Why no backend: ETPH has no accounts and no servers in the data path; Pro is a one-time unlock of
features that run only on the phone; the app is MIT-licensed, so anyone who wants to bypass the
gate can build it from source anyway. A server would add a service that handles purchase data,
a failure mode for an app that is often used exactly when the phone is the only internet, and
running cost, against very little abuse it could stop. Play's own answer (asked often, see
above) plus the signature check covers the realistic cases (a patched Play Store or a hooked
billing service). If abuse ever shows up, the SSHerpa design (Play Developer API check with the
`big-double-d-ci@` service account, `play_purchases` table) can be added without app changes
beyond sending the purchase token.

## Tests

- JVM: `ProGateTest`, `PairedDevicesTest`, `UsageLedgerTest`, `PurchaseVerifierTest` (real RSA
  signatures), `EntitlementEngineTest` (fake Play: purchase, acknowledge, restore, pending,
  refund, forged purchase, offline grace, no Play Store, test override), `TrafficKindTest`,
  `ServerHandshakeTest` (Pro gate after the token check), shared protocol vectors (Kotlin + Rust),
  `ProTextTest`; Rust: identity, `pro_required` + slow retry, auto-start arguments.
- Desktop e2e (`desktop-e2e-inner.sh`, stand-in phone `--free`): first computer admitted, second
  computer and a router refused with the message, a Pro phone admits a router, auto-start asked
  once per plug-in.
- Emulator e2e (`android-emulator-e2e.sh`, the real app on API 26/34/36, built with
  `-PbillingTestHooks=true` so `TestHookReceiver` can switch it to Pro; these images have no Play
  Store, so the app starts Free): Free status line, Wi-Fi refused with the Pro note and the
  Upgrade screen, free stops after a session, router and second computer refused, auto-start
  refused; then Pro: auto-start from a closed app, keeps waiting after a session, second computer
  and router admitted, data usage shown. Play bundles are checked to have
  `BILLING_TEST_HOOKS = false`.
- Real purchases can only be tested by a Play licence tester on a Play-installed build.
