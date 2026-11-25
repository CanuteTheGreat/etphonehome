# ET Phone Home - Project Status

**Last updated:** 2026-10-21
**Version:** 0.9.4 ([releases](https://github.com/CanuteTheGreat/etphonehome/releases))

Every "Done" below is something a user can do in the shipped app/client and
that a CI-executed test (or the real-phone QA rotation) exercises. Re-verify
against code, CI logs and the phone before changing a row.

## What works end to end

| Feature | Status | Proof |
|---|---|---|
| Desktop gets the phone's internet, no root | Done (v0.6.0) | Userspace relay `android/core/src/main/kotlin/com/etphonehome/relay/UserspaceRelay.kt`. CI e2e (`CI desktop-e2e*.sh`): from a container whose only way out is the tunnel, public HTTPS/HTTP, DNS, 5 MiB TCP, TCP echo both ways, UDP, ICMP, 20 parallel connections. Unit tests `UserspaceRelayTest`. Real phone: see the QA ledger |
| Phone keeps its own internet during a session | Done (v0.6.0) | No phone VPN at all (`TetherService` is a plain foreground service); real-phone QA checks the phone's own ping/HTTP during a session |
| USB (adb forward) | Done | e2e USB scenarios (adb shim with real forward/reverse semantics), real phone |
| WiFi + token | Done | e2e, real phone |
| WiFi encryption (TLS 1.3, token-pinned Ed25519, both ends) | Done (v0.6.0), default on | Android `tls/CertPin.kt` (Bouncy Castle JSSE) + `session/TcpLink.kt`; desktop `tls/certpin.rs`; shared key vector in `CertPinTest` / `pubkey_matches_the_android_vector`; e2e: default TLS, wrong token fails closed, plaintext refused when required |
| Bluetooth: Android + Linux desktop (BlueZ RFCOMM) | Code complete; no real-device evidence yet | `TetherService.serveBluetooth`, `runner.rs connect_bluetooth` |
| Bluetooth: Windows desktop | **Not implemented** | The Windows client says so and points to USB/WiFi; no doc claims otherwise |
| Phone status and live statistics | Done (v0.6.0) | "Waiting for desktop" until a handshake completes; peer, encryption, bytes each way, connections, uptime, filter state (`StatusText`, `StatusTextTest`); notification with Stop |
| Traffic filter on the phone | Done (v0.6.0) | Desktop sends the profile filter as a ConfigUpdate after the handshake (`FilterConfig::to_config_update`, session test); phone applies it to the relay (`FilterConfig.applyUpdate`, `FilterConfigTest`, relay filter tests); e2e checks the phone logged the profile's rules and the blocked traffic fails |
| Traffic filter on the desktop | Done | `session.rs` uplink filter; e2e |
| DNS for the tunnel on the desktop | Done (v0.6.0) | `runner::configure_dns` (resolvectl / netsh); e2e asserts the resolvectl call |
| Auto-reconnect, Ctrl-C disconnect, profiles, mDNS, discover-ip, setup-adb | Done | Unit tests + e2e (mDNS only unit-tested; not testable from CI containers) |
| Desktop GUI | Linux build ships; connects from saved profiles | `desktop/gui`; no Windows GUI build |
| Releases | Published by the `release.yml` CI workflow (Linux/Windows CLI, Linux GUI, APK), verified by the workflow | |

## Not implemented (and not claimed)

- **Root mode.** Removed in v0.6.0: nothing could start it and its iptables
  design redirected the phone's own traffic. A correct design needs kernel
  NAT (a tun device on the phone, `ip_forward`, MASQUERADE out of the
  phone's uplink for the tether subnet). Started: steps 1-6b are
  `RootTunHelper` (launches the root helper under su, `tun-pipe <ifname>`, returns the framed port; the helper binary `tun-pipe` is in desktop/tun-pipe: attaches to the tun device and relays the framed packets; cross-built for the four Android ABIs by the CI helper build-tun-pipe-android.sh and packaged in the APK as lib/<abi>/libtun_pipe.so, CI-checked; nothing in the app runs it yet) + `RootNatSession` (step 6e: su check, upstream discovery, tun + NAT + helper + pump in order with full rollback on any failure and an exact teardown on close; core-tested with a fake root shell, not yet wired to a UI switch) + `KernelNatPlan` + `TunDevicePlan` + `TunPump` + `StreamPacketPort` (framed pipe to the root helper that will hold the tun fd) (core/relay: plans, and the desktop<->tun packet pump that drops spoofed/malformed packets), the validated, tunnel-scoped iptables/ip
  command plan with an exact teardown, unit-tested. Not yet run by anything:
  a real rooted phone (the app has the Root mode switch, off by default, and falls back to the userspace relay when su is refused; byte stats, the desktop's traffic filter and a TCP MSS clamp (best effort) apply in root mode; none of it has run on a rooted device yet), so root mode is not yet claimed as working.
- Windows Bluetooth, multi-device,
  mobile-data usage tracking, Play Store listing.

## Where the evidence lives

- CI: `Rust CI` (fmt, clippy, audit, tests, e2e, builds) and
  `Android CI` (lint, app + core unit tests, debug/release APK).
- Real device: `~/.hermes/state/qa-findings/etphonehome.md` (QA ledger) and
  the etphonehome-real-device-qa procedure.
