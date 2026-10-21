//! ADB over USB through Linux usbfs (/dev/bus/usb), no libusb: the phone's
//! adb interface (class 0xff, subclass 0x42, protocol 0x01) is found in
//! sysfs, claimed, and spoken to with synchronous bulk transfers.

use super::proto::Header;
use super::Link;
use std::fs;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

/// A phone with USB debugging on, as found in sysfs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UsbAdbDevice {
    pub dev_node: PathBuf,
    pub interface: u32,
    pub ep_in: u8,
    pub ep_out: u8,
    pub max_packet: usize,
    pub serial: String,
    pub product: String,
    pub sysfs: String,
}

fn read_trim(p: &Path) -> Option<String> {
    fs::read_to_string(p).ok().map(|s| s.trim().to_string())
}

/// Every adb interface under `sysfs` (normally /sys/bus/usb/devices).
pub fn find_devices(sysfs: &Path) -> Vec<UsbAdbDevice> {
    let mut out = Vec::new();
    let Ok(entries) = fs::read_dir(sysfs) else {
        return out;
    };
    let mut names: Vec<String> = entries
        .filter_map(|e| e.ok())
        .map(|e| e.file_name().to_string_lossy().to_string())
        .filter(|n| n.contains(':'))
        .collect();
    names.sort();
    for name in names {
        let ifdir = sysfs.join(&name);
        let class = read_trim(&ifdir.join("bInterfaceClass"));
        let sub = read_trim(&ifdir.join("bInterfaceSubClass"));
        let proto = read_trim(&ifdir.join("bInterfaceProtocol"));
        if (class.as_deref(), sub.as_deref(), proto.as_deref())
            != (Some("ff"), Some("42"), Some("01"))
        {
            continue;
        }
        let Some(iface) = read_trim(&ifdir.join("bInterfaceNumber"))
            .and_then(|s| u32::from_str_radix(&s, 16).ok())
        else {
            continue;
        };
        let (mut ep_in, mut ep_out, mut max_packet) = (None, None, 512usize);
        if let Ok(eps) = fs::read_dir(&ifdir) {
            for ep in eps.filter_map(|e| e.ok()) {
                let p = ep.path();
                if !ep.file_name().to_string_lossy().starts_with("ep_") {
                    continue;
                }
                if read_trim(&p.join("type")).as_deref() != Some("Bulk") {
                    continue;
                }
                let Some(addr) = read_trim(&p.join("bEndpointAddress"))
                    .and_then(|s| u8::from_str_radix(&s, 16).ok())
                else {
                    continue;
                };
                if let Some(mp) = read_trim(&p.join("wMaxPacketSize"))
                    .and_then(|s| usize::from_str_radix(&s, 16).ok())
                {
                    if mp > 0 {
                        max_packet = mp;
                    }
                }
                if addr & 0x80 != 0 {
                    ep_in = Some(addr);
                } else {
                    ep_out = Some(addr);
                }
            }
        }
        let (Some(ep_in), Some(ep_out)) = (ep_in, ep_out) else {
            continue;
        };
        let devname = name.split(':').next().unwrap_or_default();
        let devdir = sysfs.join(devname);
        let (Some(bus), Some(dev)) = (
            read_trim(&devdir.join("busnum")).and_then(|s| s.parse::<u32>().ok()),
            read_trim(&devdir.join("devnum")).and_then(|s| s.parse::<u32>().ok()),
        ) else {
            continue;
        };
        out.push(UsbAdbDevice {
            dev_node: PathBuf::from(format!("/dev/bus/usb/{bus:03}/{dev:03}")),
            interface: iface,
            ep_in,
            ep_out,
            max_packet,
            serial: read_trim(&devdir.join("serial")).unwrap_or_default(),
            product: [
                read_trim(&devdir.join("manufacturer")),
                read_trim(&devdir.join("product")),
            ]
            .into_iter()
            .flatten()
            .collect::<Vec<_>>()
            .join(" "),
            sysfs: devname.to_string(),
        });
    }
    out
}

/// Pick the phone: the one with `serial`, else the only/first one.
pub fn pick(devs: &[UsbAdbDevice], serial: Option<&str>) -> io::Result<UsbAdbDevice> {
    let serial = serial.map(str::trim).filter(|s| !s.is_empty());
    let found = match serial {
        Some(s) => devs.iter().find(|d| d.serial == s),
        None => devs.first(),
    };
    found.cloned().ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::NotFound,
            match (serial, devs.is_empty()) {
                (_, true) => "no phone with USB debugging on the USB port (plug it in, unlock it, and turn on \
                              Developer options > USB debugging)"
                    .to_string(),
                (Some(s), false) => format!(
                    "no phone with serial {s} on USB (found: {})",
                    devs.iter().map(|d| d.serial.as_str()).collect::<Vec<_>>().join(", ")
                ),
                _ => unreachable!(),
            },
        )
    })
}

// ---- usbfs ioctls ---------------------------------------------------------

#[repr(C)]
struct BulkTransfer {
    ep: u32,
    len: u32,
    timeout: u32,
    data: *mut libc::c_void,
}

/// Linux _IOC: MIPS/PowerPC use 3 direction bits at 29 and other values.
pub(crate) const fn ioc(dir: u32, nr: u32, size: usize) -> u32 {
    let ty = b'U' as u32;
    if cfg!(any(
        target_arch = "mips",
        target_arch = "mips64",
        target_arch = "powerpc",
        target_arch = "powerpc64"
    )) {
        let d = (if dir & 1 != 0 { 4 } else { 0 }) | (if dir & 2 != 0 { 2 } else { 0 });
        (d << 29) | ((size as u32) << 16) | (ty << 8) | nr
    } else {
        (dir << 30) | ((size as u32) << 16) | (ty << 8) | nr
    }
}
const W: u32 = 1;
const R: u32 = 2;
const USBDEVFS_BULK: u32 = ioc(R | W, 2, std::mem::size_of::<BulkTransfer>());
const USBDEVFS_CLAIMINTERFACE: u32 = ioc(R, 15, 4);
const USBDEVFS_RELEASEINTERFACE: u32 = ioc(R, 16, 4);
const USBDEVFS_CLEAR_HALT: u32 = ioc(R, 21, 4);

/// usbfs request numbers, for tests.
pub fn ioctl_numbers() -> (u32, u32) {
    (USBDEVFS_BULK, USBDEVFS_CLAIMINTERFACE)
}

pub struct UsbLink {
    fd: OwnedFd,
    dev: UsbAdbDevice,
    out_lock: Mutex<()>,
    /// (bytes received but not consumed yet, transfer buffer)
    in_lock: Mutex<(Vec<u8>, Vec<u8>)>,
}

const POLL_MS: u32 = 500;

impl UsbLink {
    pub fn open(dev: &UsbAdbDevice) -> io::Result<Self> {
        let path = std::ffi::CString::new(dev.dev_node.as_os_str().as_encoded_bytes())?;
        // SAFETY: path is a valid C string; open has no other preconditions.
        let raw = unsafe { libc::open(path.as_ptr(), libc::O_RDWR | libc::O_CLOEXEC) };
        if raw < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: raw is a freshly opened descriptor we own.
        let fd = unsafe { OwnedFd::from_raw_fd(raw) };
        let mut iface = dev.interface;
        // SAFETY: usbfs CLAIMINTERFACE takes a pointer to the interface number.
        if unsafe { libc::ioctl(fd.as_raw_fd(), USBDEVFS_CLAIMINTERFACE as _, &mut iface) } < 0 {
            return Err(io::Error::last_os_error());
        }
        for ep in [dev.ep_in, dev.ep_out] {
            let mut e = ep as u32;
            // SAFETY: as above; a failed CLEAR_HALT is harmless.
            unsafe { libc::ioctl(fd.as_raw_fd(), USBDEVFS_CLEAR_HALT as _, &mut e) };
        }
        Ok(Self {
            fd,
            dev: dev.clone(),
            out_lock: Mutex::new(()),
            in_lock: Mutex::new((Vec::new(), vec![0u8; super::proto::MAX_PAYLOAD + 24])),
        })
    }

    fn bulk(&self, ep: u8, buf: &mut [u8], timeout_ms: u32) -> io::Result<usize> {
        let mut t = BulkTransfer {
            ep: ep as u32,
            len: buf.len() as u32,
            timeout: timeout_ms,
            data: buf.as_mut_ptr() as *mut libc::c_void,
        };
        // SAFETY: t points at a live buffer of t.len bytes for the call.
        let r = unsafe { libc::ioctl(self.fd.as_raw_fd(), USBDEVFS_BULK as _, &mut t) };
        if r < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(r as usize)
        }
    }

    fn write_all(&self, mut data: &[u8]) -> io::Result<()> {
        // usbfs caps one transfer at 16 KiB on older kernels.
        while !data.is_empty() {
            let n = data.len().min(16384);
            let mut chunk = data[..n].to_vec();
            let w = self.bulk(self.dev.ep_out, &mut chunk, 5000)?;
            data = &data[w..];
        }
        Ok(())
    }
}

fn is_timeout(e: &io::Error) -> bool {
    e.raw_os_error() == Some(libc::ETIMEDOUT)
}

impl Link for UsbLink {
    fn send(&self, hdr: &Header, data: &[u8]) -> io::Result<()> {
        let _g = self.out_lock.lock().unwrap();
        self.write_all(&hdr.encode())?;
        if !data.is_empty() {
            self.write_all(data)?;
            if data.len().is_multiple_of(self.dev.max_packet) {
                // End the transfer with a zero-length packet.
                let mut z = [0u8; 0];
                self.bulk(self.dev.ep_out, &mut z, 1000)?;
            }
        }
        Ok(())
    }

    fn recv(&self) -> io::Result<Option<(Header, Vec<u8>)>> {
        let mut guard = self.in_lock.lock().unwrap();
        // Read with a large buffer: an adbd that sends header and payload in
        // one transfer must not overflow a 24-byte read.
        let (pending, buf) = &mut *guard;
        while pending.len() < 24 {
            match self.bulk(self.dev.ep_in, buf, POLL_MS) {
                Ok(n) => pending.extend_from_slice(&buf[..n]),
                Err(e) if is_timeout(&e) && pending.is_empty() => return Ok(None),
                Err(e) if is_timeout(&e) => continue,
                Err(e) => return Err(e),
            }
        }
        let hdr = Header::decode(&pending[..24])?;
        let need = 24 + hdr.data_length as usize;
        let mut waited = 0;
        while pending.len() < need {
            match self.bulk(self.dev.ep_in, buf, POLL_MS) {
                Ok(n) => pending.extend_from_slice(&buf[..n]),
                Err(e) if is_timeout(&e) && waited < 20 => waited += 1,
                Err(e) => return Err(e),
            }
        }
        let data = pending[24..need].to_vec();
        pending.drain(..need);
        Ok(Some((hdr, data)))
    }

    fn close(&self) {}
}

impl Drop for UsbLink {
    fn drop(&mut self) {
        let mut iface = self.dev.interface;
        // SAFETY: releasing the interface we claimed; errors are irrelevant.
        unsafe {
            libc::ioctl(
                self.fd.as_raw_fd(),
                USBDEVFS_RELEASEINTERFACE as _,
                &mut iface,
            )
        };
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn usbfs_ioctl_numbers_match_linux_headers() {
        let (bulk, claim) = ioctl_numbers();
        if cfg!(target_arch = "x86_64") || cfg!(target_arch = "aarch64") {
            // From <linux/usbdevice_fs.h> on 64-bit: USBDEVFS_BULK = 0xc0185502.
            assert_eq!(bulk, 0xc018_5502);
            assert_eq!(claim, 0x8004_550f);
        }
    }

    fn write(p: &Path, s: &str) {
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        fs::write(p, format!("{s}\n")).unwrap();
    }

    #[test]
    fn finds_the_adb_interface_in_sysfs() {
        let root = std::env::temp_dir().join(format!("etph-sysfs-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        // A phone on 1-1 with MTP (interface 0) and adb (interface 1).
        write(&root.join("1-1/busnum"), "1");
        write(&root.join("1-1/devnum"), "7");
        write(&root.join("1-1/serial"), "R5CR70PMMQR");
        write(&root.join("1-1/manufacturer"), "SAMSUNG");
        write(&root.join("1-1/product"), "SAMSUNG_Android");
        for (k, v) in [
            ("bInterfaceClass", "06"),
            ("bInterfaceSubClass", "01"),
            ("bInterfaceProtocol", "01"),
            ("bInterfaceNumber", "00"),
        ] {
            write(&root.join("1-1:1.0").join(k), v);
        }
        for (k, v) in [
            ("bInterfaceClass", "ff"),
            ("bInterfaceSubClass", "42"),
            ("bInterfaceProtocol", "01"),
            ("bInterfaceNumber", "01"),
        ] {
            write(&root.join("1-1:1.1").join(k), v);
        }
        for (ep, dir, addr) in [("ep_81", "in", "81"), ("ep_02", "out", "02")] {
            write(&root.join("1-1:1.1").join(ep).join("type"), "Bulk");
            write(&root.join("1-1:1.1").join(ep).join("direction"), dir);
            write(
                &root.join("1-1:1.1").join(ep).join("bEndpointAddress"),
                addr,
            );
            write(
                &root.join("1-1:1.1").join(ep).join("wMaxPacketSize"),
                "0200",
            );
        }
        // A USB stick: ignored.
        write(&root.join("1-2:1.0/bInterfaceClass"), "08");
        let devs = find_devices(&root);
        assert_eq!(devs.len(), 1, "{devs:?}");
        let d = &devs[0];
        assert_eq!(d.dev_node, PathBuf::from("/dev/bus/usb/001/007"));
        assert_eq!(
            (d.interface, d.ep_in, d.ep_out, d.max_packet),
            (1, 0x81, 0x02, 512)
        );
        assert_eq!(d.serial, "R5CR70PMMQR");
        assert_eq!(d.product, "SAMSUNG SAMSUNG_Android");
        assert_eq!(pick(&devs, Some("R5CR70PMMQR")).unwrap(), *d);
        assert_eq!(pick(&devs, None).unwrap(), *d);
        assert!(pick(&devs, Some("other"))
            .unwrap_err()
            .to_string()
            .contains("found: R5CR70PMMQR"));
        assert!(pick(&[], None)
            .unwrap_err()
            .to_string()
            .contains("USB debugging"));
        let _ = fs::remove_dir_all(&root);
    }
}
