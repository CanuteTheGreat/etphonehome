use ring::hkdf;

/// PSK identity used for TLS-PSK
pub const PSK_IDENTITY: &[u8] = b"etphonehome-tls-psk";

/// Pre-shared key identity
#[derive(Debug, Clone)]
pub struct PskIdentity {
    /// Identity bytes sent to server
    pub identity: Vec<u8>,
    /// Derived PSK (32 bytes for AES-256)
    pub key: Vec<u8>,
}

/// Derive a TLS PSK from a token string
///
/// Uses HKDF-SHA256 to derive a 32-byte key from the token.
/// This matches the Android implementation.
///
/// # Arguments
/// * `token` - The authentication token (e.g., WiFi or Bluetooth token)
///
/// # Returns
/// A `PskIdentity` containing both the identity and derived key
pub fn derive_psk(token: &str) -> PskIdentity {
    let salt = hkdf::Salt::new(hkdf::HKDF_SHA256, b"ETPhoneHome-TLS-Salt");
    let prk = salt.extract(token.as_bytes());

    let info = [PSK_IDENTITY];
    let okm = prk
        .expand(&info, hkdf::HKDF_SHA256)
        .expect("HKDF expand should not fail");

    let mut key = vec![0u8; 32];
    okm.fill(&mut key).expect("HKDF fill should not fail");

    PskIdentity {
        identity: PSK_IDENTITY.to_vec(),
        key,
    }
}

/// Verify that a PSK matches the expected value
pub fn verify_psk(token: &str, expected_key: &[u8]) -> bool {
    let derived = derive_psk(token);
    // Constant-time comparison to prevent timing attacks. `ring`'s own
    // constant_time module is deprecated/internal-only now, so compare
    // manually: fold in a length check up front (this doesn't leak secret
    // data, just PSK vs. key-material shape) then XOR+OR every byte
    // unconditionally so the loop never branches on secret content.
    if derived.key.len() != expected_key.len() {
        return false;
    }
    let mut diff: u8 = 0;
    for (a, b) in derived.key.iter().zip(expected_key.iter()) {
        diff |= a ^ b;
    }
    diff == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_derive_psk() {
        let psk = derive_psk("test-token-12345");
        assert_eq!(psk.key.len(), 32);
        assert_eq!(psk.identity, PSK_IDENTITY);
    }

    #[test]
    fn test_derive_psk_deterministic() {
        let psk1 = derive_psk("same-token");
        let psk2 = derive_psk("same-token");
        assert_eq!(psk1.key, psk2.key);
    }

    #[test]
    fn test_derive_psk_different_tokens() {
        let psk1 = derive_psk("token-a");
        let psk2 = derive_psk("token-b");
        assert_ne!(psk1.key, psk2.key);
    }

    #[test]
    fn test_verify_psk() {
        let psk = derive_psk("verify-test");
        assert!(verify_psk("verify-test", &psk.key));
        assert!(!verify_psk("wrong-token", &psk.key));
    }
}
