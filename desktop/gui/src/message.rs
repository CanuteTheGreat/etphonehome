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
    SaveProfile(Profile),
    CancelProfileEdit,

    // Profile form updates
    ProfileNameChanged(String),
    ProfileHostChanged(String),
    ProfilePortChanged(String),
    ProfileConnectionTypeChanged(String),
    ProfileDescriptionChanged(String),
    ProfileAddressChanged(String),

    // State updates from connection service
    StateChanged(ConnectionState),
    StatsUpdated(ConnectionStats),
    LogReceived(LogEntry),

    // Settings
    ToggleDarkMode,
    ClearLogs,

    // Async task results
    ProfilesLoaded(Vec<Profile>),
    ProfileSaved(Result<(), String>),
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
    pub fn title(&self) -> &'static str {
        match self {
            View::Connection => "Connection",
            View::Profiles => "Profiles",
            View::Logs => "Logs",
            View::Settings => "Settings",
        }
    }
}
