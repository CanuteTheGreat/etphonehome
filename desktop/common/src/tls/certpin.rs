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
    params.distinguished_name = rcgen::DistinguishedName::new();
    let cert = params
        .self_signed(&keypair)
        .expect("self-signed cert generation should not fail");

    let cert_der = cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(keypair.serialize_der().into());
    (cert_der, key_der)
}

/// Extract the raw 32-byte Ed25519 public key from a DER certificate's
/// SubjectPublicKeyInfo. Returns `None` if the cert doesn't contain an
/// Ed25519 key (e.g. a non-Ed25519 or malformed cert) in the expected,
/// fixed encoding: `OID(Ed25519) || BIT STRING(33 bytes, 0 unused, 32-byte key)`.
///
/// The Ed25519 OID also appears (without a following public-key bit string)
/// in the certificate's two *signature algorithm* fields -- both the
/// top-level one and the one duplicated inside TBSCertificate -- so this
/// scans every occurrence of the OID and only accepts the one immediately
/// followed by a 33-byte BIT STRING (`03 21 00`), which is the SPKI
/// encoding unique to the public key field itself.
fn extract_ed25519_pubkey(cert: &CertificateDer<'_>) -> Option<[u8; 32]> {
    let bytes = cert.as_ref();
    for oid_pos in bytes
        .windows(ED25519_OID.len())
        .enumerate()
        .filter_map(|(i, w)| (w == ED25519_OID).then_some(i))
    {
        let after_oid = oid_pos + ED25519_OID.len();
        if bytes.len() < after_oid + 3 + 32 {
            continue;
        }
        // Expect: 0x03 (BIT STRING tag), 0x21 (length = 33), 0x00 (0 unused
        // bits), then the 32 raw public key bytes. The signature-algorithm
        // occurrences of this OID are followed by other TBSCertificate
        // fields (Validity, etc.) or nothing matching this pattern, so they
        // fall through to the next iteration instead of being misread.
        if bytes[after_oid] == 0x03 && bytes[after_oid + 1] == 0x21 && bytes[after_oid + 2] == 0x00
        {
            let mut key = [0u8; 32];
            key.copy_from_slice(&bytes[after_oid + 3..after_oid + 3 + 32]);
            return Some(key);
        }
    }
    None
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
}
