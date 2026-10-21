//! ETPH in the macOS menu bar / the Windows notification area.
//!
//! The app itself is unprivileged: it asks the ETPH service (`etphonehome
//! service`, run as root by launchd / as LocalSystem by the Windows service
//! manager) to connect over USB or to a saved profile, to disconnect, and for
//! the session's status, which it shows in the menu and as the icon colour.
#![cfg_attr(windows, windows_subsystem = "windows")]

#[cfg(any(target_os = "macos", windows))]
mod app;

#[cfg(any(target_os = "macos", windows))]
fn main() -> anyhow::Result<()> {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();
    app::run()
}

#[cfg(not(any(target_os = "macos", windows)))]
fn main() {
    eprintln!(
        "etph-tray is the macOS/Windows app; on Linux use etphonehome-gui or the etphonehome CLI."
    );
    std::process::exit(2);
}
