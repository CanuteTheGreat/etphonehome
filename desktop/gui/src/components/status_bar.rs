//! Status bar component

use iced::widget::{container, row, text, horizontal_space};
use iced::{Element, Length};

use etphonehome_common::{ConnectionState, ConnectionStats};

use crate::message::Message;
use crate::style::{format_bytes, format_duration};

/// Render a status bar
pub fn view<'a>(state: &'a ConnectionState, stats: &'a ConnectionStats) -> Element<'a, Message> {
    let status_text = match state {
        ConnectionState::Disconnected => "Disconnected",
        ConnectionState::Connecting { .. } => "Connecting...",
        ConnectionState::Connected { .. } => "Connected",
        ConnectionState::Reconnecting { .. } => "Reconnecting...",
        ConnectionState::Error { .. } => "Error",
    };

    let status_color = match state {
        ConnectionState::Connected { .. } => iced::Color::from_rgb(0.2, 0.8, 0.3),
        ConnectionState::Error { .. } => iced::Color::from_rgb(0.9, 0.2, 0.2),
        ConnectionState::Connecting { .. } | ConnectionState::Reconnecting { .. } => {
            iced::Color::from_rgb(0.9, 0.7, 0.1)
        }
        ConnectionState::Disconnected => iced::Color::from_rgb(0.5, 0.5, 0.5),
    };

    let status = text(status_text)
        .size(12)
        .color(status_color);

    let stats_text = if matches!(state, ConnectionState::Connected { .. }) {
        text(format!(
            "TX: {} | RX: {} | Uptime: {}",
            format_bytes(stats.bytes_sent),
            format_bytes(stats.bytes_received),
            format_duration(stats.uptime_seconds)
        ))
        .size(12)
        .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
    } else {
        text("")
            .size(12)
    };

    let content = row![
        status,
        horizontal_space(),
        stats_text,
    ]
    .align_y(iced::Alignment::Center);

    container(content)
        .padding(5)
        .width(Length::Fill)
        .style(container::rounded_box)
        .into()
}
