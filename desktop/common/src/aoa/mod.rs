//! USB without USB debugging: Android Open Accessory (AOA 2).
//!
//! A phone in its normal USB mode (MTP, charging, ...) is asked with three
//! vendor control requests (GET_PROTOCOL, SEND_STRING x6, START) to become a
//! "USB accessory" peer. It then re-enumerates as Google's 18d1:2d00 (or
//! 2d01 when USB debugging is also on) with one vendor interface and two
//! bulk endpoints. Android matches the strings we sent against the app's
//! `res/xml/accessory_filter.xml` and asks the user "Open ETPH to handle
//! ...?"; the app then opens the accessory and the tunnel runs over the
//! bulk endpoints. No Developer options, no adb key prompt.
//!
//! The byte stream has no notion of "connected", so both ends first agree
//! on a fresh start with a tiny preamble ([`preamble`]): HELLO+nonce from
//! us (repeated until answered), READY+nonce from the phone, START+nonce
//! from us, then the normal ETPH protocol. That makes stale bytes from an
//! earlier session (on either side) harmless.
//!
//! Linux only for now (usbfs, like the built-in ADB host): routers and the
//! Linux desktop client.

pub mod preamble;
#[cfg(target_os = "linux")]
pub mod usb;

/// Strings the host sends; the app's accessory filter matches
/// manufacturer + model (keep in sync with
/// android/app/src/main/res/xml/accessory_filter.xml).
pub const MANUFACTURER: &str = "Errant Solutions";
pub const MODEL: &str = "ETPH";
pub const VERSION: &str = "1";
/// Shown by Android when no installed app handles the accessory.
pub const URI: &str = "https://errant.solutions/etphonehome/";

/// Google's accessory-mode vendor id and product ids (accessory,
/// accessory+adb, audio variants).
pub const GOOGLE_VID: u16 = 0x18d1;
pub const ACCESSORY_PIDS: [u16; 6] = [0x2d00, 0x2d01, 0x2d02, 0x2d03, 0x2d04, 0x2d05];

/// AOA control requests.
pub const REQ_GET_PROTOCOL: u8 = 51;
pub const REQ_SEND_STRING: u8 = 52;
pub const REQ_START: u8 = 53;

/// The six AOA strings, in index order.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AccessoryStrings {
    pub manufacturer: String,
    pub model: String,
    pub description: String,
    pub version: String,
    pub uri: String,
    pub serial: String,
}

impl AccessoryStrings {
    /// Ours. `name` is this host's name as the phone shows it.
    pub fn etph(name: &str, router: bool) -> Self {
        let kind = if router { "router" } else { "computer" };
        Self {
            manufacturer: MANUFACTURER.into(),
            model: MODEL.into(),
            description: format!("Internet sharing for {name} ({kind})"),
            version: VERSION.into(),
            uri: URI.into(),
            serial: crate::identity::current().id,
        }
    }

    pub fn by_index(&self) -> [&str; 6] {
        [
            &self.manufacturer,
            &self.model,
            &self.description,
            &self.version,
            &self.uri,
            &self.serial,
        ]
    }
}

/// Control transfers to the phone (usbfs on Linux, a fake in tests).
pub trait Control {
    /// Device-to-host vendor request; returns the bytes read.
    fn vendor_in(
        &self,
        request: u8,
        value: u16,
        index: u16,
        len: usize,
    ) -> std::io::Result<Vec<u8>>;
    /// Host-to-device vendor request with `data`.
    fn vendor_out(&self, request: u8, value: u16, index: u16, data: &[u8]) -> std::io::Result<()>;
}

/// Ask the phone to switch to accessory mode: GET_PROTOCOL (>= 1), the six
/// strings (NUL-terminated), START. Returns the AOA protocol version. The
/// phone then drops off the bus and comes back as 18d1:2d0x.
pub fn start_accessory(ctrl: &dyn Control, strings: &AccessoryStrings) -> anyhow::Result<u16> {
    let v = ctrl
        .vendor_in(REQ_GET_PROTOCOL, 0, 0, 2)
        .map_err(|e| anyhow::anyhow!("the phone does not answer the accessory request ({e})"))?;
    if v.len() < 2 {
        anyhow::bail!("the phone gave a short accessory protocol answer");
    }
    let version = u16::from_le_bytes([v[0], v[1]]);
    if version < 1 {
        anyhow::bail!("the phone does not support USB accessories (AOA protocol {version})");
    }
    for (i, s) in strings.by_index().iter().enumerate() {
        let mut data = s.as_bytes().to_vec();
        data.truncate(255);
        data.push(0);
        ctrl.vendor_out(REQ_SEND_STRING, 0, i as u16, &data)?;
    }
    ctrl.vendor_out(REQ_START, 0, 0, &[])?;
    Ok(version)
}

/// A phone's name for messages: "SAMSUNG_Android" -> "SAMSUNG Android".
pub fn pretty_name(manufacturer: &str, product: &str) -> String {
    let p = product.replace('_', " ");
    let m = manufacturer.trim();
    let out = if m.is_empty() || p.to_lowercase().starts_with(&m.to_lowercase()) {
        p
    } else {
        format!("{m} {p}")
    };
    let out = out.trim().to_string();
    if out.is_empty() {
        "an Android phone".into()
    } else {
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    /// A phone's AOA control endpoint: answers GET_PROTOCOL, records the
    /// strings, and "re-enumerates" on START.
    struct FakePhone {
        protocol: Option<u16>,
        strings: RefCell<Vec<(u16, Vec<u8>)>>,
        started: RefCell<bool>,
    }

    impl Control for FakePhone {
        fn vendor_in(&self, request: u8, _v: u16, _i: u16, len: usize) -> std::io::Result<Vec<u8>> {
            assert_eq!(request, REQ_GET_PROTOCOL);
            assert_eq!(len, 2);
            match self.protocol {
                // A non-Android device stalls the unknown vendor request.
                None => Err(std::io::Error::from_raw_os_error(32)),
                Some(p) => Ok(p.to_le_bytes().to_vec()),
            }
        }
        fn vendor_out(&self, request: u8, _v: u16, index: u16, data: &[u8]) -> std::io::Result<()> {
            assert!(!*self.started.borrow(), "nothing may follow START");
            match request {
                REQ_SEND_STRING => self.strings.borrow_mut().push((index, data.to_vec())),
                REQ_START => *self.started.borrow_mut() = true,
                r => panic!("unexpected request {r}"),
            }
            Ok(())
        }
    }

    fn phone(p: Option<u16>) -> FakePhone {
        FakePhone {
            protocol: p,
            strings: RefCell::new(vec![]),
            started: RefCell::new(false),
        }
    }

    #[test]
    fn switches_a_phone_to_accessory_mode_with_our_strings() {
        let f = phone(Some(2));
        let s = AccessoryStrings {
            manufacturer: MANUFACTURER.into(),
            model: MODEL.into(),
            description: "Internet sharing for slate (router)".into(),
            version: "1".into(),
            uri: URI.into(),
            serial: "abc".into(),
        };
        assert_eq!(start_accessory(&f, &s).unwrap(), 2);
        assert!(*f.started.borrow());
        let got = f.strings.borrow();
        assert_eq!(got.len(), 6);
        assert_eq!(got[0], (0, b"Errant Solutions\0".to_vec()));
        assert_eq!(got[1], (1, b"ETPH\0".to_vec()));
        assert_eq!(got[2].0, 2);
        assert_eq!(got[5], (5, b"abc\0".to_vec()));
    }

    #[test]
    fn a_device_without_aoa_is_left_alone() {
        let f = phone(None);
        let e = start_accessory(&f, &AccessoryStrings::etph("x", true)).unwrap_err();
        assert!(e.to_string().contains("does not answer"), "{e}");
        assert!(!*f.started.borrow());
        let f = phone(Some(0));
        assert!(start_accessory(&f, &AccessoryStrings::etph("x", true)).is_err());
        assert!(f.strings.borrow().is_empty());
    }

    #[test]
    fn strings_match_the_app_filter() {
        let filter = include_str!("../../../../android/app/src/main/res/xml/accessory_filter.xml");
        assert!(
            filter.contains(&format!("manufacturer=\"{MANUFACTURER}\"")),
            "{filter}"
        );
        assert!(filter.contains(&format!("model=\"{MODEL}\"")), "{filter}");
    }

    #[test]
    fn pretty_names() {
        assert_eq!(pretty_name("SAMSUNG", "SAMSUNG_Android"), "SAMSUNG Android");
        assert_eq!(pretty_name("Google", "Pixel 8"), "Google Pixel 8");
        assert_eq!(pretty_name("", ""), "an Android phone");
    }
}
