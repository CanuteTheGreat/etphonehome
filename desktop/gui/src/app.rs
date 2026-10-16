//! Main application state and logic

use std::time::Duration;

use iced::widget::{button, column, container, horizontal_space, row, text};
use iced::{Element, Length, Subscription, Task, Theme};

use etphonehome_common::runner::{self, RetryPolicy};
use etphonehome_common::{
    Config, ConnectionService, ConnectionState, ConnectionStats, ConnectionType, FilterConfig,
    LogEntry, LogLevel, Profile,
};
use tokio::sync::{broadcast, watch};

use crate::message::{Message, View};
use crate::views;

/// Main application state
pub struct EtPhoneHomeApp {
    /// Current view
    current_view: View,

    /// Connection service
    connection_service: ConnectionService,

    /// Current connection state
    connection_state: ConnectionState,

    /// Current statistics
    stats: ConnectionStats,

    /// Log entries
    logs: Vec<LogEntry>,

    /// Available profiles
    profiles: Vec<Profile>,

    /// Currently selected profile name
    selected_profile: Option<String>,

    /// Auto-reconnect enabled
    auto_reconnect: bool,

    /// Dark mode enabled
    dark_mode: bool,

    /// Profile being edited (for create/edit form)
    editing_profile: Option<ProfileForm>,

    /// Stops the running connection task (Disconnect button).
    shutdown: Option<watch::Sender<bool>>,

    /// Log events published by the connection task.
    log_rx: broadcast::Receiver<LogEntry>,
}

/// Profile edit form state
#[derive(Debug, Clone, Default)]
pub struct ProfileForm {
    pub name: String,
    pub host: String,
    pub port: String,
    pub connection_type: String,
    pub description: String,
    pub address: String,
    pub is_new: bool,
    /// The profile's name at the time the form was opened, if editing an existing
    /// profile (None for a brand-new one). Needed because `name` itself is freely
    /// editable in the form -- without tracking the pre-edit name separately, saving
    /// a renamed profile would upsert a new entry under the new name while leaving
    /// the old-named entry behind as an orphaned duplicate, both in memory and in
    /// config.toml.
    pub original_name: Option<String>,
    /// The profile's traffic filter config, carried through the edit form untouched.
    /// Without this, editing *any* field of an existing profile (even just its
    /// description) would silently reset `filter` back to `FilterConfig::default()`
    /// on save, discarding the user's configured filter rules -- the form had no way
    /// to remember them since they're not editable here.
    pub filter: FilterConfig,
}

impl ProfileForm {
    pub fn new() -> Self {
        Self {
            name: String::new(),
            host: "192.168.1.1".to_string(),
            port: "8888".to_string(),
            connection_type: "wifi".to_string(),
            description: String::new(),
            address: String::new(),
            is_new: true,
            original_name: None,
            filter: FilterConfig::default(),
        }
    }

    pub fn from_profile(profile: &Profile) -> Self {
        Self {
            name: profile.name.clone(),
            host: profile
                .host
                .clone()
                .unwrap_or_else(|| "192.168.1.1".to_string()),
            port: profile
                .port
                .map(|p| p.to_string())
                .unwrap_or_else(|| "8888".to_string()),
            connection_type: format!("{:?}", profile.connection_type).to_lowercase(),
            description: profile.description.clone().unwrap_or_default(),
            address: profile.address.clone().unwrap_or_default(),
            is_new: false,
            original_name: Some(profile.name.clone()),
            filter: profile.filter.clone(),
        }
    }
}

impl EtPhoneHomeApp {
    pub fn new() -> (Self, Task<Message>) {
        let connection_service = ConnectionService::new();
        let log_rx = connection_service.subscribe_logs();

        let app = Self {
            current_view: View::Connection,
            connection_service,
            connection_state: ConnectionState::Disconnected,
            stats: ConnectionStats::default(),
            logs: Vec::new(),
            profiles: Vec::new(),
            selected_profile: None,
            auto_reconnect: true,
            dark_mode: false,
            editing_profile: None,
            shutdown: None,
            log_rx,
        };

        // Load profiles on startup
        let load_profiles = Task::perform(
            async { load_profiles_from_config().await },
            Message::ProfilesLoaded,
        );

        (app, load_profiles)
    }

    pub fn update(&mut self, message: Message) -> Task<Message> {
        match message {
            // Navigation
            Message::NavigateTo(view) => {
                self.current_view = view;
                Task::none()
            }

            // Connection actions
            Message::Connect => {
                // The Connect button used to only set the label to
                // "Connecting" -- nothing ever opened a connection. It now
                // runs the same session runner the CLI uses, reporting state
                // and stats through `connection_service`.
                if self.shutdown.is_some() {
                    return Task::none();
                }
                let Some(profile) = self
                    .selected_profile
                    .as_ref()
                    .and_then(|n| self.profiles.iter().find(|p| &p.name == n))
                    .cloned()
                else {
                    return Task::none();
                };
                log::info!("Connecting to profile: {}", profile.name);
                let (tx, rx) = runner::shutdown_channel();
                self.shutdown = Some(tx);
                self.connection_state = ConnectionState::Connecting {
                    profile: profile.clone(),
                    attempt: 1,
                };
                self.connection_service
                    .set_state(self.connection_state.clone());
                let svc = self.connection_service.clone();
                let auto = self.auto_reconnect;
                Task::perform(
                    async move {
                        let config = Config::load_or_default().map_err(|e| format!("{:#}", e))?;
                        let policy = RetryPolicy {
                            reconnect: auto,
                            max_retries: config.default.max_retries,
                        };
                        let iface = config.default.interface.clone();
                        runner::connect_profile(&config, &profile, &iface, policy, rx, Some(svc))
                            .await
                            .map_err(|e| format!("{:#}", e))
                    },
                    Message::ConnectionResult,
                )
            }

            Message::Disconnect => {
                if let Some(tx) = self.shutdown.take() {
                    let _ = tx.send(true);
                }
                self.connection_state = ConnectionState::Disconnected;
                self.connection_service
                    .set_state(ConnectionState::Disconnected);
                self.stats = ConnectionStats::default();
                log::info!("Disconnected");
                Task::none()
            }

            Message::ToggleAutoReconnect => {
                self.auto_reconnect = !self.auto_reconnect;
                self.connection_service
                    .set_auto_reconnect(self.auto_reconnect);
                Task::none()
            }

            // Profile selection
            Message::SelectProfile(name) => {
                self.selected_profile = Some(name);
                Task::none()
            }

            Message::CreateProfile => {
                self.editing_profile = Some(ProfileForm::new());
                Task::none()
            }

            Message::EditProfile(name) => {
                if let Some(profile) = self.profiles.iter().find(|p| p.name == name) {
                    self.editing_profile = Some(ProfileForm::from_profile(profile));
                }
                Task::none()
            }

            Message::DeleteProfile(name) => {
                self.profiles.retain(|p| p.name != name);
                if self.selected_profile.as_ref() == Some(&name) {
                    self.selected_profile = self.profiles.first().map(|p| p.name.clone());
                }
                // Persist the removal to disk immediately. Without this, deleting a
                // profile only mutated the in-memory `self.profiles` list -- the next
                // app restart reloads straight from config.toml via
                // `load_profiles_from_config()`, which still has the "deleted" profile
                // on it, silently undoing the deletion.
                Task::perform(delete_profile_from_config(name), Message::ProfileDeleted)
            }

            Message::SaveProfile(original_name, profile) => {
                // If this is a rename (editing an existing profile whose name field was
                // changed), drop the stale entry under the old name first -- otherwise
                // upserting under the new name leaves the old-named profile behind as an
                // orphaned duplicate instead of actually renaming it.
                if let Some(old_name) = &original_name {
                    if old_name != &profile.name {
                        self.profiles.retain(|p| &p.name != old_name);
                        if self.selected_profile.as_ref() == Some(old_name) {
                            self.selected_profile = Some(profile.name.clone());
                        }
                    }
                }

                // Update or add profile
                if let Some(pos) = self.profiles.iter().position(|p| p.name == profile.name) {
                    self.profiles[pos] = profile.clone();
                } else {
                    self.profiles.push(profile.clone());
                }
                self.editing_profile = None;
                if self.selected_profile.is_none() {
                    self.selected_profile = Some(profile.name.clone());
                }
                // Persist to disk. Same bug as DeleteProfile above: creating/editing a
                // profile here previously only updated in-memory state, never writing
                // it to config.toml, so any profile created or edited in the GUI was
                // silently lost the moment the app restarted.
                Task::perform(
                    save_profile_to_config(original_name, profile),
                    Message::ProfileSaved,
                )
            }

            Message::CancelProfileEdit => {
                self.editing_profile = None;
                Task::none()
            }

            // Profile form updates
            Message::ProfileNameChanged(name) => {
                if let Some(form) = &mut self.editing_profile {
                    form.name = name;
                }
                Task::none()
            }

            Message::ProfileHostChanged(host) => {
                if let Some(form) = &mut self.editing_profile {
                    form.host = host;
                }
                Task::none()
            }

            Message::ProfilePortChanged(port) => {
                if let Some(form) = &mut self.editing_profile {
                    form.port = port;
                }
                Task::none()
            }

            Message::ProfileConnectionTypeChanged(conn_type) => {
                if let Some(form) = &mut self.editing_profile {
                    form.connection_type = conn_type;
                }
                Task::none()
            }

            Message::ProfileDescriptionChanged(desc) => {
                if let Some(form) = &mut self.editing_profile {
                    form.description = desc;
                }
                Task::none()
            }

            Message::ProfileAddressChanged(addr) => {
                if let Some(form) = &mut self.editing_profile {
                    form.address = addr;
                }
                Task::none()
            }

            // State updates
            Message::StateChanged(state) => {
                self.connection_state = state;
                Task::none()
            }

            Message::StatsUpdated(stats) => {
                self.stats = stats;
                Task::none()
            }

            Message::LogReceived(entry) => {
                self.logs.push(entry);
                // Keep last 1000 log entries
                if self.logs.len() > 1000 {
                    self.logs.remove(0);
                }
                Task::none()
            }

            // Settings
            Message::ToggleDarkMode => {
                self.dark_mode = !self.dark_mode;
                Task::none()
            }

            Message::ClearLogs => {
                self.logs.clear();
                Task::none()
            }

            // Async results
            Message::ProfilesLoaded(profiles) => {
                self.profiles = profiles;
                self.selected_profile = self.profiles.first().map(|p| p.name.clone());
                Task::none()
            }

            Message::ProfileSaved(result) => {
                if let Err(e) = result {
                    log::error!("Failed to save profile to config file: {}", e);
                }
                Task::none()
            }

            Message::ProfileDeleted(result) => {
                if let Err(e) = result {
                    log::error!("Failed to delete profile from config file: {}", e);
                }
                Task::none()
            }

            Message::ConnectionResult(result) => {
                self.shutdown = None;
                match result {
                    Ok(()) => self.connection_state = ConnectionState::Disconnected,
                    Err(message) => {
                        log::error!("Connection failed: {}", message);
                        self.connection_service
                            .log(LogLevel::Error, message.clone());
                        self.connection_state = ConnectionState::Error { message };
                    }
                }
                Task::none()
            }

            // Periodic tick: pull live state/stats/logs from the connection task.
            Message::Tick => {
                if self.shutdown.is_some() {
                    self.connection_state = self.connection_service.state();
                    self.stats = self.connection_service.stats();
                }
                loop {
                    match self.log_rx.try_recv() {
                        Ok(entry) => {
                            self.logs.push(entry);
                        }
                        Err(broadcast::error::TryRecvError::Lagged(_)) => continue,
                        Err(_) => break,
                    }
                }
                if self.logs.len() > 1000 {
                    let excess = self.logs.len() - 1000;
                    self.logs.drain(..excess);
                }
                if let ConnectionState::Connected { started_at, .. } = &self.connection_state {
                    if self.stats.uptime_seconds == 0 {
                        self.stats.uptime_seconds = started_at.elapsed().as_secs() as u32;
                    }
                }
                Task::none()
            }
        }
    }

    pub fn view(&self) -> Element<'_, Message> {
        let sidebar = self.view_sidebar();
        let content = match self.current_view {
            View::Connection => views::connection::view(self),
            View::Profiles => views::profiles::view(self),
            View::Logs => views::logs::view(self),
            View::Settings => views::settings::view(self),
        };

        let main_content = row![
            sidebar,
            container(content)
                .width(Length::Fill)
                .height(Length::Fill)
                .padding(20)
        ];

        container(main_content)
            .width(Length::Fill)
            .height(Length::Fill)
            .into()
    }

    pub fn subscription(&self) -> Subscription<Message> {
        // Tick every second for stats updates
        iced::time::every(Duration::from_secs(1)).map(|_| Message::Tick)
    }

    pub fn theme(&self) -> Theme {
        if self.dark_mode {
            Theme::Dark
        } else {
            Theme::Light
        }
    }

    fn view_sidebar(&self) -> Element<'_, Message> {
        let current = self.current_view;

        let nav_button = |label: &'static str, view: View| -> Element<Message> {
            let is_active = view == current;
            button(text(label))
                .width(Length::Fill)
                .padding(10)
                .style(if is_active {
                    button::primary
                } else {
                    button::secondary
                })
                .on_press(Message::NavigateTo(view))
                .into()
        };

        let nav_items = column![
            nav_button("Connection", View::Connection),
            nav_button("Profiles", View::Profiles),
            nav_button("Logs", View::Logs),
            nav_button("Settings", View::Settings),
        ]
        .spacing(5);

        let status_text = match &self.connection_state {
            ConnectionState::Connected { .. } => "Connected",
            ConnectionState::Connecting { .. } => "Connecting...",
            ConnectionState::Reconnecting { .. } => "Reconnecting...",
            ConnectionState::Error { .. } => "Error",
            ConnectionState::Disconnected => "Disconnected",
        };

        let status_color = match &self.connection_state {
            ConnectionState::Connected { .. } => iced::Color::from_rgb(0.2, 0.8, 0.3),
            ConnectionState::Error { .. } => iced::Color::from_rgb(0.9, 0.2, 0.2),
            ConnectionState::Connecting { .. } | ConnectionState::Reconnecting { .. } => {
                iced::Color::from_rgb(0.9, 0.7, 0.1)
            }
            ConnectionState::Disconnected => iced::Color::from_rgb(0.5, 0.5, 0.5),
        };

        let status_indicator = text(status_text).color(status_color);

        let sidebar_content = column![
            text("ET Phone Home").size(20),
            horizontal_space(),
            nav_items,
            horizontal_space(),
            status_indicator,
        ]
        .spacing(10)
        .padding(10)
        .width(Length::Fixed(180.0));

        container(sidebar_content)
            .height(Length::Fill)
            .style(container::rounded_box)
            .into()
    }

    // Public getters for views
    pub fn connection_state(&self) -> &ConnectionState {
        &self.connection_state
    }

    pub fn stats(&self) -> &ConnectionStats {
        &self.stats
    }

    pub fn profiles(&self) -> &[Profile] {
        &self.profiles
    }

    pub fn selected_profile(&self) -> Option<&String> {
        self.selected_profile.as_ref()
    }

    pub fn auto_reconnect(&self) -> bool {
        self.auto_reconnect
    }

    pub fn logs(&self) -> &[LogEntry] {
        &self.logs
    }

    pub fn dark_mode(&self) -> bool {
        self.dark_mode
    }

    pub fn editing_profile(&self) -> Option<&ProfileForm> {
        self.editing_profile.as_ref()
    }
}

/// Persist a created/edited profile to the on-disk config file.
///
/// Loads the current config (or defaults if none exists yet), upserts the
/// given profile into it, and writes it back out. Runs on a blocking thread
/// since `Config::load`/`Config::save` do synchronous file I/O.
///
/// `original_name` is the profile's name before this edit (None for a brand-new
/// profile). When it differs from `profile.name` this is a rename: the old-named
/// entry is removed first so the rename doesn't leave a duplicate profile behind
/// under the stale name in config.toml.
async fn save_profile_to_config(
    original_name: Option<String>,
    profile: Profile,
) -> Result<(), String> {
    tokio::task::spawn_blocking(move || {
        let mut config = Config::load_or_default().map_err(|e| e.to_string())?;
        if let Some(old_name) = &original_name {
            if old_name != &profile.name {
                config.remove_profile(old_name);
            }
        }
        config.upsert_profile(profile);
        let path = Config::default_path().map_err(|e| e.to_string())?;
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        config.save(&path).map_err(|e| e.to_string())
    })
    .await
    .map_err(|e| e.to_string())?
}

/// Persist a profile deletion to the on-disk config file.
async fn delete_profile_from_config(name: String) -> Result<(), String> {
    tokio::task::spawn_blocking(move || {
        let mut config = Config::load_or_default().map_err(|e| e.to_string())?;
        config.remove_profile(&name);
        let path = Config::default_path().map_err(|e| e.to_string())?;
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        config.save(&path).map_err(|e| e.to_string())
    })
    .await
    .map_err(|e| e.to_string())?
}

/// Load profiles from config file
async fn load_profiles_from_config() -> Vec<Profile> {
    match Config::load_or_default() {
        Ok(config) => config.profiles,
        Err(e) => {
            log::warn!("Failed to load config: {}", e);
            // Return a default profile
            vec![Profile {
                name: "Default".to_string(),
                connection_type: ConnectionType::Usb,
                description: Some("Default USB connection".to_string()),
                port: Some(8888),
                host: None,
                address: None,
                filter: FilterConfig::default(),
            }]
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn app_with(profiles: Vec<Profile>) -> EtPhoneHomeApp {
        let (mut app, _) = EtPhoneHomeApp::new();
        let _ = app.update(Message::ProfilesLoaded(profiles));
        app
    }

    fn wifi(name: &str) -> Profile {
        Profile {
            name: name.into(),
            connection_type: ConnectionType::Wifi,
            description: None,
            port: Some(8888),
            host: Some("192.168.1.10".into()),
            address: None,
            filter: FilterConfig::default(),
        }
    }

    #[test]
    fn new_profile_form_defaults_to_the_app_port() {
        // The form used to default to 8765 while the Android app listens on
        // 8888, so every GUI-created profile pointed at the wrong port.
        assert_eq!(ProfileForm::new().port, "8888");
        let mut p = wifi("x");
        p.port = None;
        assert_eq!(ProfileForm::from_profile(&p).port, "8888");
    }

    #[test]
    fn rename_replaces_instead_of_duplicating() {
        let mut app = app_with(vec![wifi("a"), wifi("b")]);
        assert_eq!(app.selected_profile().map(String::as_str), Some("a"));
        let mut renamed = wifi("a2");
        renamed.filter.enabled = true;
        let _ = app.update(Message::SaveProfile(Some("a".into()), renamed));
        let names: Vec<_> = app.profiles().iter().map(|p| p.name.clone()).collect();
        assert_eq!(names, vec!["b".to_string(), "a2".to_string()]);
        assert_eq!(app.selected_profile().map(String::as_str), Some("a2"));
        assert!(app.profiles()[1].filter.enabled);
    }

    #[test]
    fn edit_form_carries_filter_through() {
        let mut p = wifi("f");
        p.filter.enabled = true;
        let mut app = app_with(vec![p]);
        let _ = app.update(Message::EditProfile("f".into()));
        assert!(app.editing_profile().unwrap().filter.enabled);
        let _ = app.update(Message::CancelProfileEdit);
        assert!(app.editing_profile().is_none());
    }

    #[test]
    fn deleting_selected_profile_moves_selection() {
        let mut app = app_with(vec![wifi("a"), wifi("b")]);
        let _ = app.update(Message::DeleteProfile("a".into()));
        assert_eq!(app.profiles().len(), 1);
        assert_eq!(app.selected_profile().map(String::as_str), Some("b"));
    }

    #[test]
    fn connect_starts_a_session_and_disconnect_stops_it() {
        let mut app = app_with(vec![wifi("a")]);
        let _task = app.update(Message::Connect);
        assert!(matches!(
            app.connection_state(),
            ConnectionState::Connecting { .. }
        ));
        assert!(
            app.shutdown.is_some(),
            "Connect must start a connection task"
        );
        let mut rx = app.shutdown.as_ref().unwrap().subscribe();
        // A second click while connecting is ignored.
        let _ = app.update(Message::Connect);
        let _ = app.update(Message::Disconnect);
        assert!(matches!(
            app.connection_state(),
            ConnectionState::Disconnected
        ));
        assert!(*rx.borrow_and_update(), "Disconnect must signal the task");
        assert!(app.shutdown.is_none());
    }

    #[test]
    fn connection_failure_is_shown_and_logged() {
        let mut app = app_with(vec![wifi("a")]);
        let _ = app.update(Message::Connect);
        let _ = app.update(Message::ConnectionResult(Err("connection refused".into())));
        match app.connection_state() {
            ConnectionState::Error { message } => assert!(message.contains("refused")),
            s => panic!("{:?}", s),
        }
        let _ = app.update(Message::Tick);
        assert!(app.logs().iter().any(|l| l.message.contains("refused")));
    }

    #[test]
    fn tick_pulls_live_state_and_stats_from_the_service() {
        let mut app = app_with(vec![wifi("a")]);
        let _ = app.update(Message::Connect);
        app.connection_service
            .set_state(ConnectionState::Connected {
                profile: wifi("a"),
                started_at: std::time::Instant::now(),
            });
        app.connection_service.update_stats(ConnectionStats {
            bytes_received: 1234,
            packets_received: 3,
            ..Default::default()
        });
        app.connection_service.log_info("tunnel up");
        let _ = app.update(Message::Tick);
        assert!(matches!(
            app.connection_state(),
            ConnectionState::Connected { .. }
        ));
        assert_eq!(app.stats().bytes_received, 1234);
        assert!(app.logs().iter().any(|l| l.message == "tunnel up"));
    }

    #[test]
    fn toggles() {
        let mut app = app_with(vec![]);
        assert!(app.auto_reconnect());
        let _ = app.update(Message::ToggleAutoReconnect);
        assert!(!app.auto_reconnect());
        assert!(!app.dark_mode());
        let _ = app.update(Message::ToggleDarkMode);
        assert!(app.dark_mode());
        let _ = app.update(Message::NavigateTo(View::Logs));
        let _ = app.update(Message::ClearLogs);
        assert!(app.logs().is_empty());
    }
}
