//! Logs view - connection log viewer

use iced::widget::{button, column, container, row, scrollable, text, vertical_space};
use iced::{Element, Length};

use etphonehome_common::LogLevel;

use crate::app::EtPhoneHomeApp;
use crate::message::Message;

/// Render the logs view
pub fn view(app: &EtPhoneHomeApp) -> Element<'_, Message> {
    let title = text("Logs").size(28);

    let clear_btn = button(text("Clear Logs"))
        .padding(8)
        .style(button::secondary)
        .on_press(Message::ClearLogs);

    let log_count =
        text(format!("{} entries", app.logs().len())).color(iced::Color::from_rgb(0.5, 0.5, 0.5));

    let header = row![title, text("").width(Length::Fill), log_count, clear_btn,]
        .spacing(10)
        .align_y(iced::Alignment::Center);

    let log_content: Element<Message> = if app.logs().is_empty() {
        container(text("No log entries yet").color(iced::Color::from_rgb(0.5, 0.5, 0.5)))
            .center_x(Length::Fill)
            .center_y(Length::Fill)
            .into()
    } else {
        let entries: Vec<Element<Message>> = app
            .logs()
            .iter()
            .rev() // Show newest first
            .take(500) // Limit displayed entries for performance
            .map(|entry| {
                let level_color = match entry.level {
                    LogLevel::Debug => iced::Color::from_rgb(0.5, 0.5, 0.5),
                    LogLevel::Info => iced::Color::from_rgb(0.3, 0.6, 0.9),
                    LogLevel::Warn => iced::Color::from_rgb(0.9, 0.7, 0.1),
                    LogLevel::Error => iced::Color::from_rgb(0.9, 0.2, 0.2),
                };

                let level_str = match entry.level {
                    LogLevel::Debug => "DEBUG",
                    LogLevel::Info => "INFO ",
                    LogLevel::Warn => "WARN ",
                    LogLevel::Error => "ERROR",
                };

                let elapsed = entry.timestamp.elapsed();
                let time_str = format!(
                    "-{:02}:{:02}:{:02}",
                    elapsed.as_secs() / 3600,
                    (elapsed.as_secs() % 3600) / 60,
                    elapsed.as_secs() % 60
                );

                row![
                    text(time_str)
                        .size(12)
                        .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
                        .width(Length::Fixed(80.0)),
                    text(level_str)
                        .size(12)
                        .color(level_color)
                        .width(Length::Fixed(50.0)),
                    text(&entry.message).size(12).width(Length::Fill),
                ]
                .spacing(10)
                .into()
            })
            .collect();

        scrollable(column(entries).spacing(2))
            .height(Length::Fill)
            .into()
    };

    let content = column![
        header,
        vertical_space().height(10),
        container(log_content)
            .width(Length::Fill)
            .height(Length::Fill)
            .style(container::rounded_box)
            .padding(10),
    ]
    .spacing(10)
    .padding(10);

    container(content)
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
}
