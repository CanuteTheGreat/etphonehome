//! Runs the real `etphonehome` binary.

use etphonehome_common::protocol::{
    capabilities, ErrorCode, ErrorMessage, HandshakeResponse, Message, PROTOCOL_VERSION,
};
use std::io::Write;
use std::net::TcpListener;
use std::path::PathBuf;
use std::process::{Command, Output};

fn bin() -> Command {
    let mut c = Command::new(env!("CARGO_BIN_EXE_etphonehome"));
    c.env("RUST_LOG", "info").env_remove("SUDO_USER");
    c
}

fn tmp_config(name: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!(
        "etph-cli-{}-{}-{}",
        name,
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    d.join("config.toml")
}

fn run(cfg: &PathBuf, args: &[&str]) -> Output {
    bin().arg("--config").arg(cfg).args(args).output().unwrap()
}

fn stdout(o: &Output) -> String {
    String::from_utf8_lossy(&o.stdout).into_owned()
}
fn stderr(o: &Output) -> String {
    String::from_utf8_lossy(&o.stderr).into_owned()
}

#[test]
fn help_lists_every_documented_subcommand() {
    let o = bin().arg("--help").output().unwrap();
    assert!(o.status.success());
    let s = stdout(&o);
    for cmd in [
        "usb",
        "wifi",
        "bluetooth",
        "setup-adb",
        "discover-ip",
        "discover-wifi",
        "connect",
        "list-profiles",
        "save-profile",
        "delete-profile",
    ] {
        assert!(s.contains(cmd), "--help is missing `{}`:\n{}", cmd, s);
    }
    let o = bin().args(["wifi", "--help"]).output().unwrap();
    assert!(o.status.success(), "wifi --help failed: {}", stderr(&o));
    assert!(stdout(&o).contains("--tls"));
    assert!(stdout(&o).contains("--reconnect"));
}

#[test]
fn profile_lifecycle_via_cli() {
    let cfg = tmp_config("lifecycle");
    let o = run(&cfg, &["list-profiles"]);
    assert!(o.status.success());
    assert!(stdout(&o).contains("No saved profiles"));

    // README spelling: --type
    let o = run(
        &cfg,
        &[
            "save-profile",
            "my-home-wifi",
            "--type",
            "wifi",
            "--host",
            "192.168.1.100",
            "--description",
            "Home WiFi connection",
        ],
    );
    assert!(o.status.success(), "{}", stderr(&o));
    let o = run(
        &cfg,
        &[
            "save-profile",
            "my-phone-bt",
            "--type",
            "bluetooth",
            "--address",
            "AA:BB:CC:DD:EE:FF",
        ],
    );
    assert!(o.status.success(), "{}", stderr(&o));

    let o = run(&cfg, &["list-profiles"]);
    let s = stdout(&o);
    assert!(s.contains("my-home-wifi (WiFi)"), "{}", s);
    assert!(s.contains("Home WiFi connection"), "{}", s);
    assert!(s.contains("Host: 192.168.1.100:8888"), "{}", s);
    assert!(s.contains("my-phone-bt (Bluetooth)"), "{}", s);
    assert!(s.contains("Address: AA:BB:CC:DD:EE:FF"), "{}", s);

    // Output goes to stdout even with logging turned down (it used to be
    // emitted through `log::info!`, so RUST_LOG=error printed nothing).
    let o = bin()
        .env("RUST_LOG", "error")
        .arg("--config")
        .arg(&cfg)
        .arg("list-profiles")
        .output()
        .unwrap();
    assert!(stdout(&o).contains("my-home-wifi"));

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = std::fs::metadata(&cfg).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
    }

    let o = run(&cfg, &["delete-profile", "my-home-wifi"]);
    assert!(o.status.success(), "{}", stderr(&o));
    let o = run(&cfg, &["delete-profile", "my-home-wifi"]);
    assert!(!o.status.success(), "deleting a missing profile must fail");
    assert!(stderr(&o).contains("not found"));
    let s = stdout(&run(&cfg, &["list-profiles"]));
    assert!(!s.contains("my-home-wifi"));
    assert!(s.contains("my-phone-bt"));
}

#[test]
fn invalid_profiles_are_rejected() {
    let cfg = tmp_config("invalid");
    let o = run(&cfg, &["save-profile", "x", "--type", "serial"]);
    assert!(!o.status.success());
    assert!(stderr(&o).contains("Invalid connection type"));
    let o = run(&cfg, &["save-profile", "x", "--type", "wifi"]);
    assert!(!o.status.success());
    assert!(stderr(&o).contains("--host"));
    let o = run(&cfg, &["connect", "nope"]);
    assert!(!o.status.success());
    assert!(
        stderr(&o).contains("Profile 'nope' not found"),
        "{}",
        stderr(&o)
    );
}

#[test]
fn env_var_selects_config_file() {
    let cfg = tmp_config("env");
    let o = bin()
        .env("ETPHONEHOME_CONFIG", &cfg)
        .args(["save-profile", "cable", "--type", "usb"])
        .output()
        .unwrap();
    assert!(o.status.success(), "{}", stderr(&o));
    assert!(cfg.exists());
}

/// Minimal Android stand-in on a thread: reads the HandshakeReq and answers
/// like the app does for a wrong token.
fn rejecting_peer() -> (u16, std::thread::JoinHandle<Option<String>>) {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    let h = std::thread::spawn(move || {
        let (mut s, _) = l.accept().ok()?;
        let token = match Message::read_from(&mut s).ok()? {
            Message::HandshakeReq(r) => r.token,
            _ => None,
        };
        let mut buf = Vec::new();
        Message::Error(ErrorMessage {
            error_code: ErrorCode::AuthFailed,
            message: "Authentication failed: invalid token".into(),
        })
        .write_to(&mut buf)
        .unwrap();
        s.write_all(&buf).ok()?;
        token
    });
    (port, h)
}

#[test]
fn wrong_token_reports_android_rejection() {
    let (port, peer) = rejecting_peer();
    let o = bin()
        .args([
            "wifi",
            "--host",
            "127.0.0.1",
            "--port",
            &port.to_string(),
            "--token",
            "wrong-token",
        ])
        .output()
        .unwrap();
    assert!(!o.status.success());
    let err = stderr(&o);
    assert!(err.contains("invalid token"), "{}", err);
    assert_eq!(peer.join().unwrap().as_deref(), Some("wrong-token"));
}

#[test]
fn wifi_accepts_hostnames() {
    // Handshake succeeds against localhost; without CAP_NET_ADMIN the run
    // then stops at TUN creation, which proves the hostname resolved and
    // the handshake completed.
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    let peer = std::thread::spawn(move || {
        let (mut s, _) = l.accept().unwrap();
        let _ = Message::read_from(&mut s).unwrap();
        let mut buf = Vec::new();
        Message::HandshakeResp(HandshakeResponse {
            version: PROTOCOL_VERSION,
            capabilities: capabilities::WIFI,
            ip_address: 0x0a000002,
            dns: 0x08080808,
        })
        .write_to(&mut buf)
        .unwrap();
        s.write_all(&buf).unwrap();
        std::thread::sleep(std::time::Duration::from_secs(2));
    });
    let o = bin()
        .args([
            "wifi",
            "--host",
            "localhost",
            "--port",
            &port.to_string(),
            "--token",
            "t",
            "--interface",
            "etphclitest0",
        ])
        .output()
        .unwrap();
    peer.join().unwrap();
    let err = stderr(&o);
    assert!(err.contains("Handshake successful"), "{}", err);
    if !o.status.success() {
        assert!(err.contains("TUN"), "{}", err);
    }
}
