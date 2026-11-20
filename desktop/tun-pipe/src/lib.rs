//! Root helper for the phone app's root mode (Android `RootTunHelper`).
//!
//! The app cannot open `/dev/net/tun`, so it starts `tun-pipe <ifname>` under
//! `su`. The helper attaches to the already-created tun device and relays
//! packets over stdin/stdout, each framed as a 2-byte big-endian length
//! followed by the packet (the same framing as Kotlin `StreamPacketPort`).
//! A frame length of 0, or a stream that ends inside a frame, is a protocol
//! error; a clean end between frames is end of stream.

use std::io::{self, Read, Write};
use std::sync::mpsc;
use std::thread;

/// Largest packet a frame can carry (2-byte length).
pub const MAX_PACKET: usize = 65535;

/// An interface name the kernel accepts and that cannot be mistaken for an option.
pub fn valid_iface(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 15
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'.' || b == b'-')
}

/// Writes one framed packet and flushes.
pub fn write_frame<W: Write>(w: &mut W, packet: &[u8]) -> io::Result<()> {
    if packet.is_empty() || packet.len() > MAX_PACKET {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("bad packet length {}", packet.len()),
        ));
    }
    let mut frame = Vec::with_capacity(packet.len() + 2);
    frame.extend_from_slice(&(packet.len() as u16).to_be_bytes());
    frame.extend_from_slice(packet);
    w.write_all(&frame)?;
    w.flush()
}

/// Reads one framed packet into `buf`. `Ok(None)` is a clean end of stream.
pub fn read_frame<R: Read>(r: &mut R, buf: &mut Vec<u8>) -> io::Result<Option<usize>> {
    let mut hdr = [0u8; 2];
    let mut got = 0;
    while got < 2 {
        match r.read(&mut hdr[got..]) {
            Ok(0) if got == 0 => return Ok(None),
            Ok(0) => {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "stream ended inside a frame header",
                ))
            }
            Ok(n) => got += n,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
            Err(e) => return Err(e),
        }
    }
    let len = u16::from_be_bytes(hdr) as usize;
    if len == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "bad frame length 0",
        ));
    }
    buf.clear();
    buf.resize(len, 0);
    r.read_exact(buf)?;
    Ok(Some(len))
}

/// Relays between the tun device and the framed pipe until either side ends.
///
/// `tun_in` yields one packet per `read` (a tun fd opened without packet
/// info); `tun_out` takes one packet per `write`. Returns the first error, or
/// `Ok(())` when the app closed its end of the pipe (or the tun ended).
pub fn pump<TI, TO, PI, PO>(
    mut tun_in: TI,
    mut tun_out: TO,
    mut pipe_in: PI,
    mut pipe_out: PO,
) -> io::Result<()>
where
    TI: Read + Send + 'static,
    TO: Write + Send + 'static,
    PI: Read + Send + 'static,
    PO: Write + Send + 'static,
{
    let (tx, rx) = mpsc::channel::<io::Result<()>>();
    let tx_app = tx.clone();
    thread::spawn(move || {
        let mut buf = vec![0u8; MAX_PACKET];
        let r = (|| -> io::Result<()> {
            loop {
                let n = tun_in.read(&mut buf)?;
                if n == 0 {
                    return Ok(());
                }
                write_frame(&mut pipe_out, &buf[..n])?;
            }
        })();
        let _ = tx_app.send(r);
    });
    thread::spawn(move || {
        let mut buf = Vec::new();
        let r = (|| -> io::Result<()> {
            while let Some(n) = read_frame(&mut pipe_in, &mut buf)? {
                tun_out.write_all(&buf[..n])?;
            }
            Ok(())
        })();
        let _ = tx.send(r);
    });
    // Whichever direction ends first decides: the app closing the pipe is the
    // normal stop; a tun or pipe error is reported. The other thread may be
    // blocked in a read and dies with the process.
    rx.recv()
        .map_err(|_| io::Error::other("pump threads vanished"))?
}

#[cfg(unix)]
pub mod tun {
    use std::fs::{File, OpenOptions};
    use std::io;
    use std::os::fd::AsRawFd;

    const IFNAMSIZ: usize = 16;

    #[repr(C)]
    #[allow(dead_code)] // filled in and handed to the kernel, never read back
    struct IfReq {
        name: [u8; IFNAMSIZ],
        flags: libc::c_short,
        pad: [u8; 22],
    }

    /// Attaches to the existing tun device `name` (IFF_TUN, no packet info).
    pub fn open(name: &str) -> io::Result<File> {
        if !super::valid_iface(name) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "not a plain interface name",
            ));
        }
        let f = ["/dev/net/tun", "/dev/tun"]
            .iter()
            .find_map(|p| OpenOptions::new().read(true).write(true).open(p).ok())
            .ok_or_else(|| {
                io::Error::new(io::ErrorKind::NotFound, "no /dev/net/tun or /dev/tun")
            })?;
        let mut req = IfReq {
            name: [0; IFNAMSIZ],
            flags: (libc::IFF_TUN | libc::IFF_NO_PI) as libc::c_short,
            pad: [0; 22],
        };
        req.name[..name.len()].copy_from_slice(name.as_bytes());
        // SAFETY: `req` is a valid, correctly sized ifreq and the fd is open.
        let rc = unsafe { libc::ioctl(f.as_raw_fd(), 0x4004_54ca as _, &mut req as *mut IfReq) };
        if rc < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(f)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    use std::sync::{Arc, Mutex};

    #[test]
    fn interface_names_are_plain() {
        assert!(valid_iface("etph0"));
        assert!(valid_iface("tun-1.a_b"));
        assert!(valid_iface("123456789012345"));
        assert!(!valid_iface(""));
        assert!(!valid_iface("1234567890123456"));
        assert!(!valid_iface("-x; id"));
        assert!(!valid_iface("a b"));
        assert!(!valid_iface("a/b"));
    }

    #[test]
    fn frames_round_trip_and_keep_boundaries() {
        let mut wire = Vec::new();
        write_frame(&mut wire, &[1, 2, 3]).unwrap();
        write_frame(&mut wire, &[9]).unwrap();
        assert_eq!(wire, vec![0, 3, 1, 2, 3, 0, 1, 9]);
        let mut r = Cursor::new(wire);
        let mut buf = Vec::new();
        assert_eq!(read_frame(&mut r, &mut buf).unwrap(), Some(3));
        assert_eq!(buf, [1, 2, 3]);
        assert_eq!(read_frame(&mut r, &mut buf).unwrap(), Some(1));
        assert_eq!(buf, [9]);
        assert_eq!(read_frame(&mut r, &mut buf).unwrap(), None);
    }

    #[test]
    fn largest_frame_is_accepted_and_empty_or_oversize_refused() {
        let big = vec![7u8; MAX_PACKET];
        let mut wire = Vec::new();
        write_frame(&mut wire, &big).unwrap();
        assert_eq!(&wire[..2], &[0xff, 0xff]);
        let mut buf = Vec::new();
        assert_eq!(
            read_frame(&mut Cursor::new(wire), &mut buf).unwrap(),
            Some(MAX_PACKET)
        );
        assert_eq!(
            write_frame(&mut Vec::new(), &[]).unwrap_err().kind(),
            io::ErrorKind::InvalidInput
        );
        assert_eq!(
            write_frame(&mut Vec::new(), &vec![0u8; MAX_PACKET + 1])
                .unwrap_err()
                .kind(),
            io::ErrorKind::InvalidInput
        );
    }

    #[test]
    fn zero_length_and_truncated_frames_are_errors() {
        let mut buf = Vec::new();
        let e = read_frame(&mut Cursor::new(vec![0, 0]), &mut buf).unwrap_err();
        assert_eq!(e.kind(), io::ErrorKind::InvalidData);
        let e = read_frame(&mut Cursor::new(vec![0]), &mut buf).unwrap_err();
        assert_eq!(e.kind(), io::ErrorKind::UnexpectedEof);
        let e = read_frame(&mut Cursor::new(vec![0, 5, 1, 2]), &mut buf).unwrap_err();
        assert_eq!(e.kind(), io::ErrorKind::UnexpectedEof);
    }

    #[derive(Clone, Default)]
    struct Sink(Arc<Mutex<Vec<Vec<u8>>>>);
    impl Write for Sink {
        fn write(&mut self, b: &[u8]) -> io::Result<usize> {
            self.0.lock().unwrap().push(b.to_vec());
            Ok(b.len())
        }
        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    /// Yields each queued packet as one read, then blocks like an idle tun.
    struct Packets(Vec<Vec<u8>>);
    impl Read for Packets {
        fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
            if self.0.is_empty() {
                loop {
                    thread::park();
                }
            }
            let p = self.0.remove(0);
            buf[..p.len()].copy_from_slice(&p);
            Ok(p.len())
        }
    }

    #[test]
    fn pump_moves_packets_both_ways_one_frame_per_packet() {
        let tun_written = Sink::default();
        let pipe_written = Sink::default();
        let mut inbound = Vec::new();
        write_frame(&mut inbound, &[0x45, 1, 2]).unwrap();
        write_frame(&mut inbound, &[0x60, 9]).unwrap();
        pump(
            Packets(vec![vec![0x45, 7, 7, 7], vec![0x60]]),
            tun_written.clone(),
            Cursor::new(inbound),
            pipe_written.clone(),
        )
        .unwrap();
        // tun -> app: each tun packet became exactly one frame (wait for the other thread)
        let mut want = Vec::new();
        write_frame(&mut want, &[0x45, 7, 7, 7]).unwrap();
        write_frame(&mut want, &[0x60]).unwrap();
        for _ in 0..200 {
            let got: Vec<u8> = pipe_written.0.lock().unwrap().concat();
            if got == want {
                break;
            }
            thread::sleep(std::time::Duration::from_millis(10));
        }
        assert_eq!(pipe_written.0.lock().unwrap().concat(), want);
        // app -> tun: each frame became exactly one tun write
        assert_eq!(
            *tun_written.0.lock().unwrap(),
            vec![vec![0x45, 1, 2], vec![0x60, 9]]
        );
    }

    #[test]
    fn pump_reports_a_protocol_error_from_the_app() {
        let e = pump(
            Packets(vec![]),
            Sink::default(),
            Cursor::new(vec![0u8, 0]),
            Sink::default(),
        )
        .unwrap_err();
        assert_eq!(e.kind(), io::ErrorKind::InvalidData);
    }
}
