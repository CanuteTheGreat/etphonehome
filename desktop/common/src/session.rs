//! Transport-agnostic tethering session: the application handshake and the
//! bidirectional packet-forwarding loop shared by the Linux CLI, the Windows
//! CLI and the GUI.
//!
//! Before this module existed each binary carried its own copy of the
//! forwarding loop (two ~300-line near-identical copies in
//! `linux/src/main.rs` and `windows/src/main.rs`, none in the GUI, whose
//! Connect button only flipped a status label), and that loop had a real
//! stall: the TUN device sat behind one `Mutex` shared by the reader and the
//! writer, and the reader held the lock for the whole duration of a blocking
//! `read(2)`. Packets arriving from Android could therefore only be written
//! to the TUN device in the gap between two outbound packets -- an idle
//! desktop never received anything. The loop here uses the async TUN device
//! split into independent read/write halves, so neither direction can block
//! the other.
//!
//! The loop is generic over any packet device (`AsyncRead + AsyncWrite`
//! where one `read` returns exactly one IP packet, which is what a TUN
//! device does) and any byte transport, which is what makes it testable
//! without root: the tests below drive it with an in-memory packet device
//! over a real TCP socket.

use crate::client::connect_tls_async;
use crate::connection_service::{ConnectionService, ConnectionStats};
use crate::filter::{FilterConfig, FilterEngine};
use crate::protocol::{
    is_version_compatible, Disconnect, HandshakeRequest, HandshakeResponse, IpPacket, Message,
    ProtocolError, MAGIC, PROTOCOL_VERSION,
};
use anyhow::{Context, Result};
use log::{debug, error, info, warn};
use std::future::Future;
use std::net::SocketAddr;
// portable-atomic: 32-bit MIPS routers have no native 64-bit atomics.
use portable_atomic::AtomicU64;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::mpsc;

pub type BoxedRead = Box<dyn AsyncRead + Unpin + Send>;
pub type BoxedWrite = Box<dyn AsyncWrite + Unpin + Send>;

/// Largest packet a single protocol frame can carry.
pub const MAX_PACKET: usize = u16::MAX as usize;

/// Read one framed protocol message from an async byte stream.
pub async fn read_message<R: AsyncRead + Unpin + ?Sized>(
    reader: &mut R,
) -> Result<Message, ProtocolError> {
    let mut header = [0u8; 5];
    reader.read_exact(&mut header).await?;
    if u16::from_be_bytes([header[0], header[1]]) != MAGIC {
        // Fail before reading a payload whose length field is garbage.
        return Err(ProtocolError::InvalidMagic);
    }
    let length = u16::from_be_bytes([header[3], header[4]]) as usize;
    let mut frame = vec![0u8; 5 + length];
    frame[..5].copy_from_slice(&header);
    reader.read_exact(&mut frame[5..]).await?;
    Message::read_from(&mut frame.as_slice())
}

/// Write one framed protocol message to an async byte stream and flush it.
pub async fn write_message<W: AsyncWrite + Unpin + ?Sized>(
    writer: &mut W,
    msg: &Message,
) -> Result<(), ProtocolError> {
    let mut buf = Vec::new();
    msg.write_to(&mut buf)?;
    writer.write_all(&buf).await?;
    writer.flush().await?;
    Ok(())
}

/// Client side of the application handshake over any async stream (plain
/// TCP, cert-pinned TLS, Bluetooth RFCOMM).
///
/// A peer `ErrorMsg` (e.g. Android rejecting a wrong token) is surfaced as
/// [`ProtocolError::PeerError`] carrying the peer's code and text, rather
/// than the generic "unexpected message" the old per-binary copies reported.
pub async fn client_handshake<S: AsyncRead + AsyncWrite + Unpin + ?Sized>(
    stream: &mut S,
    capabilities: u32,
    token: Option<String>,
) -> Result<HandshakeResponse, ProtocolError> {
    let request = match token {
        Some(t) => HandshakeRequest::with_token(capabilities, t),
        None => HandshakeRequest::new(capabilities),
    };
    write_message(stream, &Message::HandshakeReq(request)).await?;
    match read_message(stream).await? {
        Message::HandshakeResp(resp) => {
            if !is_version_compatible(resp.version) {
                return Err(ProtocolError::VersionMismatch {
                    local_major: (PROTOCOL_VERSION >> 8) as u8,
                    peer_major: (resp.version >> 8) as u8,
                    peer_version: resp.version,
                });
            }
            Ok(resp)
        }
        Message::Error(err) => Err(ProtocolError::PeerError {
            code: err.error_code,
            message: err.message,
        }),
        _ => Err(ProtocolError::UnexpectedMessage),
    }
}

/// An established, handshaked connection to the Android device.
pub struct Transport {
    pub read: BoxedRead,
    pub write: BoxedWrite,
    pub handshake: HandshakeResponse,
}

/// Run the client handshake over an already-connected stream (used directly
/// for Bluetooth RFCOMM) and split it into a [`Transport`].
pub async fn handshake_stream<S>(
    mut stream: S,
    capabilities: u32,
    token: Option<String>,
    timeout: Duration,
) -> Result<Transport>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let handshake =
        tokio::time::timeout(timeout, client_handshake(&mut stream, capabilities, token))
            .await
            .context("timed out waiting for the handshake response")?
            .context("handshake with Android device failed")?;
    log_handshake(&handshake);
    let (read, write) = tokio::io::split(stream);
    Ok(Transport {
        read: Box::new(read),
        write: Box::new(write),
        handshake,
    })
}

/// How to reach the Android device over TCP.
#[derive(Debug, Clone)]
pub struct ConnectOptions {
    pub addr: SocketAddr,
    pub capabilities: u32,
    pub token: Option<String>,
    pub tls: bool,
    pub timeout: Duration,
}

/// Connect over TCP (plaintext or cert-pinned TLS) and complete the
/// handshake. TLS without a token is refused: the token is the pin.
pub async fn connect(opts: &ConnectOptions) -> Result<Transport> {
    if opts.tls {
        let token = opts.token.clone().context(
            "TLS was requested but no pairing token was provided -- cert pinning has no trust \
             anchor without one. Set a token (--token / config.auth.wifi_token) or disable TLS.",
        )?;
        let outcome = tokio::time::timeout(
            opts.timeout,
            connect_tls_async(opts.addr, opts.capabilities, token),
        )
        .await
        .context(format!("timed out connecting to {}", opts.addr))?
        .context(format!(
            "TLS connection to Android device at {} failed (wrong pairing token, peer without \
             TLS support, or protocol version mismatch)",
            opts.addr
        ))?;
        let (read, write) = tokio::io::split(outcome.stream);
        return Ok(Transport {
            read: Box::new(read),
            write: Box::new(write),
            handshake: outcome.handshake,
        });
    }

    info!("Connecting to Android device at {}...", opts.addr);
    let tcp = tokio::time::timeout(opts.timeout, tokio::net::TcpStream::connect(opts.addr))
        .await
        .context(format!("timed out connecting to {}", opts.addr))?
        .context(format!(
            "Failed to connect to Android device at {} (is tethering started in the app? \
             for USB check `adb forward --list`, for WiFi check the IP and firewall)",
            opts.addr
        ))?;
    tcp.set_nodelay(true)?;
    handshake_stream(tcp, opts.capabilities, opts.token.clone(), opts.timeout).await
}

fn log_handshake(resp: &HandshakeResponse) {
    info!("Handshake successful");
    info!("  Protocol version: {:#06x}", resp.version);
    info!("  Android capabilities: {:#010x}", resp.capabilities);
    info!(
        "  Assigned IP: {}",
        crate::client::int_to_ip(resp.ip_address)
    );
    info!("  DNS: {}", crate::client::int_to_ip(resp.dns));
}

/// Why a forwarding session ended.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionEnd {
    /// Android sent a Disconnect message.
    PeerDisconnected(String),
    /// The transport failed, closed, or went silent past the idle timeout.
    TransportClosed(String),
    /// The local packet device failed or closed.
    DeviceClosed(String),
    /// The caller's shutdown future fired (Ctrl-C, GUI Disconnect).
    Cancelled,
}

impl SessionEnd {
    /// Whether auto-reconnect should try again after this outcome.
    pub fn should_reconnect(&self) -> bool {
        !matches!(self, SessionEnd::Cancelled)
    }
}

/// Forwarding knobs.
#[derive(Clone)]
pub struct ForwardOptions {
    pub filter: FilterConfig,
    /// Optional GUI/status sink for live statistics.
    pub service: Option<ConnectionService>,
    /// Tear the session down if nothing at all arrives from Android for
    /// this long. The app sends a StatsUpdate every 5 s and a Keepalive every
    /// 30 s, so 30 s of silence means the link is dead (phone off WiFi, out
    /// of range). Without it the read blocks forever: the TUN device and the
    /// default route through it stay up with no internet behind them, and
    /// auto-reconnect never triggers. (It was 90 s; the nightly WiFi-drop
    /// test asks for a dead tunnel to be gone within 45 s.)
    pub idle_timeout: Option<Duration>,
    /// Send Android a Keepalive this often, so the phone can tell a silent
    /// desktop (laptop off WiFi, lid closed) from an idle one and free the
    /// session for the next connection. `None` disables it (tests).
    pub keepalive_interval: Option<Duration>,
}

/// How often the desktop tells the phone it is still there.
pub const DESKTOP_KEEPALIVE: Duration = Duration::from_secs(10);

impl Default for ForwardOptions {
    fn default() -> Self {
        Self {
            filter: FilterConfig::default(),
            service: None,
            idle_timeout: Some(Duration::from_secs(30)),
            keepalive_interval: Some(DESKTOP_KEEPALIVE),
        }
    }
}

#[derive(Default)]
struct Counters {
    bytes_sent: AtomicU64,
    bytes_received: AtomicU64,
    packets_sent: AtomicU64,
    packets_received: AtomicU64,
    peer_uptime: AtomicU64,
}

impl Counters {
    fn snapshot(&self) -> ConnectionStats {
        ConnectionStats {
            bytes_sent: self.bytes_sent.load(Ordering::Relaxed),
            bytes_received: self.bytes_received.load(Ordering::Relaxed),
            uptime_seconds: self.peer_uptime.load(Ordering::Relaxed) as u32,
            packets_sent: self.packets_sent.load(Ordering::Relaxed),
            packets_received: self.packets_received.load(Ordering::Relaxed),
        }
    }
}

/// Forward packets between `device` and the Android transport until either
/// side ends or `shutdown` resolves. On shutdown a Disconnect message is
/// sent to Android (best effort) so it can tear its side down cleanly.
pub async fn forward<D, F>(
    transport: Transport,
    device: D,
    opts: ForwardOptions,
    shutdown: F,
) -> Result<(SessionEnd, ConnectionStats)>
where
    D: AsyncRead + AsyncWrite + Send + 'static,
    F: Future<Output = ()> + Send,
{
    let Transport {
        read: mut net_read,
        write: mut net_write,
        ..
    } = transport;
    let (mut dev_read, mut dev_write) = tokio::io::split(device);
    let filter = Arc::new(FilterEngine::new(opts.filter.clone()));
    let counters = Arc::new(Counters::default());
    let started = Instant::now();

    // Single writer task owns the network write half; everything that wants
    // to send (uplink packets, the final Disconnect) goes through it, so
    // frames can never interleave.
    let (out_tx, mut out_rx) = mpsc::channel::<Message>(256);
    let mut writer = tokio::spawn(async move {
        while let Some(msg) = out_rx.recv().await {
            let last = matches!(msg, Message::Disconnect(_));
            if let Err(e) = write_message(&mut net_write, &msg).await {
                return SessionEnd::TransportClosed(format!("write to Android failed: {}", e));
            }
            if last {
                let _ = net_write.shutdown().await;
                break;
            }
        }
        SessionEnd::TransportClosed("writer stopped".into())
    });

    // Hand the profile's traffic filter to the phone first, so the phone
    // enforces the same rules on the flows it opens for us (an empty,
    // disabled config is sent too: the phone's state is always explicit).
    let _ = out_tx
        .send(Message::ConfigUpdate(opts.filter.to_config_update()))
        .await;
    // One keepalive straight away: it arms the phone's idle timeout, so a
    // link that dies in the first seconds of a session is noticed too (the
    // nightly WiFi-drop test cut the link 2 s in, before the first periodic
    // keepalive, and the phone held the dead session for minutes).
    if opts.keepalive_interval.is_some() {
        let _ = out_tx.send(Message::Keepalive).await;
    }

    // Uplink: device -> filter -> Android.
    let mut uplink = {
        let out_tx = out_tx.clone();
        let filter = Arc::clone(&filter);
        let counters = Arc::clone(&counters);
        tokio::spawn(async move {
            let mut buf = vec![0u8; MAX_PACKET];
            loop {
                let n = match dev_read.read(&mut buf).await {
                    Ok(0) => return SessionEnd::DeviceClosed("device closed".into()),
                    Ok(n) => n,
                    Err(e) => {
                        return SessionEnd::DeviceClosed(format!("device read failed: {}", e))
                    }
                };
                let pkt = &buf[..n];
                if !filter.should_allow(pkt) {
                    continue;
                }
                counters.bytes_sent.fetch_add(n as u64, Ordering::Relaxed);
                counters.packets_sent.fetch_add(1, Ordering::Relaxed);
                if out_tx
                    .send(Message::IpPacket(IpPacket::new(pkt.to_vec())))
                    .await
                    .is_err()
                {
                    return SessionEnd::TransportClosed("writer stopped".into());
                }
            }
        })
    };

    // Downlink: Android -> device.
    let mut downlink = {
        let counters = Arc::clone(&counters);
        let idle = opts.idle_timeout;
        tokio::spawn(async move {
            loop {
                let next = match idle {
                    Some(t) => match tokio::time::timeout(t, read_message(&mut net_read)).await {
                        Ok(r) => r,
                        Err(_) => {
                            return SessionEnd::TransportClosed(format!(
                                "no traffic or keepalive from Android for {}s",
                                t.as_secs()
                            ))
                        }
                    },
                    None => read_message(&mut net_read).await,
                };
                match next {
                    Ok(Message::IpPacket(pkt)) => {
                        counters
                            .bytes_received
                            .fetch_add(pkt.data.len() as u64, Ordering::Relaxed);
                        counters.packets_received.fetch_add(1, Ordering::Relaxed);
                        if let Err(e) = dev_write.write_all(&pkt.data).await {
                            return SessionEnd::DeviceClosed(format!("device write failed: {}", e));
                        }
                    }
                    Ok(Message::StatsUpdate(s)) => {
                        debug!(
                            "Android stats: sent={} recv={} uptime={}s",
                            s.bytes_sent, s.bytes_received, s.uptime_seconds
                        );
                        counters
                            .peer_uptime
                            .store(s.uptime_seconds as u64, Ordering::Relaxed);
                    }
                    Ok(Message::Disconnect(d)) => {
                        info!("Android disconnected: {}", d.reason);
                        return SessionEnd::PeerDisconnected(d.reason);
                    }
                    Ok(Message::Keepalive) => debug!("keepalive from Android"),
                    Ok(Message::Error(e)) => {
                        warn!("Android reported error {:?}: {}", e.error_code, e.message);
                    }
                    Ok(other) => debug!("ignoring unexpected message: {:?}", other),
                    Err(ProtocolError::Io(e)) => {
                        return SessionEnd::TransportClosed(format!("connection lost: {}", e))
                    }
                    Err(e) => {
                        error!("Protocol error: {}", e);
                        return SessionEnd::TransportClosed(format!("protocol error: {}", e));
                    }
                }
            }
        })
    };

    // Desktop -> Android keepalive. The phone only enforces its idle
    // timeout once it has seen one, so phones and desktops from before this
    // existed keep working with each other.
    let keepalive = opts.keepalive_interval.map(|every| {
        let out_tx = out_tx.clone();
        tokio::spawn(async move {
            let mut tick = tokio::time::interval_at(tokio::time::Instant::now() + every, every);
            tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            loop {
                tick.tick().await;
                if out_tx.send(Message::Keepalive).await.is_err() {
                    break;
                }
            }
        })
    });

    // Periodic stats push for the GUI.
    let stats_pump = opts.service.clone().map(|svc| {
        let counters = Arc::clone(&counters);
        tokio::spawn(async move {
            let mut tick = tokio::time::interval(Duration::from_secs(1));
            loop {
                tick.tick().await;
                let mut s = counters.snapshot();
                if s.uptime_seconds == 0 {
                    s.uptime_seconds = started.elapsed().as_secs() as u32;
                }
                svc.update_stats(s);
            }
        })
    });

    let end = tokio::select! {
        r = &mut uplink => r.unwrap_or_else(|e| SessionEnd::DeviceClosed(format!("uplink task failed: {}", e))),
        r = &mut downlink => r.unwrap_or_else(|e| SessionEnd::TransportClosed(format!("downlink task failed: {}", e))),
        r = &mut writer => r.unwrap_or_else(|e| SessionEnd::TransportClosed(format!("writer task failed: {}", e))),
        _ = shutdown => SessionEnd::Cancelled,
    };

    uplink.abort();
    downlink.abort();
    if let Some(p) = stats_pump {
        p.abort();
    }
    if let Some(k) = keepalive {
        k.abort();
    }
    if matches!(end, SessionEnd::Cancelled | SessionEnd::DeviceClosed(_)) {
        // Tell Android we're going away so it doesn't wait for a timeout.
        let _ = out_tx
            .send(Message::Disconnect(Disconnect::new(
                "desktop client disconnected",
            )))
            .await;
        drop(out_tx);
        let _ = tokio::time::timeout(Duration::from_secs(2), &mut writer).await;
    }
    writer.abort();

    let stats = counters.snapshot();
    if let Some(svc) = &opts.service {
        svc.update_stats(stats.clone());
    }
    info!(
        "Session ended ({:?}): {} packets / {} bytes up, {} packets / {} bytes down, {} filtered",
        end,
        stats.packets_sent,
        stats.bytes_sent,
        stats.packets_received,
        stats.bytes_received,
        filter.get_stats().0
    );
    Ok((end, stats))
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::filter::{FilterAction, FilterMode, FilterRule, Protocol as FProto};
    use crate::protocol::{capabilities, ErrorCode, ErrorMessage, StatsUpdate};
    use std::pin::Pin;
    use std::task::{Context as TaskCx, Poll};
    use tokio::net::{TcpListener, TcpStream};

    /// In-memory packet device with TUN semantics: each `read` yields one
    /// whole packet, each `write` consumes one whole packet.
    pub struct ChannelDevice {
        pub inbound: mpsc::UnboundedReceiver<Vec<u8>>,
        pub outbound: mpsc::UnboundedSender<Vec<u8>>,
    }

    impl AsyncRead for ChannelDevice {
        fn poll_read(
            mut self: Pin<&mut Self>,
            cx: &mut TaskCx<'_>,
            buf: &mut tokio::io::ReadBuf<'_>,
        ) -> Poll<std::io::Result<()>> {
            match self.inbound.poll_recv(cx) {
                Poll::Ready(Some(pkt)) => {
                    buf.put_slice(&pkt);
                    Poll::Ready(Ok(()))
                }
                Poll::Ready(None) => Poll::Ready(Ok(())),
                Poll::Pending => Poll::Pending,
            }
        }
    }

    impl AsyncWrite for ChannelDevice {
        fn poll_write(
            self: Pin<&mut Self>,
            _cx: &mut TaskCx<'_>,
            buf: &[u8],
        ) -> Poll<std::io::Result<usize>> {
            let _ = self.outbound.send(buf.to_vec());
            Poll::Ready(Ok(buf.len()))
        }
        fn poll_flush(self: Pin<&mut Self>, _cx: &mut TaskCx<'_>) -> Poll<std::io::Result<()>> {
            Poll::Ready(Ok(()))
        }
        fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut TaskCx<'_>) -> Poll<std::io::Result<()>> {
            Poll::Ready(Ok(()))
        }
    }

    pub fn channel_device() -> (
        ChannelDevice,
        mpsc::UnboundedSender<Vec<u8>>,
        mpsc::UnboundedReceiver<Vec<u8>>,
    ) {
        let (in_tx, in_rx) = mpsc::unbounded_channel();
        let (out_tx, out_rx) = mpsc::unbounded_channel();
        (
            ChannelDevice {
                inbound: in_rx,
                outbound: out_tx,
            },
            in_tx,
            out_rx,
        )
    }

    fn resp() -> HandshakeResponse {
        HandshakeResponse {
            version: PROTOCOL_VERSION,
            capabilities: capabilities::WIFI,
            ip_address: 0x0a000002,
            dns: 0x08080808,
            device_name: None,
        }
    }

    /// Minimal IPv4 packet with the given protocol number.
    fn ipv4(proto: u8, payload: &[u8]) -> Vec<u8> {
        let total = 20 + payload.len();
        let mut p = vec![0u8; 20];
        p[0] = 0x45;
        p[2..4].copy_from_slice(&(total as u16).to_be_bytes());
        p[8] = 64;
        p[9] = proto;
        p[12..16].copy_from_slice(&[10, 0, 0, 1]);
        p[16..20].copy_from_slice(&[10, 0, 0, 2]);
        p.extend_from_slice(payload);
        p
    }

    async fn pair() -> (TcpStream, TcpStream) {
        let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = l.local_addr().unwrap();
        let (c, s) = tokio::join!(TcpStream::connect(addr), l.accept());
        (c.unwrap(), s.unwrap().0)
    }

    #[tokio::test]
    async fn handshake_sends_token_and_returns_response() {
        let (mut c, mut s) = pair().await;
        let server = tokio::spawn(async move {
            let req = match read_message(&mut s).await.unwrap() {
                Message::HandshakeReq(r) => r,
                m => panic!("got {:?}", m),
            };
            write_message(&mut s, &Message::HandshakeResp(resp()))
                .await
                .unwrap();
            req
        });
        let r = client_handshake(&mut c, capabilities::WIFI, Some("tok123".into()))
            .await
            .unwrap();
        assert_eq!(r.ip_address, 0x0a000002);
        let req = server.await.unwrap();
        assert_eq!(req.token.as_deref(), Some("tok123"));
        assert_eq!(req.capabilities, capabilities::WIFI);
        assert_eq!(req.version, PROTOCOL_VERSION);
    }

    #[tokio::test]
    async fn handshake_surfaces_peer_auth_rejection() {
        let (mut c, mut s) = pair().await;
        tokio::spawn(async move {
            let _ = read_message(&mut s).await.unwrap();
            let e = Message::Error(ErrorMessage {
                error_code: ErrorCode::AuthFailed,
                message: "Authentication failed: invalid token".into(),
            });
            write_message(&mut s, &e).await.unwrap();
        });
        let err = client_handshake(&mut c, capabilities::WIFI, Some("wrong".into()))
            .await
            .unwrap_err();
        match err {
            ProtocolError::PeerError { code, message } => {
                assert!(matches!(code, ErrorCode::AuthFailed));
                assert!(message.contains("invalid token"));
            }
            e => panic!("expected PeerError, got {:?}", e),
        }
    }

    #[tokio::test]
    async fn handshake_rejects_incompatible_major_version() {
        let (mut c, mut s) = pair().await;
        tokio::spawn(async move {
            let _ = read_message(&mut s).await.unwrap();
            let mut r = resp();
            r.version = 0x0200;
            write_message(&mut s, &Message::HandshakeResp(r))
                .await
                .unwrap();
        });
        let err = client_handshake(&mut c, capabilities::USB, None)
            .await
            .unwrap_err();
        assert!(matches!(
            err,
            ProtocolError::VersionMismatch { peer_major: 2, .. }
        ));
    }

    #[tokio::test]
    async fn read_message_rejects_bad_magic_without_reading_payload() {
        let (mut c, mut s) = pair().await;
        s.write_all(&[0xde, 0xad, 0x03, 0xff, 0xff]).await.unwrap();
        assert!(matches!(
            read_message(&mut c).await,
            Err(ProtocolError::InvalidMagic)
        ));
    }

    fn transport(c: TcpStream) -> Transport {
        let (r, w) = c.into_split();
        Transport {
            read: Box::new(r),
            write: Box::new(w),
            handshake: resp(),
        }
    }

    /// Full data path over a real TCP socket: uplink packets reach the peer
    /// framed as IpPacket, downlink packets reach the device, and -- the
    /// regression this module fixes -- a downlink packet is delivered while
    /// the device has nothing to send (the old shared-mutex loop stalled
    /// here until the next outbound packet).
    #[tokio::test]
    async fn forwards_both_directions_and_downlink_does_not_wait_for_uplink() {
        let (c, mut s) = pair().await;
        let (dev, dev_in, mut dev_out) = channel_device();
        let (stop_tx, stop_rx) = tokio::sync::oneshot::channel::<()>();
        let session = tokio::spawn(forward(
            transport(c),
            dev,
            ForwardOptions::default(),
            async {
                let _ = stop_rx.await;
            },
        ));

        // Downlink first, with zero uplink traffic.
        let down = ipv4(17, b"from-android");
        write_message(&mut s, &Message::IpPacket(IpPacket::new(down.clone())))
            .await
            .unwrap();
        let got = tokio::time::timeout(Duration::from_secs(5), dev_out.recv())
            .await
            .expect("downlink packet stalled")
            .unwrap();
        assert_eq!(got, down);

        // Stats and keepalive are consumed, not written to the device.
        write_message(
            &mut s,
            &Message::StatsUpdate(StatsUpdate {
                bytes_sent: 1,
                bytes_received: 2,
                uptime_seconds: 42,
            }),
        )
        .await
        .unwrap();
        write_message(&mut s, &Message::Keepalive).await.unwrap();

        // The phone first gets the (disabled) filter config.
        match tokio::time::timeout(Duration::from_secs(5), read_message(&mut s))
            .await
            .unwrap()
            .unwrap()
        {
            Message::ConfigUpdate(c) => {
                assert!(c
                    .config
                    .contains(&("filter.enabled".into(), "false".into())))
            }
            m => panic!("expected ConfigUpdate first, got {:?}", m),
        }
        // Then a keepalive (arms the phone's idle timeout).
        match tokio::time::timeout(Duration::from_secs(5), read_message(&mut s))
            .await
            .unwrap()
            .unwrap()
        {
            Message::Keepalive => {}
            m => panic!("expected the initial Keepalive, got {:?}", m),
        }

        // Uplink.
        let up = ipv4(6, b"from-desktop");
        dev_in.send(up.clone()).unwrap();
        match tokio::time::timeout(Duration::from_secs(5), read_message(&mut s))
            .await
            .unwrap()
            .unwrap()
        {
            Message::IpPacket(p) => assert_eq!(p.data, up),
            m => panic!("expected IpPacket, got {:?}", m),
        }
        assert!(
            dev_out.try_recv().is_err(),
            "stats/keepalive leaked to device"
        );

        // Cancel: peer must receive a Disconnect.
        stop_tx.send(()).unwrap();
        match tokio::time::timeout(Duration::from_secs(5), read_message(&mut s))
            .await
            .unwrap()
            .unwrap()
        {
            Message::Disconnect(_) => {}
            m => panic!("expected Disconnect, got {:?}", m),
        }
        let (end, stats) = session.await.unwrap().unwrap();
        assert_eq!(end, SessionEnd::Cancelled);
        assert_eq!(stats.packets_sent, 1);
        assert_eq!(stats.packets_received, 1);
        assert_eq!(stats.bytes_received, down.len() as u64);
        assert_eq!(stats.uptime_seconds, 42);
    }

    #[tokio::test]
    async fn filter_blocks_uplink_packets() {
        let (c, mut s) = pair().await;
        let (dev, dev_in, _dev_out) = channel_device();
        let opts = ForwardOptions {
            filter: FilterConfig {
                enabled: true,
                mode: FilterMode::Blacklist,
                rules: vec![FilterRule {
                    id: "no-icmp".into(),
                    enabled: true,
                    action: FilterAction::Block,
                    protocol: Some(FProto::Icmp),
                    dest_ip: None,
                    dest_port: None,
                    source_ip: None,
                    source_port: None,
                    description: None,
                }],
                log_blocked: false,
            },
            ..Default::default()
        };
        let session = tokio::spawn(forward(transport(c), dev, opts, std::future::pending()));
        // The profile's rules go to the phone before any packet.
        match tokio::time::timeout(Duration::from_secs(5), read_message(&mut s))
            .await
            .unwrap()
            .unwrap()
        {
            Message::ConfigUpdate(c) => {
                assert!(c.config.contains(&("filter.enabled".into(), "true".into())));
                assert!(c.config.contains(&("filter.rules".into(), "1".into())));
                assert!(c
                    .config
                    .contains(&("filter.rule.0".into(), "no-icmp|true|block|icmp||||".into())));
            }
            m => panic!("expected ConfigUpdate first, got {:?}", m),
        }
        assert!(matches!(
            tokio::time::timeout(Duration::from_secs(5), read_message(&mut s))
                .await
                .unwrap()
                .unwrap(),
            Message::Keepalive
        ));
        dev_in.send(ipv4(1, b"ping")).unwrap();
        let udp = ipv4(17, b"dns");
        dev_in.send(udp.clone()).unwrap();
        match tokio::time::timeout(Duration::from_secs(5), read_message(&mut s))
            .await
            .unwrap()
            .unwrap()
        {
            Message::IpPacket(p) => assert_eq!(p.data, udp, "ICMP should have been filtered"),
            m => panic!("expected IpPacket, got {:?}", m),
        }
        write_message(&mut s, &Message::Disconnect(Disconnect::new("bye")))
            .await
            .unwrap();
        let (end, stats) = session.await.unwrap().unwrap();
        assert_eq!(end, SessionEnd::PeerDisconnected("bye".into()));
        assert_eq!(stats.packets_sent, 1);
    }

    #[tokio::test]
    async fn peer_close_ends_session_for_reconnect() {
        let (c, s) = pair().await;
        let (dev, _dev_in, _dev_out) = channel_device();
        drop(s);
        let (end, _) = forward(
            transport(c),
            dev,
            ForwardOptions::default(),
            std::future::pending(),
        )
        .await
        .unwrap();
        assert!(matches!(end, SessionEnd::TransportClosed(_)));
        assert!(end.should_reconnect());
    }

    #[tokio::test]
    async fn silent_peer_hits_idle_timeout() {
        let (c, _s) = pair().await;
        let (dev, _dev_in, _dev_out) = channel_device();
        let opts = ForwardOptions {
            idle_timeout: Some(Duration::from_millis(300)),
            ..Default::default()
        };
        let (end, _) = forward(transport(c), dev, opts, std::future::pending())
            .await
            .unwrap();
        match end {
            SessionEnd::TransportClosed(why) => assert!(why.contains("no traffic"), "{}", why),
            e => panic!("expected idle timeout, got {:?}", e),
        }
    }

    /// The phone frees a session whose desktop went silent (WiFi drop, lid
    /// closed) only if the desktop keeps saying it is alive while idle.
    #[tokio::test]
    async fn idle_desktop_sends_keepalives_to_the_phone() {
        let (c, mut s) = pair().await;
        let (dev, _dev_in, _dev_out) = channel_device();
        let opts = ForwardOptions {
            keepalive_interval: Some(Duration::from_millis(100)),
            ..Default::default()
        };
        let session = tokio::spawn(forward(transport(c), dev, opts, std::future::pending()));
        let mut keepalives = 0;
        let started = Instant::now();
        // One right away, then one per interval.
        while keepalives < 4 {
            match tokio::time::timeout(Duration::from_secs(5), read_message(&mut s))
                .await
                .expect("no keepalive from an idle desktop")
                .unwrap()
            {
                Message::Keepalive => keepalives += 1,
                Message::ConfigUpdate(_) => {}
                m => panic!("unexpected {:?}", m),
            }
        }
        // The initial one + three ticks of 100 ms: not a burst, not seconds apart.
        let took = started.elapsed();
        assert!(took >= Duration::from_millis(250), "{:?}", took);
        assert!(took < Duration::from_secs(3), "{:?}", took);
        write_message(&mut s, &Message::Disconnect(Disconnect::new("bye")))
            .await
            .unwrap();
        let (end, _) = session.await.unwrap().unwrap();
        assert_eq!(end, SessionEnd::PeerDisconnected("bye".into()));
    }

    #[test]
    fn default_timeouts_detect_a_dead_link_within_45s() {
        let o = ForwardOptions::default();
        let idle = o.idle_timeout.expect("idle timeout must be on by default");
        assert!(idle <= Duration::from_secs(45), "{:?}", idle);
        // The app sends StatsUpdate every 5 s: never time out a live phone.
        assert!(idle >= Duration::from_secs(15), "{:?}", idle);
        let k = o
            .keepalive_interval
            .expect("keepalives must be on by default");
        // Several keepalives fit in the phone's 30 s idle window.
        assert!(k <= Duration::from_secs(10), "{:?}", k);
    }

    #[tokio::test]
    async fn stats_are_published_to_connection_service() {
        let (c, mut s) = pair().await;
        let (dev, _dev_in, mut dev_out) = channel_device();
        let svc = ConnectionService::new();
        let mut rx = svc.subscribe_stats();
        let opts = ForwardOptions {
            service: Some(svc.clone()),
            ..Default::default()
        };
        let session = tokio::spawn(forward(transport(c), dev, opts, std::future::pending()));
        write_message(&mut s, &Message::IpPacket(IpPacket::new(ipv4(17, b"x"))))
            .await
            .unwrap();
        dev_out.recv().await.unwrap();
        let mut seen = false;
        for _ in 0..5 {
            let st = tokio::time::timeout(Duration::from_secs(3), rx.recv())
                .await
                .unwrap()
                .unwrap();
            if st.packets_received == 1 {
                seen = true;
                break;
            }
        }
        assert!(seen, "ConnectionService never saw the received packet");
        write_message(&mut s, &Message::Disconnect(Disconnect::new("done")))
            .await
            .unwrap();
        session.await.unwrap().unwrap();
        assert_eq!(svc.stats().packets_received, 1);
    }

    #[tokio::test]
    async fn connect_refuses_tls_without_token() {
        let opts = ConnectOptions {
            addr: "127.0.0.1:9".parse().unwrap(),
            capabilities: capabilities::WIFI,
            token: None,
            tls: true,
            timeout: Duration::from_secs(1),
        };
        let err = connect(&opts).await.err().expect("must refuse");
        assert!(format!("{:#}", err).contains("no pairing token"));
    }

    #[tokio::test]
    async fn connect_plaintext_and_tls_against_real_listener() {
        use crate::tls::certpin;
        // Plaintext
        let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = l.local_addr().unwrap();
        tokio::spawn(async move {
            let (mut s, _) = l.accept().await.unwrap();
            let _ = read_message(&mut s).await.unwrap();
            write_message(&mut s, &Message::HandshakeResp(resp()))
                .await
                .unwrap();
            tokio::time::sleep(Duration::from_millis(200)).await;
        });
        let t = connect(&ConnectOptions {
            addr,
            capabilities: capabilities::USB,
            token: None,
            tls: false,
            timeout: Duration::from_secs(5),
        })
        .await
        .unwrap();
        assert_eq!(t.handshake.dns, 0x08080808);

        // Cert-pinned TLS: right token connects, wrong token fails closed.
        let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = l.local_addr().unwrap();
        let cfg = Arc::new(certpin::server_tls_config("pairing-token").unwrap());
        tokio::spawn(async move {
            for _ in 0..2 {
                let (sock, _) = l.accept().await.unwrap();
                let acc = tokio_rustls::TlsAcceptor::from(Arc::clone(&cfg));
                tokio::spawn(async move {
                    if let Ok(mut tls) = acc.accept(sock).await {
                        let _ = read_message(&mut tls).await;
                        let _ = write_message(&mut tls, &Message::HandshakeResp(resp())).await;
                        tokio::time::sleep(Duration::from_millis(200)).await;
                    }
                });
            }
        });
        let ok = connect(&ConnectOptions {
            addr,
            capabilities: capabilities::WIFI,
            token: Some("pairing-token".into()),
            tls: true,
            timeout: Duration::from_secs(5),
        })
        .await;
        assert!(ok.is_ok(), "{:?}", ok.err());
        let bad = connect(&ConnectOptions {
            addr,
            capabilities: capabilities::WIFI,
            token: Some("other-token".into()),
            tls: true,
            timeout: Duration::from_secs(5),
        })
        .await;
        assert!(bad.is_err(), "wrong pairing token must not connect");
    }
}
