//! ET Phone Home Desktop GUI
//!
//! A graphical interface for the ET Phone Home tethering application.
#![cfg_attr(windows, windows_subsystem = "windows")]

mod app;
mod components;
mod message;
mod style;
mod views;

use app::EtPhoneHomeApp;

fn main() -> iced::Result {
    // On Windows the wgpu/DX12 path panics ("Surface::configure: Invalid surface",
    // DXGI_ERROR_NOT_CURRENTLY_AVAILABLE) on machines without a usable GPU swap chain
    // (VMs, RDP, CI). This form UI needs no GPU, so use iced's software renderer
    // unless the user chose a backend.
    #[cfg(windows)]
    if std::env::var_os("ICED_BACKEND").is_none() {
        std::env::set_var("ICED_BACKEND", "tiny-skia");
    }

    etphonehome_common::logfmt::init("info");

    log::info!("Starting ET Phone Home GUI");

    iced::application(
        "ET Phone Home",
        EtPhoneHomeApp::update,
        EtPhoneHomeApp::view,
    )
    .subscription(EtPhoneHomeApp::subscription)
    .theme(EtPhoneHomeApp::theme)
    .window_size((800.0, 600.0))
    .run_with(EtPhoneHomeApp::new)
}
