//! Log viewer component

use iced::widget::{column, container, row, scrollable, text};
use iced::{Element, Length};

use etphonehome_common::{LogEntry, LogLevel};

use crate::message::Message;

/// Render a log viewer
pub fn view<'a>(logs: &'a [LogEntry], max_entries: usize) -> Element<'a, Message> {
    if logs.is_empty() {
        return container(
            text("No logs")
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
        )
        .center_x(Length::Fill)
        .center_y(Length::Fill)
        .into();
    }

    let entries: Vec<Element<Message>> = logs
        .iter()
        .rev()
        .take(max_entries)
        .map(|entry| view_entry(entry))
        .collect();

    scrollable(column(entries).spacing(1))
        .height(Length::Fill)
        .into()
}

/// Render a single log entry
fn view_entry(entry: &LogEntry) -> Element<'_, Message> {
    let level_color = match entry.level {
        LogLevel::Debug => iced::Color::from_rgb(0.5, 0.5, 0.5),
        LogLevel::Info => iced::Color::from_rgb(0.3, 0.6, 0.9),
        LogLevel::Warn => iced::Color::from_rgb(0.9, 0.7, 0.1),
        LogLevel::Error => iced::Color::from_rgb(0.9, 0.2, 0.2),
    };

    let level_str = match entry.level {
        LogLevel::Debug => "DBG",
        LogLevel::Info => "INF",
        LogLevel::Warn => "WRN",
        LogLevel::Error => "ERR",
    };

    row![
        text(level_str)
            .size(10)
            .color(level_color)
            .width(Length::Fixed(30.0)),
        text(&entry.message)
            .size(10)
            .width(Length::Fill),
    ]
    .spacing(5)
    .into()
}

/// Render a compact log viewer (for sidebar or status bar)
pub fn view_compact<'a>(logs: &'a [LogEntry], max_entries: usize) -> Element<'a, Message> {
    let entries: Vec<Element<Message>> = logs
        .iter()
        .rev()
        .take(max_entries)
        .map(|entry| {
            text(&entry.message)
                .size(9)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
                .into()
        })
        .collect();

    column(entries).spacing(1).into()
}
