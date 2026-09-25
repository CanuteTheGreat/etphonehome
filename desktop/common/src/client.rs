use crate::protocol::*;
use log::{debug, error, info};
use std::io::{self, BufReader, BufWriter};
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

/// ET Phone Home client
pub struct Client {
    stream: TcpStream,
    capabilities: u32,
    token: Option<String>,
}

impl Client {
    /// Connect to Android device
    pub fn connect(addr: SocketAddr, capabilities: u32, token: Option<String>) -> io::Result<Self> {
        info!("Connecting to Android device at {}...", addr);

        let stream = TcpStream::connect_timeout(&addr, Duration::from_secs(10))?;
        stream.set_nodelay(true)?;
        stream.set_read_timeout(Some(Duration::from_secs(30)))?;
        stream.set_write_timeout(Some(Duration::from_secs(30)))?;

        info!("Connected successfully");

        Ok(Self {
            stream,
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
        } // BufWriter is flushed when dropped

        // Receive handshake response
        let mut reader = BufReader::new(&self.stream);
        let response = Message::read_from(&mut reader)?;

        match response {
            Message::HandshakeResp(resp) => {
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
        Ok(())
    }

    /// Receive a message from Android
    pub fn receive_message(&mut self) -> Result<Message, ProtocolError> {
        let mut reader = BufReader::new(&self.stream);
        Message::read_from(&mut reader)
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

    /// Get the underlying TCP stream for async operations
    pub fn into_stream(self) -> TcpStream {
        self.stream
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
