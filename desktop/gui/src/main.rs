//! ET Phone Home Desktop GUI
//!
//! A graphical interface for the ET Phone Home tethering application.

mod app;
mod message;
mod style;
mod views;
mod components;

use app::EtPhoneHomeApp;

fn main() -> iced::Result {
    env_logger::Builder::from_env(
        env_logger::Env::default().default_filter_or("info")
    ).init();

    log::info!("Starting ET Phone Home GUI");

    iced::application("ET Phone Home", EtPhoneHomeApp::update, EtPhoneHomeApp::view)
        .subscription(EtPhoneHomeApp::subscription)
        .theme(EtPhoneHomeApp::theme)
        .window_size((800.0, 600.0))
        .run_with(EtPhoneHomeApp::new)
}
