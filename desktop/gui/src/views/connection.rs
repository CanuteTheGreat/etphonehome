//! Connection view - main dashboard

use iced::widget::{
    button, column, container, horizontal_space, pick_list, row, text, vertical_space,
};
use iced::{Element, Length};

use etphonehome_common::ConnectionState;

use crate::app::EtPhoneHomeApp;
use crate::components::stats_card;
use crate::message::Message;
use crate::style::{format_bytes, format_duration};

/// Render the connection view
pub fn view(app: &EtPhoneHomeApp) -> Element<'_, Message> {
    let title = text("Connection").size(28);

    // Profile selector
    let profile_names: Vec<String> = app.profiles().iter().map(|p| p.name.clone()).collect();
    let profile_selector = pick_list(
        profile_names,
        app.selected_profile().cloned(),
        Message::SelectProfile,
    )
    .placeholder("Select a profile")
    .width(Length::Fixed(200.0));

    let profile_row = row![text("Profile: "), profile_selector,]
        .spacing(10)
        .align_y(iced::Alignment::Center);

    // Connection buttons
    let (connect_btn, disconnect_btn) = match app.connection_state() {
        ConnectionState::Disconnected | ConnectionState::Error { .. } => (
            button(text("Connect"))
                .padding(10)
                .style(button::primary)
                .on_press(Message::Connect),
            button(text("Disconnect"))
                .padding(10)
                .style(button::secondary),
        ),
        ConnectionState::Connected { .. } => (
            button(text("Connect")).padding(10).style(button::secondary),
            button(text("Disconnect"))
                .padding(10)
                .style(button::danger)
                .on_press(Message::Disconnect),
        ),
        _ => (
            button(text("Connect")).padding(10).style(button::secondary),
            button(text("Disconnect"))
                .padding(10)
                .style(button::danger)
                .on_press(Message::Disconnect),
        ),
    };

    let button_row = row![connect_btn, disconnect_btn,].spacing(10);

    // Connection status
    let status_text = match app.connection_state() {
        ConnectionState::Disconnected => "Not connected".to_string(),
        ConnectionState::Connecting { profile, attempt } => {
            format!("Connecting to {} (attempt {})...", profile.name, attempt)
        }
        ConnectionState::Connected { profile, .. } => {
            format!("Connected to {}", profile.name)
        }
        ConnectionState::Reconnecting {
            profile,
            attempt,
            delay_secs,
        } => {
            format!(
                "Reconnecting to {} (attempt {}, waiting {}s)...",
                profile.name, attempt, delay_secs
            )
        }
        ConnectionState::Error { message } => {
            format!("Error: {}", message)
        }
    };

    let status_color = match app.connection_state() {
        ConnectionState::Connected { .. } => iced::Color::from_rgb(0.2, 0.8, 0.3),
        ConnectionState::Error { .. } => iced::Color::from_rgb(0.9, 0.2, 0.2),
        ConnectionState::Connecting { .. } | ConnectionState::Reconnecting { .. } => {
            iced::Color::from_rgb(0.9, 0.7, 0.1)
        }
        ConnectionState::Disconnected => iced::Color::from_rgb(0.5, 0.5, 0.5),
    };

    let status = text(status_text).size(16).color(status_color);

    // Statistics cards - clone the formatted strings
    let stats = app.stats();
    let sent_str = format_bytes(stats.bytes_sent);
    let received_str = format_bytes(stats.bytes_received);
    let uptime_str = format_duration(stats.uptime_seconds);

    let stats_row = row![
        stats_card::view("Sent", sent_str),
        stats_card::view("Received", received_str),
        stats_card::view("Uptime", uptime_str),
    ]
    .spacing(20);

    // Auto-reconnect toggle
    let auto_reconnect_btn = button(text(if app.auto_reconnect() {
        "Auto-reconnect: ON"
    } else {
        "Auto-reconnect: OFF"
    }))
    .padding(8)
    .style(if app.auto_reconnect() {
        button::primary
    } else {
        button::secondary
    })
    .on_press(Message::ToggleAutoReconnect);

    let content = column![
        title,
        vertical_space().height(20),
        profile_row,
        vertical_space().height(10),
        button_row,
        vertical_space().height(20),
        status,
        vertical_space().height(30),
        text("Statistics").size(20),
        vertical_space().height(10),
        stats_row,
        vertical_space().height(30),
        row![auto_reconnect_btn, horizontal_space(),],
    ]
    .spacing(5)
    .padding(10);

    container(content)
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
}
