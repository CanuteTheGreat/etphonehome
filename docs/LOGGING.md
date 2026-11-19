# Logging

Every ETPH component writes one line per event as space-separated `key=value` pairs, with a level, so a line is readable by a person
and parseable by a script (`logread`, `grep level=ERROR`, a log shipper). The message is always the last field, quoted and escaped, so an event never spans two lines.

## Line format

Router client and every desktop binary (Linux/OpenWrt, macOS, FreeBSD/OPNsense/pfSense, Windows, GUI, tray):

```
ts=2026-11-19T12:34:56Z level=INFO target=etphonehome_common::client msg="Tethering connection established"
```

Android app (logcat already shows time and tag, so the message part carries the level and text):

```
11-19 12:34:56.789  I/ETPhoneHome: level=INFO msg="Auto-start requested, sharing is already on"
11-19 12:35:01.002  E/NsdManager:  level=ERROR msg="Failed to register NSD service" err=java.io.IOException
```

Android stand-in used by the CI end-to-end test (plain JVM, standard error): `ts=... tag=standin level=WARN msg="..."`.

Exception lines carry the exception class (`err=`) and never its message, which can hold typed text or file names. Levels: ERROR, WARN, INFO (default), DEBUG, TRACE (Rust only). Android DEBUG lines exist only in debug builds.

## Where to read them

| Where | Location |
| --- | --- |
| OpenWrt / GL.iNet router | `logread -e etph` (the client runs under netifd/procd, which sends its output to syslog; the hotplug and uplink hooks log through `logger -t etph` with `level=`, `source=`, `event=`) |
| Android app | `adb logcat -s ETPhoneHome` (and the other component tags, for example `adb logcat | grep ' msg='`). Nothing is written to a file or uploaded. |
| Linux, macOS | standard error of the process; under a service manager that is its journal or log |
| FreeBSD, OPNsense, pfSense | `/var/log/etphonehome.log` |
| Windows | console, or `%ProgramData%\ETPH\service.log` for the Windows service |

## Setting the level

- **Router:** the per-interface UCI option `loglevel` in `/etc/config/network` (`option loglevel 'debug'` on the `proto 'etph'` interface, then `ifup`);
  the netifd handler passes it to the client as `RUST_LOG`.
- **Desktop client:** `ETPH_LOG=debug etphonehome ...` (or `RUST_LOG`; `ETPH_LOG` wins). Module filters work: `ETPH_LOG=warn,etphonehome_common::client=debug`.
- **Android:** debug builds log DEBUG and up, release builds INFO and up.

## Tests that assert the lines

- Rust: `desktop/common/src/logfmt.rs` unit tests build the real logger against an in-memory pipe and assert the exact fields, escaping and level filtering
  (run by `cargo test --all` in rust-ci.yml).
- Android: `android/core/src/test/kotlin/com/etphonehome/log/LogTest.kt` asserts the exact lines for every level, exception handling, escaping, the level threshold
  and the stand-in's timestamped line (run by the Android core unit tests).
- Router scripts: `openwrt/test/log-format-test.sh` runs the uplink hook's `logger` lines through a stub and asserts the key=value text, and that `loglevel` reaches the client
  (run on every branch push by android-ci.yml).
