//! The AOA start-of-session preamble, and the byte pump between an
//! accessory link and the async session.
//!
//! Wire format: three fixed 24-byte messages, 16-byte magic + 8-byte nonce.
//! The magics start with two NUL bytes, so they can never be mistaken for
//! an ETPH frame (which starts with "ET") by a phone still parsing a stale
//! session: that session fails at once and the phone goes back to looking
//! for HELLO.
//!
//!   host  -> phone  HELLO+n   (repeated every second until answered)
//!   phone -> host   READY+n   (for the newest HELLO it saw)
//!   host  -> phone  START+n   (the phone skips everything up to here)
//!   ... ETPH protocol (HandshakeRequest first) ...
//!
//! The phone side is android/core .../session/AccessoryPreamble.kt.

use anyhow::{bail, Result};
use log::debug;
use std::io;
use std::sync::Arc;
use std::time::{Duration, Instant};

pub const MAGIC_LEN: usize = 16;
pub const NONCE_LEN: usize = 8;
pub const MSG_LEN: usize = MAGIC_LEN + NONCE_LEN;
pub const HELLO: &[u8; MAGIC_LEN] = b"\0\0ETPH-AOA-HELLO";
pub const READY: &[u8; MAGIC_LEN] = b"\0\0ETPH-AOA-READY";
pub const START: &[u8; MAGIC_LEN] = b"\0\0ETPH-AOA-START";

pub type Nonce = [u8; NONCE_LEN];

pub fn message(magic: &[u8; MAGIC_LEN], nonce: &Nonce) -> [u8; MSG_LEN] {
    let mut m = [0u8; MSG_LEN];
    m[..MAGIC_LEN].copy_from_slice(magic);
    m[MAGIC_LEN..].copy_from_slice(nonce);
    m
}

/// First complete `magic` message in `buf` (with `nonce`, when given):
/// (offset of the message, its nonce).
pub fn find(buf: &[u8], magic: &[u8; MAGIC_LEN], nonce: Option<&Nonce>) -> Option<(usize, Nonce)> {
    if buf.len() < MSG_LEN {
        return None;
    }
    (0..=buf.len() - MSG_LEN).find_map(|i| {
        if &buf[i..i + MAGIC_LEN] != magic {
            return None;
        }
        let mut n = [0u8; NONCE_LEN];
        n.copy_from_slice(&buf[i + MAGIC_LEN..i + MSG_LEN]);
        match nonce {
            Some(want) if *want != n => None,
            _ => Some((i, n)),
        }
    })
}

pub fn new_nonce() -> Nonce {
    use rand_core::RngCore;
    let mut n = [0u8; NONCE_LEN];
    rand_core::OsRng.fill_bytes(&mut n);
    n
}

/// A raw accessory byte link (bulk endpoints; a socket pair in tests).
pub trait RawLink: Send + Sync {
    /// Write all of `data`. `TimedOut` = the phone is not reading (the app
    /// has not opened the accessory yet).
    fn write(&self, data: &[u8], timeout: Duration) -> io::Result<()>;
    /// Next chunk of bytes; `Ok(None)` when nothing arrived within `timeout`.
    fn read(&self, timeout: Duration) -> io::Result<Option<Vec<u8>>>;
    /// Unblock readers and writers (best effort).
    fn close(&self);
}

/// Host side of the preamble. `waiting` is called once if the phone has not
/// answered after a few seconds (the user still has to open ETPH / tap OK).
/// Returns bytes that arrived after READY (normally none).
pub fn host_handshake(
    link: &dyn RawLink,
    nonce: Nonce,
    wait: Duration,
    waiting: &dyn Fn(),
) -> Result<Vec<u8>> {
    let hello = message(HELLO, &nonce);
    let start = Instant::now();
    let deadline = start + wait;
    let mut last_hello: Option<Instant> = None;
    let mut told = false;
    let mut buf: Vec<u8> = Vec::new();
    loop {
        let now = Instant::now();
        if now > deadline {
            bail!(
                "the phone did not answer on the accessory link within {}s (open ETPH on the phone and \
                 tap OK on \"Open ETPH ...?\")",
                wait.as_secs()
            );
        }
        if !told && now.duration_since(start) > Duration::from_secs(3) {
            waiting();
            told = true;
        }
        if last_hello.is_none_or(|t| now.duration_since(t) >= Duration::from_secs(1)) {
            match link.write(&hello, Duration::from_millis(1000)) {
                Ok(()) => {}
                // Nobody reads on the phone yet: keep trying.
                Err(e) if e.kind() == io::ErrorKind::TimedOut => debug!("aoa: HELLO not taken yet"),
                Err(e) => return Err(e.into()),
            }
            last_hello = Some(Instant::now());
        }
        if let Some(chunk) = link.read(Duration::from_millis(250))? {
            buf.extend_from_slice(&chunk);
            if let Some((at, _)) = find(&buf, READY, Some(&nonce)) {
                let rest = buf[at + MSG_LEN..].to_vec();
                link.write(&message(START, &nonce), Duration::from_secs(5))?;
                return Ok(rest);
            }
            // Keep only a possible partial message at the end.
            if buf.len() > MSG_LEN {
                buf.drain(..buf.len() - (MSG_LEN - 1));
            }
        }
    }
}

/// Pump bytes between the link and one end of a tokio duplex pipe (the
/// session's stream). `leftover` is delivered first.
pub fn bridge(link: Arc<dyn RawLink>, leftover: Vec<u8>, chunk: usize) -> tokio::io::DuplexStream {
    let (ours, theirs) = tokio::io::duplex(512 * 1024);
    let (mut their_read, mut their_write) = tokio::io::split(theirs);
    let (down_tx, mut down_rx) = tokio::sync::mpsc::unbounded_channel::<Vec<u8>>();
    if !leftover.is_empty() {
        let _ = down_tx.send(leftover);
    }
    let closed = Arc::new(std::sync::atomic::AtomicBool::new(false));
    // Phone -> pipe.
    {
        let link = link.clone();
        let closed = closed.clone();
        std::thread::spawn(move || {
            while !closed.load(std::sync::atomic::Ordering::Relaxed) {
                match link.read(Duration::from_millis(500)) {
                    Ok(None) => continue,
                    Ok(Some(d)) => {
                        if down_tx.send(d).is_err() {
                            break;
                        }
                    }
                    Err(e) => {
                        debug!("aoa link read ended: {e}");
                        break;
                    }
                }
            }
            closed.store(true, std::sync::atomic::Ordering::Relaxed);
        });
    }
    // Pipe -> phone: whatever is buffered, up to `chunk` bytes per write.
    let (up_tx, up_rx) = std::sync::mpsc::sync_channel::<Vec<u8>>(8);
    {
        let link = link.clone();
        let closed = closed.clone();
        std::thread::spawn(move || {
            while let Ok(d) = up_rx.recv() {
                if let Err(e) = link.write(&d, Duration::from_secs(10)) {
                    debug!("aoa link write ended: {e}");
                    break;
                }
            }
            closed.store(true, std::sync::atomic::Ordering::Relaxed);
            link.close();
        });
    }
    tokio::spawn(async move {
        use tokio::io::AsyncReadExt;
        let mut buf = vec![0u8; chunk];
        loop {
            match their_read.read(&mut buf).await {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    let tx = up_tx.clone();
                    let d = buf[..n].to_vec();
                    if tokio::task::spawn_blocking(move || tx.send(d))
                        .await
                        .map(|r| r.is_err())
                        .unwrap_or(true)
                    {
                        break;
                    }
                }
            }
        }
        drop(up_tx);
    });
    tokio::spawn(async move {
        use tokio::io::AsyncWriteExt;
        while let Some(d) = down_rx.recv().await {
            if their_write.write_all(&d).await.is_err() {
                break;
            }
        }
        let _ = their_write.shutdown().await;
    });
    ours
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;
    use std::sync::Mutex;

    /// One direction of a fake bulk pipe.
    type Pipe = (Mutex<mpsc::Sender<Vec<u8>>>, Mutex<mpsc::Receiver<Vec<u8>>>);

    /// Host end of a fake accessory: what the host writes goes to the
    /// phone thread; `reading` says whether the phone has opened the
    /// accessory (before that, writes time out like a NAKed bulk OUT).
    struct FakeLink {
        pipe: Pipe,
        reading: Arc<std::sync::atomic::AtomicBool>,
    }

    impl RawLink for FakeLink {
        fn write(&self, data: &[u8], _t: Duration) -> io::Result<()> {
            if !self.reading.load(std::sync::atomic::Ordering::SeqCst) {
                return Err(io::ErrorKind::TimedOut.into());
            }
            self.pipe
                .0
                .lock()
                .unwrap()
                .send(data.to_vec())
                .map_err(|_| io::ErrorKind::BrokenPipe.into())
        }
        fn read(&self, t: Duration) -> io::Result<Option<Vec<u8>>> {
            match self.pipe.1.lock().unwrap().recv_timeout(t) {
                Ok(d) => Ok(Some(d)),
                Err(mpsc::RecvTimeoutError::Timeout) => Ok(None),
                Err(_) => Err(io::ErrorKind::BrokenPipe.into()),
            }
        }
        fn close(&self) {}
    }

    /// The phone side, as AccessoryPreamble.kt does it: scan for HELLO,
    /// answer READY for the newest one, skip to START, return what follows.
    fn phone_preamble(rx: &mpsc::Receiver<Vec<u8>>, tx: &mpsc::Sender<Vec<u8>>) -> Vec<u8> {
        let mut buf = Vec::new();
        let mut answered: Option<Nonce> = None;
        loop {
            buf.extend_from_slice(&rx.recv().unwrap());
            if let Some(n) = answered {
                if let Some((at, _)) = find(&buf, START, Some(&n)) {
                    return buf[at + MSG_LEN..].to_vec();
                }
            }
            // Newest HELLO wins.
            let mut from = 0;
            let mut newest = None;
            while let Some((at, n)) = find(&buf[from..], HELLO, None) {
                newest = Some(n);
                from += at + MSG_LEN;
            }
            if let Some(n) = newest {
                if answered != Some(n) {
                    tx.send(message(READY, &n).to_vec()).unwrap();
                    answered = Some(n);
                }
            }
        }
    }

    fn setup(open_after: Duration, stale: Vec<u8>) -> (FakeLink, std::thread::JoinHandle<Vec<u8>>) {
        let (h2p_tx, h2p_rx) = mpsc::channel::<Vec<u8>>();
        let (p2h_tx, p2h_rx) = mpsc::channel::<Vec<u8>>();
        let reading = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let r2 = reading.clone();
        // Stale bytes from an earlier session wait in the host's direction.
        if !stale.is_empty() {
            p2h_tx.send(stale).unwrap();
        }
        let phone = std::thread::spawn(move || {
            std::thread::sleep(open_after);
            r2.store(true, std::sync::atomic::Ordering::SeqCst);
            let rest = phone_preamble(&h2p_rx, &p2h_tx);
            // Echo the first ETPH bytes back, like a session would answer.
            let mut got = rest;
            while got.len() < 5 {
                got.extend_from_slice(&h2p_rx.recv().unwrap());
            }
            p2h_tx.send(b"EThello".to_vec()).unwrap();
            got
        });
        (
            FakeLink {
                pipe: (Mutex::new(h2p_tx), Mutex::new(p2h_rx)),
                reading,
            },
            phone,
        )
    }

    #[test]
    fn handshake_then_stream() {
        let (link, phone) = setup(Duration::from_millis(0), vec![]);
        let rest = host_handshake(&link, [7; 8], Duration::from_secs(5), &|| {}).unwrap();
        assert!(rest.is_empty());
        link.write(b"ETabc", Duration::from_secs(1)).unwrap();
        assert_eq!(phone.join().unwrap(), b"ETabc");
        assert_eq!(
            link.read(Duration::from_secs(1)).unwrap().unwrap(),
            b"EThello"
        );
    }

    #[test]
    fn waits_for_the_app_and_ignores_stale_bytes() {
        // The app opens the accessory 2.5 s late (the user tapping OK), and an
        // old session left frames plus an old READY in the pipe.
        let mut stale = b"ET\x01\x00\x10garbage-from-an-old-session".to_vec();
        stale.extend_from_slice(&message(READY, &[1; 8]));
        let (link, phone) = setup(Duration::from_millis(2500), stale);
        let told = std::sync::atomic::AtomicBool::new(false);
        let rest = host_handshake(&link, [9; 8], Duration::from_secs(10), &|| {
            told.store(true, std::sync::atomic::Ordering::SeqCst)
        })
        .unwrap();
        assert!(rest.is_empty());
        link.write(b"ETxyz", Duration::from_secs(1)).unwrap();
        assert_eq!(phone.join().unwrap(), b"ETxyz");
    }

    #[test]
    fn says_what_to_do_when_the_phone_never_answers() {
        let (link, _phone) = setup(Duration::from_secs(3600), vec![]);
        let told = std::sync::atomic::AtomicBool::new(false);
        let e = host_handshake(&link, [3; 8], Duration::from_millis(3500), &|| {
            told.store(true, std::sync::atomic::Ordering::SeqCst)
        })
        .unwrap_err();
        assert!(e.to_string().contains("open ETPH"), "{e}");
        assert!(told.load(std::sync::atomic::Ordering::SeqCst));
    }

    #[test]
    fn find_respects_the_nonce_and_partial_messages() {
        let mut b = b"xx".to_vec();
        b.extend_from_slice(&message(READY, &[1; 8]));
        b.extend_from_slice(&message(READY, &[2; 8]));
        assert_eq!(find(&b, READY, Some(&[2; 8])).unwrap().0, 2 + MSG_LEN);
        assert_eq!(find(&b, READY, None).unwrap(), (2, [1; 8]));
        assert!(find(&b[..MSG_LEN], READY, None).is_none());
        // The magics can never start an ETPH frame ("ET").
        for m in [HELLO, READY, START] {
            assert_ne!(&m[..2], b"ET");
        }
    }
}
