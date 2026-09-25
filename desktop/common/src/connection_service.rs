//! Connection service for managing tethering connections
//!
//! This module provides a reusable service that can be used by both
//! the CLI and GUI applications.

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::sync::{broadcast, mpsc};

use crate::config::Profile;

/// Connection state
#[derive(Debug, Clone)]
pub enum ConnectionState {
    /// Not connected to any device
    Disconnected,
    /// Attempting to connect
    Connecting { profile: Profile, attempt: u32 },
    /// Successfully connected
    Connected {
        profile: Profile,
        started_at: Instant,
    },
    /// Reconnecting after connection loss
    Reconnecting {
        profile: Profile,
        attempt: u32,
        delay_secs: u64,
    },
    /// Connection failed with error
    Error { message: String },
}

impl Default for ConnectionState {
    fn default() -> Self {
        ConnectionState::Disconnected
    }
}

/// Connection statistics
#[derive(Debug, Clone, Default)]
pub struct ConnectionStats {
    pub bytes_sent: u64,
    pub bytes_received: u64,
    pub uptime_seconds: u32,
    pub packets_sent: u64,
    pub packets_received: u64,
}

/// Commands that can be sent to the connection service
#[derive(Debug, Clone)]
pub enum ConnectionCommand {
    /// Connect using a profile
    Connect(Profile),
    /// Disconnect from current connection
    Disconnect,
    /// Enable/disable auto-reconnect
    SetAutoReconnect(bool),
}

/// Log entry from the connection service
#[derive(Debug, Clone)]
pub struct LogEntry {
    pub timestamp: Instant,
    pub level: LogLevel,
    pub message: String,
}

/// Log level
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum LogLevel {
    Debug,
    Info,
    Warn,
    Error,
}

/// Connection service for managing tethering connections
///
/// This service provides:
/// - Connection state management
/// - Statistics broadcasting
/// - Log event broadcasting
/// - Command handling
pub struct ConnectionService {
    state: Arc<Mutex<ConnectionState>>,
    stats: Arc<Mutex<ConnectionStats>>,
    auto_reconnect: Arc<Mutex<bool>>,

    // Broadcast channels for GUI updates
    state_tx: broadcast::Sender<ConnectionState>,
    stats_tx: broadcast::Sender<ConnectionStats>,
    log_tx: broadcast::Sender<LogEntry>,

    // Command channel
    command_tx: mpsc::Sender<ConnectionCommand>,
    command_rx: Arc<Mutex<Option<mpsc::Receiver<ConnectionCommand>>>>,
}

impl ConnectionService {
    /// Create a new connection service
    pub fn new() -> Self {
        let (state_tx, _) = broadcast::channel(16);
        let (stats_tx, _) = broadcast::channel(16);
        let (log_tx, _) = broadcast::channel(100);
        let (command_tx, command_rx) = mpsc::channel(16);

        Self {
            state: Arc::new(Mutex::new(ConnectionState::Disconnected)),
            stats: Arc::new(Mutex::new(ConnectionStats::default())),
            auto_reconnect: Arc::new(Mutex::new(true)),
            state_tx,
            stats_tx,
            log_tx,
            command_tx,
            command_rx: Arc::new(Mutex::new(Some(command_rx))),
        }
    }

    /// Get current connection state
    pub fn state(&self) -> ConnectionState {
        self.state.lock().unwrap().clone()
    }

    /// Get current statistics
    pub fn stats(&self) -> ConnectionStats {
        self.stats.lock().unwrap().clone()
    }

    /// Subscribe to state changes
    pub fn subscribe_state(&self) -> broadcast::Receiver<ConnectionState> {
        self.state_tx.subscribe()
    }

    /// Subscribe to statistics updates
    pub fn subscribe_stats(&self) -> broadcast::Receiver<ConnectionStats> {
        self.stats_tx.subscribe()
    }

    /// Subscribe to log entries
    pub fn subscribe_logs(&self) -> broadcast::Receiver<LogEntry> {
        self.log_tx.subscribe()
    }

    /// Get command sender for controlling the service
    pub fn command_sender(&self) -> mpsc::Sender<ConnectionCommand> {
        self.command_tx.clone()
    }

    /// Update connection state
    pub fn set_state(&self, new_state: ConnectionState) {
        let mut state = self.state.lock().unwrap();
        *state = new_state.clone();
        let _ = self.state_tx.send(new_state);
    }

    /// Update statistics
    pub fn update_stats(&self, stats: ConnectionStats) {
        let mut current = self.stats.lock().unwrap();
        *current = stats.clone();
        let _ = self.stats_tx.send(stats);
    }

    /// Log a message
    pub fn log(&self, level: LogLevel, message: impl Into<String>) {
        let entry = LogEntry {
            timestamp: Instant::now(),
            level,
            message: message.into(),
        };
        let _ = self.log_tx.send(entry);
    }

    /// Log info message
    pub fn log_info(&self, message: impl Into<String>) {
        self.log(LogLevel::Info, message);
    }

    /// Log error message
    pub fn log_error(&self, message: impl Into<String>) {
        self.log(LogLevel::Error, message);
    }

    /// Check if auto-reconnect is enabled
    pub fn is_auto_reconnect_enabled(&self) -> bool {
        *self.auto_reconnect.lock().unwrap()
    }

    /// Set auto-reconnect
    pub fn set_auto_reconnect(&self, enabled: bool) {
        *self.auto_reconnect.lock().unwrap() = enabled;
    }

    /// Calculate retry delay with exponential backoff
    pub fn calculate_retry_delay(attempt: u32) -> Duration {
        // 2^(attempt-1) seconds, capped at 60 seconds
        let secs = if attempt <= 1 {
            1
        } else {
            std::cmp::min(1u64 << (attempt - 1), 60)
        };
        Duration::from_secs(secs)
    }
}

impl Default for ConnectionService {
    fn default() -> Self {
        Self::new()
    }
}
