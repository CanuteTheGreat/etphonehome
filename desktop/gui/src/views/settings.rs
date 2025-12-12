//! Settings view

use iced::widget::{button, column, container, row, text, vertical_space};
use iced::{Element, Length};

use crate::app::EtPhoneHomeApp;
use crate::message::Message;

/// Render the settings view
pub fn view(app: &EtPhoneHomeApp) -> Element<Message> {
    let title = text("Settings").size(28);

    // Appearance section
    let appearance_title = text("Appearance").size(20);

    let dark_mode_btn = button(
        text(if app.dark_mode() {
            "Dark Mode: ON"
        } else {
            "Dark Mode: OFF"
        })
    )
    .padding(10)
    .style(if app.dark_mode() {
        button::primary
    } else {
        button::secondary
    })
    .on_press(Message::ToggleDarkMode);

    let appearance_section = column![
        appearance_title,
        vertical_space().height(10),
        row![
            text("Theme"),
            text("").width(Length::Fill),
            dark_mode_btn,
        ]
        .align_y(iced::Alignment::Center),
    ]
    .spacing(5);

    // Connection section
    let connection_title = text("Connection").size(20);

    let auto_reconnect_btn = button(
        text(if app.auto_reconnect() {
            "Auto-reconnect: ON"
        } else {
            "Auto-reconnect: OFF"
        })
    )
    .padding(10)
    .style(if app.auto_reconnect() {
        button::primary
    } else {
        button::secondary
    })
    .on_press(Message::ToggleAutoReconnect);

    let connection_section = column![
        connection_title,
        vertical_space().height(10),
        row![
            text("Automatic reconnection"),
            text("").width(Length::Fill),
            auto_reconnect_btn,
        ]
        .align_y(iced::Alignment::Center),
    ]
    .spacing(5);

    // About section
    let about_title = text("About").size(20);

    let version_info = column![
        row![
            text("Version"),
            text("").width(Length::Fill),
            text("0.5.0").color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ],
        row![
            text("Protocol"),
            text("").width(Length::Fill),
            text("v1.0").color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ],
    ]
    .spacing(5);

    let about_section = column![
        about_title,
        vertical_space().height(10),
        version_info,
    ]
    .spacing(5);

    let content = column![
        title,
        vertical_space().height(20),
        appearance_section,
        vertical_space().height(30),
        connection_section,
        vertical_space().height(30),
        about_section,
    ]
    .spacing(10)
    .padding(10);

    container(content)
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
}
