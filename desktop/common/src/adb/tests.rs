//! The ADB host against a fake adbd over TCP: auth with a known key, the
//! "Allow USB debugging?" flow for a new key, a stream to a service with
//! ADB flow control, refusal, and the model from the banner.

use super::key::AdbKey;
use super::proto::*;
use super::*;
use std::net::TcpListener;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::OnceLock;

fn key() -> &'static AdbKey {
    static K: OnceLock<AdbKey> = OnceLock::new();
    K.get_or_init(|| {
        let dir = std::env::temp_dir().join(format!("etph-adbkey-{}", std::process::id()));
        AdbKey::load_or_create(&dir, "root@OpenWrt").unwrap()
    })
}

fn send(s: &mut TcpStream, h: Header, data: &[u8]) {
    let mut b = h.encode().to_vec();
    b.extend_from_slice(data);
    s.write_all(&b).unwrap();
}

fn recv(s: &mut TcpStream) -> (Header, Vec<u8>) {
    let mut h = [0u8; 24];
    s.read_exact(&mut h).unwrap();
    let h = Header::decode(&h).unwrap();
    let mut d = vec![0u8; h.data_length as usize];
    s.read_exact(&mut d).unwrap();
    assert_eq!(checksum(&d), h.data_check, "host sends payload checksums");
    (h, d)
}

struct Fake {
    addr: String,
    offered_key: Arc<Mutex<Option<String>>>,
}

/// A fake adbd: trusts `trusted` (or nobody), accepts a public key after
/// "the user taps Allow", serves "tcp:<port>" by connecting to 127.0.0.1.
fn fake_adbd(trust_our_key: bool) -> Fake {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = l.local_addr().unwrap().to_string();
    let offered = Arc::new(Mutex::new(None));
    let off = offered.clone();
    std::thread::spawn(move || {
        let (mut s, _) = l.accept().unwrap();
        let (h, banner) = recv(&mut s);
        assert_eq!(h.command, A_CNXN);
        assert!(banner.starts_with(b"host::"));
        let token: Vec<u8> = (0..20u8).map(|i| i.wrapping_mul(37).wrapping_add(5)).collect();
        send(&mut s, Header::new(A_AUTH, AUTH_TOKEN, 0, &token), &token);
        let (h, sig) = recv(&mut s);
        assert_eq!((h.command, h.arg0), (A_AUTH, AUTH_SIGNATURE));
        let ok = key().verify(&token, &sig);
        assert!(ok, "the signature must verify with the host's key");
        if !trust_our_key {
            send(&mut s, Header::new(A_AUTH, AUTH_TOKEN, 0, &token), &token);
            let (h, pk) = recv(&mut s);
            assert_eq!((h.command, h.arg0), (A_AUTH, AUTH_RSAPUBLICKEY));
            *off.lock().unwrap() = Some(String::from_utf8_lossy(&pk).trim_end_matches('\0').to_string());
            std::thread::sleep(Duration::from_millis(300)); // the user reads the dialog
        }
        let b = b"device::ro.product.name=p;ro.product.model=Pixel 8;ro.product.device=shiba;features=shell_v2\0";
        send(&mut s, Header::new(A_CNXN, VERSION, 4096, b), b);
        let (h, svc) = recv(&mut s);
        assert_eq!(h.command, A_OPEN);
        let local = h.arg0;
        let svc = String::from_utf8_lossy(&svc).trim_end_matches('\0').to_string();
        let port: u16 = svc.strip_prefix("tcp:").and_then(|p| p.parse().ok()).unwrap_or(0);
        let Ok(mut target) = TcpStream::connect(("127.0.0.1", port)) else {
            send(&mut s, Header::new(A_CLSE, 0, local, &[]), &[]);
            return;
        };
        const REMOTE: u32 = 77;
        send(&mut s, Header::new(A_OKAY, REMOTE, local, &[]), &[]);
        let mut t2 = target.try_clone().unwrap();
        let mut s2 = s.try_clone().unwrap();
        let (okay_tx, okay_rx) = mpsc::channel::<()>();
        let wlock = Arc::new(Mutex::new(()));
        let wl2 = wlock.clone();
        // target -> host, one WRTE in flight
        std::thread::spawn(move || {
            let mut buf = [0u8; 4096];
            loop {
                let n = match t2.read(&mut buf) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => n,
                };
                {
                    let _g = wl2.lock().unwrap();
                    send(&mut s2, Header::new(A_WRTE, REMOTE, local, &buf[..n]), &buf[..n]);
                }
                if okay_rx.recv_timeout(Duration::from_secs(10)).is_err() {
                    break;
                }
            }
        });
        // host -> target
        loop {
            let mut hb = [0u8; 24];
            if s.read_exact(&mut hb).is_err() {
                break;
            }
            let h = Header::decode(&hb).unwrap();
            let mut d = vec![0u8; h.data_length as usize];
            s.read_exact(&mut d).unwrap();
            match h.command {
                A_WRTE => {
                    assert!(d.len() <= 4096, "host must respect the device's max payload");
                    target.write_all(&d).unwrap();
                    let _g = wlock.lock().unwrap();
                    send(&mut s, Header::new(A_OKAY, REMOTE, local, &[]), &[]);
                }
                A_OKAY => {
                    let _ = okay_tx.send(());
                }
                A_CLSE => break,
                _ => {}
            }
        }
    });
    Fake {
        addr,
        offered_key: offered,
    }
}

fn echo_server() -> u16 {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    std::thread::spawn(move || {
        let (mut s, _) = l.accept().unwrap();
        let mut r = s.try_clone().unwrap();
        std::io::copy(&mut r, &mut s).ok();
    });
    port
}

struct Seen(AtomicBool, Mutex<String>);
impl AuthObserver for Seen {
    fn waiting_for_allow(&self, fp: &str) {
        self.0.store(true, Ordering::SeqCst);
        *self.1.lock().unwrap() = fp.to_string();
    }
}

async fn roundtrip(trusted: bool) {
    let port = echo_server();
    let fake = fake_adbd(trusted);
    let link: Arc<dyn Link> = Arc::new(TcpLink::connect(&fake.addr, Duration::from_secs(5)).unwrap());
    let seen = Arc::new(Seen(AtomicBool::new(false), Mutex::new(String::new())));
    let seen2 = seen.clone();
    let st = tokio::task::spawn_blocking(move || {
        open_stream(link, key(), &*seen2, Duration::from_secs(10), &format!("tcp:{port}"))
    })
    .await
    .unwrap()
    .unwrap();
    assert_eq!(st.connected.model().as_deref(), Some("Pixel 8"));
    assert_eq!(st.connected.max_data, 4096);
    assert_eq!(seen.0.load(Ordering::SeqCst), !trusted, "Allow prompt reported iff the key is new");
    if !trusted {
        let offered = fake.offered_key.lock().unwrap().clone().unwrap();
        assert_eq!(offered, key().public_key_line());
        assert_eq!(*seen.1.lock().unwrap(), key().fingerprint());
    }
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let (mut r, mut w) = tokio::io::split(st.io);
    let data: Vec<u8> = (0..300_000u32).map(|i| (i * 7 + i / 255) as u8).collect();
    let d2 = data.clone();
    let writer = tokio::spawn(async move {
        w.write_all(&d2).await.unwrap();
        w
    });
    let mut got = vec![0u8; data.len()];
    tokio::time::timeout(Duration::from_secs(30), r.read_exact(&mut got))
        .await
        .expect("echo through the adb stream in time")
        .unwrap();
    assert!(got == data, "bytes through the ADB stream changed");
    drop(writer.await.unwrap());
}

#[tokio::test(flavor = "multi_thread")]
async fn known_key_signs_the_token_and_streams_both_ways() {
    roundtrip(true).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn new_key_is_offered_for_the_allow_prompt() {
    roundtrip(false).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn a_closed_port_on_the_phone_is_a_clear_error() {
    let fake = fake_adbd(true);
    let link: Arc<dyn Link> = Arc::new(TcpLink::connect(&fake.addr, Duration::from_secs(5)).unwrap());
    let r = tokio::task::spawn_blocking(move || open_stream(link, key(), &NoObserver, Duration::from_secs(5), "tcp:1"))
        .await
        .unwrap();
    let e = format!("{:#}", r.err().expect("must fail"));
    assert!(e.contains("is ETPH started in USB mode"), "{e}");
}

#[test]
fn android_public_key_layout_and_key_files() {
    let k = key();
    let blob = k.android_public_key();
    assert_eq!(blob.len(), 524);
    assert_eq!(&blob[0..4], &64u32.to_le_bytes());
    assert_eq!(&blob[520..524], &65537u32.to_le_bytes());
    // n0inv * n[0] == -1 mod 2^32
    let n0inv = u32::from_le_bytes(blob[4..8].try_into().unwrap());
    let n0 = u32::from_le_bytes(blob[8..12].try_into().unwrap());
    assert_eq!(n0inv.wrapping_mul(n0), u32::MAX);
    // Reloading the saved key gives the same public key and fingerprint.
    let dir = std::env::temp_dir().join(format!("etph-adbkey-{}", std::process::id()));
    let again = AdbKey::load_or_create(&dir, "root@OpenWrt").unwrap();
    assert_eq!(again.public_key_line(), k.public_key_line());
    let pubfile = std::fs::read_to_string(dir.join("adbkey.pub")).unwrap();
    assert_eq!(crate::host::adb_key_fingerprint(&pubfile).unwrap(), k.fingerprint());
    assert_eq!(k.fingerprint().len(), 47);
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = std::fs::metadata(dir.join("adbkey")).unwrap().permissions().mode();
        assert_eq!(mode & 0o077, 0, "private key must not be readable by others");
    }
}
