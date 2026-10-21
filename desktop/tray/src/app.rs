use anyhow::Result;
use etphonehome_common::daemon::{self, Reply, Request};
use std::time::{Duration, Instant};
use tao::event::{Event, StartCause};
use tao::event_loop::{ControlFlow, EventLoopBuilder, EventLoopProxy};
use tray_icon::menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem, Submenu};
use tray_icon::{Icon, TrayIcon, TrayIconBuilder};

const HELP_URL: &str = "https://errant.solutions/etphonehome/#desktop";
const POLL: Duration = Duration::from_secs(2);

enum UserEvent {
    Menu(MenuEvent),
    Reply(Result<Reply, String>),
}

/// Ask the service on a worker thread; the answer comes back as an event.
fn ask(proxy: &EventLoopProxy<UserEvent>, req: Request) {
    let proxy = proxy.clone();
    std::thread::spawn(move || {
        let r = daemon::request_blocking(&req).map_err(|e| format!("{:#}", e));
        let _ = proxy.send_event(UserEvent::Reply(r));
    });
}

/// A filled circle on a transparent square: the state at a glance.
fn dot(rgb: [u8; 3]) -> Icon {
    const N: u32 = 32;
    let mut px = Vec::with_capacity((N * N * 4) as usize);
    let c = (N as f32 - 1.0) / 2.0;
    for y in 0..N {
        for x in 0..N {
            let d = ((x as f32 - c).powi(2) + (y as f32 - c).powi(2)).sqrt();
            let a = (13.5 - d).clamp(0.0, 1.0);
            px.extend_from_slice(&[rgb[0], rgb[1], rgb[2], (a * 255.0) as u8]);
        }
    }
    Icon::from_rgba(px, N, N).expect("valid icon")
}

fn colour(state: &str) -> [u8; 3] {
    match state {
        "connected" => [0x2e, 0xa0, 0x43],
        "connecting" | "authorizing" | "retrying" => [0xe0, 0xa1, 0x00],
        "failed" | "service-down" => [0xcf, 0x22, 0x2e],
        _ => [0x8c, 0x95, 0x9f],
    }
}

fn human(bytes: u64) -> String {
    const U: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];
    let mut v = bytes as f64;
    let mut i = 0;
    while v >= 1000.0 && i < U.len() - 1 {
        v /= 1000.0;
        i += 1;
    }
    if i == 0 {
        format!("{bytes} B")
    } else {
        format!("{v:.1} {}", U[i])
    }
}

struct Ui {
    tray: TrayIcon,
    state_item: MenuItem,
    phone_item: MenuItem,
    pro_item: MenuItem,
    traffic_item: MenuItem,
    usb_item: MenuItem,
    profiles_menu: Submenu,
    profile_items: Vec<(MenuItem, String)>,
    disconnect_item: MenuItem,
    help_item: MenuItem,
    quit_item: MenuItem,
    last_state: String,
}

impl Ui {
    fn build() -> Result<Self> {
        let menu = Menu::new();
        let state_item = MenuItem::new("ETPH: checking the service…", false, None);
        let phone_item = MenuItem::new("Phone: -", false, None);
        let pro_item = MenuItem::new("ETPH Pro on the phone: -", false, None);
        let traffic_item = MenuItem::new("No traffic yet", false, None);
        let usb_item = MenuItem::new("Connect over USB", true, None);
        let profiles_menu = Submenu::new("Connect to a saved phone", false);
        let disconnect_item = MenuItem::new("Disconnect", false, None);
        let help_item = MenuItem::new("Help and Wi-Fi setup…", true, None);
        let quit_item = MenuItem::new("Quit ETPH menu", true, None);
        menu.append_items(&[
            &state_item,
            &phone_item,
            &pro_item,
            &traffic_item,
            &PredefinedMenuItem::separator(),
            &usb_item,
            &profiles_menu,
            &disconnect_item,
            &PredefinedMenuItem::separator(),
            &help_item,
            &quit_item,
        ])?;
        let tray = TrayIconBuilder::new()
            .with_menu(Box::new(menu))
            .with_tooltip("ETPH - internet from your phone")
            .with_icon(dot(colour("idle")))
            .build()?;
        Ok(Self {
            tray,
            state_item,
            phone_item,
            pro_item,
            traffic_item,
            usb_item,
            profiles_menu,
            profile_items: Vec::new(),
            disconnect_item,
            help_item,
            quit_item,
            last_state: String::new(),
        })
    }

    fn show(&mut self, r: &Result<Reply, String>) {
        let (state, label) = match r {
            Err(e) => (
                "service-down".to_string(),
                format!(
                    "ETPH service not running ({})",
                    e.lines().next().unwrap_or("")
                ),
            ),
            Ok(rep) => {
                let what = rep.session.as_deref().unwrap_or("");
                let label = match rep.state.as_str() {
                    "idle" => "ETPH: not connected".to_string(),
                    "connected" => format!("ETPH: connected ({what})"),
                    "authorizing" => "ETPH: unlock the phone and tap Allow".to_string(),
                    "connecting" => format!("ETPH: connecting ({what})…"),
                    "retrying" => format!("ETPH: waiting for the phone ({what})"),
                    s => format!("ETPH: {s}"),
                };
                (rep.state.clone(), label)
            }
        };
        self.state_item.set_text(&label);
        if let Ok(rep) = r {
            self.phone_item.set_text(format!(
                "Phone: {}",
                rep.device
                    .as_deref()
                    .unwrap_or(if rep.state == "connected" {
                        "connected"
                    } else {
                        "-"
                    })
            ));
            self.pro_item.set_text(match rep.phone_pro {
                Some(true) => "ETPH Pro on the phone: yes",
                Some(false) => "ETPH Pro on the phone: no (free: USB, one computer)",
                None => "ETPH Pro on the phone: -",
            });
            self.traffic_item
                .set_text(if rep.bytes_sent + rep.bytes_received > 0 {
                    format!(
                        "Sent {}, received {}",
                        human(rep.bytes_sent),
                        human(rep.bytes_received)
                    )
                } else if let Some(e) = &rep.last_error {
                    let short: String = e.chars().take(90).collect();
                    format!("Last error: {short}")
                } else {
                    "No traffic yet".to_string()
                });
            let busy = !matches!(rep.state.as_str(), "idle" | "stopped" | "failed");
            self.disconnect_item.set_enabled(busy);
            self.usb_item.set_enabled(true);
            let names: Vec<String> = self.profile_items.iter().map(|(_, n)| n.clone()).collect();
            if names != rep.profiles {
                for (item, _) in self.profile_items.drain(..) {
                    let _ = self.profiles_menu.remove(&item);
                }
                for name in &rep.profiles {
                    let item = MenuItem::new(name, true, None);
                    let _ = self.profiles_menu.append(&item);
                    self.profile_items.push((item, name.clone()));
                }
                self.profiles_menu.set_enabled(!rep.profiles.is_empty());
            }
        } else {
            self.usb_item.set_enabled(false);
            self.disconnect_item.set_enabled(false);
        }
        if state != self.last_state {
            let _ = self.tray.set_icon(Some(dot(colour(&state))));
            let _ = self.tray.set_tooltip(Some(label.as_str()));
            self.last_state = state;
        }
    }
}

fn open_url(url: &str) {
    #[cfg(target_os = "macos")]
    let _ = std::process::Command::new("open").arg(url).spawn();
    #[cfg(windows)]
    let _ = std::process::Command::new("cmd")
        .args(["/C", "start", "", url])
        .spawn();
}

pub fn run() -> Result<()> {
    #[allow(unused_mut)]
    let mut event_loop = EventLoopBuilder::<UserEvent>::with_user_event().build();
    #[cfg(target_os = "macos")]
    {
        use tao::platform::macos::{ActivationPolicy, EventLoopExtMacOS};
        event_loop.set_activation_policy(ActivationPolicy::Accessory);
    }
    let proxy = event_loop.create_proxy();
    {
        let proxy = proxy.clone();
        MenuEvent::set_event_handler(Some(move |e| {
            let _ = proxy.send_event(UserEvent::Menu(e));
        }));
    }
    let mut ui: Option<Ui> = None;
    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::WaitUntil(Instant::now() + POLL);
        match event {
            Event::NewEvents(StartCause::Init) => {
                match Ui::build() {
                    Ok(u) => ui = Some(u),
                    Err(e) => {
                        log::error!("cannot create the tray icon: {e:#}");
                        *control_flow = ControlFlow::Exit;
                        return;
                    }
                }
                ask(
                    &proxy,
                    Request {
                        cmd: "status".into(),
                        ..Default::default()
                    },
                );
            }
            Event::NewEvents(StartCause::ResumeTimeReached { .. }) => {
                ask(
                    &proxy,
                    Request {
                        cmd: "status".into(),
                        ..Default::default()
                    },
                );
            }
            Event::UserEvent(UserEvent::Reply(r)) => {
                if let Some(u) = ui.as_mut() {
                    u.show(&r);
                }
            }
            Event::UserEvent(UserEvent::Menu(e)) => {
                let Some(u) = ui.as_mut() else { return };
                let id = e.id();
                if id == u.quit_item.id() {
                    *control_flow = ControlFlow::Exit;
                } else if id == u.help_item.id() {
                    open_url(HELP_URL);
                } else if id == u.usb_item.id() {
                    u.state_item.set_text("ETPH: connecting (USB)…");
                    ask(
                        &proxy,
                        Request {
                            cmd: "connect".into(),
                            mode: Some("usb".into()),
                            ..Default::default()
                        },
                    );
                } else if id == u.disconnect_item.id() {
                    ask(
                        &proxy,
                        Request {
                            cmd: "disconnect".into(),
                            ..Default::default()
                        },
                    );
                } else if let Some((_, name)) = u.profile_items.iter().find(|(i, _)| i.id() == id) {
                    ask(
                        &proxy,
                        Request {
                            cmd: "connect".into(),
                            profile: Some(name.clone()),
                            ..Default::default()
                        },
                    );
                }
            }
            _ => {}
        }
    });
}
