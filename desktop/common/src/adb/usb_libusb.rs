//! ADB over USB through libusb (macOS: IOKit, Windows: WinUSB, FreeBSD:
//! the base system's libusb), for every platform without Linux usbfs. Same
//! job as [`super::usb`] on Linux: find the phone's adb interface (class
//! 0xff, subclass 0x42, protocol 0x01), claim it, and move ADB messages
//! over its bulk endpoints -- no adb tool, no Android SDK.
//!
//! Windows binds WinUSB to the adb interface by itself on phones whose adbd
//! announces the Microsoft OS descriptor (`WINUSB` compatible ID; adbd has
//! done so since Android 10, and Google's and most OEM phones expose it).
//! Phones that don't need the vendor's (or Google's) USB driver once.

use super::proto::Header;
use super::Link;
use rusb::{Direction, TransferType, UsbContext};
use std::io;
use std::sync::Mutex;
use std::time::Duration;

/// A phone with USB debugging on, as libusb sees it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UsbAdbDevice {
    pub bus: u8,
    pub address: u8,
    pub interface: u8,
    pub ep_in: u8,
    pub ep_out: u8,
    pub max_packet: usize,
    pub serial: String,
    pub product: String,
    /// Where it is plugged in ("bus-port.port"), shown by `usb-list`.
    pub sysfs: String,
}

fn port_path(dev: &rusb::Device<rusb::Context>) -> String {
    let ports = dev.port_numbers().unwrap_or_default();
    let p: Vec<String> = ports.iter().map(|p| p.to_string()).collect();
    format!("{}-{}", dev.bus_number(), p.join("."))
}

/// Every adb interface on the USB buses.
pub fn find_devices() -> Vec<UsbAdbDevice> {
    let mut out = Vec::new();
    let Ok(ctx) = rusb::Context::new() else {
        return out;
    };
    let Ok(list) = ctx.devices() else {
        return out;
    };
    for dev in list.iter() {
        let Ok(desc) = dev.device_descriptor() else {
            continue;
        };
        let Ok(cfg) = dev
            .active_config_descriptor()
            .or_else(|_| dev.config_descriptor(0))
        else {
            continue;
        };
        for iface in cfg.interfaces() {
            for alt in iface.descriptors() {
                if (alt.class_code(), alt.sub_class_code(), alt.protocol_code())
                    != (0xff, 0x42, 0x01)
                {
                    continue;
                }
                let (mut ep_in, mut ep_out, mut max_packet) = (None, None, 512usize);
                for ep in alt.endpoint_descriptors() {
                    if ep.transfer_type() != TransferType::Bulk {
                        continue;
                    }
                    if ep.max_packet_size() > 0 {
                        max_packet = ep.max_packet_size() as usize;
                    }
                    match ep.direction() {
                        Direction::In => ep_in = Some(ep.address()),
                        Direction::Out => ep_out = Some(ep.address()),
                    }
                }
                let (Some(ep_in), Some(ep_out)) = (ep_in, ep_out) else {
                    continue;
                };
                // Strings need an open handle; a phone we may not open yet
                // (Windows without WinUSB bound) is still listed.
                let (serial, product) = match dev.open() {
                    Ok(h) => (
                        h.read_serial_number_string_ascii(&desc).unwrap_or_default(),
                        h.read_product_string_ascii(&desc).unwrap_or_default(),
                    ),
                    Err(_) => (String::new(), String::new()),
                };
                out.push(UsbAdbDevice {
                    bus: dev.bus_number(),
                    address: dev.address(),
                    interface: alt.interface_number(),
                    ep_in,
                    ep_out,
                    max_packet,
                    serial,
                    product: if product.is_empty() {
                        format!("{:04x}:{:04x}", desc.vendor_id(), desc.product_id())
                    } else {
                        product
                    },
                    sysfs: port_path(&dev),
                });
            }
        }
    }
    out.sort_by(|a, b| a.sysfs.cmp(&b.sysfs));
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

fn to_io(e: rusb::Error) -> io::Error {
    let kind = match e {
        rusb::Error::Timeout => io::ErrorKind::TimedOut,
        rusb::Error::Access => io::ErrorKind::PermissionDenied,
        rusb::Error::NoDevice | rusb::Error::NotFound => io::ErrorKind::NotFound,
        rusb::Error::Busy => io::ErrorKind::ResourceBusy,
        _ => io::ErrorKind::Other,
    };
    io::Error::new(kind, format!("USB: {e}"))
}

pub struct UsbLink {
    handle: rusb::DeviceHandle<rusb::Context>,
    dev: UsbAdbDevice,
    out_lock: Mutex<()>,
    /// (bytes received but not consumed yet, transfer buffer)
    in_lock: Mutex<(Vec<u8>, Vec<u8>)>,
}

const POLL: Duration = Duration::from_millis(500);

impl UsbLink {
    pub fn open(dev: &UsbAdbDevice) -> io::Result<Self> {
        let ctx = rusb::Context::new().map_err(to_io)?;
        let found = ctx
            .devices()
            .map_err(to_io)?
            .iter()
            .find(|d| d.bus_number() == dev.bus && d.address() == dev.address)
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "the phone left the USB bus"))?;
        let handle = found.open().map_err(|e| {
            let mut err = to_io(e);
            if cfg!(windows) && matches!(e, rusb::Error::NotSupported | rusb::Error::Access | rusb::Error::NotFound) {
                err = io::Error::new(
                    err.kind(),
                    format!(
                        "{err}. Windows has no WinUSB driver on this phone's adb interface: install \
                         the phone maker's USB driver (or Google's USB driver) once, then plug it in again"
                    ),
                );
            }
            err
        })?;
        let _ = handle.set_auto_detach_kernel_driver(true);
        handle.claim_interface(dev.interface).map_err(to_io)?;
        let _ = handle.clear_halt(dev.ep_in);
        let _ = handle.clear_halt(dev.ep_out);
        Ok(Self {
            handle,
            dev: dev.clone(),
            out_lock: Mutex::new(()),
            in_lock: Mutex::new((Vec::new(), vec![0u8; super::proto::MAX_PAYLOAD + 24])),
        })
    }

    fn write_all(&self, mut data: &[u8]) -> io::Result<()> {
        while !data.is_empty() {
            let n = data.len().min(16384);
            let w = self
                .handle
                .write_bulk(self.dev.ep_out, &data[..n], Duration::from_secs(5))
                .map_err(to_io)?;
            data = &data[w..];
        }
        Ok(())
    }

    fn read(&self, buf: &mut [u8]) -> io::Result<usize> {
        self.handle
            .read_bulk(self.dev.ep_in, buf, POLL)
            .map_err(to_io)
    }
}

fn is_timeout(e: &io::Error) -> bool {
    e.kind() == io::ErrorKind::TimedOut
}

impl Link for UsbLink {
    fn send(&self, hdr: &Header, data: &[u8]) -> io::Result<()> {
        let _g = self.out_lock.lock().unwrap();
        self.write_all(&hdr.encode())?;
        if !data.is_empty() {
            self.write_all(data)?;
            if data.len().is_multiple_of(self.dev.max_packet) {
                // End the transfer with a zero-length packet.
                self.handle
                    .write_bulk(self.dev.ep_out, &[], Duration::from_secs(1))
                    .map_err(to_io)?;
            }
        }
        Ok(())
    }

    fn recv(&self) -> io::Result<Option<(Header, Vec<u8>)>> {
        let mut guard = self.in_lock.lock().unwrap();
        let (pending, buf) = &mut *guard;
        while pending.len() < 24 {
            match self.read(buf) {
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
            match self.read(buf) {
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
        let _ = self.handle.release_interface(self.dev.interface);
    }
}
