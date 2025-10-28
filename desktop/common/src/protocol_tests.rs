#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::*;

    #[test]
    fn test_handshake_request_serialization() {
        let req = HandshakeRequest::new(capabilities::USB | capabilities::WIFI);
        let bytes = req.to_bytes();

        assert_eq!(bytes.len(), 6);
        assert_eq!(u16::from_be_bytes([bytes[0], bytes[1]]), PROTOCOL_VERSION);
    }

    #[test]
    fn test_handshake_request_roundtrip() {
        let original = HandshakeRequest::new(capabilities::USB | capabilities::WIFI);
        let bytes = original.to_bytes();
        let parsed = HandshakeRequest::from_bytes(&bytes).unwrap();

        assert_eq!(original.version, parsed.version);
        assert_eq!(original.capabilities, parsed.capabilities);
    }

    #[test]
    fn test_handshake_response_roundtrip() {
        let original = HandshakeResponse {
            version: PROTOCOL_VERSION,
            capabilities: capabilities::USB | capabilities::ROOT,
            ip_address: 0x0A000002, // 10.0.0.2
            dns: 0x08080808,         // 8.8.8.8
        };

        let bytes = original.to_bytes();
        let parsed = HandshakeResponse::from_bytes(&bytes).unwrap();

        assert_eq!(original.version, parsed.version);
        assert_eq!(original.capabilities, parsed.capabilities);
        assert_eq!(original.ip_address, parsed.ip_address);
        assert_eq!(original.dns, parsed.dns);
    }

    #[test]
    fn test_ip_packet_roundtrip() {
        let data = vec![0x45, 0x00, 0x00, 0x54, 0x12, 0x34];
        let original = IpPacket::new(data.clone());

        let bytes = original.to_bytes();
        let parsed = IpPacket::from_bytes(&bytes).unwrap();

        assert_eq!(original.data, parsed.data);
    }

    #[test]
    fn test_stats_update_roundtrip() {
        let original = StatsUpdate {
            bytes_sent: 1234567890,
            bytes_received: 9876543210,
            uptime_seconds: 3600,
        };

        let bytes = original.to_bytes();
        let parsed = StatsUpdate::from_bytes(&bytes).unwrap();

        assert_eq!(original.bytes_sent, parsed.bytes_sent);
        assert_eq!(original.bytes_received, parsed.bytes_received);
        assert_eq!(original.uptime_seconds, parsed.uptime_seconds);
    }

    #[test]
    fn test_disconnect_roundtrip() {
        let original = Disconnect::new("User requested disconnect");

        let bytes = original.to_bytes();
        let parsed = Disconnect::from_bytes(&bytes).unwrap();

        assert_eq!(original.reason, parsed.reason);
    }

    #[test]
    fn test_config_update_roundtrip() {
        let mut config = vec![
            ("key1".to_string(), "value1".to_string()),
            ("key2".to_string(), "value2".to_string()),
        ];
        let original = ConfigUpdate { config: config.clone() };

        let bytes = original.to_bytes();
        let parsed = ConfigUpdate::from_bytes(&bytes).unwrap();

        assert_eq!(original.config.len(), parsed.config.len());
        for (k, v) in &original.config {
            assert!(parsed.config.iter().any(|(pk, pv)| pk == k && pv == v));
        }
    }

    #[test]
    fn test_message_with_header() {
        let req = HandshakeRequest::new(capabilities::USB);
        let msg = Message::HandshakeReq(req);

        let mut buffer = Vec::new();
        msg.write_to(&mut buffer).unwrap();

        // Check magic bytes
        assert_eq!(buffer[0..2], MAGIC.to_be_bytes());

        // Check message type
        assert_eq!(buffer[2], MessageType::HandshakeReq as u8);

        // Parse back
        let parsed = Message::read_from(&mut &buffer[..]).unwrap();
        match parsed {
            Message::HandshakeReq(_) => (),
            _ => panic!("Wrong message type parsed"),
        }
    }

    #[test]
    fn test_invalid_magic_bytes() {
        let mut buffer = vec![0xFF, 0xFF, 0x01, 0x00, 0x00]; // Wrong magic
        let result = Message::read_from(&mut &buffer[..]);

        assert!(result.is_err());
        match result {
            Err(ProtocolError::InvalidMagic) => (),
            _ => panic!("Expected InvalidMagic error"),
        }
    }

    #[test]
    fn test_message_too_short() {
        let buffer = vec![0x45, 0x54, 0x01]; // Only 3 bytes
        let result = Message::read_from(&mut &buffer[..]);

        assert!(result.is_err());
    }

    #[test]
    fn test_unknown_message_type() {
        let buffer = vec![
            0x45, 0x54, // Magic
            0xFF,       // Unknown type
            0x00, 0x00  // Length = 0
        ];
        let result = Message::read_from(&mut &buffer[..]);

        assert!(result.is_err());
        match result {
            Err(ProtocolError::UnknownMessageType(0xFF)) => (),
            _ => panic!("Expected UnknownMessageType error"),
        }
    }

    #[test]
    fn test_all_message_types() {
        let messages = vec![
            Message::HandshakeReq(HandshakeRequest::new(capabilities::USB)),
            Message::HandshakeResp(HandshakeResponse {
                version: PROTOCOL_VERSION,
                capabilities: capabilities::USB,
                ip_address: 0x0A000002,
                dns: 0x08080808,
            }),
            Message::IpPacket(IpPacket::new(vec![1, 2, 3, 4])),
            Message::StatsUpdate(StatsUpdate {
                bytes_sent: 100,
                bytes_received: 200,
                uptime_seconds: 60,
            }),
            Message::ConfigUpdate(ConfigUpdate {
                config: vec![("test".to_string(), "value".to_string())],
            }),
            Message::Disconnect(Disconnect::new("test")),
            Message::Keepalive,
        ];

        for msg in messages {
            let mut buffer = Vec::new();
            msg.write_to(&mut buffer).unwrap();
            let parsed = Message::read_from(&mut &buffer[..]).unwrap();

            // Just verify it parses without panicking
            match (&msg, &parsed) {
                (Message::HandshakeReq(_), Message::HandshakeReq(_)) => (),
                (Message::HandshakeResp(_), Message::HandshakeResp(_)) => (),
                (Message::IpPacket(_), Message::IpPacket(_)) => (),
                (Message::StatsUpdate(_), Message::StatsUpdate(_)) => (),
                (Message::ConfigUpdate(_), Message::ConfigUpdate(_)) => (),
                (Message::Disconnect(_), Message::Disconnect(_)) => (),
                (Message::Keepalive, Message::Keepalive) => (),
                _ => panic!("Message type mismatch"),
            }
        }
    }

    #[test]
    fn test_capabilities_flags() {
        assert_eq!(capabilities::USB, 1 << 0);
        assert_eq!(capabilities::WIFI, 1 << 1);
        assert_eq!(capabilities::BLUETOOTH, 1 << 2);
        assert_eq!(capabilities::ROOT, 1 << 3);
        assert_eq!(capabilities::IPV6, 1 << 4);

        // Test combining flags
        let combined = capabilities::USB | capabilities::WIFI | capabilities::ROOT;
        assert_eq!(combined, 0b01101);
    }
}
