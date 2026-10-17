# ET Phone Home - Android App

Shares the phone's internet with a computer over USB, WiFi or Bluetooth.
No root and no VPN: the phone keeps its own internet while tethering.

## How it works

The desktop client sends its IP packets over the tether link. The app's
userspace relay (`core/src/main/kotlin/com/etphonehome/relay/`) terminates
each TCP connection and UDP flow itself and opens an ordinary socket to the
same destination, the way any app does, then turns the replies back into IP
packets for the desktop. Ping to the phone (10.0.0.2) is answered locally;
ping to other hosts is relayed when the phone can reach them.

## Features

- USB (the desktop runs `adb forward`; the app listens on the phone's loopback only)
- WiFi with token authentication and TLS 1.3 by default (pinned to the token; "Require encryption" switch)
- Bluetooth RFCOMM with token authentication (desktop side: Linux only)
- Live status: "Waiting for desktop" until a desktop completes the handshake, then peer, encryption, data each way, connections, uptime, filter state
- Foreground notification with a Stop button
- Traffic filter rules received from the desktop profile and enforced by the relay

## Requirements

- Android 8.0+ (API 26+)
- USB debugging (USB mode only)

## Layout

```
android/
├── app/                      # Android shell
│   └── src/main/java/com/etphonehome/
│       ├── MainActivity.kt           # UI: mode, Start/Stop, status, statistics
│       ├── service/TetherService.kt  # Foreground service: USB/WiFi/Bluetooth listeners
│       ├── service/StatusText.kt     # State -> text (unit-tested)
│       └── util/                     # Tokens, settings, mDNS advertiser
└── core/                     # Plain Kotlin/JVM, no Android dependencies
    ├── src/main/kotlin/com/etphonehome/
    │   ├── relay/            # UserspaceRelay (TCP/UDP/ICMP), packet parsing/building
    │   ├── session/          # TetherSession, TcpLink (TLS/plaintext detection), SessionLoop
    │   ├── tls/              # CertPin: token-pinned TLS 1.3 (Bouncy Castle JSSE)
    │   ├── protocol/         # Wire protocol
    │   └── filter/           # Traffic filter
    └── standin/              # The core as a JVM program; CI runs the real desktop client against it
```

`core` is a separate Gradle build pulled into the app with `includeBuild('core')`,
so it can be built and tested without the Android SDK.

## Building and testing

```bash
cd android
./gradlew assembleDebug test        # app (needs the Android SDK)
(cd core && ../gradlew test)        # core: relay, TLS, session, protocol, filter tests
(cd core && ../gradlew installDist) # core/standin/build/install/standin/bin/standin
```

## Troubleshooting

```bash
adb logcat -s ETPhoneHome Relay
```

`Handshake complete (...)` and `Relay started` mark a working session;
`TLS handshake complete: TLSv1.3 ...` an encrypted one.
