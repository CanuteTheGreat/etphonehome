//! `etphonehome service`: the privileged half of the desktop apps (macOS
//! menu-bar app, Windows tray app). It runs as root / LocalSystem (launchd
//! daemon, Windows service, rc.d) and owns the tethering session; the
//! unprivileged UI talks to it over a local control channel:
//!
//! * Unix: a stream socket (default `/var/run/etphonehome.sock`).
//! * Windows: TCP on 127.0.0.1 with a token in `%ProgramData%\ETPH\control.token`
//!   (readable by local users, so a web page can't drive it).
//!
//! One JSON request per line, one JSON reply per line:
//! `{"cmd":"status"}`, `{"cmd":"connect","mode":"usb"}`,
//! `{"cmd":"connect","profile":"home"}`, `{"cmd":"disconnect"}`.
//!
//! What the channel can do is deliberately small: plug-in USB and profiles an
//! administrator saved (`sudo etphonehome save-profile ...`), so a local user
//! can't point this computer's traffic at a phone of their choosing.

use crate::config::Config;
use crate::filter::FilterConfig;
use crate::host::{HostIntegration, StatusFile};
use crate::protocol::capabilities;
use crate::runner::{self, RetryPolicy, SessionPlan, Target, DEFAULT_PORT};
use crate::session::ConnectOptions;
use anyhow::{bail, Context, Result};
use log::{info, warn};
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
#[cfg(unix)]
use std::path::Path;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{
    AsyncBufRead, AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader,
};
use tokio::sync::{watch, Mutex, Semaphore};
use tokio::task::JoinHandle;

/// Where the control channel lives by default.
#[cfg(unix)]
pub fn default_control() -> PathBuf {
    PathBuf::from("/var/run/etphonehome.sock")
}

/// Windows: the loopback port; the token file sits in [`state_dir`].
#[cfg(windows)]
pub const CONTROL_PORT: u16 = 48_888;

/// Where the service keeps its status file (and on Windows the token).
pub fn state_dir() -> PathBuf {
    #[cfg(windows)]
    {
        let base = std::env::var_os("ProgramData").unwrap_or_else(|| "C:\\ProgramData".into());
        PathBuf::from(base).join("ETPH")
    }
    #[cfg(not(windows))]
    {
        PathBuf::from("/var/run/etphonehome")
    }
}

/// The config file the service reads its saved profiles from. Save
/// profiles for it with `etphonehome --config <this path> save-profile ...`
/// as root / Administrator.
pub fn system_config_path() -> PathBuf {
    #[cfg(windows)]
    {
        state_dir().join("config.toml")
    }
    #[cfg(target_os = "macos")]
    {
        PathBuf::from("/Library/Application Support/ETPH/config.toml")
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        PathBuf::from("/usr/local/etc/etphonehome/config.toml")
    }
}

/// One control request.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Default)]
pub struct Request {
    pub cmd: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mode: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub profile: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
}

/// The reply: `ok`, and for `status` the session as the status file has it.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Default)]
pub struct Reply {
    pub ok: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    /// idle | connecting | authorizing | connected | retrying | stopped | failed
    #[serde(default)]
    pub state: String,
    /// What the session was started for ("USB", or the profile name).
    #[serde(default)]
    pub session: Option<String>,
    #[serde(default)]
    pub device: Option<String>,
    #[serde(default)]
    pub phone_pro: Option<bool>,
    #[serde(default)]
    pub interface: Option<String>,
    #[serde(default)]
    pub last_error: Option<String>,
    #[serde(default)]
    pub bytes_sent: u64,
    #[serde(default)]
    pub bytes_received: u64,
    #[serde(default)]
    pub profiles: Vec<String>,
    #[serde(default)]
    pub version: String,
}

struct Running {
    what: String,
    stop: watch::Sender<bool>,
    task: JoinHandle<()>,
    status: Arc<StatusFile>,
}

#[derive(Default)]
struct State {
    running: Option<Running>,
}

/// Service options.
#[derive(Debug, Clone)]
pub struct Options {
    #[cfg(unix)]
    pub control: PathBuf,
    pub interface: String,
    pub usb_port: u16,
    /// Saved profiles ($ETPHONEHOME_CONFIG, else [`system_config_path`]).
    pub config: PathBuf,
}

impl Options {
    fn load_config(&self) -> Result<Config> {
        if self.config.exists() {
            Config::load(&self.config)
        } else {
            Ok(Config::default())
        }
    }
}

impl Default for Options {
    fn default() -> Self {
        Self {
            config: std::env::var_os("ETPHONEHOME_CONFIG")
                .map(PathBuf::from)
                .unwrap_or_else(system_config_path),
            #[cfg(unix)]
            control: default_control(),
            interface: "etph0".into(),
            usb_port: DEFAULT_PORT,
        }
    }
}

fn usb_plan(port: u16, interface: &str, status: Arc<StatusFile>) -> SessionPlan {
    SessionPlan {
        target: Target::Tcp {
            opts: ConnectOptions {
                addr: SocketAddr::from(([127, 0, 0, 1], port)),
                capabilities: capabilities::USB,
                token: None,
                tls: false,
                timeout: Duration::from_secs(10),
            },
            adb_port: Some(port),
        },
        tun_name: interface.to_string(),
        filter: FilterConfig::default(),
        host: HostIntegration {
            status: Some(status),
            ..HostIntegration::default()
        },
    }
}

async fn stop_running(state: &mut State) {
    if let Some(r) = state.running.take() {
        info!("stopping the {} session", r.what);
        let _ = r.stop.send(true);
        let mut task = r.task;
        if tokio::time::timeout(Duration::from_secs(10), &mut task)
            .await
            .is_err()
        {
            warn!("session did not stop in 10 s; aborting it");
            task.abort();
        }
    }
}

fn status_reply(state: &State, opts: &Options) -> Reply {
    let profiles = opts
        .load_config()
        .map(|c| c.list_profiles())
        .unwrap_or_default();
    let mut reply = Reply {
        ok: true,
        state: "idle".into(),
        profiles,
        version: env!("CARGO_PKG_VERSION").into(),
        ..Default::default()
    };
    if let Some(r) = &state.running {
        let s = r.status.snapshot();
        reply.state = if r.task.is_finished() && s.state != "failed" {
            "stopped".into()
        } else {
            s.state
        };
        reply.session = Some(r.what.clone());
        reply.device = s.device;
        reply.phone_pro = s.phone_pro;
        reply.interface = Some(s.interface);
        reply.last_error = s.last_error;
        reply.bytes_sent = s.bytes_sent;
        reply.bytes_received = s.bytes_received;
    }
    reply
}

async fn handle(req: Request, state: &Mutex<State>, opts: &Options) -> Reply {
    let mut st = state.lock().await;
    match req.cmd.as_str() {
        "status" => status_reply(&st, opts),
        "disconnect" => {
            stop_running(&mut st).await;
            status_reply(&st, opts)
        }
        "connect" => match start(&mut st, &req, opts).await {
            Ok(()) => status_reply(&st, opts),
            Err(e) => Reply {
                ok: false,
                error: Some(format!("{:#}", e)),
                ..status_reply(&st, opts)
            },
        },
        other => Reply {
            ok: false,
            error: Some(format!("unknown command {other:?}")),
            ..Default::default()
        },
    }
}

async fn start(st: &mut State, req: &Request, opts: &Options) -> Result<()> {
    let dir = state_dir();
    let status_path = dir.join("status.json");
    let mkdir = || {
        std::fs::create_dir_all(&dir).with_context(|| format!("cannot create {}", dir.display()))
    };
    let (what, plan) = match (&req.profile, req.mode.as_deref()) {
        (Some(name), _) => {
            let config = opts.load_config()?;
            let profile = config
                .get_profile(name)
                .cloned()
                .with_context(|| format!("no saved profile named {name:?}"))?;
            mkdir()?;
            let mut plan = runner::plan_for_profile(&config, &profile, &opts.interface).await?;
            let status = StatusFile::new(status_path, "profile", name, &opts.interface);
            plan.host.status = Some(status);
            (name.clone(), plan)
        }
        (None, Some("usb") | None) => {
            mkdir()?;
            let status = StatusFile::new(status_path, "usb", "USB", &opts.interface);
            (
                "USB".to_string(),
                usb_plan(opts.usb_port, &opts.interface, status),
            )
        }
        (None, Some(m)) => bail!("unknown mode {m:?} (usb, or a saved profile)"),
    };
    stop_running(st).await;
    let status = plan.host.status.clone().expect("status set above");
    let (tx, rx) = watch::channel(false);
    info!("starting the {} session", what);
    let task = tokio::spawn(async move {
        let policy = RetryPolicy {
            reconnect: true,
            max_retries: 0,
        };
        if let Err(e) = runner::run_plan(&plan, policy, rx, None, None).await {
            warn!("session ended: {:#}", e);
        }
    });
    st.running = Some(Running {
        what,
        stop: tx,
        task,
        status,
    });
    Ok(())
}

/// The longest control request line the service will buffer. Real requests are a few
/// hundred bytes; the socket is writable by every local user, so an unbounded `read_line`
/// would let any of them grow the root service's memory without limit.
pub const MAX_REQUEST_LINE: usize = 16 * 1024;

/// How many control connections the service serves at once. Each is cheap, but the
/// socket is open to every local user, so the count is capped instead of unbounded.
pub const MAX_CONTROL_CONNECTIONS: usize = 32;

/// Read one `\n`-terminated request line of at most [`MAX_REQUEST_LINE`] bytes.
/// `Ok(None)` = the peer closed the connection; a longer line is an `InvalidData`
/// error after reading at most `MAX_REQUEST_LINE + 1` bytes (nothing more is buffered).
async fn read_request_line<R: AsyncBufRead + Unpin>(r: &mut R) -> std::io::Result<Option<String>> {
    let mut buf = Vec::new();
    let n = (&mut *r)
        .take(MAX_REQUEST_LINE as u64 + 1)
        .read_until(b'\n', &mut buf)
        .await?;
    if n == 0 {
        return Ok(None);
    }
    if buf.last() == Some(&b'\n') {
        buf.pop();
        if buf.last() == Some(&b'\r') {
            buf.pop();
        }
    } else if buf.len() > MAX_REQUEST_LINE {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "request line too long",
        ));
    }
    String::from_utf8(buf)
        .map(Some)
        .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))
}

async fn serve_conn<S>(
    stream: S,
    state: Arc<Mutex<State>>,
    opts: Arc<Options>,
    token: Option<Arc<String>>,
) where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let (r, mut w) = tokio::io::split(stream);
    let mut reader = BufReader::new(r);
    loop {
        let line =
            match tokio::time::timeout(Duration::from_secs(30), read_request_line(&mut reader))
                .await
            {
                Ok(Ok(Some(line))) => line,
                Ok(Err(e)) if e.kind() == std::io::ErrorKind::InvalidData => {
                    // Oversized or not UTF-8: answer once and drop the connection.
                    let mut out = serde_json::to_string(&Reply {
                        ok: false,
                        error: Some("request too large or not text".into()),
                        ..Default::default()
                    })
                    .unwrap_or_default();
                    out.push('\n');
                    let _ = w.write_all(out.as_bytes()).await;
                    break;
                }
                _ => break,
            };
        let reply = match serde_json::from_str::<Request>(&line) {
            Ok(req) => {
                if let Some(t) = &token {
                    if req.token.as_deref() != Some(t.as_str()) {
                        Reply {
                            ok: false,
                            error: Some("bad or missing control token".into()),
                            ..Default::default()
                        }
                    } else {
                        handle(req, &state, &opts).await
                    }
                } else {
                    handle(req, &state, &opts).await
                }
            }
            Err(e) => Reply {
                ok: false,
                error: Some(format!("bad request: {e}")),
                ..Default::default()
            },
        };
        let mut out = serde_json::to_string(&reply).unwrap_or_default();
        out.push('\n');
        if w.write_all(out.as_bytes()).await.is_err() {
            break;
        }
    }
}

/// Run the service until `shutdown` fires; the running session (if any) is
/// disconnected cleanly first.
pub async fn run(opts: Options, mut shutdown: watch::Receiver<bool>) -> Result<()> {
    let state = Arc::new(Mutex::new(State::default()));
    let opts = Arc::new(opts);
    #[cfg(unix)]
    let listener = {
        let path = &opts.control;
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir).ok();
        }
        let _ = std::fs::remove_file(path);
        let l = tokio::net::UnixListener::bind(path)
            .with_context(|| format!("cannot listen on {}", path.display()))?;
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o666))?;
        info!("ETPH service listening on {}", path.display());
        l
    };
    #[cfg(windows)]
    let (listener, token) = {
        let l = tokio::net::TcpListener::bind(("127.0.0.1", CONTROL_PORT))
            .await
            .with_context(|| format!("cannot listen on 127.0.0.1:{CONTROL_PORT}"))?;
        let dir = state_dir();
        std::fs::create_dir_all(&dir)?;
        let token = crate::identity::new_id();
        std::fs::write(dir.join("control.token"), &token)?;
        info!("ETPH service listening on 127.0.0.1:{}", CONTROL_PORT);
        (l, Arc::new(token))
    };
    let slots = Arc::new(Semaphore::new(MAX_CONTROL_CONNECTIONS));
    loop {
        tokio::select! {
            _ = shutdown.wait_for(|v| *v) => break,
            acc = listener.accept() => {
                let (stream, _) = match acc {
                    Ok(x) => x,
                    Err(e) => { warn!("control accept: {e}"); continue; }
                };
                let Ok(permit) = slots.clone().try_acquire_owned() else {
                    warn!("control: too many connections, dropping one");
                    continue;
                };
                let (state, opts) = (state.clone(), opts.clone());
                #[cfg(unix)]
                tokio::spawn(async move {
                    serve_conn(stream, state, opts, None).await;
                    drop(permit);
                });
                #[cfg(windows)]
                {
                    let token = token.clone();
                    tokio::spawn(async move {
                        serve_conn(stream, state, opts, Some(token)).await;
                        drop(permit);
                    });
                }
            }
        }
    }
    stop_running(&mut *state.lock().await).await;
    #[cfg(unix)]
    let _ = std::fs::remove_file(&opts.control);
    info!("ETPH service stopped");
    Ok(())
}

/// Client side, for the tray/menu-bar apps and `etphonehome ctl`.
pub async fn request(req: &Request, #[cfg(unix)] control: &Path) -> Result<Reply> {
    let mut req = req.clone();
    #[cfg(unix)]
    let stream = tokio::net::UnixStream::connect(control)
        .await
        .with_context(|| format!("the ETPH service is not running ({})", control.display()))?;
    #[cfg(windows)]
    let stream = {
        let token = std::fs::read_to_string(state_dir().join("control.token"))
            .context("the ETPH service is not running (no control token)")?;
        req.token = Some(token.trim().to_string());
        tokio::net::TcpStream::connect(("127.0.0.1", CONTROL_PORT))
            .await
            .context("the ETPH service is not running")?
    };
    let (r, mut w) = tokio::io::split(stream);
    let mut line = serde_json::to_string(&req)?;
    line.push('\n');
    w.write_all(line.as_bytes()).await?;
    let mut lines = BufReader::new(r).lines();
    let reply = tokio::time::timeout(Duration::from_secs(20), lines.next_line())
        .await
        .context("the ETPH service did not answer")??
        .context("the ETPH service closed the connection")?;
    req.token = None;
    Ok(serde_json::from_str(&reply)?)
}

/// Blocking [`request`] for UI threads.
pub fn request_blocking(req: &Request) -> Result<Reply> {
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    #[cfg(unix)]
    return rt.block_on(request(req, &default_control()));
    #[cfg(windows)]
    return rt.block_on(request(req));
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    #[tokio::test]
    async fn control_socket_answers_status_and_refuses_unknown_commands() {
        let dir = std::env::temp_dir().join(format!("etph-svc-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let sock = dir.join("c.sock");
        let opts = Options {
            control: sock.clone(),
            config: dir.join("none.toml"),
            ..Default::default()
        };
        let (tx, rx) = watch::channel(false);
        let svc = tokio::spawn(run(opts, rx));
        for _ in 0..50 {
            if sock.exists() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        let r = request(
            &Request {
                cmd: "status".into(),
                ..Default::default()
            },
            &sock,
        )
        .await
        .unwrap();
        assert!(r.ok);
        assert_eq!(r.state, "idle");
        assert_eq!(r.version, env!("CARGO_PKG_VERSION"));
        let r = request(
            &Request {
                cmd: "reboot".into(),
                ..Default::default()
            },
            &sock,
        )
        .await
        .unwrap();
        assert!(!r.ok);
        assert!(r.error.unwrap().contains("unknown command"));
        let r = request(
            &Request {
                cmd: "connect".into(),
                profile: Some("nope".into()),
                ..Default::default()
            },
            &sock,
        )
        .await
        .unwrap();
        assert!(!r.ok, "unknown profiles are refused");
        assert_eq!(r.state, "idle");
        let r = request(
            &Request {
                cmd: "connect".into(),
                mode: Some("wifi".into()),
                ..Default::default()
            },
            &sock,
        )
        .await
        .unwrap();
        assert!(
            !r.ok,
            "ad-hoc Wi-Fi targets are not accepted over the control channel"
        );
        tx.send(true).unwrap();
        svc.await.unwrap().unwrap();
        assert!(!sock.exists(), "socket removed on shutdown");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn requests_round_trip() {
        let r = Request {
            cmd: "connect".into(),
            mode: Some("usb".into()),
            ..Default::default()
        };
        let s = serde_json::to_string(&r).unwrap();
        assert_eq!(s, r#"{"cmd":"connect","mode":"usb"}"#);
        assert_eq!(serde_json::from_str::<Request>(&s).unwrap(), r);
    }
}

#[cfg(test)]
mod request_line_tests {
    use super::*;

    async fn read(input: &[u8]) -> std::io::Result<Option<String>> {
        let mut r = BufReader::new(input);
        read_request_line(&mut r).await
    }

    #[tokio::test]
    async fn reads_a_normal_line_and_strips_the_newline() {
        assert_eq!(
            read(b"{\"cmd\":\"status\"}\n").await.unwrap().as_deref(),
            Some("{\"cmd\":\"status\"}")
        );
        assert_eq!(read(b"x\r\n").await.unwrap().as_deref(), Some("x"));
        // A last line without a newline (peer closed) is still a request.
        assert_eq!(read(b"tail").await.unwrap().as_deref(), Some("tail"));
        assert_eq!(read(b"").await.unwrap(), None);
    }

    #[tokio::test]
    async fn a_line_of_exactly_the_limit_is_accepted() {
        let mut line = vec![b'a'; MAX_REQUEST_LINE];
        line.push(b'\n');
        assert_eq!(read(&line).await.unwrap().unwrap().len(), MAX_REQUEST_LINE);
    }

    #[tokio::test]
    async fn an_oversized_line_is_refused_without_buffering_it_all() {
        // No newline within the limit: refused, and at most limit+1 bytes were consumed.
        let big = vec![b'a'; MAX_REQUEST_LINE * 4];
        let mut r = BufReader::new(&big[..]);
        let err = read_request_line(&mut r).await.unwrap_err();
        assert_eq!(err.kind(), std::io::ErrorKind::InvalidData);
        let mut rest = Vec::new();
        r.read_to_end(&mut rest).await.unwrap();
        assert!(
            rest.len() >= MAX_REQUEST_LINE * 4 - MAX_REQUEST_LINE - 1 - 8192,
            "the reader must not have pulled the whole oversized input in"
        );
        let mut long_with_newline = vec![b'a'; MAX_REQUEST_LINE + 1];
        long_with_newline.push(b'\n');
        assert!(read(&long_with_newline).await.is_err());
    }

    #[tokio::test]
    async fn non_utf8_is_refused() {
        let err = read(b"\xff\xfe\n").await.unwrap_err();
        assert_eq!(err.kind(), std::io::ErrorKind::InvalidData);
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn the_service_answers_an_oversized_request_with_an_error_and_keeps_serving() {
        let dir = std::env::temp_dir().join(format!("etph-svc-big-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let sock = dir.join("c.sock");
        let opts = Options {
            control: sock.clone(),
            config: dir.join("none.toml"),
            ..Default::default()
        };
        let (tx, rx) = watch::channel(false);
        let svc = tokio::spawn(run(opts, rx));
        for _ in 0..50 {
            if sock.exists() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        let mut s = tokio::net::UnixStream::connect(&sock).await.unwrap();
        // 64 KiB with no newline: the service must reply and close, not buffer it all.
        let _ = s.write_all(&vec![b'a'; 64 * 1024]).await;
        let mut reply = String::new();
        let _ = BufReader::new(&mut s).read_line(&mut reply).await;
        assert!(reply.contains("too large"), "reply was {reply:?}");
        let r = request(
            &Request {
                cmd: "status".into(),
                ..Default::default()
            },
            &sock,
        )
        .await
        .unwrap();
        assert!(
            r.ok,
            "the service still answers after the oversized request"
        );
        tx.send(true).unwrap();
        svc.await.unwrap().unwrap();
        std::fs::remove_dir_all(&dir).ok();
    }
}
