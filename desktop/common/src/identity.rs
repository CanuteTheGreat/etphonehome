//! Who this client is, for the phone: a random id kept next to the config
//! file (so the phone can tell "the same computer again" from "another
//! computer"), the host name (shown in the phone's list of paired computers
//! and routers), and whether it runs as a router uplink.
//!
//! ETPH Pro is enforced by the phone: the free version works with one
//! computer over USB, routers and further computers need Pro ON THE PHONE.
//! Nothing here is a licence; it only lets the phone apply that rule.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClientIdentity {
    pub id: String,
    pub name: String,
    pub router: bool,
}

static ROUTER: AtomicBool = AtomicBool::new(false);
static CACHED: Mutex<Option<(String, String)>> = Mutex::new(None);

/// Mark this process as a router uplink (`--router`, set by OpenWrt's `proto etph`).
pub fn set_router(router: bool) {
    ROUTER.store(router, Ordering::Relaxed);
}

/// Router mode: `--router` or ETPH_ROUTER=1 in the environment.
pub fn is_router() -> bool {
    ROUTER.load(Ordering::Relaxed)
        || std::env::var("ETPH_ROUTER")
            .map(|v| v == "1" || v == "true")
            .unwrap_or(false)
}

/// This client's identity (computed once per process).
pub fn current() -> ClientIdentity {
    let mut c = CACHED.lock().unwrap_or_else(|e| e.into_inner());
    let (id, name) = c
        .get_or_insert_with(|| (load_or_create_id(), host_name()))
        .clone();
    let router = is_router();
    ClientIdentity {
        id,
        name: if router {
            router_name(&name, router_model().as_deref())
        } else {
            name
        },
        router,
    }
}

/// How the phone shows a router: "slate (GL.iNet GL-AR750S, OpenWrt)".
pub fn router_name(host: &str, model: Option<&str>) -> String {
    match model.map(str::trim).filter(|m| !m.is_empty()) {
        Some(m) => {
            // "GL.iNet GL-AR750S (NOR/NAND)" -> "GL.iNet GL-AR750S"
            let m = match m.find(" (") {
                Some(i) if m.ends_with(')') => &m[..i],
                _ => m,
            };
            // "NVIDIA NVIDIA Jetson ..." -> "NVIDIA Jetson ..."
            let words: Vec<&str> = m.split_whitespace().collect();
            let mut m = if words.len() > 1 && words[0] == words[1] {
                words[1..].join(" ")
            } else {
                words.join(" ")
            };
            // The handshake carries 64 bytes of name: keep ", OpenWrt)" in it.
            let room = crate::protocol::MAX_CLIENT_FIELD.saturating_sub(host.len() + 12);
            if m.len() > room {
                let mut cut = room.saturating_sub(1);
                while cut > 0 && !m.is_char_boundary(cut) {
                    cut -= 1;
                }
                m.truncate(cut);
                m = format!("{}…", m.trim_end());
            }
            format!("{host} ({m}, OpenWrt)")
        }
        None => format!("{host} (OpenWrt router)"),
    }
}

/// The router's model: OpenWrt's /tmp/sysinfo/model, else the device tree.
fn router_model() -> Option<String> {
    ["/tmp/sysinfo/model", "/proc/device-tree/model"]
        .iter()
        .find_map(|p| std::fs::read_to_string(p).ok())
        .map(|s| {
            s.trim_matches(|c: char| c == '\0' || c.is_whitespace())
                .to_string()
        })
        .filter(|s| !s.is_empty())
}

/// Where the id lives: next to config.toml (under sudo, the invoking user's).
pub fn id_path() -> Option<PathBuf> {
    crate::config::Config::default_path()
        .ok()
        .and_then(|p| p.parent().map(|d| d.join("client-id")))
}

fn load_or_create_id() -> String {
    if let Ok(v) = std::env::var("ETPH_CLIENT_ID") {
        if !v.trim().is_empty() {
            return v.trim().to_string();
        }
    }
    let Some(path) = id_path() else {
        return fallback_id();
    };
    if let Ok(s) = std::fs::read_to_string(&path) {
        let s = s.trim();
        if valid_id(s) {
            return s.to_string();
        }
    }
    let id = new_id();
    let saved = path
        .parent()
        .map(|d| std::fs::create_dir_all(d).is_ok())
        .unwrap_or(true)
        && std::fs::write(&path, format!("{id}\n")).is_ok();
    if saved {
        #[cfg(unix)]
        crate::config::chown_to_sudo_user(&path);
        id
    } else {
        // Read-only config dir: a stable id from the host name, so the phone
        // still sees the same computer every time.
        fallback_id()
    }
}

fn valid_id(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= crate::protocol::MAX_CLIENT_FIELD
        && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
}

/// 128 random bits, hex.
pub fn new_id() -> String {
    use rand_core::RngCore;
    let mut b = [0u8; 16];
    rand_core::OsRng.fill_bytes(&mut b);
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn fallback_id() -> String {
    let h = host_name();
    let mut out = String::from("host-");
    out.extend(
        h.chars()
            .filter(|c| c.is_ascii_alphanumeric() || *c == '-')
            .take(40),
    );
    out
}

/// The host name, as the phone shows it.
pub fn host_name() -> String {
    if let Ok(v) = std::env::var("ETPH_CLIENT_NAME") {
        if !v.trim().is_empty() {
            return v.trim().to_string();
        }
    }
    #[cfg(unix)]
    {
        if let Ok(h) = std::fs::read_to_string("/proc/sys/kernel/hostname") {
            let h = h.trim();
            if !h.is_empty() {
                return h.to_string();
            }
        }
        if let Ok(h) = std::env::var("HOSTNAME") {
            if !h.trim().is_empty() {
                return h.trim().to_string();
            }
        }
    }
    #[cfg(windows)]
    if let Ok(h) = std::env::var("COMPUTERNAME") {
        if !h.trim().is_empty() {
            return h.trim().to_string();
        }
    }
    "computer".to_string()
}

/// The text shown when the phone refused us because it needs ETPH Pro.
pub fn pro_required_hint(phone_message: &str) -> String {
    format!(
        "{phone_message} -- ETPH Pro is a one-time purchase in the ETPH app on the phone; nothing \
         needs to be bought or licensed on this computer or router."
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn routers_are_named_with_their_model() {
        assert_eq!(
            router_name("slate", Some("GL.iNet GL-AR750S (NOR/NAND)\n")),
            "slate (GL.iNet GL-AR750S, OpenWrt)"
        );
        assert_eq!(
            router_name("OpenWrt", Some("QEMU Standard PC (Q35 + ICH9, 2009)")),
            "OpenWrt (QEMU Standard PC, OpenWrt)"
        );
        assert_eq!(router_name("gw", None), "gw (OpenWrt router)");
        let long = router_name(
            "etph-usb-router",
            Some("NVIDIA NVIDIA Jetson AGX Orin Developer Kit"),
        );
        assert!(long.len() <= crate::protocol::MAX_CLIENT_FIELD, "{long}");
        assert!(
            long.starts_with("etph-usb-router (NVIDIA Jetson") && long.ends_with(", OpenWrt)"),
            "{long}"
        );
    }

    #[test]
    fn new_ids_are_random_hex_and_valid() {
        let a = new_id();
        let b = new_id();
        assert_eq!(a.len(), 32);
        assert_ne!(a, b);
        assert!(valid_id(&a));
        assert!(!valid_id("has space"));
        assert!(!valid_id(""));
    }

    #[test]
    fn fallback_id_is_stable_and_valid() {
        assert_eq!(fallback_id(), fallback_id());
        assert!(valid_id(&fallback_id()));
    }

    #[test]
    fn router_identity_sets_the_router_capability_in_the_request() {
        use crate::protocol::{capabilities, HandshakeRequest};
        let mut id = current();
        assert!(!id.id.is_empty() && !id.name.is_empty());
        id.router = true;
        let req = HandshakeRequest::for_identity(capabilities::USB, None, &id);
        assert_ne!(req.capabilities & capabilities::ROUTER, 0);
        assert_eq!(req.client_id.as_deref(), Some(id.id.as_str()));
        id.router = false;
        let req = HandshakeRequest::for_identity(capabilities::USB, Some("t".into()), &id);
        assert_eq!(req.capabilities, capabilities::USB);
        assert_eq!(req.token.as_deref(), Some("t"));
    }

    #[test]
    fn hint_says_the_licence_is_on_the_phone() {
        let h = pro_required_hint("Pro required on the phone: routers are part of ETPH Pro.");
        assert!(h.contains("nothing needs to be bought or licensed on this computer or router"));
    }
}
