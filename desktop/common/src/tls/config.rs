use serde::{Deserialize, Serialize};

/// TLS configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TlsConfig {
    /// Whether TLS is enabled
    #[serde(default)]
    pub enabled: bool,

    /// TLS mode (PSK or certificate)
    #[serde(default)]
    pub mode: TlsMode,

    /// Verify server certificate (for certificate mode)
    #[serde(default = "default_true")]
    pub verify_server: bool,
}

fn default_true() -> bool {
    true
}

impl Default for TlsConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            mode: TlsMode::Psk,
            verify_server: true,
        }
    }
}

/// TLS authentication mode
#[derive(Debug, Clone, Copy, Serialize, Deserialize, Default, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum TlsMode {
    /// Pre-Shared Key (derived from token)
    #[default]
    Psk,
    /// Certificate-based authentication
    Certificate,
}

impl TlsConfig {
    /// Create a new TLS config with PSK mode enabled
    pub fn psk_enabled() -> Self {
        Self {
            enabled: true,
            mode: TlsMode::Psk,
            verify_server: true,
        }
    }

    /// Create a disabled TLS config
    pub fn disabled() -> Self {
        Self::default()
    }
}
