//! Golden wire-format vectors shared with the Android app's
//! `ProtocolVectorsTest.kt` (protocol/test-vectors.txt). If either side
//! changes how a message is laid out on the wire, its own test fails.

use super::*;
use std::collections::HashMap;

fn vectors() -> HashMap<String, Vec<u8>> {
    include_str!("../../../protocol/test-vectors.txt")
        .lines()
        .filter(|l| !l.trim().is_empty() && !l.starts_with('#'))
        .map(|l| {
            let (name, hex) = l.split_once(' ').expect("name hex");
            let bytes = (0..hex.len())
                .step_by(2)
                .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
                .collect();
            (name.to_string(), bytes)
        })
        .collect()
}

fn encode(m: &Message) -> Vec<u8> {
    let mut b = Vec::new();
    m.write_to(&mut b).unwrap();
    b
}

fn decode(b: &[u8]) -> Message {
    Message::read_from(&mut &b[..]).unwrap()
}

#[test]
fn every_message_matches_the_shared_vectors() {
    let v = vectors();
    assert_eq!(v.len(), 13, "vector file changed; update both test suites");

    let req = Message::HandshakeReq(HandshakeRequest::with_token(
        capabilities::WIFI,
        "AbC123XyZ4567890".into(),
    ));
    assert_eq!(encode(&req), v["handshake_req_token"]);
    match decode(&v["handshake_req_token"]) {
        Message::HandshakeReq(r) => {
            assert_eq!(r.version, PROTOCOL_VERSION);
            assert_eq!(r.capabilities, capabilities::WIFI);
            assert_eq!(r.token.as_deref(), Some("AbC123XyZ4567890"));
        }
        m => panic!("{:?}", m),
    }

    let req = Message::HandshakeReq(HandshakeRequest::new(capabilities::USB));
    assert_eq!(encode(&req), v["handshake_req_no_token"]);
    match decode(&v["handshake_req_no_token"]) {
        Message::HandshakeReq(r) => assert_eq!(r.token, None),
        m => panic!("{:?}", m),
    }

    let resp = Message::HandshakeResp(HandshakeResponse {
        version: PROTOCOL_VERSION,
        capabilities: capabilities::USB | capabilities::WIFI,
        ip_address: 0x0a000002,
        dns: 0x08080808,
        device_name: None,
    });
    assert_eq!(encode(&resp), v["handshake_resp"]);
    match decode(&v["handshake_resp"]) {
        Message::HandshakeResp(r) => {
            assert_eq!(r.capabilities, 3);
            assert_eq!(r.ip_address, 0x0a000002);
            assert_eq!(r.dns, 0x08080808);
        }
        m => panic!("{:?}", m),
    }
    // 0.8.0: the phone may append its name; old desktops read the first
    // 14 bytes and ignore the rest (the plain vector still decodes to None).
    let resp_dev = Message::HandshakeResp(HandshakeResponse {
        version: PROTOCOL_VERSION,
        capabilities: capabilities::USB | capabilities::WIFI,
        ip_address: 0x0a000002,
        dns: 0x08080808,
        device_name: Some("Pixel 8".into()),
    });
    assert_eq!(encode(&resp_dev), v["handshake_resp_device"]);
    match decode(&v["handshake_resp_device"]) {
        Message::HandshakeResp(r) => {
            assert_eq!(r.device_name.as_deref(), Some("Pixel 8"));
            assert_eq!(r.dns, 0x08080808);
        }
        m => panic!("{:?}", m),
    }
    match decode(&v["handshake_resp"]) {
        Message::HandshakeResp(r) => assert_eq!(r.device_name, None),
        m => panic!("{:?}", m),
    }

    let pkt: Vec<u8> = v["ip_packet"][5..].to_vec();
    assert_eq!(
        encode(&Message::IpPacket(IpPacket::new(pkt.clone()))),
        v["ip_packet"]
    );
    match decode(&v["ip_packet"]) {
        Message::IpPacket(p) => assert_eq!(p.data, pkt),
        m => panic!("{:?}", m),
    }

    let stats = Message::StatsUpdate(StatsUpdate {
        bytes_sent: 1234567890,
        bytes_received: 9876543210,
        uptime_seconds: 3600,
    });
    assert_eq!(encode(&stats), v["stats_update"]);
    match decode(&v["stats_update"]) {
        Message::StatsUpdate(s) => {
            assert_eq!(s.bytes_sent, 1234567890);
            assert_eq!(s.bytes_received, 9876543210);
            assert_eq!(s.uptime_seconds, 3600);
        }
        m => panic!("{:?}", m),
    }

    let cfg = Message::ConfigUpdate(ConfigUpdate {
        config: vec![("filter.enabled".into(), "true".into())],
    });
    assert_eq!(encode(&cfg), v["config_update"]);

    assert_eq!(
        encode(&Message::Disconnect(Disconnect::new("bye"))),
        v["disconnect"]
    );
    assert_eq!(encode(&Message::Keepalive), v["keepalive"]);
    assert!(matches!(decode(&v["keepalive"]), Message::Keepalive));

    let err = Message::Error(ErrorMessage {
        error_code: ErrorCode::AuthFailed,
        message: "nope!".into(),
    });
    assert_eq!(encode(&err), v["error_auth_failed"]);
    match decode(&v["error_auth_failed"]) {
        Message::Error(e) => {
            assert!(matches!(e.error_code, ErrorCode::AuthFailed));
            assert_eq!(e.message, "nope!");
        }
        m => panic!("{:?}", m),
    }
}

#[test]
fn pro_vectors_client_identity_pro_flag_and_pro_required() {
    let v = vectors();
    let req = Message::HandshakeReq(HandshakeRequest {
        client_id: Some("c0ffee".into()),
        client_name: Some("laptop".into()),
        ..HandshakeRequest::new(capabilities::USB | capabilities::ROUTER)
    });
    assert_eq!(encode(&req), v["handshake_req_identity"]);
    match decode(&v["handshake_req_identity"]) {
        Message::HandshakeReq(r) => {
            assert_eq!(r.token, None);
            assert_eq!(r.client_id.as_deref(), Some("c0ffee"));
            assert_eq!(r.client_name.as_deref(), Some("laptop"));
            assert_ne!(r.capabilities & capabilities::ROUTER, 0);
        }
        m => panic!("{:?}", m),
    }
    let resp = Message::HandshakeResp(HandshakeResponse {
        version: PROTOCOL_VERSION,
        capabilities: capabilities::USB | capabilities::PRO,
        ip_address: 0x0a000002,
        dns: 0x08080808,
        device_name: None,
    });
    assert_eq!(encode(&resp), v["handshake_resp_pro"]);
    match decode(&v["handshake_resp_pro"]) {
        Message::HandshakeResp(r) => assert!(r.phone_has_pro()),
        m => panic!("{:?}", m),
    }
    let err = Message::Error(ErrorMessage {
        error_code: ErrorCode::ProRequired,
        message: "pro".into(),
    });
    assert_eq!(encode(&err), v["error_pro_required"]);
    match decode(&v["error_pro_required"]) {
        Message::Error(e) => assert!(matches!(e.error_code, ErrorCode::ProRequired)),
        m => panic!("{:?}", m),
    }
    // A phone without Pro answers without the flag.
    match decode(&v["handshake_resp"]) {
        Message::HandshakeResp(r) => assert!(!r.phone_has_pro()),
        m => panic!("{:?}", m),
    }
}
