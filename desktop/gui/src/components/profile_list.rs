//! Profile list component

use iced::widget::{button, column, container, scrollable, text};
use iced::{Element, Length};

use etphonehome_common::Profile;

use crate::message::Message;

/// Render a list of profiles
pub fn view<'a>(
    profiles: &'a [Profile],
    selected: Option<&'a String>,
) -> Element<'a, Message> {
    if profiles.is_empty() {
        return container(
            text("No profiles")
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
        )
        .center_x(Length::Fill)
        .into();
    }

    let items: Vec<Element<Message>> = profiles
        .iter()
        .map(|profile| {
            let is_selected = selected == Some(&profile.name);

            let name = text(&profile.name).size(14);

            let conn_type = text(format!("{:?}", profile.connection_type))
                .size(10)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5));

            let item = button(
                column![name, conn_type].spacing(2)
            )
            .padding(8)
            .width(Length::Fill)
            .style(if is_selected {
                button::primary
            } else {
                button::secondary
            })
            .on_press(Message::SelectProfile(profile.name.clone()));

            item.into()
        })
        .collect();

    scrollable(column(items).spacing(5))
        .height(Length::Fill)
        .into()
}
