//! The router's adb key: an RSA-2048 key in the same files the adb tool
//! uses ($HOME/.android/adbkey = PKCS#8 PEM, adbkey.pub = Android's
//! RSAPublicKey struct in base64 + " name"), so a key made by either works
//! with both, and "Always allow from this computer" on the phone keeps
//! working across client upgrades (OpenWrt: /etc/etph/.android, kept by
//! sysupgrade).

use anyhow::{Context, Result};
use rsa::pkcs8::{DecodePrivateKey, EncodePrivateKey, LineEnding};
use rsa::traits::PublicKeyParts;
use rsa::{BigUint, Pkcs1v15Sign, RsaPrivateKey};
use std::path::{Path, PathBuf};

pub struct AdbKey {
    key: RsaPrivateKey,
    name: String,
}

const WORDS: usize = 64; // 2048-bit modulus in 32-bit words

/// $HOME/.android (what adb uses).
pub fn default_dir() -> PathBuf {
    PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| "/root".into())).join(".android")
}

fn base64(data: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
    for c in data.chunks(3) {
        let n = (c[0] as u32) << 16
            | (*c.get(1).unwrap_or(&0) as u32) << 8
            | *c.get(2).unwrap_or(&0) as u32;
        out.push(T[(n >> 18) as usize & 63] as char);
        out.push(T[(n >> 12) as usize & 63] as char);
        out.push(if c.len() > 1 {
            T[(n >> 6) as usize & 63] as char
        } else {
            '='
        });
        out.push(if c.len() > 2 {
            T[n as usize & 63] as char
        } else {
            '='
        });
    }
    out
}

impl AdbKey {
    /// Load `dir/adbkey`, or create (and save) a new key. Creating one takes
    /// a while on slow router CPUs (tens of seconds on MIPS).
    pub fn load_or_create(dir: &Path, name: &str) -> Result<Self> {
        let path = dir.join("adbkey");
        if let Ok(pem) = std::fs::read_to_string(&path) {
            let key = RsaPrivateKey::from_pkcs8_pem(&pem)
                .with_context(|| format!("{} is not an RSA private key", path.display()))?;
            let k = Self {
                key,
                name: name.to_string(),
            };
            // Keep adbkey.pub in step (the fingerprint and `adb` read it).
            let pubp = dir.join("adbkey.pub");
            if std::fs::read_to_string(&pubp)
                .map(|s| !s.starts_with(&k.public_key_b64()))
                .unwrap_or(true)
            {
                let _ = std::fs::write(&pubp, k.public_key_line() + "\n");
            }
            return Ok(k);
        }
        log::info!(
            "Creating this device's adb key in {} (once)...",
            dir.display()
        );
        let key =
            RsaPrivateKey::new(&mut rand_core::OsRng, 2048).context("RSA key generation failed")?;
        let k = Self {
            key,
            name: name.to_string(),
        };
        k.save(dir)?;
        Ok(k)
    }

    pub fn save(&self, dir: &Path) -> Result<()> {
        std::fs::create_dir_all(dir)?;
        let pem = self.key.to_pkcs8_pem(LineEnding::LF)?;
        let path = dir.join("adbkey");
        let tmp = dir.join("adbkey.tmp");
        {
            use std::io::Write;
            let mut opts = std::fs::OpenOptions::new();
            opts.write(true).create(true).truncate(true);
            #[cfg(unix)]
            std::os::unix::fs::OpenOptionsExt::mode(&mut opts, 0o600);
            let mut f = opts.open(&tmp)?;
            f.write_all(pem.as_bytes())?;
            f.sync_all()?;
        }
        std::fs::rename(&tmp, &path)?;
        std::fs::write(dir.join("adbkey.pub"), self.public_key_line() + "\n")?;
        Ok(())
    }

    /// Android's RSAPublicKey struct (android_pubkey.c), 524 bytes.
    pub fn android_public_key(&self) -> Vec<u8> {
        let n = self.key.n();
        let mut n_words = [0u32; WORDS];
        for (i, chunk) in n.to_bytes_le().chunks(4).enumerate().take(WORDS) {
            let mut w = [0u8; 4];
            w[..chunk.len()].copy_from_slice(chunk);
            n_words[i] = u32::from_le_bytes(w);
        }
        // n0inv = -1 / n mod 2^32 (Newton iteration on the inverse).
        let n0 = n_words[0];
        let mut inv: u32 = n0;
        for _ in 0..5 {
            inv = inv.wrapping_mul(2u32.wrapping_sub(n0.wrapping_mul(inv)));
        }
        let n0inv = 0u32.wrapping_sub(inv);
        let rr = (BigUint::from(1u8) << (WORDS * 32 * 2)) % n;
        let mut rr_words = [0u32; WORDS];
        for (i, chunk) in rr.to_bytes_le().chunks(4).enumerate().take(WORDS) {
            let mut w = [0u8; 4];
            w[..chunk.len()].copy_from_slice(chunk);
            rr_words[i] = u32::from_le_bytes(w);
        }
        let e: u32 = self
            .key
            .e()
            .to_bytes_le()
            .iter()
            .enumerate()
            .map(|(i, b)| (*b as u32) << (8 * i))
            .sum();
        let mut out = Vec::with_capacity(524);
        out.extend_from_slice(&(WORDS as u32).to_le_bytes());
        out.extend_from_slice(&n0inv.to_le_bytes());
        n_words
            .iter()
            .for_each(|w| out.extend_from_slice(&w.to_le_bytes()));
        rr_words
            .iter()
            .for_each(|w| out.extend_from_slice(&w.to_le_bytes()));
        out.extend_from_slice(&e.to_le_bytes());
        out
    }

    pub fn public_key_b64(&self) -> String {
        base64(&self.android_public_key())
    }

    /// adbkey.pub's line: "<base64> <name>".
    pub fn public_key_line(&self) -> String {
        format!("{} {}", self.public_key_b64(), self.name)
    }

    /// What the phone's "Allow USB debugging?" dialog shows.
    pub fn fingerprint(&self) -> String {
        crate::host::adb_key_fingerprint(&self.public_key_line()).unwrap_or_default()
    }

    /// adbd's AUTH TOKEN challenge: RSA PKCS#1 v1.5 over the 20-byte token
    /// as if it were a SHA-1 digest (adb's RSA_sign(NID_sha1, ...)).
    pub fn sign_token(&self, token: &[u8]) -> Result<Vec<u8>> {
        anyhow::ensure!(
            token.len() == 20,
            "unexpected adb auth token length {}",
            token.len()
        );
        Ok(self.key.sign(Pkcs1v15Sign::new::<sha1::Sha1>(), token)?)
    }

    #[cfg(test)]
    pub fn verify(&self, token: &[u8], sig: &[u8]) -> bool {
        self.key
            .to_public_key()
            .verify(Pkcs1v15Sign::new::<sha1::Sha1>(), token, sig)
            .is_ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base64_matches_rfc4648() {
        assert_eq!(base64(b""), "");
        assert_eq!(base64(b"f"), "Zg==");
        assert_eq!(base64(b"fo"), "Zm8=");
        assert_eq!(base64(b"foobar"), "Zm9vYmFy");
    }
}
