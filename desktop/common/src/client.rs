use crate::protocol::*;
use crate::tls::certpin;
use log::{debug, error, info};
use std::io::{self, BufReader, BufWriter, Write};
use std::net::{SocketAddr, TcpStream};
use std::sync::Arc;
use std::time::Duration;

/// ET Phone Home client (plaintext/legacy path).
///
/// For cert-pinned TLS connections, see [`connect_tls_async`] instead --
/// that path is fully async from the TCP connect onward because
/// `tokio_rustls` has no public API for grafting an already-completed
/// synchronous `rustls` handshake onto an async socket afterward, so trying
/// to reuse this synchronous `Client` for the handshake and then "hand off"
/// to async packet forwarding (the way the plaintext path does via
/// `into_stream()`) does not work for TLS. The TLS path therefore drives the
/// TCP connect, the TLS handshake, and the application handshake messages
/// all on the async runtime, then hands the still-open
/// `tokio_rustls::client::TlsStream` straight to the same packet-forwarding
/// loop the plaintext path uses (which is already transport-agnostic: it
/// only calls generic `AsyncRead`/`AsyncWrite` methods).
pub struct Client {
    stream: TcpStream,
    // Persistent read buffer across calls. Wrapping `&self.stream` in a
    // fresh `BufReader` on every `receive_message()` call (the old
    // behavior) silently drops any bytes it over-read from the socket into
    // its internal buffer but didn't consume before being dropped at the
    // end of that call -- the next call's brand new `BufReader` has no way
    // to recover them, so a single TCP read that happens to land two
    // protocol messages in one syscall would corrupt the stream: the first
    // message parses fine, the trailing bytes of the second message are
    // silently discarded, and the *next* `receive_message()` call
    // desyncs off wire framing entirely (reads someone else's message
    // header as if it started at that offset). Keeping one long-lived
    // `BufReader` for the life of the connection is the standard fix.
    reader: BufReader<TcpStream>,
    capabilities: u32,
    token: Option<String>,
}

impl Client {
    /// Connect to Android device (plaintext)
    pub fn connect(addr: SocketAddr, capabilities: u32, token: Option<String>) -> io::Result<Self> {
        info!("Connecting to Android device at {}...", addr);

        let stream = TcpStream::connect_timeout(&addr, Duration::from_secs(10))?;
        stream.set_nodelay(true)?;
        stream.set_read_timeout(Some(Duration::from_secs(30)))?;
        stream.set_write_timeout(Some(Duration::from_secs(30)))?;

        let reader_handle = stream.try_clone()?;

        info!("Connected successfully (plaintext)");

        Ok(Self {
            stream,
            reader: BufReader::new(reader_handle),
            capabilities,
            token,
        })
    }

    /// Perform handshake with Android device
    pub fn handshake(&mut self) -> Result<HandshakeResponse, ProtocolError> {
        info!("Performing handshake...");

        // Send handshake request with token if available
        let request = if let Some(token) = &self.token {
            info!("Using authentication token");
            HandshakeRequest::with_token(self.capabilities, token.clone())
        } else {
            HandshakeRequest::new(self.capabilities)
        };
        let msg = Message::HandshakeReq(request);

        {
            let mut writer = BufWriter::new(&self.stream);
            msg.write_to(&mut writer)?;
            // Explicit flush + error propagation: BufWriter's Drop impl
            // silently discards any I/O error from its final flush (this is
            // documented std behavior), so relying on drop-to-flush here
            // would let a failed handshake write look like a success to the
            // caller. Flush explicitly so a broken pipe/connection reset at
            // this exact boundary surfaces as a real error instead.
            writer.flush()?;
        }

        // Receive handshake response using the persistent reader (see the
        // `reader` field doc comment for why a fresh BufReader per call is
        // unsafe here).
        let response = Message::read_from(&mut self.reader)?;

        match response {
            Message::HandshakeResp(resp) => {
                if !is_version_compatible(resp.version) {
                    let local_major = (PROTOCOL_VERSION >> 8) as u8;
                    let peer_major = (resp.version >> 8) as u8;
                    error!(
                        "Protocol version mismatch: local major {}, Android major {} (Android sent {:#06x})",
                        local_major, peer_major, resp.version
                    );
                    return Err(ProtocolError::VersionMismatch {
                        local_major,
                        peer_major,
                        peer_version: resp.version,
                    });
                }
                info!("Handshake successful");
                info!("  Protocol version: {:#06x}", resp.version);
                info!("  Android capabilities: {:#010x}", resp.capabilities);
                info!("  Assigned IP: {}", int_to_ip(resp.ip_address));
                info!("  DNS: {}", int_to_ip(resp.dns));
                Ok(resp)
            }
            Message::Error(err) => {
                error!("Handshake failed: {:?} - {}", err.error_code, err.message);
                Err(ProtocolError::UnexpectedMessage)
            }
            _ => {
                error!("Unexpected response during handshake");
                Err(ProtocolError::UnexpectedMessage)
            }
        }
    }

    /// Send a message to Android
    pub fn send_message(&mut self, msg: Message) -> Result<(), ProtocolError> {
        let mut writer = BufWriter::new(&self.stream);
        msg.write_to(&mut writer)?;
        // See handshake() for why this explicit flush matters: BufWriter's
        // Drop impl swallows flush errors, so without this call a failed
        // network write here would be silently reported as Ok(()).
        writer.flush()?;
        Ok(())
    }

    /// Receive a message from Android
    pub fn receive_message(&mut self) -> Result<Message, ProtocolError> {
        Message::read_from(&mut self.reader)
    }

    /// Send an IP packet to Android
    pub fn send_packet(&mut self, packet: Vec<u8>) -> Result<(), ProtocolError> {
        let msg = Message::IpPacket(IpPacket::new(packet));
        self.send_message(msg)
    }

    /// Receive an IP packet from Android
    pub fn receive_packet(&mut self) -> Result<Option<Vec<u8>>, ProtocolError> {
        match self.receive_message()? {
            Message::IpPacket(packet) => Ok(Some(packet.data)),
            Message::StatsUpdate(stats) => {
                debug!(
                    "Stats: sent={} recv={} uptime={}s",
                    stats.bytes_sent, stats.bytes_received, stats.uptime_seconds
                );
                Ok(None)
            }
            Message::Keepalive => {
                debug!("Received keepalive");
                Ok(None)
            }
            Message::Disconnect(disc) => {
                info!("Android disconnected: {}", disc.reason);
                Err(ProtocolError::Io(io::Error::new(
                    io::ErrorKind::ConnectionAborted,
                    disc.reason,
                )))
            }
            msg => {
                debug!("Unexpected message type: {:?}", msg);
                Ok(None)
            }
        }
    }

    /// Get the underlying TCP stream for async operations (plaintext path only)
    pub fn into_stream(self) -> TcpStream {
        self.stream
    }
}

/// Result of a successful cert-pinned TLS connect: the still-open,
/// already-handshaked (both TLS and application-level) async stream, ready
/// to be split and handed to the packet-forwarding loop exactly like the
/// plaintext path's `tokio::net::TcpStream` is.
pub struct TlsConnectOutcome {
    pub stream: tokio_rustls::client::TlsStream<tokio::net::TcpStream>,
    pub handshake: HandshakeResponse,
}

/// Connect to the Android device over cert-pinned TLS (see
/// `crate::tls::certpin` for the pinning design) and complete the
/// application-level handshake, all over the async runtime.
///
/// `token` is required: it is both the application auth token sent in the
/// `HandshakeRequest` *and* the pinning trust anchor for the TLS layer, so
/// there is no ambiguity about which secret the connection is rooted in.
/// If the peer doesn't present a certificate matching the key derived from
/// `token`, the TLS handshake fails here with an error -- this function
/// never falls back to a plaintext or unpinned connection.
pub async fn connect_tls_async(
    addr: SocketAddr,
    capabilities: u32,
    token: String,
) -> Result<TlsConnectOutcome, ProtocolError> {
    use std::io::Error as IoError;
    use std::io::ErrorKind;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    info!(
        "Connecting to Android device at {} (cert-pinned TLS)...",
        addr
    );
    let tcp = tokio::net::TcpStream::connect(addr).await?;
    tcp.set_nodelay(true)?;

    let config = certpin::client_tls_config(&token)
        .map_err(|e| IoError::new(ErrorKind::Other, format!("TLS config error: {}", e)))?;
    let connector = tokio_rustls::TlsConnector::from(Arc::new(config));
    let server_name = rustls::pki_types::ServerName::IpAddress(addr.ip().into());

    info!("Starting cert-pinned TLS handshake...");
    let mut tls = connector.connect(server_name, tcp).await.map_err(|e| {
        error!(
            "TLS handshake failed: {} (wrong pairing token, or peer doesn't support TLS?)",
            e
        );
        IoError::new(ErrorKind::Other, format!("TLS handshake failed: {}", e))
    })?;
    {
        let (_, conn) = tls.get_ref();
        info!(
            "TLS handshake complete: protocol={:?} cipher={:?}",
            conn.protocol_version(),
            conn.negotiated_cipher_suite()
        );
    }

    // Application-level handshake, sent/received over the now-encrypted
    // stream -- same wire format as the plaintext path, just carried inside
    // TLS records instead of raw TCP segments.
    let request = HandshakeRequest::with_token(capabilities, token);
    let msg = Message::HandshakeReq(request);
    let mut buf = Vec::new();
    msg.write_to(&mut buf)?;
    tls.write_all(&buf).await?;
    tls.flush().await?;

    let mut header = [0u8; 5];
    tls.read_exact(&mut header).await?;
    let magic = ((header[0] as u16) << 8) | (header[1] as u16);
    if magic != MAGIC {
        return Err(ProtocolError::InvalidMagic);
    }
    let length = ((header[3] as u16) << 8) | (header[4] as u16);
    let mut payload = vec![0u8; length as usize];
    tls.read_exact(&mut payload).await?;
    let mut full_msg = header.to_vec();
    full_msg.extend_from_slice(&payload);
    let response = Message::read_from(&mut std::io::Cursor::new(full_msg))?;

    match response {
        Message::HandshakeResp(resp) => {
            if !is_version_compatible(resp.version) {
                let local_major = (PROTOCOL_VERSION >> 8) as u8;
                let peer_major = (resp.version >> 8) as u8;
                error!(
                    "Protocol version mismatch: local major {}, Android major {} (Android sent {:#06x})",
                    local_major, peer_major, resp.version
                );
                return Err(ProtocolError::VersionMismatch {
                    local_major,
                    peer_major,
                    peer_version: resp.version,
                });
            }
            info!("Handshake successful (TLS-encrypted)");
            info!("  Protocol version: {:#06x}", resp.version);
            info!("  Android capabilities: {:#010x}", resp.capabilities);
            info!("  Assigned IP: {}", int_to_ip(resp.ip_address));
            info!("  DNS: {}", int_to_ip(resp.dns));
            Ok(TlsConnectOutcome {
                stream: tls,
                handshake: resp,
            })
        }
        Message::Error(err) => {
            error!("Handshake failed: {:?} - {}", err.error_code, err.message);
            Err(ProtocolError::UnexpectedMessage)
        }
        _ => {
            error!("Unexpected response during handshake");
            Err(ProtocolError::UnexpectedMessage)
        }
    }
}

/// Convert IP address from integer to string
pub fn int_to_ip(ip: u32) -> String {
    format!(
        "{}.{}.{}.{}",
        (ip >> 24) & 0xFF,
        (ip >> 16) & 0xFF,
        (ip >> 8) & 0xFF,
        ip & 0xFF
    )
}

/// Convert IP address from string to integer
pub fn ip_to_int(ip: &str) -> Result<u32, std::net::AddrParseError> {
    let addr: std::net::Ipv4Addr = ip.parse()?;
    let octets = addr.octets();
    Ok(((octets[0] as u32) << 24)
        | ((octets[1] as u32) << 16)
        | ((octets[2] as u32) << 8)
        | (octets[3] as u32))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ip_conversion() {
        let ip = "192.168.1.1";
        let int_val = ip_to_int(ip).unwrap();
        let back = int_to_ip(int_val);
        assert_eq!(ip, back);
    }
}
