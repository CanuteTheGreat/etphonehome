# ET Phone Home: threat model and security review

Status: reviewed against `main` on 2026-11-18 (version 0.9.3 sources). Reporting policy: [SECURITY.md](../../SECURITY.md).

## 1. What is being protected

ET Phone Home (ETPH) lets a computer or router use a phone's internet. The phone runs an Android app that relays the computer's TCP, UDP and ping traffic through ordinary phone sockets (a userspace relay, no VPN, no root). The computer or router runs the `etphonehome` client (Linux, Windows, macOS, FreeBSD, OpenWrt, OPNsense, pfSense).

There is **no ETPH server, account system or cloud relay**. Nothing in the data path leaves the cable, the LAN or the Bluetooth link between the two peers. That removes whole classes of risk (credential database, multi-tenant isolation, server RCE) and shapes the boundaries below.

Assets, most valuable first:

| # | Asset | Where it lives |
|---|---|---|
| A1 | The phone's internet connection and data plan (who may use it) | Phone app: `android/core/.../relay/UserspaceRelay.kt`, `android/core/.../session/TetherSession.kt` |
| A2 | The user's traffic in transit between computer/router and phone (confidentiality, integrity) | USB link, Wi-Fi, Bluetooth |
| A3 | The pairing tokens (Wi-Fi, Bluetooth) | Phone: `android/app/.../util/TokenManager.kt` (SharedPreferences, excluded from backup); computer: `auth.wifi_token` in `config.toml` (`desktop/common/src/config.rs`, mode 0600); router: UCI `network.<if>.token` |
| A4 | The router's ADB key (the phone's "always allow from this computer" trust) | `desktop/common/src/adb/key.rs` (0600), `/etc/etph/.android` on routers |
| A5 | The computer's/router's own network configuration (routes, DNS, tun device) | `desktop/common/src/runner.rs`, `host.rs`, `openwrt/etph/files/lib/netifd/` |
| A6 | Release integrity: the binaries, packages and installers users run | `.forgejo/workflows/release.yml`, `keys/release-signing.asc`, `openwrt/etph-openwrt-install.sh` |
| A7 | The Pro entitlement (a one-time Google Play purchase) | `android/core/.../pro/` (low value by design, see `docs/pro.md`) |

Attacker profiles: (a) someone on the same Wi-Fi as the phone or router; (b) another app on the phone; (c) another local user on the computer/router; (d) someone who can read or alter a download or a build; (e) someone with brief physical access to the cable or phone. A fully compromised phone or computer is out of scope: each peer is trusted by the other once paired.

## 2. Trust boundaries

```
                       B3 pairing (token, client id)
  +-----------------+  B1 USB (adb forward / AOA)   +--------------------+
  | computer/router |<------------------------------>| phone (ETPH app)   |--> internet
  |  etphonehome    |  B2 Wi-Fi TLS 1.3 / Bluetooth  |  userspace relay   |    (B7)
  +--------+--------+                                +---------+----------+
           | B4 local control (service socket, LuCI/rpcd, netifd hooks)
  local users / web UI
  ----------------------------------------------------------------------------
  B5 distribution: Forgejo release -> errant.solutions / GitHub / Google Play -> user
  B6 Rustinion: none in this repository (see 3.6)
```

| ID | Boundary | Peers |
|---|---|---|
| B1 | USB link | client <-> phone, through `adb forward` (loopback on the phone) or Android Open Accessory |
| B2 | Network link | client <-> phone over Wi-Fi (TLS 1.3, certificate pinned to the token) or Bluetooth RFCOMM |
| B3 | Enrolment (pairing) | token shown on the phone, entered on the computer/router; client id and paired-computers list |
| B4 | Local control | unprivileged local users/UIs <-> the privileged service and router scripts |
| B5 | Update and distribution path | build -> release -> installer -> device |
| B6 | Rustinion integration | not present in this repository |
| B7 | Phone relay -> internet/LAN | what the phone will connect to on the computer's behalf |

## 3. STRIDE per boundary

Legend: **M** = mitigation in place (file), **R** = residual risk (section 5), **F** = fixed by this review (section 4).

### 3.1 B1 USB link (client <-> phone)

| | Threat | Mitigation / status |
|---|---|---|
| S | An app on the phone connects to the loopback listener and is treated as the USB computer (no token on loopback) | **R-1.** `TetherService.serveTcp` treats loopback as USB. The relay refuses to reach the phone's own loopback/local addresses, so the app gains the phone's internet only, which it already has (`UserspaceRelay.isPhoneLoopback`). |
| S | A hostile computer is plugged in | M: Android asks to authorise the computer's ADB key (`adb/key.rs`) or to open ETPH for the accessory (AOA, `aoa/`). Free tier admits one computer; Pro admits more, the phone lists and can Forget each (`PairedDevices.kt`, `ProGate.kt`). |
| T | Cable-level tampering | Out of scope (physical access to the cable ends the threat model). USB payload is not encrypted; an attacker in the cable sees the traffic of a user who chose USB. |
| R | Who used the phone | M: paired list with last-seen times and per-computer usage (Pro) on the phone only. |
| I | Traffic sniffing on the cable | R-5 (no encryption on USB by design: point-to-point, loopback behind `adb forward`). |
| D | A connection that never finishes the handshake holds the single session slot | M: whole-handshake deadline in `TetherSession.run`, first-byte timeout in `TcpLink.accept`. |
| E | Any app can start USB sharing via the exported `AutoStartActivity` | M: Android 14+ caller check and the Free/Pro gate; **R-2** on older Android. |

### 3.2 B2 Network link (Wi-Fi, Bluetooth)

| | Threat | Mitigation / status |
|---|---|---|
| S | A LAN host impersonates the phone, or a client impersonates the computer | M: mutual TLS 1.3 where each side's Ed25519 key is derived from the token and pinned by the peer; a wrong token aborts the handshake and there is no plaintext fallback (`desktop/common/src/tls/certpin.rs`, `android/core/.../tls/CertPin.kt`, `client.rs`). Router/desktop default to TLS (`cli.rs`, `proto etph` `tls 1`). |
| S | Token guessing | M: 16 characters from a 62-letter alphabet drawn from `SecureRandom` (about 95 bits, `TokenManager.generateToken`); constant-time compare (`tokenMatches`); missing token is rejected (`verifyWifiToken`). An active LAN attacker can read the phone's pinned public key during a TLS handshake (TLS 1.3 encrypts the certificate only against passive observers), which allows an offline guess of the token; 95 bits makes that infeasible. |
| T | Downgrade to plaintext | M: the phone's "require encryption" switch defaults on (`util/Settings.kt`), the desktop never falls back (`tls/config.rs`). Turning it off (`--no-tls`) sends the token in the clear; the phone's on-screen text says so. |
| R | Repudiation | Phone logs the peer address and result of every handshake (`TetherSession`). No signed audit trail: single-user device. |
| I | Traffic confidentiality on Wi-Fi/Bluetooth | M: Wi-Fi is TLS 1.3 AEAD. Bluetooth is RFCOMM between paired devices (link-level encryption by Bluetooth, no TLS) plus the same token check in the handshake (`TetherService.kt`, `verifyBluetoothToken`); its confidentiality is that of the Bluetooth link. |
| D | Handshake flooding, slow clients | M: one session at a time, handshake deadline, `MAX_PAYLOAD_SIZE` frame limit (`protocol/Protocol.kt`, `protocol.rs`). |
| E | A hostile peer injecting packets towards the phone itself or the LAN | M: relay refuses flows to the phone's addresses; traffic filter rules from the profile are enforced on the phone (`filter/`); see B7. |

### 3.3 B3 Enrolment (pairing)

Enrolment is local and manual: the user reads the token on the phone and gives it to the computer (`etphonehome wifi --host ... --token ...`, `--save-profile`, or LuCI "Phone uplink"). USB needs no token; the phone's own authorisation dialogs are the enrolment. There is no enrolment server and no remote enrolment.

| | Threat | Mitigation / status |
|---|---|---|
| S | A client claims another client's id to hide among paired computers | The id is a random 128-bit value kept next to the config (`identity.rs`); it is a label for the phone's list, not a credential (the token and the TLS pin are). A second computer with the same id only appears as the same entry. |
| T | Token read from disk by another local user | M: `config.toml` is written 0600 through a fresh file (`config.rs::save`, tests `save_never_writes_tokens_into_a_world_readable_file`); router token is in `/etc/config/network` (root, 0600) and passed to the client by environment, not argv (`proto etph`); LuCI passes it via environment (`etph-luci`). |
| I | Token in `ps` | **R-4**: `etphonehome wifi --token` still takes it on the command line (`ETPH_TOKEN` is supported). The phone's instruction text shows the argv form. |
| I | Token in cloud backup | M: excluded in `res/xml/backup_rules.xml` and `data_extraction_rules.xml`. |
| R | Rotating a leaked token | M: "new token" in the app (refused while sharing); computers re-pair. |
| D | Pairing spam | M: the paired list keeps at most 32 entries (`PairedDevices.MAX`) and Forget is one tap. |
| E | Routers/extra computers without Pro | M: `ProGate.admit` runs after the token check (`MessageStream.kt` `ServerHandshake`), so an unauthenticated peer learns nothing about Pro state. |

### 3.4 B4 Local control (service, LuCI, netifd)

| | Threat | Mitigation / status |
|---|---|---|
| S/E | A local user drives the root service (`etphonehome service`, macOS/Windows/BSD) | By design the socket (`/var/run/etphonehome.sock`, 0666) accepts only: status, disconnect, connect over USB, or connect to a profile an administrator saved. It cannot name a new target (`daemon.rs::start`). **R-3**: any local user can start/stop a session. Windows adds a token file readable by local users, to keep web pages from driving it. |
| D | Oversized request or connection flood on that socket | **F-2**: bounded request line and bounded connection count (`daemon.rs`: `MAX_REQUEST_LINE`, `MAX_CONTROL_CONNECTIONS`, tests in `request_line_tests`). |
| T | Shell/UCI injection through LuCI or netifd | M: `etph-luci` validates interface names (`valid_name`), transport, host (`[A-Za-z0-9.:_-]`) and numeric port; the token is quoted and comes from the environment; rpcd ACL limits LuCI to `etph-luci` (`luci-app-etph.json`). The client builds every OS command from argument vectors (`Command::new(..).args(..)`), never through a shell; phone-supplied values (device name, DNS, addresses) reach hooks only as environment variables that `etph-hook` quotes. |
| T | Hook scripts | The `--up-script/--down-script` run as the client's user and are configured by an administrator; the phone cannot choose them. |
| I | Status files | `/var/run/etph/*.json` hold state (pid, interface, phone name, byte counters), never the token (`host.rs::Status`). |
| D | Hotplug storms | `30-etph` only nudges running clients (SIGUSR1) and `ifup`s uplinks that asked for it; repeated events are harmless. |

### 3.5 B5 Update and distribution path

ETPH has no self-updater. Android updates come from Google Play (or a manually installed APK); desktop and router packages come from the release page or the installers.

| | Threat | Mitigation / status |
|---|---|---|
| T | Tampered or substituted package | M: every release carries `SHA256SUMS`, a detached OpenPGP signature `SHA256SUMS.asc` (key `5439 3549 39B9 C055 2A68  E9BB E323 A6A3 AC0A 6506`, `keys/release-signing.asc`), and a CycloneDX SBOM `etphonehome-<tag>.cdx.json` covered by the sums. `sign-sha256sums.sh` fails closed and re-verifies with `gpgv` against only the committed key. The APK is also signed with the ETPH release key (`sign-apk.sh`, signer job on a dedicated runner). |
| T | Router installer fetches an unverified package | **F-1**: `openwrt/etph-openwrt-install.sh` now refuses to install any package whose SHA-256 is not in the release's `SHA256SUMS`, rejects unsafe tag/architecture strings, and fails closed when the sums are missing. Tests: `openwrt/test/installer-test.sh` (run in `android-ci.yml` on every branch push). **R-6** remains: the router cannot check the OpenPGP signature. |
| S | Forged "release" on a mirror | M: signature check on a workstation (SECURITY.md); public GitHub mirror is a copy, not a root of trust. |
| T | Compromised build or dependency | M: `cargo audit` and OSV-Scanner gates (`rust-ci.yml`, `android-ci.yml`, `osv-scanner.toml`), a dedicated signer runner for the APK and the OpenPGP key, SBOM per release, source-tree check that shipped files name only public hosts (`check-no-internal-hosts.py`). |
| E | Signing key theft | The OpenPGP key and the Android keystore live in CI secrets only; `sign-sha256sums.sh` imports the key into a 0700 temp keyring and removes it. |
| R | Unsigned platform installers | **R-7**: the release pipeline has no Authenticode step for the Windows MSI, and the macOS package is ad-hoc signed and not notarized (`desktop/packaging/macos/build-pkg.sh`, `docs/DESKTOP.md`). Integrity is the signed `SHA256SUMS` only. |
| D | Release asset upload drops | M: upload retry loops in `release.yml`. |

### 3.6 B6 Rustinion integration

There is none in this repository. `grep -ri rustinion` finds only two CI helper scripts (`ec2-guard.sh`, `mutation-incremental.py`). On router images that also carry the Rustinion agent (the Patronus images), the two are separate processes with separate credentials; ETPH exposes no remote management plane, accepts no commands from Rustinion, and writes no secret that Rustinion reads. If such an integration is added (for example fleet inventory of paired phones), it needs its own review: do not forward the token or the ADB key, and expose status files only.

### 3.7 B7 Phone relay -> internet/LAN

| | Threat | Mitigation / status |
|---|---|---|
| E | The computer reaches services on the phone's own host (other apps' localhost servers, ETPH's own token-free loopback listener) | M: `UserspaceRelay.isPhoneLoopback` / `config.localAddress` check on TCP and UDP; IPv6 loopback and v4-mapped addresses are refused. |
| E | The relay is used to reach the phone's LAN | Accepted by design: the computer gets the phone's network, like any tether. The traffic filter can block ranges (`filter/FilterEngine.kt`). |
| D | Flow exhaustion | M: `maxTcpFlows`, bounded queues in `UserspaceRelay`, data limit (Pro). |
| I | The phone's DNS answers | The phone's resolver is trusted; the desktop points DNS at it (`runner.rs`). |

## 4. Code security review: findings

Scope: enrolment and pairing (`identity.rs`, `config.rs`, `TokenManager.kt`, `PairedDevices.kt`), authentication (`tls/`, `CertPin.kt`, `ServerHandshake`, `TcpLink.kt`), update/distribution (`release.yml`, `sign-sha256sums.sh`, `gen-sbom.py`, installer), the OpenWrt package (`openwrt/etph`, `openwrt/luci-app-etph`, `build-packages.py`), the local control service (`daemon.rs`) and the Android manifest.

| ID | Severity | Finding | Status |
|---|---|---|---|
| F-1 | Medium | The OpenWrt/GL.iNet installer downloaded the package and installed it (`apk add --allow-untrusted`, `opkg install <file>`) with no integrity check, and built URLs from an unvalidated tag (including the tag read from the GitHub API) and architecture string. Anyone able to alter the download (a mirror, a proxy in front of the download host) could run code as root on the router. | Fixed: SHA-256 gate against the release `SHA256SUMS`, strict tag/arch validation, fail closed. `openwrt/test/installer-test.sh` covers verified, tampered, missing sums, unlisted package, unsafe tag, unsafe arch for both opkg and apk. |
| F-2 | Medium | The root control service read request lines with no length limit and accepted unlimited connections on a socket every local user can write to (and on Windows on a loopback port), so a local unprivileged user could exhaust the service's memory. | Fixed: `read_request_line` caps a line at 16 KiB and refuses the rest, at most 32 concurrent control connections. Unit tests plus a socket test (oversized request gets an error and the service keeps answering). |
| F-3 | Medium (release pipeline) | `release.yml`'s checksums job uploaded the SBOM twice in a row; on a real release the second upload of the same asset name would fail or duplicate the asset, failing the release at the very step that signs it. | Fixed (duplicate removed); the throwaway-tag run in section 6 exercises the path. |
| R-1 | Low | A token-free loopback listener exists whenever Wi-Fi mode runs, reachable by other apps on the phone. | Accepted: gives only the phone's own internet, the relay blocks the phone's loopback; the phone shows the session. Revisit if per-app network isolation is ever needed. |
| R-2 | Low | `AutoStartActivity` is exported (adb must start it). Android 14+ checks the caller; older versions do not. | Accepted: only starts USB sharing, behind the Pro gate. |
| R-3 | Low | The service socket is writable by every local user. | Accepted: capability is limited (section 3.4). Possible hardening: peer-credential check plus a group. |
| R-4 | Low | `--token` on the command line is visible to other local users in `ps`. | Open: use `ETPH_TOKEN`; the on-screen instruction could switch to a token prompt. |
| R-5 | Info | USB link is unencrypted by design. | Accepted. |
| R-6 | Low | Routers verify SHA-256 only, not the OpenPGP signature (no `gpgv` on OpenWrt). | Accepted: the sums and packages come over the same TLS origin; verify the signature on a workstation before installing in high-assurance setups. A `usign` signature would close it. |
| R-7 | Low | No Authenticode signing for the Windows MSI, macOS package not notarized. | Accepted until a signing identity exists; documented in `docs/DESKTOP.md`. |

Checked and fine: token generation and comparison, null-token rejection, handshake deadline, TLS pin and no-plaintext fallback, config/ADB-key file modes, backup exclusions, argv-only command execution, LuCI input validation, rpcd ACL scope, hotplug/netifd scripts, test-hook receiver disabled in release builds (`android:enabled="${billingTestHooks}"`), public-repo leak guard (`check-no-internal-hosts.py`).

## 5. Residual risk summary

1. Pairing relies on the secrecy of a 16-character token typed or pasted by the user; anyone who sees it (screen, shell history, `ps`) can pair until the token is rotated.
2. USB mode trusts whoever is on the cable and any app on the phone that can reach loopback.
3. A compromised phone or computer owns the session; the peers are mutually trusted after pairing.
4. Release integrity on routers is a hash check; the OpenPGP signature is checked off-device.
5. Windows and macOS installers are not vendor-signed.

## 6. Release pipeline proof

The signed `SHA256SUMS` and SBOM path is proven on a throwaway pre-release tag and then deleted, see the dated entry in [CHANGELOG.md](../../CHANGELOG.md) and `STATUS.md`.

## 7. Maintenance

Revisit this document with every change to the handshake or pairing flow, the control service, the OpenWrt package or `release.yml`. New findings go in section 4 with a severity; every High/Medium needs a fix and a test in the same change.
