# ET Phone Home Protocol Specification

## Overview

This document describes the communication protocol between the Android app and desktop companion apps.

## Protocol Design Goals

1. Simple and efficient binary protocol
2. Support for multiple connection types (USB, WiFi, Bluetooth)
3. Minimal overhead
4. Support for connection status and statistics

## Message Format

All messages use a simple binary format with a header followed by payload:

```
+--------+--------+----------+----------------+
| Magic  | Type   | Length   | Payload        |
| 2 bytes| 1 byte | 2 bytes  | N bytes        |
+--------+--------+----------+----------------+
```

### Header Fields

- **Magic** (2 bytes): `0x4554` ('ET' in ASCII) - Protocol identifier
- **Type** (1 byte): Message type (see below)
- **Length** (2 bytes, big-endian): Payload length in bytes

### Message Types

| Type | Name                | Direction    | Description                           |
|------|---------------------|--------------|---------------------------------------|
| 0x01 | HANDSHAKE_REQ       | Desktop → Android | Initial connection handshake       |
| 0x02 | HANDSHAKE_RESP      | Android → Desktop | Handshake response                 |
| 0x03 | IP_PACKET           | Bidirectional | IPv4 or IPv6 packet                  |
| 0x04 | STATS_UPDATE        | Android → Desktop | Connection statistics              |
| 0x05 | CONFIG_UPDATE       | Desktop → Android | Configuration changes              |
| 0x06 | DISCONNECT          | Bidirectional | Graceful disconnect                  |
| 0x07 | KEEPALIVE           | Bidirectional | Keep connection alive                |
| 0x08 | ERROR               | Bidirectional | Error notification                   |

## Connection Flow

### 1. Initial Handshake

```
Desktop                                    Android
   |                                          |
   |------- HANDSHAKE_REQ ------------------>|
   |  (version, capabilities)                |
   |                                          |
   |<------ HANDSHAKE_RESP -------------------|
   |  (version, capabilities, assigned IP)   |
   |                                          |
```

#### HANDSHAKE_REQ Payload
```
+----------+---------------+--------------+----------------+
| Version  | Capabilities  | Token Length | Token (UTF-8)  |
| 2 bytes  | 4 bytes       | 2 bytes      | N bytes        |
+----------+---------------+--------------+----------------+
```
Token Length is 0 when no token is sent (USB). WiFi and Bluetooth
require the token shown in the Android app; a wrong or missing token is
answered with an ERROR message (code 0x03, AUTH_FAILED) and the connection is
closed.

Since 0.9.0 the client appends who it is after the token (optional; phones
before 0.9.0 stop reading after the token):

```
+-----------+-------------------+-------------+---------------------+
| Id Length | Client id (UTF-8) | Name Length | Client name (UTF-8) |
| 2 bytes   | N bytes (<= 64)   | 2 bytes     | N bytes (<= 64)     |
+-----------+-------------------+-------------+---------------------+
```
The client id is a random id the client keeps (so the phone can tell the same
computer from another one); the name is its host name. A client running as a
router's uplink sets capability bit 7 (ROUTER). The phone (ETPH Pro gate, after
the token check) may answer ERROR 0x06 PRO_REQUIRED, whose text starts with
"Pro required on the phone: ". The HANDSHAKE_RESP capabilities carry bit 8
(PRO) when the phone has ETPH Pro. See [docs/pro.md](../docs/pro.md).

| Capability bit | Name | Meaning |
|---|---|---|
| 0 | USB | |
| 1 | WIFI | |
| 2 | BLUETOOTH | |
| 3 | ROOT | (unused) |
| 4 | IPV6 | (unused) |
| 5 | TLS | |
| 6 | TLS_PSK | |
| 7 | ROUTER | request: the client is a router uplink |
| 8 | PRO | response: the phone has ETPH Pro |

Byte-exact examples of every message type are in
[`test-vectors.txt`](test-vectors.txt); both the Rust and the Kotlin test
suites check their encoders and decoders against that file.

#### HANDSHAKE_RESP Payload
```
+----------+---------------+------------+-----------+-------------+---------------+
| Version  | Capabilities  | IP Address | DNS       | Name Length | Name (UTF-8)  |
| 2 bytes  | 4 bytes       | 4 bytes    | 4 bytes   | 2 bytes     | N bytes       |
+----------+---------------+------------+-----------+-------------+---------------+
```
The device name (app 0.8.0+, e.g. `samsung SM-N986U`, at most 64 bytes) is
optional: phones before 0.8.0 send only the first 14 bytes, and desktops
before 0.8.0 read only those and ignore the rest. A receiver ignores a
malformed name (bad length, not UTF-8) instead of failing the handshake.

### 2. Data Transfer

Once connected, IP packets are wrapped in `IP_PACKET` messages:

```
Desktop                                    Android
   |                                          |
   |------- IP_PACKET ----------------------->|
   |  (outbound packet from desktop)         |
   |                                          |
   |<------ IP_PACKET ------------------------|
   |  (inbound packet to desktop)            |
   |                                          |
```

### 3. Statistics Updates

Android periodically sends statistics:

```
+------------+------------+------------+------------+
| Bytes Sent | Bytes Recv | Time       | Reserved   |
| 8 bytes    | 8 bytes    | 4 bytes    | 4 bytes    |
+------------+------------+------------+------------+
```

### 4. Graceful Disconnect

Either side can initiate disconnect:

```
Desktop/Android                            Android/Desktop
   |                                          |
   |------- DISCONNECT ---------------------->|
   |  (reason code)                          |
   |                                          |
   |<------ DISCONNECT ------------------------|
   |  (acknowledgment)                       |
   |                                          |
```

## Transport Layers

### USB (Primary Method)

Uses ADB port forwarding:
```bash
adb forward tcp:8888 tcp:8888
```

Android app listens on localhost:8888, desktop connects to localhost:8888 (forwarded through ADB).

### WiFi

Android app creates a server socket on a specified port, desktop connects directly to Android device IP.

### Bluetooth

Uses Bluetooth RFCOMM sockets with a predefined UUID.

## Error Handling

### Error Codes

| Code | Name                | Description                           |
|------|---------------------|---------------------------------------|
| 0x01 | PROTOCOL_VERSION    | Incompatible protocol versions        |
| 0x02 | INVALID_MESSAGE     | Malformed message                     |
| 0x03 | AUTH_FAILED         | Authentication failed                 |
| 0x04 | TIMEOUT             | Operation timed out                   |
| 0x05 | INTERNAL_ERROR      | Internal error occurred               |
| 0x06 | PRO_REQUIRED        | This needs ETPH Pro on the phone (0.9.0+) |

### Error Message Payload

```
+------------+------------------+
| Error Code | Message Length   | Error Message    |
| 1 byte     | 2 bytes          | N bytes (UTF-8)  |
+------------+------------------+
```

## Security Considerations

1. **USB**: the app's port is on the phone's loopback, reachable only via `adb forward`
2. **Authentication**: WiFi and Bluetooth require the token shown in the app
3. **Encryption (WiFi)**: the whole byte stream can run inside TLS 1.3. The
   phone tells TLS from plaintext by the first byte (0x16 = TLS handshake,
   0x45 = this protocol's magic). Both peers derive an Ed25519 key pair from
   the pairing token (HKDF-SHA256, salt `ETPhoneHome-TLS-Salt`, info
   `etphonehome-certpin-identity-v1`), present self-signed certificates for
   it (mutual TLS) and accept only the peer certificate carrying that key.
   The app refuses plaintext WiFi unless its "Require encryption" switch is off.
4. **Traffic filter (CONFIG_UPDATE)**: right after the handshake the desktop
   sends `filter.enabled`, `filter.mode` (whitelist|blacklist),
   `filter.log_blocked`, `filter.rules=<n>` and `filter.rule.<i>=
   id|enabled|action|protocol|dest_ip|dest_port|source_ip|source_port`
   (empty field = any); the phone enforces it on the connections it relays.
5. **Liveness (KEEPALIVE)**: the phone sends a STATS_UPDATE every 5 s and a
   KEEPALIVE every 30 s; the desktop ends the session after 30 s with nothing
   from the phone. The desktop (0.6.1+) sends a KEEPALIVE right after its
   CONFIG_UPDATE and then every 10 s; once the phone has seen one it ends the
   session after 30 s of silence from the desktop, so a desktop that drops off
   the network without closing the connection (WiFi loss, lid closed) does not
   block the next connection. Desktops that never send KEEPALIVE (0.6.0) are
   not timed out.

## Version History

- **v1.0**: Initial protocol specification
