//! Minimal client-side counterpart to `testpeer`, driving the *real*
//! production client code paths in `etphonehome_common::client` (the exact
//! same `Client::connect`/`handshake` and `connect_tls_async` functions
//! `etphonehome-linux` uses) to connect, complete the handshake, send one
//! recognizable test packet, and exit -- so the resulting network capture
//! can be inspected for (a) a real handshake, (b) non-plaintext payload
//! bytes under TLS, and (c) a hard failure on a wrong pairing token.

use anyhow::{Context, Result};
use clap::Parser;
use etphonehome_common::client::{connect_tls_async, Client};
use etphonehome_common::protocol::{capabilities, Message};
use log::info;
use std::net::SocketAddr;
use tokio::io::AsyncWriteExt;

const TEST_MARKER: &str = "SECRET-PLAINTEXT-MARKER-0xDEADBEEF-DO-NOT-LEAK";

#[derive(Parser)]
struct Args {
    #[arg(long)]
    addr: SocketAddr,

    #[arg(long)]
    tls: bool,

    #[arg(long)]
    token: Option<String>,
}

#[tokio::main]
async fn main() -> Result<()> {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();
    let args = Args::parse();

    if args.tls {
        let token = args.token.clone().context("--tls requires --token")?;
        let mut outcome = connect_tls_async(args.addr, capabilities::WIFI, token)
            .await
            .context("TLS connect+handshake failed")?;
        info!("TLS connect+handshake OK: {:?}", outcome.handshake.version);

        let msg = Message::IpPacket(etphonehome_common::protocol::IpPacket::new(
            TEST_MARKER.as_bytes().to_vec(),
        ));
        let mut buf = Vec::new();
        msg.write_to(&mut buf)?;
        outcome.stream.write_all(&buf).await?;
        outcome.stream.flush().await?;
        info!("Sent test marker packet over TLS, exiting.");
    } else {
        let mut client = Client::connect(args.addr, capabilities::WIFI, args.token)
            .context("plaintext connect failed")?;
        let resp = client.handshake().context("plaintext handshake failed")?;
        info!("Plaintext handshake OK: {:?}", resp.version);

        let msg = Message::IpPacket(etphonehome_common::protocol::IpPacket::new(
            TEST_MARKER.as_bytes().to_vec(),
        ));
        client.send_message(msg)?;
        info!("Sent test marker packet in plaintext, exiting.");
    }

    Ok(())
}
