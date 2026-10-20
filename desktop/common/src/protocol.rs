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
    /// The phone is on the free version and this session needs ETPH Pro on the
    /// PHONE (Wi-Fi/Bluetooth, a router, or a second computer). Never a licence
    /// on this computer or router: the message says what to do on the phone.
    ProRequired = 0x06,
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
    /// Request: this client is a router (OpenWrt `proto etph` uplink). Routers need ETPH Pro on the phone.
    pub const ROUTER: u32 = 1 << 7;
    /// Response: the phone has ETPH Pro.
    pub const PRO: u32 = 1 << 8;
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

    #[error("Payload too large: {0} bytes exceeds the 65535-byte wire-format limit")]
    PayloadTooLarge(usize),

    /// The peer answered with an `ErrorMsg` (e.g. Android rejecting a wrong
    /// pairing token with `AuthFailed`). Previously every such rejection was
    /// reported as a bare "Unexpected message type", hiding the reason.
    #[error("Android rejected the connection ({code:?}): {message}")]
    PeerError { code: ErrorCode, message: String },
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

        // The wire header's length field is a u16. Without this check, a
        // payload over 65535 bytes was silently truncated by the `as u16`
        // cast below: the header would declare e.g. `len % 65536` bytes while
        // the full payload was still written, so the peer would read only a
        // prefix and then parse the remaining payload bytes as the next
        // message header -- desyncing the entire stream rather than failing
        // cleanly. Reject before writing anything so no partial frame is
        // ever emitted. (This also covers the nested u16 length prefixes in
        // HandshakeRequest's token and ErrorMessage's text, which can only
        // overflow if the whole payload does.)
        let payload_len = u16::try_from(payload.len())
            .map_err(|_| ProtocolError::PayloadTooLarge(payload.len()))?;

        // Write header
        writer.write_all(&MAGIC.to_be_bytes())?;
        writer.write_all(&[msg_type as u8])?;
        writer.write_all(&payload_len.to_be_bytes())?;

        // Write payload
        writer.write_all(&payload)?;

        Ok(())
    }
}

/// Handshake request
///
/// Since 0.9.0 the client appends who it is after the token field: a u16
/// length and the UTF-8 client id (a random id kept in the config dir, so the
/// phone can tell "the same computer again" from "another computer"), then a
/// u16 length and the UTF-8 client name (the hostname, shown in the phone's
/// device list). Older phones read only up to the token and ignore the rest.
#[derive(Debug, Clone)]
pub struct HandshakeRequest {
    pub version: u16,
    pub capabilities: u32,
    pub token: Option<String>,
    pub client_id: Option<String>,
    pub client_name: Option<String>,
}

/// Longest client id / client name sent or accepted (bytes).
pub const MAX_CLIENT_FIELD: usize = 64;

fn truncate_utf8(s: &str, max: usize) -> &str {
    let mut end = s.len().min(max);
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    &s[..end]
}

impl HandshakeRequest {
    pub fn new(capabilities: u32) -> Self {
        Self {
            version: PROTOCOL_VERSION,
            capabilities,
            token: None,
            client_id: None,
            client_name: None,
        }
    }

    pub fn with_token(capabilities: u32, token: String) -> Self {
        Self {
            token: Some(token),
            ..Self::new(capabilities)
        }
    }

    /// The request this client sends: its identity (id, hostname) and the
    /// ROUTER capability when it runs as a router uplink.
    pub fn for_this_client(capabilities: u32, token: Option<String>) -> Self {
        Self::for_identity(capabilities, token, &crate::identity::current())
    }

    /// [`Self::for_this_client`] for a given identity.
    pub fn for_identity(
        capabilities: u32,
        token: Option<String>,
        id: &crate::identity::ClientIdentity,
    ) -> Self {
        Self {
            version: PROTOCOL_VERSION,
            capabilities: capabilities | if id.router { capabilities::ROUTER } else { 0 },
            token,
            client_id: Some(id.id.clone()),
            client_name: Some(id.name.clone()),
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

        if self.client_id.is_some() || self.client_name.is_some() {
            for field in [&self.client_id, &self.client_name] {
                let v = truncate_utf8(field.as_deref().unwrap_or(""), MAX_CLIENT_FIELD).as_bytes();
                buf.extend_from_slice(&(v.len() as u16).to_be_bytes());
                buf.extend_from_slice(v);
            }
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
        let mut rest: &[u8] = &[];
        let token = if data.len() > 6 {
            if data.len() < 8 {
                return Err(ProtocolError::MessageTooShort);
            }
            let token_len = u16::from_be_bytes([data[6], data[7]]) as usize;
            if data.len() < 8 + token_len {
                return Err(ProtocolError::MessageTooShort);
            }
            rest = &data[8 + token_len..];
            if token_len > 0 {
                Some(String::from_utf8(data[8..8 + token_len].to_vec())?)
            } else {
                None
            }
        } else {
            None
        };

        // Optional client id and name: best effort, never fail the handshake.
        fn field(rest: &mut &[u8]) -> Option<String> {
            if rest.len() < 2 {
                return None;
            }
            let n = u16::from_be_bytes([rest[0], rest[1]]) as usize;
            if rest.len() < 2 + n {
                *rest = &[];
                return None;
            }
            let v = &rest[2..2 + n];
            *rest = &rest[2 + n..];
            (n > 0 && n <= MAX_CLIENT_FIELD)
                .then(|| std::str::from_utf8(v).ok())
                .flatten()
                .map(|s| {
                    s.chars()
                        .filter(|c| !c.is_control())
                        .collect::<String>()
                        .trim()
                        .to_string()
                })
                .filter(|s| !s.is_empty())
        }
        let client_id = field(&mut rest);
        let client_name = field(&mut rest);

        Ok(Self {
            version,
            capabilities,
            token,
            client_id,
            client_name,
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
    /// The phone's name ("samsung SM-N986U"), sent by app 0.8.0+ after the
    /// 14 fixed bytes as u16 length + UTF-8. Older peers ignore trailing
    /// bytes, and older phones simply don't send it.
    pub device_name: Option<String>,
}

/// Longest device name sent or accepted (bytes).
pub const MAX_DEVICE_NAME: usize = 64;

impl HandshakeResponse {
    fn to_bytes(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(14);
        buf.extend_from_slice(&self.version.to_be_bytes());
        buf.extend_from_slice(&self.capabilities.to_be_bytes());
        buf.extend_from_slice(&self.ip_address.to_be_bytes());
        buf.extend_from_slice(&self.dns.to_be_bytes());
        if let Some(name) = &self.device_name {
            let mut end = name.len().min(MAX_DEVICE_NAME);
            while !name.is_char_boundary(end) {
                end -= 1;
            }
            let bytes = &name.as_bytes()[..end];
            buf.extend_from_slice(&(bytes.len() as u16).to_be_bytes());
            buf.extend_from_slice(bytes);
        }
        buf
    }

    fn from_bytes(data: &[u8]) -> Result<Self, ProtocolError> {
        if data.len() < 14 {
            return Err(ProtocolError::MessageTooShort);
        }
        // Optional, best effort: a malformed name never fails the handshake.
        let device_name = (data.len() >= 16)
            .then(|| u16::from_be_bytes([data[14], data[15]]) as usize)
            .filter(|n| *n > 0 && *n <= MAX_DEVICE_NAME && data.len() >= 16 + n)
            .and_then(|n| std::str::from_utf8(&data[16..16 + n]).ok())
            .map(|s| {
                s.chars()
                    .filter(|c| !c.is_control())
                    .collect::<String>()
                    .trim()
                    .to_string()
            })
            .filter(|s| !s.is_empty());

        Ok(Self {
            version: u16::from_be_bytes([data[0], data[1]]),
            capabilities: u32::from_be_bytes([data[2], data[3], data[4], data[5]]),
            ip_address: u32::from_be_bytes([data[6], data[7], data[8], data[9]]),
            dns: u32::from_be_bytes([data[10], data[11], data[12], data[13]]),
            device_name,
        })
    }

    /// Whether the phone has ETPH Pro (Wi-Fi/Bluetooth, routers, several computers).
    pub fn phone_has_pro(&self) -> bool {
        (self.capabilities & capabilities::PRO) != 0
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
            0x06 => ErrorCode::ProRequired,
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

// Extra protocol tests live in protocol_tests.rs. That file existed for a long
// time without being referenced by any `mod` declaration, so its 13 tests were
// never compiled or run by `cargo test`; it is a child module here so it can
// reach the private to_bytes/from_bytes helpers it exercises.
#[cfg(test)]
#[path = "protocol_tests.rs"]
mod protocol_tests;

#[cfg(test)]
#[path = "protocol_vectors.rs"]
mod protocol_vectors;

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
    fn test_oversized_payload_is_rejected_without_writing_partial_frame() {
        let msg = Message::IpPacket(IpPacket::new(vec![0u8; u16::MAX as usize + 1]));
        let mut buf = Vec::new();
        match msg.write_to(&mut buf) {
            Err(ProtocolError::PayloadTooLarge(n)) => assert_eq!(n, u16::MAX as usize + 1),
            other => panic!("expected PayloadTooLarge, got {other:?}"),
        }
        assert!(
            buf.is_empty(),
            "no bytes may be written for a rejected frame"
        );
    }

    #[test]
    fn test_max_size_payload_roundtrips() {
        let data = vec![0xABu8; u16::MAX as usize];
        let msg = Message::IpPacket(IpPacket::new(data.clone()));
        let mut buf = Vec::new();
        msg.write_to(&mut buf).unwrap();
        match Message::read_from(&mut &buf[..]).unwrap() {
            Message::IpPacket(pkt) => assert_eq!(pkt.data, data),
            _ => panic!("Wrong message type"),
        }
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
