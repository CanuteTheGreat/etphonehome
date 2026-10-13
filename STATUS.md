# ET Phone Home - Project Status

**Last Updated:** October 13, 2026 (re-audited against actual code/git history/CI config)
**Version:** 0.5.0

---

## Current Status: v0.5.0 shipped, actively maintained

This file was last substantively updated in October 2025 and described v0.4.0 as
the current release with TLS, a desktop GUI, traffic filtering, and root mode
all listed as *future* v0.5.0 work. That's no longer true: v0.5.0 shipped
(`09ef719 Release v0.5.0: GUI, TLS, filtering, root mode, Forgejo CI/CD`) and
all four of those areas have had real commits and bugfixes since, including
several this session (malformed-filter-rule fail-open fix, profile
persistence fixes, protocol version enforcement, root-tethering IP-spoofing
fix). The sections below reflect what's actually true today, verified against
`git log`, the real CI/release workflows, and the code paths that run in
production — not against labels in old docs.

---

## What's actually implemented and wired in (verified)

| Feature | Status | Verified by |
|---|---|---|
| USB / WiFi tethering | Done | Real ADB/TCP connect paths in `desktop/{linux,windows}/src/main.rs` |
| Bluetooth tethering — Android + Linux (BlueZ) | Done | `connect_bluetooth` on Linux completes a real RFCOMM connection |
| Bluetooth tethering — Windows | **Not implemented** (correctly labeled) | `connect_bluetooth` in `desktop/windows/src/main.rs` opens the RFCOMM socket then explicitly `bail!`s with "requires native API integration" — this is an honest, accurate stub, left as-is |
| Auto-reconnect, token auth, mDNS discovery, TOML profiles | Done | Unchanged from v0.4.0, still exercised by tests |
| Protocol version/capability handshake | Done, enforced | Fixed this session (`b0ed3b7`, `5f0cc6c`) — a mismatched version is now rejected instead of silently proceeding |
| Root tethering (Android iptables routing) | Done, actively maintained | `android/.../root/{RootManager,RootTetheringService,IptablesManager,SelinuxHelper}.kt`; this session fixed a real bug where the routing target trusted an attacker-controllable `EXTRA_DESKTOP_IP` (`7fde1ae`) |
| Traffic filtering — Android | Done, enforced on real packets | `FilterEngine.shouldAllow()` is called in both VPN→Desktop forward loops in `VpnService.kt` (lines ~388, ~601); the fail-open malformed-rule bug here was already found and fixed this session |
| Traffic filtering — Desktop (Linux/Windows) | **Fixed today** — was configured but never enforced | See "Found and fixed today" below |
| Desktop GUI (iced-based) | Real, actively maintained — **now actually shipped**, was not | See "Found and fixed today" below |
| TLS / TLS-PSK encryption | **Not wired in — real gap, not yet fixed** | See "Top priority gap" below |

---

## Found and fixed today

### 1. Desktop traffic filter was configured but never enforced (security gap)

`desktop/common/src/filter.rs`'s `FilterEngine` (with real, tested
`should_allow()` logic, including the fail-open fix already landed this
session) was only ever constructed in its own unit tests. Every GUI-edited,
config.toml-persisted filter rule had **zero effect** on real traffic on
Linux and Windows desktop clients: `connect_and_run`/`connect_with_retry` in
`desktop/linux/src/main.rs` and `desktop/windows/src/main.rs` never
constructed a `FilterEngine` or called `should_allow()` anywhere in the TUN
read/forward loop. Users who configured "block this destination" rules in
the GUI were silently unprotected.

Fixed by threading the profile's `FilterConfig` through
`connect_with_profile` → `connect_with_retry` → `connect_and_run` on both
platforms, constructing a `FilterEngine` per connection, and calling
`should_allow()` on every packet read from the TUN interface before it's
forwarded to the Android side — mirroring exactly where Android's
`VpnService` already applies its own filter (VPN→Desktop direction).
Ad-hoc (non-profile) `usb`/`wifi` CLI commands, which have no persisted
`FilterConfig` to draw from, now explicitly pass a disabled default rather
than silently having no filter wiring at all.

Verified: `cargo test -p etphonehome-common` — all 25 unit tests pass
unchanged (including the filter tests), and `cargo check -p
etphonehome-common -p etphonehome-gui` builds clean. The platform-specific
`linux`/`windows` binaries (which need `libdbus-1-dev`/`mingw-w64`, not
available in this environment) are verified by the `build-linux` /
`build-windows` CI jobs on push.

### 2. Desktop GUI was never actually shipped (half-finished feature)

`desktop/gui` (`etphonehome-gui`, iced 0.13) is a real, actively maintained
feature — part of the v0.5.0 release and still getting bugfixes
(`14a1c9b`, `c86c3ab`, `7c4ce66`) — not a stub. But neither
`.forgejo/workflows/rust-ci.yml` nor `release.yml` ever built it as a
standalone binary; it only got compiled incidentally as a side effect of
`cargo test --all`. No CI job caught a GUI-only compile break, and no
release ever shipped a GUI binary — `README.md`/`docs/FAQ.md` still say
"Desktop GUI (ET Phone Home CLI only for now)" even though the feature has
existed and worked since v0.5.0.

Fixed:
- Added a `build-gui` job to `rust-ci.yml` (`cargo build --release -p
  etphonehome-gui`, same dependency set the existing `test` job already
  proves is sufficient) so a GUI compile break now fails CI.
- Added a `build-gui-linux` job to `release.yml` that packages
  `etphonehome-gui` into a release tarball and wired it into
  `create-release`'s `needs` and `files` list, so it now actually ships.
- A Windows GUI release job was intentionally **not** added yet — iced/wgpu
  cross-compiling via mingw-w64 is unverified in this environment and a
  silent broken Windows GUI artifact would be worse than no artifact; this
  is called out below as a follow-up rather than guessed at.
- Updated `README.md`/`docs/FAQ.md` GUI status markers (see below).

Verified: `cargo check -p etphonehome-gui` and `cargo test -p
etphonehome-gui` both pass on a real build (0 tests, clean compile — GUI
crate has no unit tests of its own, consistent with it being a thin iced
app over `etphonehome-common`, whose logic is tested). The new CI jobs
themselves are verified by the actual Forgejo CI run on this change (see PR/
commit).

---

## Top priority gap found (not yet fixed — flagged for deliberate follow-up)

### TLS / TLS-PSK is real, unfinished work — not applied to the wire

Both sides have real, non-trivial, tested crypto code:
- `desktop/common/src/tls/` — `TlsConfig`, `PskIdentity`, HKDF-SHA256
  `derive_psk()`/`verify_psk()` with constant-time comparison, 4 passing
  unit tests, `rustls`/`tokio-rustls` as real dependencies.
- `android/.../tls/{TlsConfig,PskKeyDerivation,TlsSocketWrapper}.kt` — 318
  lines total, matching PSK derivation logic, a real socket-wrapping class.
- The protocol (`protocol.rs` / `Protocol.kt`) already defines `CAP_TLS` and
  `CAP_TLS_PSK` capability bits and a `supports_tls()` helper.

None of it is actually applied to a connection. `supports_tls()` has no
caller anywhere in the codebase. `TlsSocketWrapper` is never referenced from
`VpnService.kt`. No desktop code ever constructs a `rustls`
`ClientConfig`/`ServerConfig` or wraps the TCP/RFCOMM stream in TLS before
handing it to the packet-forwarding loop. **Every WiFi and Bluetooth
connection today is plaintext on the wire**, despite the v0.5.0 release
notes and this doc (pre-today) both implying TLS had shipped.

This was deliberately **not** force-fixed today. Wiring real TLS-PSK into an
existing bespoke binary handshake, across both a Kotlin peer and a Rust
peer, correctly — i.e. actually negotiating it during the handshake,
failing closed if negotiation fails, and not silently falling back to
plaintext — is a genuine cross-cutting protocol change that needs real
device/integration testing (two real peers negotiating over an actual
socket) that isn't available in this environment. A rushed, unverified
patch here risks shipping a connection that *claims* TLS but isn't
actually one — strictly worse than today's honestly-plaintext state. This is
the #1 engineering priority for the next real work session on this project.

---

## Known honest limitations (confirmed not stubs-in-disguise, correctly labeled)

- **Bluetooth on Windows**: explicitly bails out with a clear error and
  concrete alternatives (WiFi mode, native wrapper, manual COM port). This
  label is accurate — leave as-is.
- **Multi-device, usage/data limits, traffic compression, custom DNS**: no
  code exists for any of these anywhere in the repo (checked via grep across
  `desktop/` and `android/`). These are genuinely not-yet-started future
  work, not mislabeled stubs.

---

## Roadmap (ground truth as of this audit)

### Now (highest priority)
1. **Wire TLS-PSK into the real connection handshake** (desktop + Android) —
   see gap above. Must fail closed, not silently downgrade to plaintext.
2. **Windows GUI release artifact** — verify iced/wgpu cross-compiles under
   mingw-w64 the way the CLI already does, then add a `build-gui-windows`
   job to `release.yml` (mirroring `build-windows`).

### Near-term
3. Real-world testing of the now-enforced desktop traffic filter (today's
   fix only has unit-test + compile verification, not a live two-peer
   filtered-connection test).
4. Native Windows Bluetooth support (C++/C# `Windows.Devices.Bluetooth`
   wrapper), or drop the Windows Bluetooth CLI subcommand/profile option
   entirely in favor of pointing users at WiFi mode, which already works.

### Later (v1.0.0, genuinely not started)
5. Multi-device support
6. Traffic monitoring / statistics dashboard beyond basic bytes sent/received
7. Data limits and usage alerts
8. Traffic compression
9. Custom DNS configuration

---

## Build Health (verified today)

- `cargo test -p etphonehome-common -p etphonehome-gui`: 25/25 common tests
  pass, GUI compiles clean (run on an x86_64 macOS host; the Linux-only
  `etphonehome-linux` package depends on `bluer`/dbus and isn't checkable
  there — verified instead via the `build-linux`/`build-windows` Forgejo CI
  jobs, which install `libdbus-1-dev`/`mingw-w64`).
- Android: 6 CI jobs (lint, unit tests, debug build, release build) — all
  green as of the fail-open filter fix earlier this session.

---

*This file is meant to be re-verified against real git history, CI config,
and code wiring — not taken at face value — every time it's updated. See the
methodology note in the project's audit history for why.*
