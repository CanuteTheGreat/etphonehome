//! Application styling and theming

use iced::Color;

/// Application theme colors
// Custom color palette, superseded by iced's built-in Theme system (see
// `.theme(EtPhoneHomeApp::theme)` in main.rs) — never wired in, kept for
// reference in case the built-in theme needs a custom variant later.
#[allow(dead_code)]
pub struct Theme {
    pub background: Color,
    pub surface: Color,
    pub primary: Color,
    pub secondary: Color,
    pub success: Color,
    pub warning: Color,
    pub error: Color,
    pub text: Color,
    pub text_secondary: Color,
}

#[allow(dead_code)]
impl Theme {
    pub fn light() -> Self {
        Self {
            background: Color::from_rgb(0.95, 0.95, 0.95),
            surface: Color::WHITE,
            primary: Color::from_rgb(0.2, 0.4, 0.8),
            secondary: Color::from_rgb(0.5, 0.5, 0.5),
            success: Color::from_rgb(0.2, 0.7, 0.3),
            warning: Color::from_rgb(0.9, 0.7, 0.1),
            error: Color::from_rgb(0.8, 0.2, 0.2),
            text: Color::from_rgb(0.1, 0.1, 0.1),
            text_secondary: Color::from_rgb(0.4, 0.4, 0.4),
        }
    }

    pub fn dark() -> Self {
        Self {
            background: Color::from_rgb(0.1, 0.1, 0.12),
            surface: Color::from_rgb(0.15, 0.15, 0.18),
            primary: Color::from_rgb(0.4, 0.6, 1.0),
            secondary: Color::from_rgb(0.6, 0.6, 0.6),
            success: Color::from_rgb(0.3, 0.8, 0.4),
            warning: Color::from_rgb(1.0, 0.8, 0.2),
            error: Color::from_rgb(1.0, 0.3, 0.3),
            text: Color::from_rgb(0.9, 0.9, 0.9),
            text_secondary: Color::from_rgb(0.6, 0.6, 0.6),
        }
    }
}

impl Default for Theme {
    fn default() -> Self {
        Self::light()
    }
}

/// Format bytes into human-readable string
pub fn format_bytes(bytes: u64) -> String {
    const KB: u64 = 1024;
    const MB: u64 = KB * 1024;
    const GB: u64 = MB * 1024;

    if bytes >= GB {
        format!("{:.2} GB", bytes as f64 / GB as f64)
    } else if bytes >= MB {
        format!("{:.2} MB", bytes as f64 / MB as f64)
    } else if bytes >= KB {
        format!("{:.2} KB", bytes as f64 / KB as f64)
    } else {
        format!("{} B", bytes)
    }
}

/// Format duration in seconds to human-readable string
pub fn format_duration(seconds: u32) -> String {
    let hours = seconds / 3600;
    let minutes = (seconds % 3600) / 60;
    let secs = seconds % 60;

    if hours > 0 {
        format!("{:02}:{:02}:{:02}", hours, minutes, secs)
    } else {
        format!("{:02}:{:02}", minutes, secs)
    }
}
