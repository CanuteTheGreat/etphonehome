//! AOA over Linux usbfs (/dev/bus/usb), no libusb: find phones in sysfs,
//! switch one into accessory mode with control transfers, then stream over
//! its bulk endpoints. Reads use asynchronous URBs (SUBMITURB/REAPURB), so
//! a poll timeout never loses bytes that were already transferred (a
//! synchronous USBDEVFS_BULK that times out mid-transfer drops them).

use super::preamble::RawLink;
use super::{Control, ACCESSORY_PIDS, GOOGLE_VID};
use crate::adb::usb::ioc;
use std::fs;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// One USB interface, from sysfs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Iface {
    pub number: u32,
    pub class: u8,
    pub subclass: u8,
    pub protocol: u8,
    /// (endpoint address, max packet size) of its bulk endpoints.
    pub bulk: Vec<(u8, usize)>,
}

/// One USB device (not a hub), from sysfs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UsbDev {
    /// sysfs name = physical port, e.g. "1-1" (stable across re-enumeration).
    pub port: String,
    pub dev_node: PathBuf,
    pub vid: u16,
    pub pid: u16,
    pub class: u8,
    pub manufacturer: String,
    pub product: String,
    pub serial: String,
    pub interfaces: Vec<Iface>,
}

/// Interface classes no phone shows; a device made only of these is not
/// probed (audio, HID, printer, storage, hub, smart card, video, wireless
/// controller e.g. Bluetooth dongles).
const NEVER_PHONE: [u8; 8] = [0x01, 0x03, 0x07, 0x08, 0x09, 0x0b, 0x0e, 0xe0];

/// USB vendor ids of Android phone makers (Samsung, Google, Motorola, LG,
/// HTC, Sony, Xiaomi, OnePlus, Huawei, OPPO/Realme, vivo, HMD/Nokia, ZTE,
/// ASUS, Lenovo, Fairphone, LeEco, Essential, TCL/Alcatel, Sharp, Kyocera).
const ANDROID_VENDORS: [u16; 21] = [
    0x04e8, 0x18d1, 0x22b8, 0x1004, 0x0bb4, 0x0fce, 0x2717, 0x2a70, 0x12d1, 0x22d9, 0x2d95, 0x2e04,
    0x19d2, 0x0b05, 0x17ef, 0x2ae5, 0x2b0e, 0x2e17, 0x1bbb, 0x04dd, 0x0482,
];

impl UsbDev {
    pub fn is_accessory(&self) -> bool {
        self.vid == GOOGLE_VID && ACCESSORY_PIDS.contains(&self.pid)
    }

    pub fn has_adb(&self) -> bool {
        self.interfaces
            .iter()
            .any(|i| (i.class, i.subclass, i.protocol) == (0xff, 0x42, 0x01))
    }

    /// Worth asking GET_PROTOCOL: not a hub, and it looks like an Android
    /// phone -- an MTP/PTP or adb interface, or a phone maker's vendor id
    /// (charging-only mode shows neither). Other devices (fingerprint
    /// readers, webcams, sticks) are never probed.
    pub fn maybe_phone(&self) -> bool {
        self.class != 0x09
            && !self.interfaces.is_empty()
            && self
                .interfaces
                .iter()
                .any(|i| !NEVER_PHONE.contains(&i.class))
            && (self.has_adb()
                || self.interfaces.iter().any(|i| i.class == 0x06)
                || ANDROID_VENDORS.contains(&self.vid))
    }

    pub fn name(&self) -> String {
        super::pretty_name(&self.manufacturer, &self.product)
    }

    /// The accessory interface (number 0, vendor class, two bulk endpoints).
    pub fn accessory_iface(&self) -> Option<(u32, u8, u8, usize)> {
        self.interfaces.iter().find_map(|i| {
            if i.class != 0xff || i.subclass == 0x42 {
                return None;
            }
            let ep_in = i.bulk.iter().find(|(a, _)| a & 0x80 != 0)?;
            let ep_out = i.bulk.iter().find(|(a, _)| a & 0x80 == 0)?;
            Some((i.number, ep_in.0, ep_out.0, ep_out.1.max(64)))
        })
    }
}

fn read_trim(p: &Path) -> Option<String> {
    fs::read_to_string(p).ok().map(|s| s.trim().to_string())
}

fn hex<T: TryFrom<u32>>(p: &Path) -> Option<T> {
    read_trim(p)
        .and_then(|s| u32::from_str_radix(&s, 16).ok())
        .and_then(|v| T::try_from(v).ok())
}

/// Every non-root USB device under `sysfs` (normally /sys/bus/usb/devices).
pub fn scan(sysfs: &Path) -> Vec<UsbDev> {
    let Ok(entries) = fs::read_dir(sysfs) else {
        return vec![];
    };
    let mut names: Vec<String> = entries
        .filter_map(|e| e.ok())
        .map(|e| e.file_name().to_string_lossy().to_string())
        .collect();
    names.sort();
    let mut out = Vec::new();
    for name in names
        .iter()
        .filter(|n| !n.contains(':') && !n.starts_with("usb"))
    {
        let d = sysfs.join(name);
        let (Some(vid), Some(pid)) = (
            hex::<u16>(&d.join("idVendor")),
            hex::<u16>(&d.join("idProduct")),
        ) else {
            continue;
        };
        let (Some(bus), Some(dev)) = (
            read_trim(&d.join("busnum")).and_then(|s| s.parse::<u32>().ok()),
            read_trim(&d.join("devnum")).and_then(|s| s.parse::<u32>().ok()),
        ) else {
            continue;
        };
        let mut interfaces = Vec::new();
        let prefix = format!("{name}:");
        for ifname in names.iter().filter(|n| n.starts_with(&prefix)) {
            let ifdir = sysfs.join(ifname);
            let (Some(class), Some(subclass), Some(protocol), Some(number)) = (
                hex::<u8>(&ifdir.join("bInterfaceClass")),
                hex::<u8>(&ifdir.join("bInterfaceSubClass")),
                hex::<u8>(&ifdir.join("bInterfaceProtocol")),
                hex::<u32>(&ifdir.join("bInterfaceNumber")),
            ) else {
                continue;
            };
            let mut bulk = Vec::new();
            if let Ok(eps) = fs::read_dir(&ifdir) {
                for ep in eps.filter_map(|e| e.ok()) {
                    if !ep.file_name().to_string_lossy().starts_with("ep_") {
                        continue;
                    }
                    let p = ep.path();
                    if read_trim(&p.join("type")).as_deref() != Some("Bulk") {
                        continue;
                    }
                    if let Some(addr) = hex::<u8>(&p.join("bEndpointAddress")) {
                        bulk.push((addr, hex::<usize>(&p.join("wMaxPacketSize")).unwrap_or(512)));
                    }
                }
            }
            bulk.sort();
            interfaces.push(Iface {
                number,
                class,
                subclass,
                protocol,
                bulk,
            });
        }
        out.push(UsbDev {
            port: name.clone(),
            dev_node: PathBuf::from(format!("/dev/bus/usb/{bus:03}/{dev:03}")),
            vid,
            pid,
            class: hex::<u8>(&d.join("bDeviceClass")).unwrap_or(0),
            manufacturer: read_trim(&d.join("manufacturer")).unwrap_or_default(),
            product: read_trim(&d.join("product")).unwrap_or_default(),
            serial: read_trim(&d.join("serial")).unwrap_or_default(),
            interfaces,
        });
    }
    out
}

// ---- usbfs ----------------------------------------------------------------

#[repr(C)]
struct CtrlTransfer {
    request_type: u8,
    request: u8,
    value: u16,
    index: u16,
    length: u16,
    timeout: u32,
    data: *mut libc::c_void,
}

#[repr(C)]
struct BulkTransfer {
    ep: u32,
    len: u32,
    timeout: u32,
    data: *mut libc::c_void,
}

#[repr(C)]
struct Urb {
    typ: u8,
    endpoint: u8,
    status: i32,
    flags: u32,
    buffer: *mut libc::c_void,
    buffer_length: i32,
    actual_length: i32,
    start_frame: i32,
    number_of_packets: i32,
    error_count: i32,
    signr: u32,
    usercontext: *mut libc::c_void,
}

const W: u32 = 1;
const R: u32 = 2;
const USBDEVFS_CONTROL: u32 = ioc(R | W, 0, std::mem::size_of::<CtrlTransfer>());
const USBDEVFS_BULK: u32 = ioc(R | W, 2, std::mem::size_of::<BulkTransfer>());
const USBDEVFS_SUBMITURB: u32 = ioc(R, 10, std::mem::size_of::<Urb>());
const USBDEVFS_DISCARDURB: u32 = ioc(0, 11, 0);
const USBDEVFS_REAPURBNDELAY: u32 = ioc(W, 13, std::mem::size_of::<*mut libc::c_void>());
const USBDEVFS_CLAIMINTERFACE: u32 = ioc(R, 15, 4);
const USBDEVFS_RELEASEINTERFACE: u32 = ioc(R, 16, 4);
const USBDEVFS_CLEAR_HALT: u32 = ioc(R, 21, 4);
const URB_TYPE_BULK: u8 = 3;

/// usbfs request numbers, for tests.
pub fn ioctl_numbers() -> [u32; 3] {
    [USBDEVFS_CONTROL, USBDEVFS_SUBMITURB, USBDEVFS_REAPURBNDELAY]
}

fn open_node(path: &Path) -> io::Result<OwnedFd> {
    let c = std::ffi::CString::new(path.as_os_str().as_encoded_bytes())?;
    // SAFETY: valid C string; open has no other preconditions.
    let raw = unsafe { libc::open(c.as_ptr(), libc::O_RDWR | libc::O_CLOEXEC) };
    if raw < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: freshly opened descriptor we own.
    Ok(unsafe { OwnedFd::from_raw_fd(raw) })
}

/// A device opened for control transfers (the AOA switch).
pub struct UsbControl {
    fd: OwnedFd,
}

impl UsbControl {
    pub fn open(dev: &UsbDev) -> io::Result<Self> {
        Ok(Self {
            fd: open_node(&dev.dev_node)?,
        })
    }

    fn control(
        &self,
        request_type: u8,
        request: u8,
        value: u16,
        index: u16,
        buf: &mut [u8],
    ) -> io::Result<usize> {
        let mut t = CtrlTransfer {
            request_type,
            request,
            value,
            index,
            length: buf.len() as u16,
            timeout: 1000,
            data: buf.as_mut_ptr() as *mut libc::c_void,
        };
        // SAFETY: t points at a live buffer of t.length bytes for the call.
        let r = unsafe { libc::ioctl(self.fd.as_raw_fd(), USBDEVFS_CONTROL as _, &mut t) };
        if r < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(r as usize)
        }
    }
}

impl Control for UsbControl {
    fn vendor_in(&self, request: u8, value: u16, index: u16, len: usize) -> io::Result<Vec<u8>> {
        let mut b = vec![0u8; len];
        let n = self.control(0xc0, request, value, index, &mut b)?;
        b.truncate(n);
        Ok(b)
    }
    fn vendor_out(&self, request: u8, value: u16, index: u16, data: &[u8]) -> io::Result<()> {
        let mut b = data.to_vec();
        self.control(0x40, request, value, index, &mut b)
            .map(|_| ())
    }
}

/// After START: wait for the phone to come back in accessory mode on the
/// same port (it re-enumerates within a second or two).
pub fn wait_for_accessory(sysfs: &Path, port: &str, timeout: Duration) -> Option<UsbDev> {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if let Some(d) = scan(sysfs)
            .into_iter()
            .find(|d| d.port == port && d.is_accessory())
        {
            if d.accessory_iface().is_some() && d.dev_node.exists() {
                // udev/mdev may still be creating the node's permissions.
                std::thread::sleep(Duration::from_millis(200));
                return Some(d);
            }
        }
        std::thread::sleep(Duration::from_millis(200));
    }
    None
}

/// URBs kept in flight for reading, each this big (the phone writes at most
/// 16 KiB per USB request).
const READ_URBS: usize = 4;
const READ_SIZE: usize = 16384;

struct ReadState {
    /// Never resized after open(): the kernel holds pointers into it.
    urbs: Vec<Urb>,
    bufs: Vec<Vec<u8>>,
    submitted: Vec<bool>,
    /// Next URB expected to complete (same endpoint: in order).
    next: usize,
}

// SAFETY: the raw pointers in Urb point into `bufs`, owned by the same
// struct; access is serialised by the Mutex around ReadState.
unsafe impl Send for ReadState {}

/// The accessory's bulk endpoints.
pub struct AccessoryLink {
    fd: OwnedFd,
    iface: u32,
    ep_in: u8,
    ep_out: u8,
    max_packet: usize,
    out_lock: Mutex<()>,
    read: Mutex<ReadState>,
    closed: std::sync::atomic::AtomicBool,
}

impl AccessoryLink {
    pub fn open(dev: &UsbDev) -> io::Result<Self> {
        let (iface, ep_in, ep_out, max_packet) = dev
            .accessory_iface()
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no accessory interface"))?;
        let fd = open_node(&dev.dev_node)?;
        let mut i = iface;
        // SAFETY: CLAIMINTERFACE takes a pointer to the interface number.
        if unsafe { libc::ioctl(fd.as_raw_fd(), USBDEVFS_CLAIMINTERFACE as _, &mut i) } < 0 {
            return Err(io::Error::last_os_error());
        }
        for ep in [ep_in, ep_out] {
            let mut e = ep as u32;
            // SAFETY: as above; a failed CLEAR_HALT is harmless.
            unsafe { libc::ioctl(fd.as_raw_fd(), USBDEVFS_CLEAR_HALT as _, &mut e) };
        }
        let mut bufs: Vec<Vec<u8>> = (0..READ_URBS).map(|_| vec![0u8; READ_SIZE]).collect();
        let urbs = bufs
            .iter_mut()
            .enumerate()
            .map(|(n, b)| Urb {
                typ: URB_TYPE_BULK,
                endpoint: ep_in,
                status: 0,
                flags: 0,
                buffer: b.as_mut_ptr() as *mut libc::c_void,
                buffer_length: READ_SIZE as i32,
                actual_length: 0,
                start_frame: 0,
                number_of_packets: 0,
                error_count: 0,
                signr: 0,
                usercontext: n as *mut libc::c_void,
            })
            .collect();
        let link = Self {
            fd,
            iface,
            ep_in,
            ep_out,
            max_packet,
            out_lock: Mutex::new(()),
            read: Mutex::new(ReadState {
                urbs,
                bufs,
                submitted: vec![false; READ_URBS],
                next: 0,
            }),
            closed: std::sync::atomic::AtomicBool::new(false),
        };
        {
            let mut st = link.read.lock().unwrap();
            for n in 0..READ_URBS {
                link.submit(&mut st, n)?;
            }
        }
        Ok(link)
    }

    fn submit(&self, st: &mut ReadState, n: usize) -> io::Result<()> {
        let u: &mut Urb = &mut st.urbs[n];
        u.status = 0;
        u.actual_length = 0;
        u.buffer = st.bufs[n].as_mut_ptr() as *mut libc::c_void;
        // SAFETY: the URB and its buffer live in `st` (heap, never resized:
        // stable addresses) until reaped; they are discarded and reaped in Drop.
        if unsafe { libc::ioctl(self.fd.as_raw_fd(), USBDEVFS_SUBMITURB as _, u as *mut Urb) } < 0 {
            return Err(io::Error::last_os_error());
        }
        st.submitted[n] = true;
        Ok(())
    }

    /// Reap one completed URB without blocking.
    fn reap(&self) -> io::Result<Option<usize>> {
        let mut p: *mut Urb = std::ptr::null_mut();
        // SAFETY: REAPURBNDELAY writes one pointer to a URB we submitted.
        let r = unsafe { libc::ioctl(self.fd.as_raw_fd(), USBDEVFS_REAPURBNDELAY as _, &mut p) };
        if r < 0 {
            let e = io::Error::last_os_error();
            return if e.raw_os_error() == Some(libc::EAGAIN) {
                Ok(None)
            } else {
                Err(e)
            };
        }
        // SAFETY: p is one of our boxed URBs; usercontext holds its index.
        Ok(Some(unsafe { (*p).usercontext as usize }))
    }

    fn bulk_out(&self, data: &mut [u8], timeout: Duration) -> io::Result<usize> {
        let mut t = BulkTransfer {
            ep: self.ep_out as u32,
            len: data.len() as u32,
            timeout: timeout.as_millis().max(1) as u32,
            data: data.as_mut_ptr() as *mut libc::c_void,
        };
        // SAFETY: t points at a live buffer of t.len bytes for the call.
        let r = unsafe { libc::ioctl(self.fd.as_raw_fd(), USBDEVFS_BULK as _, &mut t) };
        if r < 0 {
            let e = io::Error::last_os_error();
            if e.raw_os_error() == Some(libc::ETIMEDOUT) {
                return Err(io::ErrorKind::TimedOut.into());
            }
            Err(e)
        } else {
            Ok(r as usize)
        }
    }
}

impl RawLink for AccessoryLink {
    fn write(&self, mut data: &[u8], timeout: Duration) -> io::Result<()> {
        let _g = self.out_lock.lock().unwrap();
        while !data.is_empty() {
            // 16 KiB per transfer: the phone reads in 16 KiB requests.
            let n = data.len().min(READ_SIZE);
            let mut chunk = data[..n].to_vec();
            let w = self.bulk_out(&mut chunk, timeout)?;
            if w == n && n.is_multiple_of(self.max_packet) {
                // End the phone's read with a zero-length packet, or a
                // request bigger than this chunk would wait for more.
                let mut z = [0u8; 0];
                self.bulk_out(&mut z, timeout)?;
            }
            data = &data[w..];
        }
        Ok(())
    }

    fn read(&self, timeout: Duration) -> io::Result<Option<Vec<u8>>> {
        let deadline = Instant::now() + timeout;
        let mut st = self.read.lock().unwrap();
        loop {
            if self.closed.load(std::sync::atomic::Ordering::Relaxed) {
                return Err(io::ErrorKind::NotConnected.into());
            }
            // Completed URBs are reaped in completion order; on one
            // endpoint that is submission order.
            if let Some(n) = self.reap()? {
                st.submitted[n] = false;
                let (status, len) = (st.urbs[n].status, st.urbs[n].actual_length.max(0) as usize);
                if status != 0 {
                    return Err(match -status {
                        libc::ENODEV | libc::ESHUTDOWN | libc::EPROTO | libc::EPIPE => {
                            io::Error::new(
                                io::ErrorKind::BrokenPipe,
                                "the phone left accessory mode (unplugged?)",
                            )
                        }
                        s => io::Error::from_raw_os_error(s),
                    });
                }
                let data = st.bufs[n][..len].to_vec();
                st.next = (n + 1) % READ_URBS;
                self.submit(&mut st, n)?;
                if !data.is_empty() {
                    return Ok(Some(data));
                }
                continue;
            }
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                return Ok(None);
            }
            let mut pfd = libc::pollfd {
                fd: self.fd.as_raw_fd(),
                events: libc::POLLOUT,
                revents: 0,
            };
            // SAFETY: one valid pollfd. usbfs reports completed URBs as POLLOUT.
            let r = unsafe { libc::poll(&mut pfd, 1, left.as_millis().min(1000) as i32) };
            if r < 0 {
                let e = io::Error::last_os_error();
                if e.kind() != io::ErrorKind::Interrupted {
                    return Err(e);
                }
            } else if pfd.revents & (libc::POLLERR | libc::POLLHUP) != 0 {
                return Err(io::Error::new(
                    io::ErrorKind::BrokenPipe,
                    "the phone was unplugged",
                ));
            }
        }
    }

    fn close(&self) {
        self.closed
            .store(true, std::sync::atomic::Ordering::Relaxed);
    }
}

impl Drop for AccessoryLink {
    fn drop(&mut self) {
        let st = self.read.get_mut().unwrap_or_else(|e| e.into_inner());
        for n in 0..READ_URBS {
            if st.submitted[n] {
                let u: *mut Urb = &mut st.urbs[n];
                // SAFETY: discarding a URB we submitted.
                unsafe { libc::ioctl(self.fd.as_raw_fd(), USBDEVFS_DISCARDURB as _, u) };
            }
        }
        // Reap them (the kernel must be done with the buffers before we free them).
        let deadline = Instant::now() + Duration::from_secs(2);
        while st.submitted.iter().any(|s| *s) && Instant::now() < deadline {
            let mut p: *mut Urb = std::ptr::null_mut();
            // SAFETY: as in reap().
            let r =
                unsafe { libc::ioctl(self.fd.as_raw_fd(), USBDEVFS_REAPURBNDELAY as _, &mut p) };
            if r < 0 {
                if io::Error::last_os_error().raw_os_error() == Some(libc::EAGAIN) {
                    std::thread::sleep(Duration::from_millis(10));
                    continue;
                }
                break; // device gone: the kernel already gave the URBs back
            }
            // SAFETY: p is one of ours.
            let n = unsafe { (*p).usercontext as usize };
            st.submitted[n] = false;
        }
        if st.submitted.iter().any(|s| *s) {
            // Never free buffers the kernel may still write into.
            std::mem::forget(std::mem::take(&mut st.bufs));
        }
        let mut i = self.iface;
        // SAFETY: releasing the interface we claimed.
        unsafe { libc::ioctl(self.fd.as_raw_fd(), USBDEVFS_RELEASEINTERFACE as _, &mut i) };
        let _ = (self.ep_in, st.next);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn usbfs_numbers_match_linux_headers() {
        if cfg!(target_arch = "x86_64") || cfg!(target_arch = "aarch64") {
            // <linux/usbdevice_fs.h> on 64-bit Linux.
            assert_eq!(ioctl_numbers(), [0xc018_5500, 0x8038_550a, 0x4008_550d]);
        }
    }

    fn write(p: &Path, s: &str) {
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        fs::write(p, format!("{s}\n")).unwrap();
    }

    fn iface(root: &Path, name: &str, c: &str, s: &str, p: &str, n: &str, eps: &[&str]) {
        for (k, v) in [
            ("bInterfaceClass", c),
            ("bInterfaceSubClass", s),
            ("bInterfaceProtocol", p),
            ("bInterfaceNumber", n),
        ] {
            write(&root.join(name).join(k), v);
        }
        for ep in eps {
            let d = root.join(name).join(format!("ep_{ep}"));
            write(&d.join("type"), "Bulk");
            write(&d.join("bEndpointAddress"), ep);
            write(&d.join("wMaxPacketSize"), "0200");
        }
    }

    fn device(root: &Path, port: &str, vid: &str, pid: &str, class: &str, dev: &str) {
        let d = root.join(port);
        write(&d.join("idVendor"), vid);
        write(&d.join("idProduct"), pid);
        write(&d.join("bDeviceClass"), class);
        write(&d.join("busnum"), "1");
        write(&d.join("devnum"), dev);
        write(&d.join("manufacturer"), "SAMSUNG");
        write(&d.join("product"), "SAMSUNG_Android");
        write(&d.join("serial"), "RFGL63GW4PM");
    }

    /// The owner's Galaxy on the Slate (USB debugging off): MTP + CDC modem,
    /// plus a hub with a USB stick, then the same phone in accessory mode.
    #[test]
    fn classifies_phones_hubs_sticks_and_accessories() {
        let root = std::env::temp_dir().join(format!("etph-aoa-sysfs-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        device(&root, "1-1", "04e8", "6860", "00", "5");
        iface(&root, "1-1:1.0", "06", "01", "01", "00", &["81", "01"]);
        iface(&root, "1-1:1.1", "02", "02", "01", "01", &[]);
        iface(&root, "1-1:1.2", "0a", "00", "00", "02", &["82", "02"]);
        device(&root, "2-1", "05e3", "0618", "09", "2");
        iface(&root, "2-1:1.0", "09", "00", "00", "00", &[]);
        device(&root, "2-1.1", "05e3", "0752", "00", "3");
        iface(&root, "2-1.1:1.0", "08", "06", "50", "00", &["81", "02"]);
        fs::create_dir_all(root.join("usb1")).unwrap();
        let devs = scan(&root);
        assert_eq!(
            devs.iter().map(|d| d.port.as_str()).collect::<Vec<_>>(),
            ["1-1", "2-1", "2-1.1"]
        );
        let phone = &devs[0];
        assert!(phone.maybe_phone() && !phone.has_adb() && !phone.is_accessory());
        assert_eq!(phone.dev_node, PathBuf::from("/dev/bus/usb/001/005"));
        assert_eq!(phone.name(), "SAMSUNG Android");
        assert!(!devs[1].maybe_phone(), "hub");
        assert!(!devs[2].maybe_phone(), "USB stick");

        // START: the phone comes back on the same port as 18d1:2d01.
        let _ = fs::remove_dir_all(root.join("1-1:1.0"));
        let _ = fs::remove_dir_all(root.join("1-1:1.1"));
        let _ = fs::remove_dir_all(root.join("1-1:1.2"));
        device(&root, "1-1", "18d1", "2d01", "00", "6");
        iface(&root, "1-1:1.0", "ff", "ff", "00", "00", &["81", "01"]);
        iface(&root, "1-1:1.1", "ff", "42", "01", "01", &["82", "02"]);
        let acc = scan(&root).into_iter().find(|d| d.port == "1-1").unwrap();
        assert!(acc.is_accessory() && acc.has_adb());
        assert_eq!(acc.accessory_iface(), Some((0, 0x81, 0x01, 512)));
        let _ = fs::remove_dir_all(&root);
    }
}
