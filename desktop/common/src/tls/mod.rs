//! TLS support for ET Phone Home
//!
//! This module provides TLS encryption for WiFi and Bluetooth connections.
//! It uses PSK (Pre-Shared Key) authentication derived from the existing
//! token system.

pub mod config;
pub mod psk;

pub use config::TlsConfig;
pub use psk::{derive_psk, PskIdentity};
