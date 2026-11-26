//! A test-only `su` for emulators whose images grant root only to the adb shell.
//!
//! The root-mode end-to-end test needs the app uid to be able to run `su -c <cmd>`
//! (id, ip, iptables, `tun-pipe`) with working stdin/stdout, stderr and exit code.
//! `tun-pipe su-serve <socket>` runs as root (started from `adb root`) and runs each
//! requested command with `sh -c`; `tun-pipe su-client <socket> -c <cmd>` is what the
//! shim `su` execs. Nothing starts the server except someone who already is root.
//!
//! Wire format. Client to server: `u32 BE length`, the command, then raw stdin bytes
//! (a write shutdown is stdin EOF). Server to client: records `tag u8, len u32 BE,
//! data`; tag 1 = stdout, 2 = stderr, 3 = exit (4-byte i32 BE), after which the server closes.

use std::io::{self, Read, Write};
use std::net::Shutdown;
use std::os::unix::net::{UnixListener, UnixStream};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

const OUT: u8 = 1;
const ERR: u8 = 2;
const EXIT: u8 = 3;
const MAX_CMD: usize = 1 << 20;

fn send(w: &Mutex<UnixStream>, tag: u8, data: &[u8]) -> io::Result<()> {
    let mut s = w.lock().unwrap_or_else(|e| e.into_inner());
    s.write_all(&[tag])?;
    s.write_all(&(data.len() as u32).to_be_bytes())?;
    s.write_all(data)
}

fn forward<R: Read + Send + 'static>(mut r: R, tag: u8, w: Arc<Mutex<UnixStream>>) -> JoinHandle<()> {
    thread::spawn(move || {
        let mut buf = [0u8; 16384];
        loop {
            match r.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    if send(&w, tag, &buf[..n]).is_err() {
                        break;
                    }
                }
            }
        }
    })
}

/// Serves one client: runs its command and relays stdio and the exit code.
pub fn serve_conn(mut conn: UnixStream) -> io::Result<()> {
    let mut len = [0u8; 4];
    conn.read_exact(&mut len)?;
    let len = u32::from_be_bytes(len) as usize;
    if len == 0 || len > MAX_CMD {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "bad command length"));
    }
    let mut cmd = vec![0u8; len];
    conn.read_exact(&mut cmd)?;
    let cmd = String::from_utf8(cmd).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "command is not UTF-8"))?;
    let mut child = Command::new("sh")
        .arg("-c")
        .arg(&cmd)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?;
    let mut child_in = child.stdin.take().expect("piped stdin");
    let mut from_client = conn.try_clone()?;
    thread::spawn(move || {
        let _ = io::copy(&mut from_client, &mut child_in);
    });
    let w = Arc::new(Mutex::new(conn));
    let o = forward(child.stdout.take().expect("piped stdout"), OUT, w.clone());
    let e = forward(child.stderr.take().expect("piped stderr"), ERR, w.clone());
    let _ = o.join();
    let _ = e.join();
    let code = child.wait()?.code().unwrap_or(255);
    send(&w, EXIT, &code.to_be_bytes())?;
    let s = w.lock().unwrap_or_else(|e| e.into_inner());
    let _ = s.shutdown(Shutdown::Both);
    Ok(())
}

/// Listens on `path` (mode 0777: the app uid must connect) and serves every client on a thread.
pub fn serve(path: &str) -> io::Result<()> {
    let _ = std::fs::remove_file(path);
    let l = UnixListener::bind(path)?;
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o777))?;
    }
    for conn in l.incoming() {
        let conn = conn?;
        thread::spawn(move || {
            if let Err(e) = serve_conn(conn) {
                eprintln!("su-serve: {e}");
            }
        });
    }
    Ok(())
}

/// Runs `cmd` through the server at `path`; returns the command's exit code.
pub fn client<I, O, E>(path: &str, cmd: &str, stdin: I, out: &mut O, err: &mut E) -> io::Result<i32>
where
    I: Read + Send + 'static,
    O: Write,
    E: Write,
{
    let mut s = UnixStream::connect(path)?;
    s.write_all(&(cmd.len() as u32).to_be_bytes())?;
    s.write_all(cmd.as_bytes())?;
    let mut up = s.try_clone()?;
    thread::spawn(move || {
        let mut stdin = stdin;
        let _ = io::copy(&mut stdin, &mut up);
        let _ = up.shutdown(Shutdown::Write);
    });
    loop {
        let mut head = [0u8; 5];
        s.read_exact(&mut head).map_err(|e| {
            if e.kind() == io::ErrorKind::UnexpectedEof {
                io::Error::new(io::ErrorKind::UnexpectedEof, "su server closed before the exit code")
            } else {
                e
            }
        })?;
        let n = u32::from_be_bytes([head[1], head[2], head[3], head[4]]) as usize;
        let mut data = vec![0u8; n];
        s.read_exact(&mut data)?;
        match head[0] {
            OUT => {
                out.write_all(&data)?;
                out.flush()?;
            }
            ERR => {
                err.write_all(&data)?;
                err.flush()?;
            }
            EXIT if n == 4 => return Ok(i32::from_be_bytes([data[0], data[1], data[2], data[3]])),
            t => return Err(io::Error::new(io::ErrorKind::InvalidData, format!("bad record tag {t}"))),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn run(cmd: &str, stdin: &[u8]) -> (i32, String, String) {
        let path = std::env::temp_dir().join(format!("su-bridge-{}-{}.sock", std::process::id(), cmd.len()));
        let path = path.to_str().unwrap().to_string();
        let _ = std::fs::remove_file(&path);
        let l = UnixListener::bind(&path).unwrap();
        let server = thread::spawn(move || serve_conn(l.accept().unwrap().0).unwrap());
        let (mut o, mut e) = (Vec::new(), Vec::new());
        let code = client(&path, cmd, Cursor::new(stdin.to_vec()), &mut o, &mut e).unwrap();
        server.join().unwrap();
        let _ = std::fs::remove_file(&path);
        (code, String::from_utf8(o).unwrap(), String::from_utf8(e).unwrap())
    }

    #[test]
    fn exit_code_stdout_and_stderr_come_back_separately() {
        assert_eq!(run("printf hi; printf err >&2; exit 3", b""), (3, "hi".into(), "err".into()));
    }

    #[test]
    fn stdin_reaches_the_command_and_its_eof_ends_it() {
        assert_eq!(run("cat", b"abc\ndef"), (0, "abc\ndef".into(), String::new()));
    }

    #[test]
    fn a_command_with_newlines_and_quotes_runs_as_one_script() {
        assert_eq!(run("a='x y'\necho \"$a\"", b"").1, "x y\n");
    }
}
