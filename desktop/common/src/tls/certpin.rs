//! Cert-pinned TLS for ET Phone Home
//!
//! ## Why cert-pinning instead of literal TLS-PSK cipher suites
//!
//! The original design called for RFC 4279/8446-style TLS-PSK (a literal PSK
//! cipher suite, no certificates at all). That is not implementable with the
//! crypto libraries already in this codebase's dependency trees:
//! - `rustls` (desktop/Rust side) has never supported external PSK cipher
//!   suites -- only certificate-based auth.
//! - `javax.net.ssl` (Android side) has no public PSK cipher-suite API either.
//!
//! Decision (owner-approved): keep the existing HKDF-SHA256 derivation in
//! [`crate::tls::psk`] -- it's real, tested, and still useful -- but
//! repurpose its output as a **pinned-certificate trust anchor** instead of
//! a literal PSK key. Concretely:
//!
//! 1. Both peers derive the same 32-byte secret from the shared pairing
//!    token via `psk::derive_psk()`.
//! 2. That secret is used (via a second, domain-separated HKDF expand) as
//!    the deterministic seed for an Ed25519 keypair. Both peers that know
//!    the same token derive the **identical keypair** -- and therefore the
//!    identical public key -- independently, with no certificate exchange
//!    needed in advance.
//! 3. Each peer wraps its local keypair in a self-signed X.509 certificate
//!    and presents it during the TLS handshake (mutual TLS -- both client
//!    and server present a cert).
//! 4. Instead of validating against a CA chain (there is none), each side's
//!    custom `rustls` verifier checks that the peer's certificate contains
//!    the **expected** Ed25519 public key -- i.e. the one it itself would
//!    derive from the same token. If the token differs (wrong PSK/pairing
//!    code), the derived keys differ, the pin check fails, and `rustls`
//!    aborts the handshake. There is no path that falls back to plaintext.
//!
//! This gets the same practical security property as PSK (mutual
//! authentication rooted in a shared secret, no CA needed) while running
//! over a completely standard TLS 1.3 handshake (real ClientHello/
//! ServerHello, real ECDHE, real AEAD-encrypted records) that any TLS
//! library can terminate.

use rcgen::{CertificateParams, KeyPair, PKCS_ED25519};
use ring::hkdf;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{verify_tls12_signature, verify_tls13_signature, CryptoProvider};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::server::WebPkiClientVerifier;
use rustls::{DigitallySignedStruct, DistinguishedName, Error as TlsError, SignatureScheme};
use std::sync::Arc;

/// Domain-separation label for the cert-identity seed, distinct from the
/// `PSK_IDENTITY` label `derive_psk()` already uses -- so the 32 bytes we
/// feed into Ed25519 key generation are never the literal same bytes as a
/// (hypothetical, unused) literal PSK key.
const CERT_IDENTITY_INFO: &[u8] = b"etphonehome-certpin-identity-v1";

/// Standard fixed PKCS#8 v1 DER prefix for an Ed25519 private key (RFC 8410).
/// The only variable part is the trailing 32-byte seed.
const ED25519_PKCS8_PREFIX: [u8; 16] = [
    0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20,
];

/// Raw Ed25519 OID bytes as they appear in an X.509 SubjectPublicKeyInfo's
/// AlgorithmIdentifier (`id-Ed25519`, 1.3.101.112).
const ED25519_OID: [u8; 5] = [0x06, 0x03, 0x2b, 0x65, 0x70];

/// Derive the deterministic Ed25519 seed used as this peer's certificate
/// identity, from the same pairing token `derive_psk()` already consumes.
fn derive_cert_seed(token: &str) -> [u8; 32] {
    // Re-run HKDF-Extract with the same salt/IKM as derive_psk, but expand
    // with a different info label so this seed is cryptographically
    // independent of (and never equal to) the psk module's own output, even
    // though both ultimately trace back to the same token.
    let salt = hkdf::Salt::new(hkdf::HKDF_SHA256, b"ETPhoneHome-TLS-Salt");
    let prk = salt.extract(token.as_bytes());
    let info = [CERT_IDENTITY_INFO];
    let okm = prk
        .expand(&info, hkdf::HKDF_SHA256)
        .expect("HKDF expand should not fail");
    let mut seed = [0u8; 32];
    okm.fill(&mut seed).expect("HKDF fill should not fail");
    seed
}

/// Build an `rcgen::KeyPair` deterministically from a 32-byte Ed25519 seed.
fn keypair_from_seed(seed: &[u8; 32]) -> KeyPair {
    let mut der = Vec::with_capacity(ED25519_PKCS8_PREFIX.len() + 32);
    der.extend_from_slice(&ED25519_PKCS8_PREFIX);
    der.extend_from_slice(seed);
    let pkcs8 = PrivatePkcs8KeyDer::from(der);
    KeyPair::from_pkcs8_der_and_sign_algo(&pkcs8, &PKCS_ED25519)
        .expect("deterministic Ed25519 PKCS8 DER should always parse")
}

/// Generate a fresh self-signed certificate + private key for this peer,
/// deterministically derived from `token`. Both sides of a connection that
/// share the same token derive the same public key (but each generates its
/// own cert instance -- the cert itself need not be byte-identical, only the
/// embedded public key, which is what gets pinned).
pub fn self_signed_identity(token: &str) -> (CertificateDer<'static>, PrivateKeyDer<'static>) {
    let seed = derive_cert_seed(token);
    let keypair = keypair_from_seed(&seed);

    let mut params = CertificateParams::new(Vec::<String>::new()).expect("empty SAN list is valid");
    // A non-empty subject/issuer: the Android app's TLS stack (Bouncy
    // Castle) rejects certificates whose issuer is an empty DN, and the
    // phone checks this client certificate. Only the key is pinned, so the
    // name is cosmetic.
    let mut dn = rcgen::DistinguishedName::new();
    dn.push(rcgen::DnType::CommonName, "etphonehome");
    params.distinguished_name = dn;
    let cert = params
        .self_signed(&keypair)
        .expect("self-signed cert generation should not fail");

    let cert_der = cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(keypair.serialize_der().into());
    (cert_der, key_der)
}

/// Split one DER TLV off the front of `input`: returns `(tag, contents, rest)`.
/// Only what an X.509 certificate needs is supported (single-byte tags,
/// definite lengths up to 4 length bytes); anything else is `None`.
fn der_tlv(input: &[u8]) -> Option<(u8, &[u8], &[u8])> {
    let (&tag, after_tag) = input.split_first()?;
    if tag & 0x1f == 0x1f {
        return None; // high-tag-number form: never used in the fields we walk
    }
    let (&first, mut after_len) = after_tag.split_first()?;
    let len = if first < 0x80 {
        first as usize
    } else {
        let n = (first & 0x7f) as usize;
        if n == 0 || n > 4 || after_len.len() < n {
            return None; // indefinite length is not DER; >4 bytes is absurd here
        }
        let mut len = 0usize;
        for &b in &after_len[..n] {
            len = (len << 8) | b as usize;
        }
        after_len = &after_len[n..];
        len
    };
    if after_len.len() < len {
        return None;
    }
    Some((tag, &after_len[..len], &after_len[len..]))
}

/// Extract the raw 32-byte Ed25519 public key from a DER certificate's
/// SubjectPublicKeyInfo. Returns `None` unless the certificate's *own* key
/// (the SPKI field of TBSCertificate, which is the key the TLS handshake
/// signature is verified against) is an Ed25519 key in the RFC 8410
/// encoding: `SEQUENCE { SEQUENCE { OID 1.3.101.112 } BIT STRING (0 unused
/// bits, 32-byte key) }`.
///
/// This walks the DER structure to the SPKI field instead of searching the
/// raw bytes for the Ed25519 OID. A byte search can be fooled: a peer could
/// present a certificate for an ECDSA/RSA key it controls and carry the
/// bytes `OID(Ed25519) || 03 21 00 || <pinned key>` somewhere else in it (a
/// custom extension, a name attribute). The search would "find" the pinned
/// key, the pin check would pass, and rustls would then verify the handshake
/// signature against the peer's real, unpinned key -- a full pin bypass for
/// anyone who knows the pinned public key, which is not secret (every peer
/// sends it in its certificate).
fn extract_ed25519_pubkey(cert: &CertificateDer<'_>) -> Option<[u8; 32]> {
    // Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signature }
    let (tag, cert_body, trailing) = der_tlv(cert.as_ref())?;
    if tag != 0x30 || !trailing.is_empty() {
        return None;
    }
    let (tag, tbs, _) = der_tlv(cert_body)?;
    if tag != 0x30 {
        return None;
    }

    // TBSCertificate ::= SEQUENCE { [0] version OPTIONAL, serialNumber,
    //   signature, issuer, validity, subject, subjectPublicKeyInfo, ... }
    let mut rest = tbs;
    let (tag, _, after) = der_tlv(rest)?;
    if tag == 0xa0 {
        rest = after;
    }
    // serialNumber INTEGER, then signature/issuer/validity/subject SEQUENCEs.
    for expected in [0x02u8, 0x30, 0x30, 0x30, 0x30] {
        let (tag, _, after) = der_tlv(rest)?;
        if tag != expected {
            return None;
        }
        rest = after;
    }

    // SubjectPublicKeyInfo ::= SEQUENCE { algorithm AlgorithmIdentifier, subjectPublicKey BIT STRING }
    let (tag, spki, _) = der_tlv(rest)?;
    if tag != 0x30 {
        return None;
    }
    let (tag, algorithm, after_alg) = der_tlv(spki)?;
    // RFC 8410: the Ed25519 AlgorithmIdentifier is exactly the OID, no parameters.
    if tag != 0x30 || algorithm != ED25519_OID {
        return None;
    }
    let (tag, bits, after_bits) = der_tlv(after_alg)?;
    if tag != 0x03 || !after_bits.is_empty() || bits.len() != 33 || bits[0] != 0x00 {
        return None;
    }
    let mut key = [0u8; 32];
    key.copy_from_slice(&bits[1..]);
    Some(key)
}

/// Compute the Ed25519 public key this peer expects the other side to
/// present, derived from the same pairing token.
pub fn expected_pubkey(token: &str) -> [u8; 32] {
    let seed = derive_cert_seed(token);
    let keypair = keypair_from_seed(&seed);
    keypair
        .public_key_raw()
        .try_into()
        .expect("Ed25519 public key is always 32 bytes")
}

fn provider() -> Arc<CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

/// A `ServerCertVerifier` that accepts a server certificate if and only if
/// it contains the expected (token-derived) Ed25519 public key -- a "pin",
/// not a CA chain. Any other certificate, including a validly-signed one
/// from a real CA, is rejected: there is no path to plaintext or to
/// accepting an unpinned cert.
#[derive(Debug)]
pub struct PinnedServerVerifier {
    expected: [u8; 32],
    provider: Arc<CryptoProvider>,
}

impl PinnedServerVerifier {
    pub fn new(token: &str) -> Arc<Self> {
        Arc::new(Self {
            expected: expected_pubkey(token),
            provider: provider(),
        })
    }
}

impl ServerCertVerifier for PinnedServerVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, TlsError> {
        match extract_ed25519_pubkey(end_entity) {
            Some(actual) if actual == self.expected => Ok(ServerCertVerified::assertion()),
            Some(_) => Err(TlsError::General(
                "certificate pin mismatch: peer's identity key does not match the key derived \
                 from this device's pairing token (wrong/mismatched PSK) -- refusing to connect"
                    .into(),
            )),
            None => Err(TlsError::General(
                "peer certificate did not contain an Ed25519 public key in the expected format"
                    .into(),
            )),
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

/// Mirror of [`PinnedServerVerifier`] for the server side validating the
/// client's certificate during mutual TLS.
#[derive(Debug)]
pub struct PinnedClientVerifier {
    expected: [u8; 32],
    provider: Arc<CryptoProvider>,
}

impl PinnedClientVerifier {
    pub fn new(token: &str) -> Arc<Self> {
        Arc::new(Self {
            expected: expected_pubkey(token),
            provider: provider(),
        })
    }
}

impl ClientCertVerifier for PinnedClientVerifier {
    fn offer_client_auth(&self) -> bool {
        true
    }

    fn client_auth_mandatory(&self) -> bool {
        true
    }

    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }

    fn verify_client_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _now: UnixTime,
    ) -> Result<ClientCertVerified, TlsError> {
        match extract_ed25519_pubkey(end_entity) {
            Some(actual) if actual == self.expected => Ok(ClientCertVerified::assertion()),
            Some(_) => Err(TlsError::General(
                "certificate pin mismatch: peer's identity key does not match the key derived \
                 from this device's pairing token (wrong/mismatched PSK) -- refusing to connect"
                    .into(),
            )),
            None => Err(TlsError::General(
                "peer certificate did not contain an Ed25519 public key in the expected format"
                    .into(),
            )),
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

// `WebPkiClientVerifier` is unused directly (we implement our own pinned
// verifier instead of a CA-chain one) -- referenced here only so the import
// above compiles under all feature combinations without an unused-import
// warning if a future change swaps verifiers; harmless no-op today.
#[allow(dead_code)]
fn _silence_unused_import(_: Option<Arc<WebPkiClientVerifier>>) {}

/// Build a `rustls::ClientConfig` that performs mutual, cert-pinned TLS
/// rooted in `token`: it presents a self-signed cert derived from `token`
/// and will only accept a peer cert containing the same token's derived
/// public key.
pub fn client_tls_config(token: &str) -> Result<rustls::ClientConfig, rustls::Error> {
    let (cert, key) = self_signed_identity(token);
    let verifier = PinnedServerVerifier::new(token);

    rustls::ClientConfig::builder_with_provider(provider())
        .with_safe_default_protocol_versions()
        .expect("TLS1.2/1.3 are always supported by the ring provider")
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_client_auth_cert(vec![cert], key)
}

/// Build a `rustls::ServerConfig` mirroring [`client_tls_config`] for the
/// server side (e.g. a test-harness server peer).
pub fn server_tls_config(token: &str) -> Result<rustls::ServerConfig, rustls::Error> {
    let (cert, key) = self_signed_identity(token);
    let verifier = PinnedClientVerifier::new(token);

    rustls::ServerConfig::builder_with_provider(provider())
        .with_safe_default_protocol_versions()
        .expect("TLS1.2/1.3 are always supported by the ring provider")
        .with_client_cert_verifier(verifier)
        .with_single_cert(vec![cert], key)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tls::psk::derive_psk;

    /// Cross-language vector: android/core CertPinTest pins the same key for
    /// the same token, so the phone's TLS server and this client agree.
    #[test]
    fn pubkey_matches_the_android_vector() {
        let hex: String = expected_pubkey("pairing-token")
            .iter()
            .map(|b| format!("{:02x}", b))
            .collect();
        assert_eq!(
            hex,
            "357bab9a687ac8e8569c1f6bcfa2d336006951b3967f3570b4042a1ca187a414"
        );
    }

    #[test]
    fn same_token_derives_same_pubkey() {
        let a = expected_pubkey("shared-pairing-code");
        let b = expected_pubkey("shared-pairing-code");
        assert_eq!(a, b);
    }

    #[test]
    fn different_token_derives_different_pubkey() {
        let a = expected_pubkey("pairing-code-a");
        let b = expected_pubkey("pairing-code-b");
        assert_ne!(a, b);
    }

    #[test]
    fn cert_embeds_the_expected_pubkey() {
        let token = "roundtrip-test-token";
        let (cert, _key) = self_signed_identity(token);
        let embedded = extract_ed25519_pubkey(&cert).expect("cert should contain Ed25519 key");
        assert_eq!(embedded, expected_pubkey(token));
    }

    /// The pin must be checked against the key the handshake is actually
    /// signed with. A certificate for an attacker-held ECDSA key that merely
    /// *carries* the pinned Ed25519 key's bytes (here in a custom extension,
    /// laid out exactly like an Ed25519 SPKI) must not pass.
    #[test]
    fn pinned_key_bytes_elsewhere_in_a_non_ed25519_cert_do_not_pass_the_pin() {
        let token = "pin-bypass-test-token";
        let pinned = expected_pubkey(token);

        let mut decoy = ED25519_OID.to_vec();
        decoy.extend_from_slice(&[0x03, 0x21, 0x00]);
        decoy.extend_from_slice(&pinned);

        let attacker_key = KeyPair::generate().expect("P-256 key generation");
        let mut params =
            CertificateParams::new(Vec::<String>::new()).expect("empty SAN list is valid");
        params
            .custom_extensions
            .push(rcgen::CustomExtension::from_oid_content(
                &[1, 3, 6, 1, 4, 1, 55555, 1],
                decoy.clone(),
            ));
        let cert = params
            .self_signed(&attacker_key)
            .expect("self-signed cert generation should not fail");
        let der = cert.der().clone();

        // The decoy really is in the certificate bytes, so a raw byte search
        // for the Ed25519 OID would find the pinned key in it.
        assert!(der
            .as_ref()
            .windows(decoy.len())
            .any(|w| w == decoy.as_slice()));

        assert_eq!(extract_ed25519_pubkey(&der), None);
        let verifier = PinnedServerVerifier::new(token);
        assert!(verifier
            .verify_server_cert(
                &der,
                &[],
                &ServerName::try_from("etphonehome").unwrap(),
                &[],
                UnixTime::now(),
            )
            .is_err());
        let client_verifier = PinnedClientVerifier::new(token);
        assert!(client_verifier
            .verify_client_cert(&der, &[], UnixTime::now())
            .is_err());
    }

    #[test]
    fn genuine_pinned_cert_passes_both_verifiers() {
        let token = "pin-accept-test-token";
        let (cert, _key) = self_signed_identity(token);
        PinnedServerVerifier::new(token)
            .verify_server_cert(
                &cert,
                &[],
                &ServerName::try_from("etphonehome").unwrap(),
                &[],
                UnixTime::now(),
            )
            .expect("cert derived from the same token must pass the pin");
        PinnedClientVerifier::new(token)
            .verify_client_cert(&cert, &[], UnixTime::now())
            .expect("cert derived from the same token must pass the pin");
    }

    #[test]
    fn truncated_or_garbage_certs_are_rejected_without_panicking() {
        let (cert, _key) = self_signed_identity("truncation-test-token");
        let bytes = cert.as_ref();
        for len in 0..bytes.len() {
            let truncated = CertificateDer::from(bytes[..len].to_vec());
            assert_eq!(extract_ed25519_pubkey(&truncated), None, "len {len}");
        }
        let mut trailing = bytes.to_vec();
        trailing.push(0x00);
        assert_eq!(
            extract_ed25519_pubkey(&CertificateDer::from(trailing)),
            None
        );
        assert_eq!(
            extract_ed25519_pubkey(&CertificateDer::from(vec![
                0x30, 0x84, 0xff, 0xff, 0xff, 0xff
            ])),
            None
        );
    }

    #[test]
    fn cert_seed_differs_from_raw_psk() {
        // Domain separation: the cert identity seed must not equal the
        // literal derive_psk() output for the same token.
        let token = "domain-separation-test";
        let psk = derive_psk(token);
        let cert_seed = derive_cert_seed(token);
        assert_ne!(psk.key.as_slice(), cert_seed.as_slice());
    }

    #[test]
    fn client_and_server_configs_build_successfully() {
        let token = "config-build-test";
        client_tls_config(token).expect("client config should build");
        server_tls_config(token).expect("server config should build");
    }

    #[test]
    fn a_cert_for_another_token_fails_both_pins() {
        let (cert, _key) = self_signed_identity("token-a");
        let err = PinnedServerVerifier::new("token-b")
            .verify_server_cert(
                &cert,
                &[],
                &ServerName::try_from("etphonehome").unwrap(),
                &[],
                UnixTime::now(),
            )
            .unwrap_err();
        assert!(err.to_string().contains("pin mismatch"), "{err}");
        let err = PinnedClientVerifier::new("token-b")
            .verify_client_cert(&cert, &[], UnixTime::now())
            .unwrap_err();
        assert!(err.to_string().contains("pin mismatch"), "{err}");
    }

    /// The phone's side of mutual TLS (desktop acting as server in tests and
    /// the CI stand-in) must demand the peer's certificate: without it there
    /// is nothing to pin.
    #[test]
    fn client_verifier_requires_a_client_certificate() {
        let v = PinnedClientVerifier::new("t");
        assert!(v.offer_client_auth());
        assert!(v.client_auth_mandatory());
        assert!(v.root_hint_subjects().is_empty());
    }

    #[test]
    fn der_lengths_short_and_long_form() {
        // short form, at the 0x7f boundary
        let mut short = vec![0x04, 0x7f];
        short.extend([0xaa; 0x7f]);
        short.push(0x01);
        let (tag, body, rest) = der_tlv(&short).unwrap();
        assert_eq!((tag, body.len(), rest), (0x04, 0x7f, &[0x01u8][..]));
        // long form: 1 and 2 length bytes (0x81 0x80 = 128, 0x82 0x01 0x2c = 300)
        let mut one = vec![0x04, 0x81, 0x80];
        one.extend([0xbb; 128]);
        assert_eq!(der_tlv(&one).unwrap().1.len(), 128);
        let mut two = vec![0x04, 0x82, 0x01, 0x2c];
        two.extend([0xcc; 300]);
        let (_, body, rest) = der_tlv(&two).unwrap();
        assert_eq!((body.len(), rest.len()), (300, 0));
        // 4 length bytes is the most accepted; 5 and indefinite (0x80) are not
        assert_eq!(der_tlv(&[0x04, 0x84, 0, 0, 0, 1, 0xdd]).unwrap().1, &[0xdd]);
        assert!(der_tlv(&[0x04, 0x85, 0, 0, 0, 0, 1, 0xdd]).is_none());
        assert!(der_tlv(&[0x04, 0x80, 0x00, 0x00]).is_none());
        // length bytes missing, body shorter than its length, high-tag form
        assert!(der_tlv(&[0x04, 0x82, 0x01]).is_none());
        assert!(der_tlv(&[0x04, 0x05, 1, 2]).is_none());
        assert!(der_tlv(&[0x1f, 0x01, 0x00]).is_none());
        assert!(der_tlv(&[0x3f, 0x01, 0x00]).is_none());
        // tag 0x1e is an ordinary low tag
        assert_eq!(der_tlv(&[0x1e, 0x01, 0x07]).unwrap().0, 0x1e);
    }

    /// One wrong field in the SubjectPublicKeyInfo is enough to refuse the
    /// key: each check stands on its own.
    #[test]
    fn each_spki_field_is_checked_on_its_own() {
        let (cert, _key) = self_signed_identity("spki-fields");
        let der = cert.as_ref().to_vec();
        // AlgorithmIdentifier SEQUENCE { OID } followed by the 33-byte BIT STRING.
        let mut needle = vec![0x30, 0x05];
        needle.extend_from_slice(&ED25519_OID);
        needle.extend_from_slice(&[0x03, 0x21, 0x00]);
        let at = der
            .windows(needle.len())
            .position(|w| w == needle.as_slice())
            .expect("the SPKI layout of an Ed25519 cert");
        assert!(extract_ed25519_pubkey(&CertificateDer::from(der.clone())).is_some());
        for (offset, value, what) in [
            (0, 0x31u8, "AlgorithmIdentifier tag"),
            (6, 0x71, "OID last byte"),
            (7, 0x04, "BIT STRING tag"),
            (9, 0x01, "unused-bits byte"),
        ] {
            let mut bad = der.clone();
            bad[at + offset] = value;
            assert_eq!(
                extract_ed25519_pubkey(&CertificateDer::from(bad)),
                None,
                "{what} changed but the key was still accepted"
            );
        }
        // The SubjectPublicKeyInfo SEQUENCE tag itself (just before).
        let mut bad = der.clone();
        assert_eq!(bad[at - 2], 0x30);
        bad[at - 2] = 0x31;
        assert_eq!(extract_ed25519_pubkey(&CertificateDer::from(bad)), None);
    }
}
