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
Token Length is 0 when no token is sent (USB). WiFi, Bluetooth and root mode
require the token shown in the Android app; a wrong or missing token is
answered with an ERROR message (code 0x03, AUTH_FAILED) and the connection is
closed.

Byte-exact examples of every message type are in
[`test-vectors.txt`](test-vectors.txt); both the Rust and the Kotlin test
suites check their encoders and decoders against that file.

#### HANDSHAKE_RESP Payload
```
+----------+---------------+------------+-----------+
| Version  | Capabilities  | IP Address | DNS       |
| 2 bytes  | 4 bytes       | 4 bytes    | 4 bytes   |
+----------+---------------+------------+-----------+
```

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

### Error Message Payload

```
+------------+------------------+
| Error Code | Message Length   | Error Message    |
| 1 byte     | 2 bytes          | N bytes (UTF-8)  |
+------------+------------------+
```

## Security Considerations

1. **Local Connections**: Primary security is physical device access
2. **Optional Authentication**: Can add shared secret for WiFi mode
3. **Encryption**: TLS wrapper can be added for WiFi mode
4. **Root Mode**: Additional OS-level permissions required

## Version History

- **v1.0**: Initial protocol specification
