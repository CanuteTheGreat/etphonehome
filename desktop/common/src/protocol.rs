use std::io::{self, Read, Write};
use thiserror::Error;

/// Protocol magic bytes: 'ET'
pub const MAGIC: u16 = 0x4554;

/// Message types
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MessageType {
    HandshakeReq = 0x01,
    HandshakeResp = 0x02,
    IpPacket = 0x03,
    StatsUpdate = 0x04,
    ConfigUpdate = 0x05,
    Disconnect = 0x06,
    Keepalive = 0x07,
    ErrorMsg = 0x08,
}

impl TryFrom<u8> for MessageType {
    type Error = ProtocolError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            0x01 => Ok(MessageType::HandshakeReq),
            0x02 => Ok(MessageType::HandshakeResp),
            0x03 => Ok(MessageType::IpPacket),
            0x04 => Ok(MessageType::StatsUpdate),
            0x05 => Ok(MessageType::ConfigUpdate),
            0x06 => Ok(MessageType::Disconnect),
            0x07 => Ok(MessageType::Keepalive),
            0x08 => Ok(MessageType::ErrorMsg),
            _ => Err(ProtocolError::UnknownMessageType(value)),
        }
    }
}

/// Error codes
#[repr(u8)]
#[derive(Debug, Clone, Copy)]
pub enum ErrorCode {
    ProtocolVersion = 0x01,
    InvalidMessage = 0x02,
    AuthFailed = 0x03,
    Timeout = 0x04,
    InternalError = 0x05,
}

/// Protocol version
pub const PROTOCOL_VERSION: u16 = 0x0100; // v1.0

/// Capabilities flags
pub mod capabilities {
    pub const USB: u32 = 1 << 0;
    pub const WIFI: u32 = 1 << 1;
    pub const BLUETOOTH: u32 = 1 << 2;
    pub const ROOT: u32 = 1 << 3;
    pub const IPV6: u32 = 1 << 4;
    pub const TLS: u32 = 1 << 5;
    pub const TLS_PSK: u32 = 1 << 6;
}

/// Protocol errors
#[derive(Error, Debug)]
pub enum ProtocolError {
    #[error("IO error: {0}")]
    Io(#[from] io::Error),

    #[error("Invalid magic bytes")]
    InvalidMagic,

    #[error("Unknown message type: {0}")]
    UnknownMessageType(u8),

    #[error("Message too short")]
    MessageTooShort,

    #[error("Incomplete message")]
    IncompleteMessage,

    #[error("Invalid UTF-8: {0}")]
    InvalidUtf8(#[from] std::string::FromUtf8Error),

    #[error("Unexpected message type")]
    UnexpectedMessage,

    #[error("Protocol version mismatch: local major version {local_major}, peer major version {peer_major} (peer sent {peer_version:#06x})")]
    VersionMismatch {
        local_major: u8,
        peer_major: u8,
        peer_version: u16,
    },
}

/// Check whether a peer-advertised protocol version is compatible with
/// [`PROTOCOL_VERSION`].
///
/// The version is encoded as `(major << 8) | minor` (e.g. `0x0100` = 1.0).
/// Only the major byte is required to match; a minor version bump is
/// expected to stay backward compatible. Before this existed, both this
/// client and the Android service (see `VpnService.performHandshake` /
/// the Bluetooth handshake block) logged the peer's version and then
/// proceeded unconditionally -- a build speaking an incompatible wire
/// format would pass the handshake and only fail later, mid-stream, when
/// it sent or received a message the other side didn't expect, instead of
/// failing fast with the `ErrorCode::ProtocolVersion` / `ERR_PROTOCOL_VERSION`
/// error code that has existed since the protocol was first defined but was
/// never actually triggered by either side.
pub fn is_version_compatible(peer_version: u16) -> bool {
    (peer_version >> 8) as u8 == (PROTOCOL_VERSION >> 8) as u8
}

/// Protocol message
#[derive(Debug, Clone)]
pub enum Message {
    HandshakeReq(HandshakeRequest),
    HandshakeResp(HandshakeResponse),
    IpPacket(IpPacket),
    StatsUpdate(StatsUpdate),
    ConfigUpdate(ConfigUpdate),
    Disconnect(Disconnect),
    Keepalive,
    Error(ErrorMessage),
}

impl Message {
    /// Read a message from a reader
    pub fn read_from<R: Read>(reader: &mut R) -> Result<Self, ProtocolError> {
        let mut header = [0u8; 5];
        reader.read_exact(&mut header)?;

        let magic = u16::from_be_bytes([header[0], header[1]]);
        if magic != MAGIC {
            return Err(ProtocolError::InvalidMagic);
        }

        let msg_type = MessageType::try_from(header[2])?;
        let length = u16::from_be_bytes([header[3], header[4]]) as usize;

        let mut payload = vec![0u8; length];
        reader.read_exact(&mut payload)?;

        match msg_type {
            MessageType::HandshakeReq => Ok(Message::HandshakeReq(HandshakeRequest::from_bytes(
                &payload,
            )?)),
            MessageType::HandshakeResp => Ok(Message::HandshakeResp(
                HandshakeResponse::from_bytes(&payload)?,
            )),
            MessageType::IpPacket => Ok(Message::IpPacket(IpPacket::from_bytes(&payload)?)),
            MessageType::StatsUpdate => {
                Ok(Message::StatsUpdate(StatsUpdate::from_bytes(&payload)?))
            }
            MessageType::ConfigUpdate => {
                Ok(Message::ConfigUpdate(ConfigUpdate::from_bytes(&payload)?))
            }
            MessageType::Disconnect => Ok(Message::Disconnect(Disconnect::from_bytes(&payload)?)),
            MessageType::Keepalive => Ok(Message::Keepalive),
            MessageType::ErrorMsg => Ok(Message::Error(ErrorMessage::from_bytes(&payload)?)),
        }
    }

    /// Write a message to a writer
    pub fn write_to<W: Write>(&self, writer: &mut W) -> Result<(), ProtocolError> {
        let (msg_type, payload) = match self {
            Message::HandshakeReq(m) => (MessageType::HandshakeReq, m.to_bytes()),
            Message::HandshakeResp(m) => (MessageType::HandshakeResp, m.to_bytes()),
            Message::IpPacket(m) => (MessageType::IpPacket, m.to_bytes()),
            Message::StatsUpdate(m) => (MessageType::StatsUpdate, m.to_bytes()),
            Message::ConfigUpdate(m) => (MessageType::ConfigUpdate, m.to_bytes()),
            Message::Disconnect(m) => (MessageType::Disconnect, m.to_bytes()),
            Message::Keepalive => (MessageType::Keepalive, Vec::new()),
            Message::Error(m) => (MessageType::ErrorMsg, m.to_bytes()),
        };

        // Write header
        writer.write_all(&MAGIC.to_be_bytes())?;
        writer.write_all(&[msg_type as u8])?;
        writer.write_all(&(payload.len() as u16).to_be_bytes())?;

        // Write payload
        writer.write_all(&payload)?;

        Ok(())
    }
}

/// Handshake request
#[derive(Debug, Clone)]
pub struct HandshakeRequest {
    pub version: u16,
    pub capabilities: u32,
    pub token: Option<String>,
}

impl HandshakeRequest {
    pub fn new(capabilities: u32) -> Self {
        Self {
            version: PROTOCOL_VERSION,
            capabilities,
            token: None,
        }
    }

    pub fn with_token(capabilities: u32, token: String) -> Self {
        Self {
            version: PROTOCOL_VERSION,
            capabilities,
            token: Some(token),
        }
    }

    fn to_bytes(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(6);
        buf.extend_from_slice(&self.version.to_be_bytes());
        buf.extend_from_slice(&self.capabilities.to_be_bytes());

        // Add token if present
        if let Some(token) = &self.token {
            let token_bytes = token.as_bytes();
            buf.extend_from_slice(&(token_bytes.len() as u16).to_be_bytes());
            buf.extend_from_slice(token_bytes);
        } else {
            // No token - length = 0
            buf.extend_from_slice(&0u16.to_be_bytes());
        }

        buf
    }

    fn from_bytes(data: &[u8]) -> Result<Self, ProtocolError> {
        if data.len() < 6 {
            return Err(ProtocolError::MessageTooShort);
        }

        let version = u16::from_be_bytes([data[0], data[1]]);
        let capabilities = u32::from_be_bytes([data[2], data[3], data[4], data[5]]);

        // Check for optional token
        let token = if data.len() > 6 {
            if data.len() < 8 {
                return Err(ProtocolError::MessageTooShort);
            }
            let token_len = u16::from_be_bytes([data[6], data[7]]) as usize;
            if token_len > 0 {
                if data.len() < 8 + token_len {
                    return Err(ProtocolError::MessageTooShort);
                }
                let token_str = String::from_utf8(data[8..8 + token_len].to_vec())?;
                Some(token_str)
            } else {
                None
            }
        } else {
            None
        };

        Ok(Self {
            version,
            capabilities,
            token,
        })
    }
}

/// Handshake response
#[derive(Debug, Clone)]
pub struct HandshakeResponse {
    pub version: u16,
    pub capabilities: u32,
    pub ip_address: u32,
    pub dns: u32,
}

impl HandshakeResponse {
    fn to_bytes(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(14);
        buf.extend_from_slice(&self.version.to_be_bytes());
        buf.extend_from_slice(&self.capabilities.to_be_bytes());
        buf.extend_from_slice(&self.ip_address.to_be_bytes());
        buf.extend_from_slice(&self.dns.to_be_bytes());
        buf
    }

    fn from_bytes(data: &[u8]) -> Result<Self, ProtocolError> {
        if data.len() < 14 {
            return Err(ProtocolError::MessageTooShort);
        }

        Ok(Self {
            version: u16::from_be_bytes([data[0], data[1]]),
            capabilities: u32::from_be_bytes([data[2], data[3], data[4], data[5]]),
            ip_address: u32::from_be_bytes([data[6], data[7], data[8], data[9]]),
            dns: u32::from_be_bytes([data[10], data[11], data[12], data[13]]),
        })
    }

    /// Check if the server supports root mode
    pub fn supports_root(&self) -> bool {
        (self.capabilities & capabilities::ROOT) != 0
    }

    /// Check if root mode is currently active
    /// Root mode is active when CAP_ROOT is set AND ip_address is 0 (no VPN interface)
    pub fn is_root_mode_active(&self) -> bool {
        self.supports_root() && self.ip_address == 0
    }

    /// Check if the server supports TLS
    pub fn supports_tls(&self) -> bool {
        (self.capabilities & capabilities::TLS) != 0
    }
}

/// IP packet
#[derive(Debug, Clone)]
pub struct IpPacket {
    pub data: Vec<u8>,
}

impl IpPacket {
    pub fn new(data: Vec<u8>) -> Self {
        Self { data }
    }

    fn to_bytes(&self) -> Vec<u8> {
        self.data.clone()
    }

    fn from_bytes(data: &[u8]) -> Result<Self, ProtocolError> {
        Ok(Self {
            data: data.to_vec(),
        })
    }
}

/// Statistics update
#[derive(Debug, Clone)]
pub struct StatsUpdate {
    pub bytes_sent: u64,
    pub bytes_received: u64,
    pub uptime_seconds: u32,
}

impl StatsUpdate {
    fn to_bytes(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(20);
        buf.extend_from_slice(&self.bytes_sent.to_be_bytes());
        buf.extend_from_slice(&self.bytes_received.to_be_bytes());
        buf.extend_from_slice(&self.uptime_seconds.to_be_bytes());
        buf.extend_from_slice(&[0u8; 4]); // reserved
        buf
    }

    fn from_bytes(data: &[u8]) -> Result<Self, ProtocolError> {
        if data.len() < 20 {
            return Err(ProtocolError::MessageTooShort);
        }

        Ok(Self {
            bytes_sent: u64::from_be_bytes([
                data[0], data[1], data[2], data[3], data[4], data[5], data[6], data[7],
            ]),
            bytes_received: u64::from_be_bytes([
                data[8], data[9], data[10], data[11], data[12], data[13], data[14], data[15],
            ]),
            uptime_seconds: u32::from_be_bytes([data[16], data[17], data[18], data[19]]),
        })
    }
}

/// Configuration update
#[derive(Debug, Clone)]
pub struct ConfigUpdate {
    pub config: Vec<(String, String)>,
}

impl ConfigUpdate {
    fn to_bytes(&self) -> Vec<u8> {
        let config_str = self
            .config
            .iter()
            .map(|(k, v)| format!("{}={}", k, v))
            .collect::<Vec<_>>()
            .join("\n");
        config_str.into_bytes()
    }

    fn from_bytes(data: &[u8]) -> Result<Self, ProtocolError> {
        let config_str = String::from_utf8(data.to_vec())?;
        let config = config_str
            .lines()
            .filter(|line| !line.is_empty())
            .filter_map(|line| {
                let parts: Vec<&str> = line.splitn(2, '=').collect();
                if parts.len() == 2 {
                    Some((parts[0].to_string(), parts[1].to_string()))
                } else {
                    None
                }
            })
            .collect();

        Ok(Self { config })
    }
}

/// Disconnect message
#[derive(Debug, Clone)]
pub struct Disconnect {
    pub reason: String,
}

impl Disconnect {
    pub fn new(reason: impl Into<String>) -> Self {
        Self {
            reason: reason.into(),
        }
    }

    fn to_bytes(&self) -> Vec<u8> {
        self.reason.as_bytes().to_vec()
    }

    fn from_bytes(data: &[u8]) -> Result<Self, ProtocolError> {
        Ok(Self {
            reason: String::from_utf8(data.to_vec())?,
        })
    }
}

/// Error message
#[derive(Debug, Clone)]
pub struct ErrorMessage {
    pub error_code: ErrorCode,
    pub message: String,
}

impl ErrorMessage {
    fn to_bytes(&self) -> Vec<u8> {
        let msg_bytes = self.message.as_bytes();
        let mut buf = Vec::with_capacity(3 + msg_bytes.len());
        buf.push(self.error_code as u8);
        buf.extend_from_slice(&(msg_bytes.len() as u16).to_be_bytes());
        buf.extend_from_slice(msg_bytes);
        buf
    }

    fn from_bytes(data: &[u8]) -> Result<Self, ProtocolError> {
        if data.len() < 3 {
            return Err(ProtocolError::MessageTooShort);
        }

        let error_code = match data[0] {
            0x01 => ErrorCode::ProtocolVersion,
            0x02 => ErrorCode::InvalidMessage,
            0x03 => ErrorCode::AuthFailed,
            0x04 => ErrorCode::Timeout,
            0x05 => ErrorCode::InternalError,
            _ => ErrorCode::InternalError,
        };

        let msg_len = u16::from_be_bytes([data[1], data[2]]) as usize;
        // msg_len is attacker-controlled (it comes straight off the wire).
        // Without this bound check, a malformed/truncated ErrorMsg whose
        // declared length exceeds the actual payload causes `data[3..3 +
        // msg_len]` to panic with a slice-index-out-of-bounds, crashing
        // whichever side (client or server) parses it -- a trivial remote
        // DoS. HandshakeRequest::from_bytes already guards the equivalent
        // length-prefixed token field this same way.
        if data.len() < 3 + msg_len {
            return Err(ProtocolError::MessageTooShort);
        }
        let message = String::from_utf8(data[3..3 + msg_len].to_vec())?;

        Ok(Self {
            error_code,
            message,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_handshake_request_roundtrip() {
        let req = HandshakeRequest::new(capabilities::USB | capabilities::WIFI);
        let bytes = req.to_bytes();
        let parsed = HandshakeRequest::from_bytes(&bytes).unwrap();
        assert_eq!(req.version, parsed.version);
        assert_eq!(req.capabilities, parsed.capabilities);
    }

    #[test]
    fn test_message_write_read() {
        let msg = Message::HandshakeReq(HandshakeRequest::new(capabilities::USB));
        let mut buf = Vec::new();
        msg.write_to(&mut buf).unwrap();

        let parsed = Message::read_from(&mut &buf[..]).unwrap();
        match parsed {
            Message::HandshakeReq(req) => {
                assert_eq!(req.version, PROTOCOL_VERSION);
            }
            _ => panic!("Wrong message type"),
        }
    }

    #[test]
    fn test_ip_packet_roundtrip() {
        let data = vec![0x45, 0x00, 0x00, 0x54];
        let packet = IpPacket::new(data.clone());
        let bytes = packet.to_bytes();
        let parsed = IpPacket::from_bytes(&bytes).unwrap();
        assert_eq!(packet.data, parsed.data);
    }

    #[test]
    fn test_invalid_magic() {
        let bad_data = [0xFF, 0xFF, 0x01, 0x00, 0x00];
        let result = Message::read_from(&mut &bad_data[..]);
        assert!(result.is_err());
    }

    #[test]
    fn test_version_compatible_exact_match() {
        assert!(is_version_compatible(PROTOCOL_VERSION));
    }

    #[test]
    fn test_version_compatible_same_major_different_minor() {
        // Same major (0x01), different minor byte should still be compatible.
        assert!(is_version_compatible(0x0105));
        assert!(is_version_compatible(0x0100));
    }

    #[test]
    fn test_version_incompatible_different_major() {
        assert!(!is_version_compatible(0x0200));
        assert!(!is_version_compatible(0x0000));
    }

    #[test]
    fn test_error_message_roundtrip() {
        let msg = ErrorMessage {
            error_code: ErrorCode::AuthFailed,
            message: "nope".to_string(),
        };
        let bytes = msg.to_bytes();
        let parsed = ErrorMessage::from_bytes(&bytes).unwrap();
        assert_eq!(parsed.message, "nope");
    }

    #[test]
    fn test_error_message_truncated_length_is_rejected_not_panicking() {
        // Declares a 100-byte message but only supplies 4 bytes of payload.
        // Before the bounds check this panicked with a slice-index
        // out-of-bounds instead of returning an error -- a remote DoS via
        // any malformed/truncated ErrorMsg.
        let mut bad_data = vec![ErrorCode::InternalError as u8];
        bad_data.extend_from_slice(&100u16.to_be_bytes());
        bad_data.extend_from_slice(b"hi");

        let result = ErrorMessage::from_bytes(&bad_data);
        assert!(matches!(result, Err(ProtocolError::MessageTooShort)));
    }
}
