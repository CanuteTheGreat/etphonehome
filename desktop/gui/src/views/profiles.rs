//! Profiles view - profile management

use iced::widget::{button, column, container, row, scrollable, text, text_input, vertical_space};
use iced::{Element, Length};

use etphonehome_common::{ConnectionType, Profile, FilterConfig};

use crate::app::{EtPhoneHomeApp, ProfileForm};
use crate::message::Message;

/// Render the profiles view
pub fn view(app: &EtPhoneHomeApp) -> Element<Message> {
    let title = text("Profiles").size(28);

    if let Some(form) = app.editing_profile() {
        // Show edit form
        view_profile_form(form)
    } else {
        // Show profile list
        let create_btn = button(text("New Profile"))
            .padding(10)
            .style(button::primary)
            .on_press(Message::CreateProfile);

        let profile_list: Element<Message> = if app.profiles().is_empty() {
            text("No profiles configured. Create one to get started.")
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
                .into()
        } else {
            let items: Vec<Element<Message>> = app
                .profiles()
                .iter()
                .map(|profile| {
                    let is_selected = app.selected_profile() == Some(&profile.name);

                    let name = text(&profile.name).size(16);
                    let details = text(format!(
                        "{} - {:?}",
                        profile.host.as_deref().or(profile.address.as_deref()).unwrap_or("N/A"),
                        profile.connection_type
                    ))
                    .size(12)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5));

                    let edit_btn = button(text("Edit"))
                        .padding(5)
                        .style(button::secondary)
                        .on_press(Message::EditProfile(profile.name.clone()));

                    let delete_btn = button(text("Delete"))
                        .padding(5)
                        .style(button::danger)
                        .on_press(Message::DeleteProfile(profile.name.clone()));

                    let select_btn = button(text(if is_selected { "Selected" } else { "Select" }))
                        .padding(5)
                        .style(if is_selected {
                            button::primary
                        } else {
                            button::secondary
                        })
                        .on_press(Message::SelectProfile(profile.name.clone()));

                    let item_content = row![
                        column![name, details].width(Length::Fill),
                        select_btn,
                        edit_btn,
                        delete_btn,
                    ]
                    .spacing(10)
                    .align_y(iced::Alignment::Center);

                    container(item_content)
                        .padding(10)
                        .style(if is_selected {
                            container::rounded_box
                        } else {
                            container::transparent
                        })
                        .width(Length::Fill)
                        .into()
                })
                .collect();

            scrollable(column(items).spacing(5))
                .height(Length::Fill)
                .into()
        };

        let content = column![
            row![title, button(text("")).width(Length::Fill), create_btn],
            vertical_space().height(20),
            profile_list,
        ]
        .spacing(10)
        .padding(10);

        container(content)
            .width(Length::Fill)
            .height(Length::Fill)
            .into()
    }
}

/// Render the profile edit form
fn view_profile_form(form: &ProfileForm) -> Element<'static, Message> {
    let title = text(if form.is_new { "New Profile" } else { "Edit Profile" }).size(28);

    let name_input = column![
        text("Name"),
        text_input("Profile name", &form.name)
            .padding(10)
            .on_input(Message::ProfileNameChanged),
    ]
    .spacing(5);

    let conn_type_input = column![
        text("Connection Type"),
        row![
            button(text("USB"))
                .padding(8)
                .style(if form.connection_type == "usb" {
                    button::primary
                } else {
                    button::secondary
                })
                .on_press(Message::ProfileConnectionTypeChanged("usb".to_string())),
            button(text("WiFi"))
                .padding(8)
                .style(if form.connection_type == "wifi" {
                    button::primary
                } else {
                    button::secondary
                })
                .on_press(Message::ProfileConnectionTypeChanged("wifi".to_string())),
            button(text("Bluetooth"))
                .padding(8)
                .style(if form.connection_type == "bluetooth" {
                    button::primary
                } else {
                    button::secondary
                })
                .on_press(Message::ProfileConnectionTypeChanged("bluetooth".to_string())),
        ]
        .spacing(10),
    ]
    .spacing(5);

    // Show host field for WiFi
    let host_input = if form.connection_type == "wifi" {
        Some(column![
            text("Host"),
            text_input("192.168.1.1", &form.host)
                .padding(10)
                .on_input(Message::ProfileHostChanged),
        ]
        .spacing(5))
    } else {
        None
    };

    // Show address field for Bluetooth
    let address_input = if form.connection_type == "bluetooth" {
        Some(column![
            text("Bluetooth Address"),
            text_input("AA:BB:CC:DD:EE:FF", &form.address)
                .padding(10)
                .on_input(Message::ProfileAddressChanged),
        ]
        .spacing(5))
    } else {
        None
    };

    let port_input = column![
        text("Port"),
        text_input("8765", &form.port)
            .padding(10)
            .on_input(Message::ProfilePortChanged),
    ]
    .spacing(5);

    let description_input = column![
        text("Description (optional)"),
        text_input("Profile description", &form.description)
            .padding(10)
            .on_input(Message::ProfileDescriptionChanged),
    ]
    .spacing(5);

    let save_btn = button(text("Save"))
        .padding(10)
        .style(button::primary)
        .on_press(Message::SaveProfile(form_to_profile(form)));

    let cancel_btn = button(text("Cancel"))
        .padding(10)
        .style(button::secondary)
        .on_press(Message::CancelProfileEdit);

    let mut content_items: Vec<Element<'static, Message>> = vec![
        title.into(),
        vertical_space().height(20).into(),
        name_input.into(),
        vertical_space().height(10).into(),
        conn_type_input.into(),
        vertical_space().height(10).into(),
    ];

    if let Some(host) = host_input {
        content_items.push(host.into());
        content_items.push(vertical_space().height(10).into());
    }

    if let Some(addr) = address_input {
        content_items.push(addr.into());
        content_items.push(vertical_space().height(10).into());
    }

    content_items.push(port_input.into());
    content_items.push(vertical_space().height(10).into());
    content_items.push(description_input.into());
    content_items.push(vertical_space().height(20).into());
    content_items.push(row![save_btn, cancel_btn].spacing(10).into());

    let content = column(content_items)
        .spacing(5)
        .padding(10);

    container(content)
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
}

/// Convert form to Profile
fn form_to_profile(form: &ProfileForm) -> Profile {
    let connection_type = match form.connection_type.as_str() {
        "wifi" => ConnectionType::Wifi,
        "bluetooth" => ConnectionType::Bluetooth,
        _ => ConnectionType::Usb,
    };

    Profile {
        name: form.name.clone(),
        connection_type,
        description: if form.description.is_empty() {
            None
        } else {
            Some(form.description.clone())
        },
        port: form.port.parse().ok(),
        host: if form.host.is_empty() || form.connection_type != "wifi" {
            None
        } else {
            Some(form.host.clone())
        },
        address: if form.address.is_empty() || form.connection_type != "bluetooth" {
            None
        } else {
            Some(form.address.clone())
        },
        filter: FilterConfig::default(),
    }
}
