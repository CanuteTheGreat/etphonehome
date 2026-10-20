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
    /// Desktop GUI preferences (the CLI ignores them). `default.auto_reconnect` is shared:
    /// the GUI's auto-reconnect toggle reads and writes that one.
    #[serde(default)]
    pub gui: GuiSettings,
}

/// Desktop GUI preferences, kept in the same config.toml as the profiles.
#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq)]
pub struct GuiSettings {
    #[serde(default)]
    pub dark_mode: bool,
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
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuthSettings {
    #[serde(default)]
    pub wifi_token: String,
    #[serde(default)]
    pub bluetooth_token: String,
    /// Cert-pinned TLS (see `crate::tls::certpin`) on WiFi connections,
    /// using the WiFi token as the pinning trust anchor. On by default: the
    /// Android app serves TLS and, by default, refuses plaintext WiFi.
    /// Never falls back to plaintext; set `false` only for an app whose
    /// "Require encryption" switch is off.
    #[serde(default = "default_true")]
    pub tls_enabled: bool,
}

impl Default for AuthSettings {
    fn default() -> Self {
        Self {
            wifi_token: String::new(),
            bluetooth_token: String::new(),
            tls_enabled: true,
        }
    }
}

fn default_true() -> bool {
    true
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
    ///
    /// The new content goes to a private (0600) temp file next to the config, which is
    /// fsync'd and then renamed over it. This used to cut the existing file to zero length
    /// and rewrite it in place, and only chmod it afterwards. That had two problems:
    /// - an existing file with looser permissions (one copied from config.example.toml,
    ///   or written by an older binary) held the new tokens while still world-readable
    ///   until the chmod landed, and a reader that opened it in that window kept access;
    /// - a crash or full disk part-way through the rewrite left an empty or partial
    ///   config, losing every saved profile.
    ///
    /// A symlinked config (dotfile setups) is followed, so the link stays a link and its
    /// target is replaced. If the directory isn't writable (an `ETPHONEHOME_CONFIG` file
    /// in a directory we can't create files in), it falls back to rewriting the file in
    /// place, restricting its permissions before anything is written.
    pub fn save<P: AsRef<Path>>(&self, path: P) -> Result<()> {
        let path = path.as_ref();
        let content = toml::to_string_pretty(self).context("Failed to serialize config")?;
        let failed = || format!("Failed to write config file: {}", path.display());

        let target = resolve_symlinks(path).with_context(failed)?;
        match replace_file(&target, content.as_bytes()) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::PermissionDenied => {
                write_in_place(&target, content.as_bytes()).with_context(failed)?;
            }
            Err(e) => return Err(e).with_context(failed),
        }

        // Written via `sudo etphonehome ...`: give it back to the user
        // whose config dir it lives in (see `default_path`).
        #[cfg(unix)]
        if sudo_invoking_user_home().is_some_and(|h| path.starts_with(h)) {
            chown_to_sudo_user(&target);
        }

        Ok(())
    }

    /// Get default config path.
    ///
    /// Order: `$ETPHONEHOME_CONFIG`; then, when running under `sudo`, the
    /// *invoking* user's config dir; then the current user's config dir.
    ///
    /// The sudo case matters because the documented workflow is
    /// `etphonehome save-profile ...` (as the user) followed by
    /// `sudo etphonehome connect <name>` (TUN needs root). sudo resets HOME
    /// to /root, so the connect used to look in /root/.config and report
    /// "Profile not found" for a profile that had just been saved.
    pub fn default_path() -> Result<PathBuf> {
        if let Some(p) = std::env::var_os("ETPHONEHOME_CONFIG") {
            if !p.is_empty() {
                return Ok(PathBuf::from(p));
            }
        }
        #[cfg(unix)]
        if let Some(home) = sudo_invoking_user_home() {
            return Ok(home.join(".config").join("etphonehome").join("config.toml"));
        }
        // Try user config directory first
        if let Some(config_dir) = dirs::config_dir() {
            let path = config_dir.join("etphonehome").join("config.toml");
            return Ok(path);
        }

        // Fallback to current directory
        Ok(PathBuf::from("config.toml"))
    }

    /// Save to the default path, creating its directory. Under sudo the file
    /// is handed back to the invoking user so later unprivileged runs can
    /// still update it.
    pub fn save_default(&self) -> Result<PathBuf> {
        let path = Self::default_path()?;
        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                fs::create_dir_all(parent)?;
            }
        }
        self.save(&path)?;
        Ok(path)
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

/// Follow `path` through any symlinks to the file that should actually be written.
/// Unlike `fs::canonicalize` this works for a dangling link (its target is created).
fn resolve_symlinks(path: &Path) -> std::io::Result<PathBuf> {
    let mut p = path.to_path_buf();
    for _ in 0..40 {
        match fs::symlink_metadata(&p) {
            Ok(m) if m.file_type().is_symlink() => {
                let link = fs::read_link(&p)?;
                p = match p.parent() {
                    Some(dir) if link.is_relative() => dir.join(link),
                    _ => link,
                };
            }
            _ => return Ok(p),
        }
    }
    Err(std::io::Error::other("too many levels of symbolic links"))
}

/// Temp file used by `replace_file` for `target`: hidden, in the same directory
/// (so the rename stays on one filesystem), unique per process.
fn temp_path_for(target: &Path) -> std::io::Result<PathBuf> {
    let Some(name) = target.file_name() else {
        return Err(std::io::ErrorKind::InvalidInput.into());
    };
    let mut tmp = std::ffi::OsString::from(".");
    tmp.push(name);
    tmp.push(format!(".tmp-{}", std::process::id()));
    Ok(target.with_file_name(tmp))
}

/// Open a file readable and writable by its owner only (0600 on Unix,
/// whatever the umask or the existing file's mode). Never cuts its length.
fn open_private(path: &Path, create_new: bool) -> std::io::Result<fs::File> {
    let mut opts = fs::OpenOptions::new();
    opts.write(true).truncate(false);
    if create_new {
        opts.create_new(true);
    } else {
        opts.create(true);
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    let file = opts.open(path)?;
    #[cfg(unix)]
    {
        // `mode` only applies to a newly created file, and the umask can still
        // clear bits from it: set the exact permissions on the open handle.
        use std::os::unix::fs::PermissionsExt;
        file.set_permissions(fs::Permissions::from_mode(0o600))?;
    }
    Ok(file)
}

/// Atomically replace `target` with `content`: write a private temp file in the
/// same directory, fsync it, rename it over `target`. Readers see the old file
/// or the new one, never a partial write. The temp file is removed on failure.
fn replace_file(target: &Path, content: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    let tmp = temp_path_for(target)?;
    // A leftover from a crashed save by an earlier process with the same pid.
    let _ = fs::remove_file(&tmp);
    // The handle is closed before the rename (Windows can't rename an open file).
    let result = open_private(&tmp, true)
        .and_then(|mut file| {
            file.write_all(content)?;
            file.sync_all()
        })
        .and_then(|()| fs::rename(&tmp, target));
    if result.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    result
}

/// Rewrite `target` in place, for when its directory doesn't allow creating the
/// temp file. Its permissions are restricted before any content is written.
fn write_in_place(target: &Path, content: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    let mut file = open_private(target, false)?;
    file.set_len(0)?;
    file.write_all(content)?;
    file.sync_all()
}

/// Home directory of the user who invoked `sudo`, if we are running under it.
#[cfg(unix)]
fn sudo_invoking_user_home() -> Option<PathBuf> {
    let user = std::env::var("SUDO_USER").ok()?;
    let passwd = fs::read_to_string("/etc/passwd").ok()?;
    home_from_passwd(&user, &passwd)
}

/// Look up `user`'s home directory in passwd(5)-formatted text. Returns None
/// for root (no redirect needed) or an unknown user.
#[cfg(unix)]
fn home_from_passwd(user: &str, passwd: &str) -> Option<PathBuf> {
    if user.is_empty() || user == "root" {
        return None;
    }
    passwd.lines().find_map(|line| {
        let f: Vec<&str> = line.split(':').collect();
        if f.len() >= 6 && f[0] == user && !f[5].is_empty() {
            Some(PathBuf::from(f[5]))
        } else {
            None
        }
    })
}

#[cfg(unix)]
fn chown_to_sudo_user(path: &Path) {
    let (Ok(uid), Ok(gid)) = (std::env::var("SUDO_UID"), std::env::var("SUDO_GID")) else {
        return;
    };
    let (Ok(uid), Ok(gid)) = (uid.parse::<u32>(), gid.parse::<u32>()) else {
        return;
    };
    let mut p = Some(path);
    // The file plus any etphonehome/ directory we may have just created.
    for _ in 0..2 {
        if let Some(cur) = p {
            let _ = std::os::unix::fs::chown(cur, Some(uid), Some(gid));
            p = cur.parent();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gui_settings_default_off_for_old_configs_and_round_trip() {
        // A config.toml written before [gui] existed still loads, with dark mode off.
        let old: Config = toml::from_str("[default]\nauto_reconnect = false\n").unwrap();
        assert_eq!(old.gui, GuiSettings::default());
        assert!(!old.default.auto_reconnect);

        let mut c = Config::default();
        c.gui.dark_mode = true;
        let back: Config = toml::from_str(&toml::to_string_pretty(&c).unwrap()).unwrap();
        assert!(back.gui.dark_mode);
    }

    #[test]
    #[cfg(unix)]
    fn sudo_user_home_is_found_in_passwd() {
        let passwd = "root:x:0:0:root:/root:/bin/bash\n\
                      alice:x:1000:1000:Alice,,,:/home/alice:/bin/bash\n\
                      bob:x:1001:1001::/srv/bob:/bin/sh\n";
        assert_eq!(
            home_from_passwd("alice", passwd),
            Some(PathBuf::from("/home/alice"))
        );
        assert_eq!(
            home_from_passwd("bob", passwd),
            Some(PathBuf::from("/srv/bob"))
        );
        assert_eq!(home_from_passwd("root", passwd), None);
        assert_eq!(home_from_passwd("mallory", passwd), None);
        assert_eq!(home_from_passwd("", passwd), None);
    }

    #[test]
    fn example_config_file_parses() {
        let text = include_str!("../../config.example.toml");
        let cfg: Config = toml::from_str(text).expect("desktop/config.example.toml must parse");
        assert!(!cfg.profiles.is_empty());
        for p in &cfg.profiles {
            match p.connection_type {
                ConnectionType::Wifi => assert!(p.host.is_some(), "{}", p.name),
                ConnectionType::Bluetooth => assert!(p.address.is_some(), "{}", p.name),
                ConnectionType::Usb => {}
            }
        }
        // The repo-root copy used to describe sections the client never read
        // ([connection], [network], [security] "future" encryption...): keep it identical.
        assert_eq!(
            include_str!("../../../config.example.toml"),
            text,
            "config.example.toml must be a copy of desktop/config.example.toml"
        );
    }

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

    /// A unique empty scratch directory for a save test.
    fn scratch_dir(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "etph-config-{tag}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn config_with_token(token: &str) -> Config {
        let mut config = Config::default();
        config.auth.wifi_token = token.to_string();
        config
    }

    #[test]
    fn save_replaces_an_existing_config_and_leaves_no_temp_file() {
        let dir = scratch_dir("replace");
        let path = dir.join("config.toml");
        std::fs::write(&path, "[default]\nauto_reconnect = false\n").unwrap();

        config_with_token("new-token").save(&path).unwrap();

        let back = Config::load(&path).unwrap();
        assert_eq!(back.auth.wifi_token, "new-token");
        assert!(back.default.auto_reconnect, "old content must be gone");
        let names: Vec<_> = std::fs::read_dir(&dir)
            .unwrap()
            .map(|e| e.unwrap().file_name())
            .collect();
        assert_eq!(names, vec![std::ffi::OsString::from("config.toml")]);

        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    #[cfg(unix)]
    fn save_never_writes_tokens_into_a_world_readable_file() {
        use std::os::unix::fs::{MetadataExt, PermissionsExt};

        // A config copied from config.example.toml with the umask's 0644. Saving used to
        // rewrite this file (same inode) with the tokens in it and chmod it only after.
        let dir = scratch_dir("perms");
        let path = dir.join("config.toml");
        std::fs::write(&path, "").unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o644)).unwrap();
        let old_inode = std::fs::metadata(&path).unwrap().ino();

        config_with_token("secret-token").save(&path).unwrap();

        let meta = std::fs::metadata(&path).unwrap();
        assert_eq!(meta.permissions().mode() & 0o777, 0o600);
        assert_ne!(
            meta.ino(),
            old_inode,
            "the tokens must land in a fresh 0600 file, not in the old 0644 one"
        );
        assert_eq!(Config::load(&path).unwrap().auth.wifi_token, "secret-token");

        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    #[cfg(unix)]
    fn save_through_a_symlink_keeps_the_link_and_updates_its_target() {
        let dir = scratch_dir("symlink");
        std::fs::create_dir_all(dir.join("dotfiles")).unwrap();
        let real = dir.join("dotfiles").join("etphonehome.toml");
        std::fs::write(&real, "").unwrap();
        let link = dir.join("config.toml");
        std::os::unix::fs::symlink("dotfiles/etphonehome.toml", &link).unwrap();

        config_with_token("linked-token").save(&link).unwrap();

        assert!(std::fs::symlink_metadata(&link)
            .unwrap()
            .file_type()
            .is_symlink());
        assert_eq!(Config::load(&real).unwrap().auth.wifi_token, "linked-token");

        // A dangling link gets its target created.
        let dangling = dir.join("dangling.toml");
        std::os::unix::fs::symlink("dotfiles/new.toml", &dangling).unwrap();
        config_with_token("t2").save(&dangling).unwrap();
        assert!(std::fs::symlink_metadata(&dangling)
            .unwrap()
            .file_type()
            .is_symlink());
        let created = dir.join("dotfiles").join("new.toml");
        assert_eq!(Config::load(&created).unwrap().auth.wifi_token, "t2");

        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    #[cfg(unix)]
    fn in_place_fallback_restricts_permissions_and_replaces_content() {
        use std::os::unix::fs::PermissionsExt;

        let dir = scratch_dir("inplace");
        let path = dir.join("config.toml");
        std::fs::write(&path, "a much longer old content that must not survive\n").unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o644)).unwrap();

        write_in_place(&path, b"short\n").unwrap();

        assert_eq!(std::fs::read_to_string(&path).unwrap(), "short\n");
        let mode = std::fs::metadata(&path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);

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
