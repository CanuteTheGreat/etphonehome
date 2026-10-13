//! Protocol-compatible test peer for verifying ET Phone Home's desktop
//! client wire protocol and cert-pinned TLS end-to-end over a real network
//! link between two independent hosts.
//!
//! This is **not** the real Android server -- it's a minimal stand-in that
//! speaks the same `desktop/common/src/protocol.rs` wire format (magic/
//! type/length framing, `HandshakeReq`/`HandshakeResp`) and, when `--tls` is
//! given, the same cert-pinned TLS layer (`etphonehome_common::tls::certpin`)
//! the real `etphonehome-linux` client now uses. It exists because there's
//! no real Android device/emulator available in this environment to test
//! against; using it lets the *actual* client-side crypto and protocol code
//! be exercised against a real second process, over a real TCP connection,
//! with real captured packets -- rather than only unit-tested in isolation.

use anyhow::{anyhow, bail, ensure, Context, Result};
use clap::Parser;
use etphonehome_common::protocol::{capabilities, HandshakeResponse, Message, MAGIC, PROTOCOL_VERSION};
use etphonehome_common::tls::certpin;
use log::{error, info};
use std::sync::Arc;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

#[derive(Parser)]
struct Args {
    /// Address to listen on
    #[arg(long, default_value = "0.0.0.0:8888")]
    listen: String,

    /// Require cert-pinned TLS (mirrors `etphonehome-linux --tls`)
    #[arg(long)]
    tls: bool,

    /// Pairing token used both as the pinning trust anchor (TLS mode) and
    /// expected in the application HandshakeReq token field.
    #[arg(long)]
    token: Option<String>,
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
        let tls = args.tls;
        let token = args.token.clone();
        tokio::spawn(async move {
            if let Err(e) = handle(sock, tls, token).await {
                error!("connection from {} ended with error: {:#}", peer, e);
            } else {
                info!("connection from {} handled successfully", peer);
            }
        });
    }
}

async fn handle(sock: TcpStream, tls: bool, token: Option<String>) -> Result<()> {
    sock.set_nodelay(true).ok();

    if tls {
        let token = token.ok_or_else(|| anyhow!("--tls requires --token"))?;
        let config = certpin::server_tls_config(&token)
            .map_err(|e| anyhow!("TLS server config error: {}", e))?;
        let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(config));
        let mut tls_stream = acceptor.accept(sock).await.map_err(|e| {
            error!(
                "TLS handshake failed: {} -- wrong/mismatched pairing token, or peer isn't \
                 presenting the expected cert-pinned identity",
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
        run_protocol(&mut tls_stream, token).await
    } else {
        let mut sock = sock;
        run_protocol(&mut sock, token.unwrap_or_default()).await
    }
}

async fn run_protocol<S: AsyncRead + AsyncWrite + Unpin>(
    stream: &mut S,
    expected_token: String,
) -> Result<()> {
    let mut header = [0u8; 5];
    stream.read_exact(&mut header).await?;
    let magic = ((header[0] as u16) << 8) | (header[1] as u16);
    ensure!(magic == MAGIC, "bad magic bytes in request");
    let length = ((header[3] as u16) << 8) | (header[4] as u16);
    let mut payload = vec![0u8; length as usize];
    stream.read_exact(&mut payload).await?;
    let mut full = header.to_vec();
    full.extend_from_slice(&payload);
    let msg = Message::read_from(&mut std::io::Cursor::new(full))?;

    match msg {
        Message::HandshakeReq(req) => {
            info!(
                "HandshakeReq: version={:#06x} capabilities={:#010x} token={:?}",
                req.version, req.capabilities, req.token
            );
            if !expected_token.is_empty() {
                ensure!(
                    req.token.as_deref() == Some(expected_token.as_str()),
                    "application-level token mismatch (expected {:?}, got {:?})",
                    expected_token,
                    req.token
                );
            }
        }
        other => bail!("expected HandshakeReq, got {:?}", other),
    }

    let resp = Message::HandshakeResp(HandshakeResponse {
        version: PROTOCOL_VERSION,
        capabilities: capabilities::WIFI | capabilities::TLS | capabilities::TLS_PSK,
        ip_address: 0x0a000002, // 10.0.0.2
        dns: 0x08080808,        // 8.8.8.8
    });
    let mut buf = Vec::new();
    resp.write_to(&mut buf)?;
    stream.write_all(&buf).await?;
    stream.flush().await?;
    info!("Sent HandshakeResp -- waiting up to 10s for a follow-up test packet...");

    let mut header2 = [0u8; 5];
    match tokio::time::timeout(std::time::Duration::from_secs(10), stream.read_exact(&mut header2))
        .await
    {
        Ok(Ok(_)) => {
            let magic2 = ((header2[0] as u16) << 8) | (header2[1] as u16);
            if magic2 == MAGIC {
                let len2 = ((header2[3] as u16) << 8) | (header2[4] as u16);
                let mut payload2 = vec![0u8; len2 as usize];
                stream.read_exact(&mut payload2).await?;
                info!(
                    "Received follow-up packet ({} bytes): {:?}",
                    payload2.len(),
                    String::from_utf8_lossy(&payload2)
                );
            }
        }
        Ok(Err(e)) => info!("follow-up read ended: {}", e),
        Err(_) => info!("no follow-up packet received within 10s (fine for handshake-only test)"),
    }

    Ok(())
}
