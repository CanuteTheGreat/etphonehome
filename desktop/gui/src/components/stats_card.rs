//! Statistics card component

use iced::widget::{column, container, text};
use iced::{Element, Length};

use crate::message::Message;

/// Render a statistics card
pub fn view(label: &str, value: String) -> Element<'static, Message> {
    let content = column![
        text(label.to_string())
            .size(12)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        text(value).size(24),
    ]
    .spacing(5)
    .align_x(iced::Alignment::Center);

    container(content)
        .padding(15)
        .width(Length::Fixed(150.0))
        .style(container::rounded_box)
        .center_x(Length::Fixed(150.0))
        .into()
}
