//! Structured (key=value) logging shared by every ETPH client binary: the Linux/OpenWrt router
//! client, macOS, BSD, Windows (console and service) and the GUI/tray.
//!
//! One line per event, space-separated `key=value` pairs, the message always last and quoted:
//!
//! ```text
//! ts=2026-11-19T12:34:56Z level=INFO target=etphonehome_common::client msg="session up"
//! ```
//!
//! Levels are the `log` crate's: ERROR, WARN, INFO, DEBUG, TRACE. The filter comes from `ETPH_LOG`,
//! else `RUST_LOG` (the OpenWrt proto handler exports it from the UCI option `loglevel`), else the
//! default the binary passes (`info`). Both accept `error|warn|info|debug|trace` or env_logger
//! module filters such as `warn,etphonehome_common::client=debug`.
//!
//! Where the lines go: standard error. On OpenWrt procd/netifd sends that to syslog (`logread -e etph`);
//! under systemd it is the journal; the Windows service writes `%ProgramData%\ETPH\service.log`.

use std::io::Write;
use std::time::{SystemTime, UNIX_EPOCH};

/// Escape and quote a message so a line stays one line and `key="value"` parsers can read it.
pub fn quote(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// Seconds since the Unix epoch as `YYYY-MM-DDTHH:MM:SSZ` (UTC). Civil-from-days, no dependencies.
pub fn rfc3339_utc(secs: u64) -> String {
    let days = (secs / 86_400) as i64;
    let rem = secs % 86_400;
    let (h, m, s) = (rem / 3600, (rem % 3600) / 60, rem % 60);
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let mo = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if mo <= 2 { y + 1 } else { y };
    format!("{y:04}-{mo:02}-{d:02}T{h:02}:{m:02}:{s:02}Z")
}

/// One log line, without the trailing newline.
pub fn format_line(ts_secs: u64, level: log::Level, target: &str, msg: &str) -> String {
    format!(
        "ts={} level={} target={} msg={}",
        rfc3339_utc(ts_secs),
        level,
        target,
        quote(msg)
    )
}

/// The filter spec in effect: `ETPH_LOG`, else `RUST_LOG`, else `default`.
pub fn filter_spec(default: &str) -> String {
    std::env::var("ETPH_LOG")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .or_else(|| std::env::var("RUST_LOG").ok().filter(|v| !v.trim().is_empty()))
        .unwrap_or_else(|| default.to_string())
}

/// An env_logger builder with the structured format and the filter from [`filter_spec`].
pub fn builder(default: &str) -> env_logger::Builder {
    builder_with_spec(&filter_spec(default))
}

/// Same, with an explicit filter spec (used by tests and callers that already resolved it).
pub fn builder_with_spec(spec: &str) -> env_logger::Builder {
    let mut b = env_logger::Builder::new();
    b.parse_filters(spec);
    b.format(|buf, rec| {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        writeln!(
            buf,
            "{}",
            format_line(now, rec.level(), rec.target(), &rec.args().to_string())
        )
    });
    b
}

/// Install the structured logger for the process (standard error). Call once, first thing in `main`.
pub fn init(default: &str) {
    let _ = builder(default).try_init();
}

#[cfg(test)]
mod tests {
    use super::*;
    use log::Log;
    use std::sync::{Arc, Mutex};

    #[derive(Clone, Default)]
    struct Sink(Arc<Mutex<Vec<u8>>>);
    impl Write for Sink {
        fn write(&mut self, b: &[u8]) -> std::io::Result<usize> {
            self.0.lock().unwrap().extend_from_slice(b);
            Ok(b.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    fn emit(spec: &str, records: &[(log::Level, &str, &str)]) -> Vec<String> {
        let sink = Sink::default();
        let mut b = builder_with_spec(spec);
        b.target(env_logger::Target::Pipe(Box::new(sink.clone())));
        let logger = b.build();
        for (level, target, msg) in records {
            logger.log(
                &log::Record::builder()
                    .args(format_args!("{}", msg))
                    .level(*level)
                    .target(target)
                    .build(),
            );
        }
        logger.flush();
        let text = String::from_utf8(sink.0.lock().unwrap().clone()).unwrap();
        text.lines().map(str::to_string).collect()
    }

    #[test]
    fn epoch_and_known_dates() {
        assert_eq!(rfc3339_utc(0), "1970-01-01T00:00:00Z");
        assert_eq!(rfc3339_utc(1_700_000_000), "2023-11-14T22:13:20Z");
        // leap day
        assert_eq!(rfc3339_utc(1_709_208_000), "2024-02-29T12:00:00Z");
    }

    #[test]
    fn line_has_all_keys_in_order() {
        assert_eq!(
            format_line(0, log::Level::Warn, "etph::client", "phone unplugged"),
            "ts=1970-01-01T00:00:00Z level=WARN target=etph::client msg=\"phone unplugged\""
        );
    }

    #[test]
    fn messages_stay_on_one_line_and_are_escaped() {
        assert_eq!(quote("a \"b\"\nc\\d"), "\"a \\\"b\\\"\\nc\\\\d\"");
        assert_eq!(quote("x\u{1}y"), "\"x\\u0001y\"");
    }

    #[test]
    fn logger_writes_structured_lines_and_honours_the_level() {
        let lines = emit(
            "warn",
            &[
                (log::Level::Error, "etph::t", "boom"),
                (log::Level::Warn, "etph::t", "careful"),
                (log::Level::Info, "etph::t", "chatty"),
                (log::Level::Debug, "etph::t", "noisy"),
            ],
        );
        assert_eq!(lines.len(), 2, "{lines:?}");
        assert!(lines[0].starts_with("ts="), "{}", lines[0]);
        assert!(lines[0].contains(" level=ERROR target=etph::t msg=\"boom\""), "{}", lines[0]);
        assert!(lines[1].contains(" level=WARN target=etph::t msg=\"careful\""), "{}", lines[1]);
    }

    #[test]
    fn debug_level_lets_everything_through() {
        let lines = emit(
            "debug",
            &[
                (log::Level::Info, "a", "one"),
                (log::Level::Debug, "a", "two"),
                (log::Level::Trace, "a", "three"),
            ],
        );
        assert_eq!(lines.len(), 2, "{lines:?}");
        assert!(lines[1].contains("level=DEBUG"), "{}", lines[1]);
    }

    #[test]
    fn module_filter_syntax_is_kept() {
        let lines = emit(
            "warn,etph::loud=debug",
            &[
                (log::Level::Debug, "etph::loud", "yes"),
                (log::Level::Debug, "etph::quiet", "no"),
            ],
        );
        assert_eq!(lines.len(), 1, "{lines:?}");
        assert!(lines[0].contains("target=etph::loud"), "{}", lines[0]);
    }
}
