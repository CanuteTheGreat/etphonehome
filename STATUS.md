# ET Phone Home - Project Status

**Last Updated:** October 13, 2026 (TLS cert-pinning wired into Linux desktop, verified with live two-peer network capture)
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
| TLS / TLS-PSK encryption | **Fixed and verified this session (Linux desktop only)** -- see below | See "Found and fixed today" below |

---

## Found and fixed today

### 0. TLS is now wired into a real connection path and verified with live traffic (Linux desktop)

**Design change from the original plan (owner-approved):** literal TLS-PSK
cipher suites (RFC 4279/8446 external PSK) are not implementable with the
crypto libraries already in this codebase -- `rustls` has never supported
them, and Android's `javax.net.ssl` has no public PSK cipher-suite API
either. Instead, the existing HKDF-SHA256 derivation in
`desktop/common/src/tls/psk.rs` (unchanged, still used) now feeds a new
**cert-pinning** layer (`desktop/common/src/tls/certpin.rs`):

- Both peers derive an Ed25519 keypair deterministically from the same
  pairing token (a second, domain-separated HKDF expand of the same
  token -- not the literal `derive_psk()` output, to avoid key reuse).
- Each peer wraps its local keypair in a self-signed certificate and
  presents it during a standard mutual TLS 1.3 handshake.
- Instead of validating against a CA (there is none), a custom `rustls`
  `ServerCertVerifier`/`ClientCertVerifier` checks the peer's certificate
  against the *expected* Ed25519 public key -- the one this side would
  itself derive from the same token. A different/wrong token produces a
  different expected key, so `rustls` aborts the handshake. There is no
  code path that falls back to plaintext on a pin mismatch.

**What's wired in (Linux desktop only):**
- `desktop/common/src/client.rs`: new `connect_tls_async()` performs the
  TCP connect, TLS handshake, and the application-level `HandshakeReq`/
  `HandshakeResp` exchange entirely over the async runtime (required
  because `tokio_rustls` has no public API to graft an
  already-completed *synchronous* `rustls` session onto an async socket
  afterward -- the original plaintext `Client`/`into_stream()` hand-off
  pattern genuinely cannot be reused for TLS). The resulting
  `tokio_rustls::client::TlsStream` is handed straight to the existing
  packet-forwarding loop, which only ever calls generic
  `AsyncRead`/`AsyncWrite` methods and needed no changes to work with it.
- `desktop/linux/src/main.rs`: `connect_and_run`/`connect_with_retry` take
  a new `tls_enabled` parameter; a new `--tls` CLI flag on `wifi` and a new
  `config.auth.tls_enabled` profile setting drive it. `--tls` requires
  `--token`/a configured token (cert pinning has no trust anchor without
  one) and **fails the connection outright** rather than silently
  continuing in plaintext if the peer's cert doesn't match the pin.
  Connection logs now say explicitly `Transport security: ENCRYPTED
  (cert-pinned TLS 1.3)` or `PLAINTEXT (no TLS requested)` -- this is the
  only place in the app that made any claim about encryption status, and
  it's now accurate.
- Backward compatible: `tls_enabled` defaults to `false`, so existing
  saved profiles/configs keep working exactly as before. Nothing is forced
  on until a user explicitly opts in.

**What's verified, with real evidence (not just unit tests):** two
independent AWS EC2 Ubuntu 22.04 hosts (`ron-test-EP`, `ron-test-E2`,
`us-west-2`, terminated after the test), real `etphonehome-linux --release`
binaries built from this exact code, connected over the real routable
network between them (WiFi mode's transport is plain TCP, so this needed
no radio hardware), with `tcpdump` capturing every byte on the wire:

1. **Plaintext baseline** (today's pre-existing mode, unchanged): the
   capture (`cap_plain.pcap`) contains the literal auth token
   (`plaintoken123`) and a test payload marker in cleartext -- confirming
   the baseline really is unencrypted, as this doc has said all along.
2. **TLS, matching token**: `TLS handshake complete: protocol=TLSv1_3
   cipher=TLS13_AES_256_GCM_SHA384` on both the real client binary and a
   protocol-compatible test-server peer (see below). The capture
   (`cap_tls_match.pcap`) contains a real ClientHello record (`16 03 01`),
   6 TLS 1.3 application-data records (`17 03 03`), and **zero** occurrences
   of the token or the test payload marker anywhere in the 3KB capture --
   i.e. genuinely encrypted, not just wrapped.
3. **TLS, wrong token**: the real `etphonehome` binary refuses to connect --
   `TLS handshake failed: ... certificate pin mismatch ... refusing to
   connect`, exits non-zero. The server independently sees and rejects the
   same handshake (`received fatal alert: HandshakeFailure`). The capture
   (`cap_tls_mismatch.pcap`) shows a ClientHello went out but the
   connection aborted before any application data -- no fallback, no leak.

**Honest scope limits of this pass:**
- **Only Linux desktop is verified.** `desktop/windows` was not touched
  and does not have `--tls` wired in; Windows WiFi connections remain
  plaintext-only until the same pattern is ported there (should be a small
  change -- `connect_tls_async` and `certpin` are both platform-agnostic
  `etphonehome-common` code; the work is in `desktop/windows/src/main.rs`'s
  CLI/call-site wiring, mirroring what was just done in `desktop/linux`).
- **Android was not touched.** `android/.../tls/TlsSocketWrapper.kt` still
  uses a trust-all `X509TrustManager` (`checkServerTrusted`/
  `checkClientTrusted` do nothing) and is still never called from
  `VpnService.kt`. Porting the same cert-pinning design to Android is
  real, separate work: derive the same Ed25519 keypair from the token
  (standard JCA `Ed25519` key factory, available since API 33, or
  BouncyCastle for older API levels), build a self-signed cert, and
  replace `TlsSocketWrapper`'s trust managers with ones that pin against
  the expected key the same way `certpin.rs` does -- then wire the result
  into `VpnService.kt`'s accept loop and `performHandshake()`. Until that
  lands, **a real Desktop<->Android connection today is still plaintext,
  even with `--tls` passed on the desktop side** -- the desktop binary will
  correctly *fail to connect* to an unpatched Android build (TLS
  handshake against a plain socket just times out/resets), rather than
  silently downgrading, but it won't get you an encrypted connection to
  today's Android app either. Treat `--tls` as "ready for use once Android
  ships the matching change," not as working fleet-wide yet.
- **Bluetooth was not touched.** Only the WiFi (plain-TCP) code path got
  `--tls` wired in; Bluetooth RFCOMM connections are unaffected and remain
  plaintext as before.
- The two-peer verification used a small protocol-compatible Rust test
  harness (`desktop/testpeer/`, explicitly excluded from the real build
  workspace -- see its own doc comment), **not** the real Android server,
  because no Android device/emulator with real network bridging was
  available in this environment. It speaks the exact same wire protocol
  (`desktop/common/src/protocol.rs` framing) and the exact same
  `etphonehome_common::tls::certpin` code the real client uses, so it
  proves the desktop-side crypto and protocol wiring is real and correct
  end-to-end over a real network -- it does not prove Android
  interoperability, which remains unverified until Android is updated.

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

## Known honest limitations (confirmed not stubs-in-disguise, correctly labeled)

- **Bluetooth on Windows**: explicitly bails out with a clear error and
  concrete alternatives (WiFi mode, native wrapper, manual COM port). This
  label is accurate — leave as-is.
- **TLS on Windows desktop, Android, and Bluetooth**: real, correct
  cert-pinning code exists and is verified-working on Linux desktop WiFi
  (see above) but is not yet wired into these other paths. Not stubs, just
  genuinely not done yet — see the scope-limits list above for exactly
  what's needed for each.
- **Multi-device, usage/data limits, traffic compression, custom DNS**: no
  code exists for any of these anywhere in the repo (checked via grep across
  `desktop/` and `android/`). These are genuinely not-yet-started future
  work, not mislabeled stubs.

---

## Roadmap (ground truth as of this audit)

### Now (highest priority)
1. **Port cert-pinned TLS to Android** (`TlsSocketWrapper.kt` + `VpnService.kt`)
   — see the Android scope-limit note above. Until this lands, real
   Desktop<->Android connections stay plaintext even with `--tls` set on
   the desktop side (it will just fail to connect to an unpatched Android
   build, not silently downgrade).
2. **Port `--tls` wiring to Windows desktop** (`desktop/windows/src/main.rs`)
   — the underlying `connect_tls_async`/`certpin` code is already
   platform-agnostic; only the CLI/call-site wiring from
   `desktop/linux/src/main.rs` needs mirroring.
3. **Windows GUI release artifact** — verify iced/wgpu cross-compiles under
   mingw-w64 the way the CLI already does, then add a `build-gui-windows`
   job to `release.yml` (mirroring `build-windows`).

### Near-term
4. Real-world testing of the now-enforced desktop traffic filter (today's
   fix only has unit-test + compile verification, not a live two-peer
   filtered-connection test).
5. Extend TLS to the Bluetooth RFCOMM code path once WiFi+Android are solid.
6. Native Windows Bluetooth support (C++/C# `Windows.Devices.Bluetooth`
   wrapper), or drop the Windows Bluetooth CLI subcommand/profile option
   entirely in favor of pointing users at WiFi mode, which already works.

### Later (v1.0.0, genuinely not started)
7. Multi-device support
8. Traffic monitoring / statistics dashboard beyond basic bytes sent/received
9. Data limits and usage alerts
10. Traffic compression
11. Custom DNS configuration

---

## Build Health (verified today)

- `cargo test -p etphonehome-common`: 32/32 tests pass (was 25; +7 new
  `tls::certpin` tests), including TLS client/server config construction,
  deterministic pubkey derivation, and cert pin-embedding roundtrips — run
  on an x86_64 macOS host.
- `cargo check -p etphonehome-gui`: compiles clean, unaffected by the TLS
  change (GUI doesn't drive real connections yet, so there was nothing to
  wire there this pass — it also made zero encryption-status claims before
  today, so there was no false claim to fix in the GUI specifically).
- `etphonehome-linux --release`: built and run for real (not just checked)
  on two fresh Ubuntu 22.04 EC2 hosts — see the TLS verification section
  above for the live two-peer test results, including `tcpdump` evidence.
- Android: 6 CI jobs (lint, unit tests, debug build, release build) — all
  green as of the fail-open filter fix earlier this session.

---

*This file is meant to be re-verified against real git history, CI config,
and code wiring — not taken at face value — every time it's updated. See the
methodology note in the project's audit history for why.*
