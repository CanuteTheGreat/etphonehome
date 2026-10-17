# ET Phone Home - Project Status

**Last updated:** 2026-10-17
**Version:** 0.6.0 ([releases](https://git.canutethegreat.com/CanuteTheGreat/etphonehome/releases))

Every "Done" below is something a user can do in the shipped app/client and
that a CI-executed test (or the real-phone QA rotation) exercises. Re-verify
against code, CI logs and the phone before changing a row.

## What works end to end

| Feature | Status | Proof |
|---|---|---|
| Desktop gets the phone's internet, no root | Done (v0.6.0) | Userspace relay `android/core/src/main/kotlin/com/etphonehome/relay/UserspaceRelay.kt`. CI e2e (`.forgejo/scripts/desktop-e2e*.sh`): from a container whose only way out is the tunnel, public HTTPS/HTTP, DNS, 5 MiB TCP, TCP echo both ways, UDP, ICMP, 20 parallel connections. Unit tests `UserspaceRelayTest`. Real phone: see the QA ledger |
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
| Releases | Published to Forgejo by `release.yml` (Linux/Windows CLI, Linux GUI, APK), verified by the workflow | |

## Not implemented (and not claimed)

- **Root mode.** Removed in v0.6.0: nothing could start it and its iptables
  design redirected the phone's own traffic. A correct design needs kernel
  NAT (a tun device on the phone, `ip_forward`, MASQUERADE out of the
  phone's uplink for the tether subnet) and is not started.
- Windows Bluetooth, macOS client, IPv6 through the tunnel, multi-device,
  mobile-data usage tracking, Play Store listing.

## Where the evidence lives

- CI: Forgejo Actions `Rust CI` (fmt, clippy, audit, tests, e2e, builds) and
  `Android CI` (lint, app + core unit tests, debug/release APK).
- Real device: `~/.hermes/state/qa-findings/etphonehome.md` (QA ledger) and
  the etphonehome-real-device-qa procedure.
