use crate::filter::FilterConfig;
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};

/// Connection profile configuration
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Config {
    #[serde(default)]
    pub default: DefaultSettings,
    #[serde(default)]
    pub profiles: Vec<Profile>,
    #[serde(default)]
    pub auth: AuthSettings,
    #[serde(default)]
    pub advanced: AdvancedSettings,
}

/// Default connection settings
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DefaultSettings {
    #[serde(default = "default_auto_reconnect")]
    pub auto_reconnect: bool,
    #[serde(default)]
    pub max_retries: u32,
    #[serde(default = "default_interface")]
    pub interface: String,
}

/// Connection profile
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Profile {
    pub name: String,
    #[serde(rename = "type")]
    pub connection_type: ConnectionType,
    pub description: Option<String>,

    // USB/WiFi fields
    #[serde(skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,

    // WiFi fields
    #[serde(skip_serializing_if = "Option::is_none")]
    pub host: Option<String>,

    // Bluetooth fields
    #[serde(skip_serializing_if = "Option::is_none")]
    pub address: Option<String>,

    // Traffic filtering
    #[serde(default, skip_serializing_if = "is_filter_default")]
    pub filter: FilterConfig,
}

fn is_filter_default(config: &FilterConfig) -> bool {
    !config.enabled && config.rules.is_empty()
}

/// Connection type
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum ConnectionType {
    Usb,
    Wifi,
    Bluetooth,
}

/// Authentication settings
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct AuthSettings {
    #[serde(default)]
    pub wifi_token: String,
    #[serde(default)]
    pub bluetooth_token: String,
}

/// Advanced settings
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AdvancedSettings {
    #[serde(default)]
    pub debug: bool,
    #[serde(default = "default_timeout")]
    pub timeout: u64,
    #[serde(default = "default_buffer_size")]
    pub buffer_size: usize,
}

// Default values
fn default_auto_reconnect() -> bool {
    true
}

fn default_interface() -> String {
    "etph0".to_string()
}

fn default_timeout() -> u64 {
    30
}

fn default_buffer_size() -> usize {
    100
}

impl Default for DefaultSettings {
    fn default() -> Self {
        Self {
            auto_reconnect: default_auto_reconnect(),
            max_retries: 0,
            interface: default_interface(),
        }
    }
}

impl Default for AdvancedSettings {
    fn default() -> Self {
        Self {
            debug: false,
            timeout: default_timeout(),
            buffer_size: default_buffer_size(),
        }
    }
}

impl Config {
    /// Load configuration from file
    pub fn load<P: AsRef<Path>>(path: P) -> Result<Self> {
        let content = fs::read_to_string(path.as_ref()).context(format!(
            "Failed to read config file: {}",
            path.as_ref().display()
        ))?;

        let config: Config = toml::from_str(&content).context("Failed to parse config file")?;

        Ok(config)
    }

    /// Save configuration to file
    ///
    /// The config file holds `auth.wifi_token`/`auth.bluetooth_token` in plaintext, so on
    /// Unix it is written with 0600 permissions (owner read/write only) instead of relying
    /// on the process umask, which commonly leaves new files group/world-readable (0644) --
    /// that would let any other local user read the device's WiFi/Bluetooth auth tokens
    /// straight off disk.
    pub fn save<P: AsRef<Path>>(&self, path: P) -> Result<()> {
        let content = toml::to_string_pretty(self).context("Failed to serialize config")?;

        // On Unix, open the file with mode 0600 from the moment it's created instead of
        // writing it with the default umask (commonly 0644) and chmod'ing afterwards --
        // the write-then-chmod sequence has a real TOCTOU race where any other local user
        // can read the plaintext wifi/bluetooth auth tokens during the window between
        // creation and the permission fix landing. Opening with the restrictive mode up
        // front via OpenOptions closes that window entirely (the mode only applies when
        // the file is newly created; an existing file's permissions are left as-is until
        // explicitly reset below, matching the previous behavior for pre-existing files).
        #[cfg(unix)]
        {
            use std::io::Write;
            use std::os::unix::fs::OpenOptionsExt;
            use std::os::unix::fs::PermissionsExt;

            let mut file = fs::OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .mode(0o600)
                .open(path.as_ref())
                .context(format!(
                    "Failed to open config file: {}",
                    path.as_ref().display()
                ))?;
            file.write_all(content.as_bytes()).context(format!(
                "Failed to write config file: {}",
                path.as_ref().display()
            ))?;

            // Belt-and-suspenders: if the file already existed with looser permissions
            // (e.g. created by an older binary before this fix), explicitly tighten them
            // too, since `mode()` on OpenOptions only governs newly-created files.
            fs::set_permissions(path.as_ref(), fs::Permissions::from_mode(0o600)).context(
                format!(
                    "Failed to restrict permissions on config file: {}",
                    path.as_ref().display()
                ),
            )?;
        }

        #[cfg(not(unix))]
        {
            fs::write(path.as_ref(), content).context(format!(
                "Failed to write config file: {}",
                path.as_ref().display()
            ))?;
        }

        Ok(())
    }

    /// Get default config path
    pub fn default_path() -> Result<PathBuf> {
        // Try user config directory first
        if let Some(config_dir) = dirs::config_dir() {
            let path = config_dir.join("etphonehome").join("config.toml");
            return Ok(path);
        }

        // Fallback to current directory
        Ok(PathBuf::from("config.toml"))
    }

    /// Load from default path or create default config
    pub fn load_or_default() -> Result<Self> {
        match Self::default_path() {
            Ok(path) => {
                if path.exists() {
                    Self::load(&path)
                } else {
                    log::info!(
                        "Config file not found at {}, using defaults",
                        path.display()
                    );
                    Ok(Self::default())
                }
            }
            Err(_) => Ok(Self::default()),
        }
    }

    /// Find profile by name
    pub fn get_profile(&self, name: &str) -> Option<&Profile> {
        self.profiles.iter().find(|p| p.name == name)
    }

    /// Add or update profile
    pub fn upsert_profile(&mut self, profile: Profile) {
        if let Some(existing) = self.profiles.iter_mut().find(|p| p.name == profile.name) {
            *existing = profile;
        } else {
            self.profiles.push(profile);
        }
    }

    /// Remove profile by name
    pub fn remove_profile(&mut self, name: &str) -> bool {
        if let Some(pos) = self.profiles.iter().position(|p| p.name == name) {
            self.profiles.remove(pos);
            true
        } else {
            false
        }
    }

    /// List all profile names
    pub fn list_profiles(&self) -> Vec<String> {
        self.profiles.iter().map(|p| p.name.clone()).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_config_default() {
        let config = Config::default();
        assert!(config.default.auto_reconnect);
        assert_eq!(config.default.max_retries, 0);
        assert_eq!(config.default.interface, "etph0");
    }

    #[test]
    #[cfg(unix)]
    fn test_save_restricts_permissions() {
        use std::os::unix::fs::PermissionsExt;

        let mut config = Config::default();
        config.auth.wifi_token = "secret-token".to_string();

        let dir = std::env::temp_dir().join(format!(
            "etph-config-test-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("config.toml");

        config.save(&path).unwrap();

        let mode = std::fs::metadata(&path).unwrap().permissions().mode() & 0o777;
        assert_eq!(
            mode, 0o600,
            "config file containing auth tokens must be owner-only"
        );

        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn test_profile_serialization() {
        let profile = Profile {
            name: "test".to_string(),
            connection_type: ConnectionType::Wifi,
            description: Some("Test profile".to_string()),
            port: Some(8888),
            host: Some("192.168.1.100".to_string()),
            address: None,
            filter: FilterConfig::default(),
        };

        let toml = toml::to_string(&profile).unwrap();
        let deserialized: Profile = toml::from_str(&toml).unwrap();

        assert_eq!(deserialized.name, "test");
        assert_eq!(deserialized.connection_type, ConnectionType::Wifi);
    }
}
