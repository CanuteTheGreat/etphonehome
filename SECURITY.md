# Security policy

## Reporting a vulnerability

Email security@errant.solutions with a description, affected version and reproduction steps. Please do not open a public issue for a vulnerability. You will get an acknowledgement within 3 business days and a fix or mitigation plan within 30 days for confirmed issues; we credit reporters who want it. The policy is also published at https://errant.solutions/.well-known/security.txt.

## Supported versions

The latest release (and the current main branch) receives security fixes. Older releases are not patched; update to the newest release.

## Verifying a release

Every release carries `SHA256SUMS` covering every file, a detached OpenPGP signature `SHA256SUMS.asc`, and a CycloneDX SBOM `etphonehome-<tag>.cdx.json` (the Rust crates as resolved for the release and the Android release classpath from `android/app/gradle.lockfile`; the SBOM is listed in `SHA256SUMS`, so it is signed too).

The signing key is "Errant Solutions Release Signing <security@errant.solutions>", fingerprint `5439 3549 39B9 C055 2A68  E9BB E323 A6A3 AC0A 6506`. The public key is in this repository (`keys/release-signing.asc`) and at https://errant.solutions/signing-key.asc.

    gpg --import keys/release-signing.asc
    gpg --verify SHA256SUMS.asc SHA256SUMS
    sha256sum -c --ignore-missing SHA256SUMS

The Android APK is additionally signed with the ETPH release key (`apksigner verify --print-certs`), and the release workflow refuses to publish a release if signing fails.

## Security design in brief

- Desktop client and phone talk over a TLS 1.3 tunnel in WiFi mode (encryption can be required in the app); USB mode runs over the loopback `adb forward`.
- Dependencies are gated in CI: `cargo audit` for Rust and OSV-Scanner over the locked Gradle graph for Android (High/Critical findings fail the build unless documented with an expiry).
- No release secrets are stored in the repository; the signing keys live in CI secrets.
