//! TLS support for ET Phone Home
//!
//! This module provides TLS encryption for WiFi and Bluetooth connections.
//! It uses a shared pairing-token secret, derived via HKDF, to drive
//! certificate-pinned mutual TLS -- see `certpin` for why this replaced the
//! originally-planned literal TLS-PSK cipher suites (neither `rustls` nor
//! Android's `javax.net.ssl` support those).

pub mod certpin;
pub mod config;
pub mod psk;

pub use certpin::{client_tls_config, expected_pubkey, server_tls_config};
pub use config::TlsConfig;
pub use psk::{derive_psk, PskIdentity};
