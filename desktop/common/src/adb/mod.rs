//! A minimal ADB host, built into the client, so a router can reach a phone
//! plugged into its USB port without the adb package or an adb server:
//! find the phone's adb interface in sysfs, talk to it through usbfs bulk
//! transfers, authenticate with the router's RSA key (the phone's "Allow USB
//! debugging?" prompt), and open a stream to the app's loopback port, i.e.
//! what `adb forward tcp:8888 tcp:8888` + connecting to it does, in-process.
//!
//! The protocol layer runs over any [`Link`]: USB (Linux usbfs) or TCP (an
//! adbd in `adb tcpip` mode, which CI uses against an emulator and the unit
//! tests use against a fake adbd).

pub mod key;
pub mod proto;
#[cfg(target_os = "linux")]
pub mod usb;
/// macOS, Windows, the BSDs: the same ADB-over-USB host through libusb.
#[cfg(all(
    not(target_os = "linux"),
    not(target_os = "android"),
    feature = "usb-libusb"
))]
#[path = "usb_libusb.rs"]
pub mod usb;

/// Stand-in for the device list on platforms without a USB backend.
#[cfg(not(any(target_os = "linux", feature = "usb-libusb")))]
pub mod usb_info {
    #[derive(Debug, Clone)]
    pub struct UsbAdbDevice {
        pub serial: String,
        pub product: String,
        pub sysfs: String,
    }
}

use anyhow::{bail, Context, Result};
use log::{debug, info};
use proto::{Header, A_AUTH, A_CLSE, A_CNXN, A_OKAY, A_OPEN, A_WRTE};
use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// One ADB connection's packet transport.
pub trait Link: Send + Sync {
    /// Send one message (header + payload) atomically w.r.t. other senders.
    fn send(&self, hdr: &Header, data: &[u8]) -> io::Result<()>;
    /// Receive the next message. `Ok(None)` = nothing within the poll
    /// interval (USB timeout), try again.
    fn recv(&self) -> io::Result<Option<(Header, Vec<u8>)>>;
    /// Unblock and stop (best effort).
    fn close(&self);
}

/// ADB over TCP (adbd in tcpip mode, or a test double).
pub struct TcpLink {
    write: Mutex<TcpStream>,
    read: Mutex<TcpStream>,
}

impl TcpLink {
    pub fn connect(addr: &str, timeout: Duration) -> Result<Self> {
        let sa = std::net::ToSocketAddrs::to_socket_addrs(addr)?
            .next()
            .context("no address")?;
        let s = TcpStream::connect_timeout(&sa, timeout)
            .with_context(|| format!("cannot reach adbd at {addr}"))?;
        s.set_nodelay(true)?;
        s.set_read_timeout(Some(Duration::from_millis(500)))?;
        Ok(Self {
            write: Mutex::new(s.try_clone()?),
            read: Mutex::new(s),
        })
    }
}

impl Link for TcpLink {
    fn send(&self, hdr: &Header, data: &[u8]) -> io::Result<()> {
        let mut w = self.write.lock().unwrap();
        let mut buf = hdr.encode().to_vec();
        buf.extend_from_slice(data);
        w.write_all(&buf)
    }

    fn recv(&self) -> io::Result<Option<(Header, Vec<u8>)>> {
        let mut r = self.read.lock().unwrap();
        let mut h = [0u8; 24];
        // Only the first byte may time out; after that the message is due.
        match r.read(&mut h[..1]) {
            Ok(0) => return Err(io::ErrorKind::UnexpectedEof.into()),
            Ok(_) => {}
            Err(e)
                if matches!(
                    e.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                ) =>
            {
                return Ok(None)
            }
            Err(e) => return Err(e),
        }
        read_full(&mut r, &mut h[1..])?;
        let hdr = Header::decode(&h)?;
        let mut data = vec![0u8; hdr.data_length as usize];
        read_full(&mut r, &mut data)?;
        Ok(Some((hdr, data)))
    }

    fn close(&self) {
        let _ = self
            .write
            .lock()
            .unwrap()
            .shutdown(std::net::Shutdown::Both);
    }
}

fn read_full(r: &mut TcpStream, mut buf: &mut [u8]) -> io::Result<()> {
    let deadline = Instant::now() + Duration::from_secs(30);
    while !buf.is_empty() {
        match r.read(buf) {
            Ok(0) => return Err(io::ErrorKind::UnexpectedEof.into()),
            Ok(n) => buf = &mut buf[n..],
            Err(e)
                if matches!(
                    e.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                ) && Instant::now() < deadline => {}
            Err(e) => return Err(e),
        }
    }
    Ok(())
}

/// What the caller learns while connecting (for status files / UIs).
pub trait AuthObserver: Send + Sync {
    /// The phone is showing "Allow USB debugging?" for our key.
    fn waiting_for_allow(&self, fingerprint: &str) {
        let _ = fingerprint;
    }
}

pub struct NoObserver;
impl AuthObserver for NoObserver {}

/// Negotiated connection parameters.
#[derive(Debug, Clone)]
pub struct Connected {
    pub max_data: usize,
    /// The device banner, e.g. "device::ro.product.model=SM-N986U;...".
    pub banner: String,
}

impl Connected {
    /// `ro.product.model` from the banner, if present.
    pub fn model(&self) -> Option<String> {
        self.banner
            .split([';', ':'])
            .find_map(|kv| kv.strip_prefix("ro.product.model="))
            .map(|s| s.trim_end_matches('\0').to_string())
            .filter(|s| !s.is_empty())
    }
}

/// CNXN + AUTH. `allow_wait` bounds how long we wait for the user to tap
/// Allow on the phone after offering our public key.
pub fn handshake(
    link: &dyn Link,
    key: &key::AdbKey,
    observer: &dyn AuthObserver,
    allow_wait: Duration,
) -> Result<Connected> {
    link.send(
        &Header::new(
            A_CNXN,
            proto::VERSION,
            proto::MAX_PAYLOAD as u32,
            proto::HOST_BANNER,
        ),
        proto::HOST_BANNER,
    )?;
    let mut sent_signature = false;
    let mut sent_pubkey = false;
    let mut deadline = Instant::now() + Duration::from_secs(10);
    loop {
        if Instant::now() > deadline {
            if sent_pubkey {
                bail!(
                    "the phone did not allow USB debugging for this router within {}s (unlock it and \
                     tap Allow, ticking \"Always allow from this computer\")",
                    allow_wait.as_secs()
                );
            }
            bail!("no answer from adbd on the phone (is USB debugging on?)");
        }
        let Some((h, data)) = link.recv()? else {
            continue;
        };
        match h.command {
            A_CNXN => {
                let banner = String::from_utf8_lossy(&data).to_string();
                debug!("adb connected: {}", banner.trim_end_matches('\0'));
                return Ok(Connected {
                    max_data: (h.arg1 as usize).clamp(4096, proto::MAX_PAYLOAD),
                    banner,
                });
            }
            A_AUTH if h.arg0 == proto::AUTH_TOKEN => {
                if !sent_signature {
                    let sig = key.sign_token(&data)?;
                    link.send(&Header::new(A_AUTH, proto::AUTH_SIGNATURE, 0, &sig), &sig)?;
                    sent_signature = true;
                } else if !sent_pubkey {
                    // The phone doesn't know this key yet: offer it, which
                    // makes Android show "Allow USB debugging?".
                    let mut pk = key.public_key_line().into_bytes();
                    pk.push(0);
                    link.send(&Header::new(A_AUTH, proto::AUTH_RSAPUBLICKEY, 0, &pk), &pk)?;
                    sent_pubkey = true;
                    let fp = key.fingerprint();
                    info!(
                        "Unlock the phone and tap Allow on \"Allow USB debugging?\" (key fingerprint {})",
                        fp
                    );
                    observer.waiting_for_allow(&fp);
                    deadline = Instant::now() + allow_wait;
                } else {
                    bail!("the phone refused this router's adb key");
                }
            }
            other => debug!("adb: ignoring {:#x} before CNXN", other),
        }
    }
}

/// Bridges one ADB stream to an async byte stream.
pub struct AdbStream {
    pub io: tokio::io::DuplexStream,
    pub connected: Connected,
}

const LOCAL_ID: u32 = 1;

/// Connect, authenticate and open `service` (e.g. "tcp:8888") on the phone.
/// Blocking: call from `spawn_blocking`.
pub fn open_stream(
    link: Arc<dyn Link>,
    key: &key::AdbKey,
    observer: &dyn AuthObserver,
    allow_wait: Duration,
    service: &str,
) -> Result<AdbStream> {
    open_stream_with_auto_start(link, key, observer, allow_wait, service, None)
}

/// [`open_stream`]; when nothing listens on `service` and `auto_start` is
/// set, run that shell command on the phone first (ETPH Pro's auto-start:
/// `am start -n .../AutoStartActivity`), wait for the app, and try once more.
pub fn open_stream_with_auto_start(
    link: Arc<dyn Link>,
    key: &key::AdbKey,
    observer: &dyn AuthObserver,
    allow_wait: Duration,
    service: &str,
    auto_start: Option<&str>,
) -> Result<AdbStream> {
    let connected = handshake(&*link, key, observer, allow_wait)?;
    let remote = match open_service(&*link, service, LOCAL_ID) {
        Ok(r) => r,
        Err(e) => {
            let Some(cmd) = auto_start else { return Err(e) };
            info!("nothing listens on {service}: asking the phone to start ETPH ({cmd})");
            let out = run_shell(&*link, cmd)?;
            info!("phone: {}", out.trim());
            // The app starts its USB server within a second or two.
            let mut last = e;
            let mut opened = None;
            for _ in 0..8 {
                std::thread::sleep(Duration::from_millis(750));
                match open_service(&*link, service, LOCAL_ID) {
                    Ok(r) => {
                        opened = Some(r);
                        break;
                    }
                    Err(e) => last = e,
                }
            }
            match opened {
                Some(r) => r,
                None => return Err(last.context("auto-start requested; the phone did not start sharing (ETPH Pro and \"Start when the computer asks\" are needed on the phone)")),
            }
        }
    };
    info!("adb stream to {} open (USB, no adb server)", service);
    Ok(AdbStream {
        io: bridge(link, remote, connected.max_data),
        connected,
    })
}

/// OPEN `service` as `local` and wait for the phone's OKAY (its stream id).
fn open_service(link: &dyn Link, service: &str, local: u32) -> Result<u32> {
    let mut svc = service.as_bytes().to_vec();
    svc.push(0);
    link.send(&Header::new(A_OPEN, local, 0, &svc), &svc)?;
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        if Instant::now() > deadline {
            bail!("the phone did not answer opening {service}");
        }
        let Some((h, _)) = link.recv()? else { continue };
        match h.command {
            A_OKAY if h.arg1 == local => return Ok(h.arg0),
            A_CLSE if h.arg1 == local || h.arg1 == 0 => bail!(
                "the phone refused {service}: is ETPH started in USB mode? (nothing listens on that port)"
            ),
            _ => {}
        }
    }
}

/// Run `shell:<cmd>` on the phone and return its output (ADB "shell" service).
pub fn run_shell(link: &dyn Link, cmd: &str) -> Result<String> {
    const SHELL_ID: u32 = 2;
    let remote = open_service(link, &format!("shell:{cmd}"), SHELL_ID)?;
    let mut out = Vec::new();
    let deadline = Instant::now() + Duration::from_secs(10);
    while Instant::now() < deadline {
        let Some((h, data)) = link.recv()? else {
            continue;
        };
        match h.command {
            A_WRTE if h.arg1 == SHELL_ID => {
                out.extend_from_slice(&data);
                link.send(&Header::new(A_OKAY, SHELL_ID, remote, &[]), &[])?;
            }
            A_CLSE if h.arg1 == SHELL_ID => break,
            _ => {}
        }
    }
    let _ = link.send(&Header::new(A_CLSE, SHELL_ID, remote, &[]), &[]);
    Ok(String::from_utf8_lossy(&out).into_owned())
}

/// Pump bytes between an ADB stream (local id 1 <-> `remote`) and one end
/// of a tokio duplex pipe, honouring ADB's one-WRTE-in-flight flow control.
fn bridge(link: Arc<dyn Link>, remote: u32, max_data: usize) -> tokio::io::DuplexStream {
    let (ours, theirs) = tokio::io::duplex(256 * 1024);
    let (mut their_read, mut their_write) = tokio::io::split(theirs);
    let (okay_tx, okay_rx) = mpsc::channel::<()>();
    let (down_tx, mut down_rx) = tokio::sync::mpsc::unbounded_channel::<Vec<u8>>();
    let closed = Arc::new(std::sync::atomic::AtomicBool::new(false));

    // Reader thread: phone -> pipe, acks, OKAYs for our writes.
    {
        let link = link.clone();
        let closed = closed.clone();
        std::thread::spawn(move || {
            loop {
                if closed.load(std::sync::atomic::Ordering::Relaxed) {
                    break;
                }
                match link.recv() {
                    Ok(None) => continue,
                    Ok(Some((h, data))) => match h.command {
                        A_WRTE if h.arg1 == LOCAL_ID => {
                            if down_tx.send(data).is_err() {
                                break;
                            }
                            if link
                                .send(&Header::new(A_OKAY, LOCAL_ID, remote, &[]), &[])
                                .is_err()
                            {
                                break;
                            }
                        }
                        A_OKAY if h.arg1 == LOCAL_ID => {
                            let _ = okay_tx.send(());
                        }
                        A_CLSE if h.arg1 == LOCAL_ID || h.arg1 == 0 => break,
                        _ => {}
                    },
                    Err(e) => {
                        debug!("adb link read ended: {}", e);
                        break;
                    }
                }
            }
            closed.store(true, std::sync::atomic::Ordering::Relaxed);
            drop(down_tx);
            drop(okay_tx);
        });
    }

    // Pipe -> phone: chunks of max_data, one WRTE in flight.
    let (up_tx, up_rx) = mpsc::sync_channel::<Option<Vec<u8>>>(4);
    {
        let link = link.clone();
        let closed = closed.clone();
        std::thread::spawn(move || {
            while let Ok(Some(chunk)) = up_rx.recv() {
                if link
                    .send(&Header::new(A_WRTE, LOCAL_ID, remote, &chunk), &chunk)
                    .is_err()
                {
                    break;
                }
                // Wait for the phone's OKAY before the next WRTE.
                if okay_rx.recv_timeout(Duration::from_secs(30)).is_err() {
                    break;
                }
            }
            let _ = link.send(&Header::new(A_CLSE, LOCAL_ID, remote, &[]), &[]);
            closed.store(true, std::sync::atomic::Ordering::Relaxed);
            link.close();
        });
    }
    tokio::spawn(async move {
        use tokio::io::AsyncReadExt;
        let mut buf = vec![0u8; max_data];
        loop {
            match their_read.read(&mut buf).await {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    let tx = up_tx.clone();
                    let chunk = buf[..n].to_vec();
                    // sync_channel send may block: keep it off the runtime.
                    if tokio::task::spawn_blocking(move || tx.send(Some(chunk)))
                        .await
                        .map(|r| r.is_err())
                        .unwrap_or(true)
                    {
                        break;
                    }
                }
            }
        }
        let _ = tokio::task::spawn_blocking(move || up_tx.send(None)).await;
    });
    tokio::spawn(async move {
        use tokio::io::AsyncWriteExt;
        while let Some(d) = down_rx.recv().await {
            if their_write.write_all(&d).await.is_err() {
                break;
            }
        }
        let _ = their_write.shutdown().await;
    });
    ours
}

#[cfg(test)]
mod tests;
