//! Application messages and events

use etphonehome_common::{ConnectionState, ConnectionStats, LogEntry, Profile};

/// Application messages
#[derive(Debug, Clone)]
pub enum Message {
    // Navigation
    NavigateTo(View),

    // Connection actions
    Connect,
    Disconnect,
    ToggleAutoReconnect,

    // Profile actions
    SelectProfile(String),
    CreateProfile,
    EditProfile(String),
    DeleteProfile(String),
    /// (original_name, new_profile) -- original_name is Some(..) when editing an
    /// existing profile (None for a brand-new one) and lets the handler detect a
    /// rename so it can remove the old on-disk/in-memory entry instead of leaving
    /// a stale duplicate behind under the old name.
    SaveProfile(Option<String>, Profile),
    CancelProfileEdit,

    // Profile form updates
    ProfileNameChanged(String),
    ProfileHostChanged(String),
    ProfilePortChanged(String),
    ProfileConnectionTypeChanged(String),
    ProfileDescriptionChanged(String),
    ProfileAddressChanged(String),

    // State updates from connection service. Not yet dispatched by app.rs's
    // update loop — the connection-service subscription/event-pump wiring
    // that would construct these is still pending.
    #[allow(dead_code)]
    StateChanged(ConnectionState),
    #[allow(dead_code)]
    StatsUpdated(ConnectionStats),
    #[allow(dead_code)]
    LogReceived(LogEntry),

    // Settings
    ToggleDarkMode,
    ClearLogs,

    // Async task results
    ProfilesLoaded(Vec<Profile>),
    ProfileSaved(Result<(), String>),
    ProfileDeleted(Result<(), String>),
    #[allow(dead_code)]
    ConnectionResult(Result<(), String>),

    // Ticks for periodic updates
    Tick,
}

/// Application views
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum View {
    #[default]
    Connection,
    Profiles,
    Logs,
    Settings,
}

impl View {
    /// Human-readable label for a view — not yet wired into any view's
    /// rendering (nav bar/title currently hardcodes labels inline), kept
    /// as the intended single source of truth for when that's cleaned up.
    #[allow(dead_code)]
    pub fn title(&self) -> &'static str {
        match self {
            View::Connection => "Connection",
            View::Profiles => "Profiles",
            View::Logs => "Logs",
            View::Settings => "Settings",
        }
    }
}
