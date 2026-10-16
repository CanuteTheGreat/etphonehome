//! Protocol-compatible stand-in for the Android app, used by tests and CI.
//!
//! This is **not** the real Android server -- it's a minimal peer that
//! speaks the same `desktop/common/src/protocol.rs` wire format and, with
//! `--tls`, the same cert-pinned TLS layer (`etphonehome_common::tls::certpin`)
//! the real `etphonehome` client uses. There's no Android device or emulator
//! (no KVM) on the CI runners, so this lets the *actual* desktop client run
//! end to end over a real network connection: the `e2e` CI job starts it,
//! connects the real `etphonehome` binary to it over a real TUN device and
//! pings through the tunnel.
//!
//! Behaviour after the handshake, per connection:
//! * verifies the application token when `--token` is set (replying with an
//!   `ErrorMsg` / `AuthFailed`, like the Android app, on mismatch),
//! * answers IPv4 ICMP echo requests carried in `IpPacket` messages with
//!   echo replies (so `ping 10.0.0.2` through the tunnel succeeds),
//! * sends a `StatsUpdate` and a `Keepalive` every `--keepalive-secs`,
//! * logs every non-ICMP packet it receives,
//! * with `--disconnect-after N` sends `Disconnect` after N echo replies
//!   (exercises client auto-reconnect).

use anyhow::{anyhow, Context, Result};
use clap::Parser;
use etphonehome_common::protocol::{
    capabilities, Disconnect, ErrorCode, ErrorMessage, HandshakeResponse, IpPacket, Message,
    StatsUpdate, PROTOCOL_VERSION,
};
use etphonehome_common::session::{read_message, write_message};
use etphonehome_common::tls::certpin;
use log::{error, info, warn};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Mutex;

#[derive(Parser, Clone)]
struct Args {
    /// Address to listen on
    #[arg(long, default_value = "0.0.0.0:8888")]
    listen: String,

    /// Require cert-pinned TLS (mirrors `etphonehome wifi --tls`)
    #[arg(long)]
    tls: bool,

    /// Pairing token: TLS pinning anchor and the expected HandshakeReq token.
    #[arg(long)]
    token: Option<String>,

    /// Interval for StatsUpdate + Keepalive messages.
    #[arg(long, default_value = "5")]
    keepalive_secs: u64,

    /// Send Disconnect after this many ICMP echo replies (0 = never).
    #[arg(long, default_value = "0")]
    disconnect_after: u64,
}

#[tokio::main]
async fn main() -> Result<()> {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();
    let args = Args::parse();

    let listener = TcpListener::bind(&args.listen)
        .await
        .context("failed to bind listen address")?;
    info!(
        "testpeer listening on {} (tls={}, token={})",
        args.listen,
        args.tls,
        if args.token.is_some() { "set" } else { "none" }
    );

    loop {
        let (sock, peer) = listener.accept().await?;
        info!("connection from {}", peer);
        let args2 = args.clone();
        tokio::spawn(async move {
            if let Err(e) = handle(sock, &args2).await {
                error!("connection from {} ended with error: {:#}", peer, e);
            } else {
                info!("connection from {} handled successfully", peer);
            }
        });
    }
}

async fn handle(sock: TcpStream, args: &Args) -> Result<()> {
    sock.set_nodelay(true).ok();
    if args.tls {
        let token = args
            .token
            .clone()
            .ok_or_else(|| anyhow!("--tls requires --token"))?;
        let config = certpin::server_tls_config(&token)
            .map_err(|e| anyhow!("TLS server config error: {}", e))?;
        let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(config));
        let tls_stream = acceptor.accept(sock).await.map_err(|e| {
            error!(
                "TLS handshake failed: {} -- wrong/mismatched pairing token",
                e
            );
            anyhow!("TLS handshake failed: {}", e)
        })?;
        let (_, conn) = tls_stream.get_ref();
        info!(
            "TLS handshake complete: protocol={:?} cipher={:?}",
            conn.protocol_version(),
            conn.negotiated_cipher_suite()
        );
        run_protocol(tls_stream, args).await
    } else {
        run_protocol(sock, args).await
    }
}

async fn run_protocol<S>(stream: S, args: &Args) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let (mut rd, wr) = tokio::io::split(stream);
    let wr = Arc::new(Mutex::new(wr));

    let req = match read_message(&mut rd).await? {
        Message::HandshakeReq(req) => req,
        other => return Err(anyhow!("expected HandshakeReq, got {:?}", other)),
    };
    info!(
        "HandshakeReq: version={:#06x} capabilities={:#010x} token={}",
        req.version,
        req.capabilities,
        if req.token.is_some() {
            "present"
        } else {
            "absent"
        }
    );
    if let Some(expected) = &args.token {
        if req.token.as_deref() != Some(expected.as_str()) {
            warn!("application-level token mismatch, rejecting");
            let e = Message::Error(ErrorMessage {
                error_code: ErrorCode::AuthFailed,
                message: "Authentication failed: invalid token".into(),
            });
            write_message(&mut *wr.lock().await, &e).await?;
            return Err(anyhow!("token mismatch"));
        }
    }
    let resp = Message::HandshakeResp(HandshakeResponse {
        version: PROTOCOL_VERSION,
        capabilities: capabilities::WIFI | capabilities::TLS | capabilities::TLS_PSK,
        ip_address: 0x0a000002, // 10.0.0.2
        dns: 0x08080808,        // 8.8.8.8
    });
    write_message(&mut *wr.lock().await, &resp).await?;
    info!("Sent HandshakeResp");

    let received = Arc::new(AtomicU64::new(0));
    let started = Instant::now();
    let ticker = {
        let wr = Arc::clone(&wr);
        let received = Arc::clone(&received);
        let every = Duration::from_secs(args.keepalive_secs.max(1));
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(every).await;
                let mut w = wr.lock().await;
                let s = Message::StatsUpdate(StatsUpdate {
                    bytes_sent: 0,
                    bytes_received: received.load(Ordering::Relaxed),
                    uptime_seconds: started.elapsed().as_secs() as u32,
                });
                if write_message(&mut *w, &s).await.is_err()
                    || write_message(&mut *w, &Message::Keepalive).await.is_err()
                {
                    break;
                }
            }
        })
    };

    let mut replies = 0u64;
    let result = loop {
        let msg = match read_message(&mut rd).await {
            Ok(m) => m,
            Err(e) => break Err(anyhow!("read ended: {}", e)),
        };
        match msg {
            Message::IpPacket(pkt) => {
                received.fetch_add(pkt.data.len() as u64, Ordering::Relaxed);
                if let Some(reply) = icmp_echo_reply(&pkt.data) {
                    write_message(
                        &mut *wr.lock().await,
                        &Message::IpPacket(IpPacket::new(reply)),
                    )
                    .await?;
                    replies += 1;
                    info!("answered ICMP echo request #{}", replies);
                    if args.disconnect_after != 0 && replies >= args.disconnect_after {
                        info!("sending Disconnect after {} replies", replies);
                        let d = Message::Disconnect(Disconnect::new("testpeer: disconnect-after"));
                        write_message(&mut *wr.lock().await, &d).await?;
                        break Ok(());
                    }
                } else {
                    info!(
                        "Received packet ({} bytes): {:?}",
                        pkt.data.len(),
                        String::from_utf8_lossy(&pkt.data)
                    );
                }
            }
            Message::Disconnect(d) => {
                info!("client sent Disconnect: {}", d.reason);
                break Ok(());
            }
            Message::Keepalive => info!("client keepalive"),
            other => info!("ignoring {:?}", other),
        }
    };
    ticker.abort();
    result
}

/// Build an ICMP echo reply for an IPv4 ICMP echo request, or None.
fn icmp_echo_reply(pkt: &[u8]) -> Option<Vec<u8>> {
    if pkt.len() < 20 || pkt[0] >> 4 != 4 || pkt[9] != 1 {
        return None;
    }
    let ihl = ((pkt[0] & 0x0f) as usize) * 4;
    if ihl < 20 || pkt.len() < ihl + 8 || pkt[ihl] != 8 {
        return None;
    }
    let mut r = pkt.to_vec();
    r[12..16].copy_from_slice(&pkt[16..20]);
    r[16..20].copy_from_slice(&pkt[12..16]);
    r[8] = 64;
    r[10] = 0;
    r[11] = 0;
    let ck = checksum(&r[..ihl]);
    r[10..12].copy_from_slice(&ck.to_be_bytes());
    r[ihl] = 0; // echo reply
    r[ihl + 2] = 0;
    r[ihl + 3] = 0;
    let ck = checksum(&r[ihl..]);
    r[ihl + 2..ihl + 4].copy_from_slice(&ck.to_be_bytes());
    Some(r)
}

fn checksum(data: &[u8]) -> u16 {
    let mut sum: u32 = 0;
    for c in data.chunks(2) {
        let w = if c.len() == 2 {
            u16::from_be_bytes([c[0], c[1]])
        } else {
            u16::from_be_bytes([c[0], 0])
        };
        sum += w as u32;
    }
    while sum > 0xffff {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn echo_reply_swaps_addresses_and_has_valid_checksums() {
        // 10.0.0.1 -> 10.0.0.2 ICMP echo request, id 0x1234 seq 1, "abcd"
        let mut req = vec![
            0x45, 0, 0, 32, 0, 1, 0x40, 0, 64, 1, 0, 0, 10, 0, 0, 1, 10, 0, 0, 2, 8, 0, 0, 0, 0x12,
            0x34, 0, 1, b'a', b'b', b'c', b'd',
        ];
        let ck = checksum(&req[..20]);
        req[10..12].copy_from_slice(&ck.to_be_bytes());
        let ck = checksum(&req[20..]);
        req[22..24].copy_from_slice(&ck.to_be_bytes());

        let r = icmp_echo_reply(&req).unwrap();
        assert_eq!(&r[12..16], &[10, 0, 0, 2]);
        assert_eq!(&r[16..20], &[10, 0, 0, 1]);
        assert_eq!(r[20], 0);
        assert_eq!(checksum(&r[..20]), 0, "IP header checksum must verify");
        assert_eq!(checksum(&r[20..]), 0, "ICMP checksum must verify");
        assert_eq!(&r[24..], &req[24..], "id/seq/payload preserved");
        assert!(icmp_echo_reply(&r).is_none(), "replies are not answered");
        assert!(icmp_echo_reply(b"not an ip packet at all").is_none());
    }
}
