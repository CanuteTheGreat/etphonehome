//! TUN devices on macOS (utun, through the kernel-control socket API, no
//! kernel extension) and the BSDs (tun(4)): FreeBSD, and the closely related
//! DragonFly, NetBSD and OpenBSD.
//!
//! Both deliver one IP packet per read with a 4-byte address-family header
//! in front (utun always; tun(4) after `TUNSIFHEAD`, OpenBSD always). This
//! module strips it on read and adds it on write, so the session code sees
//! plain IP packets exactly like on Linux (`IFF_NO_PI`) and Windows (Wintun).
//!
//! Addresses are set with ifconfig(8), which every one of these systems
//! ships in its base install; the interface (and every route through it)
//! disappears when the device is dropped.

use anyhow::{bail, Context, Result};
use log::{debug, info};
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::pin::Pin;
use std::process::Command;
use std::task::{ready, Context as TaskContext, Poll};
use tokio::io::unix::AsyncFd;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

/// Address-family header values (host `AF_INET`/`AF_INET6`, big-endian).
fn af_header(packet: &[u8]) -> [u8; 4] {
    let af = if packet.first().map(|b| b >> 4) == Some(6) {
        libc::AF_INET6
    } else {
        libc::AF_INET
    } as u32;
    af.to_be_bytes()
}

/// An open TUN device, readable/writable from tokio.
pub struct AsyncTun {
    /// Closed by hand in `drop`, before the interface is destroyed.
    fd: std::mem::ManuallyDrop<AsyncFd<OwnedFd>>,
    name: String,
    /// FreeBSD/DragonFly/NetBSD: cloned interfaces outlive their descriptor
    /// on some releases, so destroy it explicitly on drop.
    destroy_on_drop: bool,
    rbuf: Vec<u8>,
}

impl std::fmt::Debug for AsyncTun {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AsyncTun")
            .field("name", &self.name)
            .finish()
    }
}

impl AsyncTun {
    /// The interface name the system gave it (`utun5`, `etph0`, `tun3`).
    pub fn name(&self) -> &str {
        &self.name
    }
}

fn set_nonblocking(fd: RawFd) -> io::Result<()> {
    // SAFETY: plain fcntl on a descriptor we own.
    unsafe {
        let flags = libc::fcntl(fd, libc::F_GETFL);
        if flags < 0 || libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) < 0 {
            return Err(io::Error::last_os_error());
        }
        libc::fcntl(fd, libc::F_SETFD, libc::FD_CLOEXEC);
    }
    Ok(())
}

fn run(cmd: &str, args: &[&str]) -> Result<()> {
    debug!("{} {}", cmd, args.join(" "));
    let out = Command::new(cmd)
        .args(args)
        .output()
        .with_context(|| format!("cannot run {cmd}"))?;
    if !out.status.success() {
        bail!(
            "{} {} failed: {}",
            cmd,
            args.join(" "),
            String::from_utf8_lossy(&out.stderr).trim()
        );
    }
    Ok(())
}

/// Give the interface its point-to-point addresses and bring it up.
fn configure(name: &str, local: &str, peer: &str, netmask: &str, mtu: u16) -> Result<()> {
    run(
        "ifconfig",
        &[
            name,
            "inet",
            local,
            peer,
            "netmask",
            netmask,
            "mtu",
            &mtu.to_string(),
            "up",
        ],
    )
}

/// Create the TUN device, address it and bring it up.
///
/// `name` is a wish: macOS always names utun devices itself (utun0..), so
/// there it only matters if it is already a `utunN` name. On the BSDs the
/// device is renamed to `name` (so firewalls like OPNsense/pfSense can
/// assign it by a stable name) when that name is free.
pub fn create(name: &str, local: &str, peer: &str, netmask: &str, mtu: u16) -> Result<AsyncTun> {
    let (fd, sys_name, destroy) = open_device(name)?;
    set_nonblocking(fd.as_raw_fd())?;
    #[allow(unused_mut)] // renamed below on the BSDs only
    let mut tun = AsyncTun {
        // `new` is deprecated in newest tokio for I/O-safety reasons that
        // don't apply here: we own this descriptor exclusively (OwnedFd).
        #[allow(deprecated)]
        fd: std::mem::ManuallyDrop::new(
            AsyncFd::new(fd).context("cannot register the TUN device with tokio")?,
        ),
        name: sys_name,
        destroy_on_drop: destroy,
        rbuf: vec![0u8; 65536 + 4],
    };
    #[cfg(not(target_os = "macos"))]
    if !name.is_empty() && name != tun.name {
        match run("ifconfig", &[&tun.name, "name", name]) {
            Ok(()) => tun.name = name.to_string(),
            Err(e) => log::warn!("keeping interface name {} ({:#})", tun.name, e),
        }
    }
    configure(&tun.name, local, peer, netmask, mtu)?;
    info!(
        "TUN interface {} up ({} -> {}, {})",
        tun.name, local, peer, netmask
    );
    Ok(tun)
}

/// Put the point-to-point address back if something removed it; true = it
/// was missing. Firewalls reconfigure an interface the admin assigned when
/// its settings are applied: pfSense with "IPv4 Configuration Type: None"
/// strips every IPv4 address from it, which would leave the uplink dead
/// until the next reconnect.
#[cfg(not(target_os = "macos"))]
pub fn ensure_address(
    name: &str,
    local: &str,
    peer: &str,
    netmask: &str,
    mtu: u16,
) -> Result<bool> {
    let out = Command::new("ifconfig")
        .args([name, "inet"])
        .output()
        .context("cannot run ifconfig")?;
    if !out.status.success() {
        bail!(
            "ifconfig {} inet failed: {}",
            name,
            String::from_utf8_lossy(&out.stderr).trim()
        );
    }
    let (l, p) = (
        local.parse().context("local address")?,
        peer.parse().context("peer address")?,
    );
    if crate::host::ifconfig_has_p2p(&String::from_utf8_lossy(&out.stdout), l, p) {
        return Ok(false);
    }
    configure(name, local, peer, netmask, mtu)?;
    Ok(true)
}

#[cfg(target_os = "macos")]
fn open_device(name: &str) -> Result<(OwnedFd, String, bool)> {
    // utun unit N+1 = utunN; 0 = the first free one.
    let unit: u32 = name
        .strip_prefix("utun")
        .and_then(|n| n.parse::<u32>().ok())
        .map(|n| n + 1)
        .unwrap_or(0);
    // SAFETY: socket(2) with constant arguments.
    let raw = unsafe { libc::socket(libc::PF_SYSTEM, libc::SOCK_DGRAM, libc::SYSPROTO_CONTROL) };
    if raw < 0 {
        return Err(io::Error::last_os_error()).context("cannot open a kernel control socket");
    }
    // SAFETY: raw is a fresh descriptor we own.
    let fd = unsafe { OwnedFd::from_raw_fd(raw) };
    // SAFETY: ctl_info is plain data; zeroed is a valid value.
    let mut info: libc::ctl_info = unsafe { std::mem::zeroed() };
    for (dst, src) in info
        .ctl_name
        .iter_mut()
        .zip(b"com.apple.net.utun_control\0".iter())
    {
        *dst = *src as libc::c_char;
    }
    // SAFETY: CTLIOCGINFO fills in the ctl_info we pass.
    if unsafe { libc::ioctl(fd.as_raw_fd(), libc::CTLIOCGINFO, &mut info) } < 0 {
        return Err(io::Error::last_os_error()).context("utun kernel control not found");
    }
    // SAFETY: sockaddr_ctl is plain data.
    let mut addr: libc::sockaddr_ctl = unsafe { std::mem::zeroed() };
    addr.sc_len = std::mem::size_of::<libc::sockaddr_ctl>() as u8;
    addr.sc_family = libc::AF_SYSTEM as u8;
    addr.ss_sysaddr = libc::AF_SYS_CONTROL as u16;
    addr.sc_id = info.ctl_id;
    addr.sc_unit = unit;
    // SAFETY: addr is a valid sockaddr_ctl of the given length.
    let r = unsafe {
        libc::connect(
            fd.as_raw_fd(),
            &addr as *const _ as *const libc::sockaddr,
            std::mem::size_of::<libc::sockaddr_ctl>() as libc::socklen_t,
        )
    };
    if r < 0 {
        let e = io::Error::last_os_error();
        if e.raw_os_error() == Some(libc::EPERM) || e.raw_os_error() == Some(libc::EACCES) {
            bail!("creating a utun interface needs root: run with sudo (or use the ETPH menu-bar app, whose helper runs as root)");
        }
        return Err(e).context("cannot create a utun interface");
    }
    let mut buf = [0u8; 64];
    let mut len = buf.len() as libc::socklen_t;
    // SAFETY: UTUN_OPT_IFNAME writes at most `len` bytes into buf.
    let r = unsafe {
        libc::getsockopt(
            fd.as_raw_fd(),
            libc::SYSPROTO_CONTROL,
            libc::UTUN_OPT_IFNAME,
            buf.as_mut_ptr() as *mut libc::c_void,
            &mut len,
        )
    };
    if r < 0 {
        return Err(io::Error::last_os_error()).context("cannot read the utun interface name");
    }
    let n = buf.iter().position(|&b| b == 0).unwrap_or(len as usize);
    let sys = String::from_utf8_lossy(&buf[..n]).to_string();
    if !name.is_empty() && !name.starts_with("utun") {
        info!(
            "macOS names tunnel interfaces itself: using {} (asked for {})",
            sys, name
        );
    }
    Ok((fd, sys, false))
}

#[cfg(not(target_os = "macos"))]
fn open_device(name: &str) -> Result<(OwnedFd, String, bool)> {
    // tun(4): each /dev/tunN is created on first open (devfs cloning on
    // FreeBSD/DragonFly; NetBSD/OpenBSD have tun0..tun3 by default) and
    // refuses a second open with EBUSY.
    let mut last = None;
    let first = name
        .strip_prefix("tun")
        .and_then(|n| n.parse::<u32>().ok())
        .unwrap_or(0);
    for n in (first..first + 1).chain(0..256) {
        let path = format!("/dev/tun{n}\0");
        // SAFETY: path is NUL-terminated.
        let raw = unsafe { libc::open(path.as_ptr() as *const libc::c_char, libc::O_RDWR) };
        if raw < 0 {
            let e = io::Error::last_os_error();
            if e.raw_os_error() == Some(libc::EACCES) || e.raw_os_error() == Some(libc::EPERM) {
                bail!("opening /dev/tun{n} needs root: run with sudo/doas");
            }
            last = Some(e);
            continue;
        }
        // SAFETY: raw is a fresh descriptor we own.
        let fd = unsafe { OwnedFd::from_raw_fd(raw) };
        multi_af_header(&fd)?;
        return Ok((fd, format!("tun{n}"), true));
    }
    Err(last.unwrap_or_else(|| io::Error::from(io::ErrorKind::NotFound)))
        .context("no free /dev/tunN (is the tun driver loaded? FreeBSD: kldload if_tun)")
}

/// Ask tun(4) for the 4-byte address-family header (`TUNSIFHEAD`), so IPv6
/// packets are framed the same way as IPv4. OpenBSD always prepends it.
#[cfg(not(target_os = "macos"))]
fn multi_af_header(fd: &OwnedFd) -> Result<()> {
    #[cfg(any(target_os = "freebsd", target_os = "dragonfly"))]
    const TUNSIFHEAD: libc::c_ulong = 0x8004_7460; // _IOW('t', 96, int)
    #[cfg(target_os = "netbsd")]
    const TUNSIFHEAD: libc::c_ulong = 0x8004_7442; // _IOW('t', 66, int)
    #[cfg(not(target_os = "openbsd"))]
    {
        let on: libc::c_int = 1;
        // SAFETY: TUNSIFHEAD takes a pointer to an int.
        if unsafe { libc::ioctl(fd.as_raw_fd(), TUNSIFHEAD as _, &on) } < 0 {
            return Err(io::Error::last_os_error()).context("TUNSIFHEAD");
        }
    }
    let _ = fd;
    Ok(())
}

impl Drop for AsyncTun {
    fn drop(&mut self) {
        // SAFETY: dropped exactly once, here; never used afterwards.
        unsafe { std::mem::ManuallyDrop::drop(&mut self.fd) };
        if self.destroy_on_drop {
            // Descriptor closed above; now remove the cloned interface.
            let _ = Command::new("ifconfig")
                .args([self.name.as_str(), "destroy"])
                .output();
        }
    }
}

impl AsyncRead for AsyncTun {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        loop {
            let mut guard = ready!(this.fd.poll_read_ready(cx))?;
            let rbuf = &mut this.rbuf;
            match guard.try_io(|fd| {
                // SAFETY: rbuf is a live buffer of rbuf.len() bytes.
                let n = unsafe {
                    libc::read(
                        fd.as_raw_fd(),
                        rbuf.as_mut_ptr() as *mut libc::c_void,
                        rbuf.len(),
                    )
                };
                if n < 0 {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(n as usize)
                }
            }) {
                Ok(Ok(n)) if n <= 4 => continue, // header only: nothing to deliver
                Ok(Ok(n)) => {
                    let pkt = &this.rbuf[4..n];
                    let take = pkt.len().min(buf.remaining());
                    buf.put_slice(&pkt[..take]);
                    return Poll::Ready(Ok(()));
                }
                Ok(Err(e)) => return Poll::Ready(Err(e)),
                Err(_would_block) => continue,
            }
        }
    }
}

impl AsyncWrite for AsyncTun {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
        packet: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        let hdr = af_header(packet);
        loop {
            let mut guard = ready!(this.fd.poll_write_ready(cx))?;
            match guard.try_io(|fd| {
                let iov = [
                    libc::iovec {
                        iov_base: hdr.as_ptr() as *mut libc::c_void,
                        iov_len: 4,
                    },
                    libc::iovec {
                        iov_base: packet.as_ptr() as *mut libc::c_void,
                        iov_len: packet.len(),
                    },
                ];
                // SAFETY: both iovecs point at live buffers of their lengths.
                let n = unsafe { libc::writev(fd.as_raw_fd(), iov.as_ptr(), 2) };
                if n < 0 {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(packet.len())
                }
            }) {
                Ok(r) => return Poll::Ready(r),
                Err(_would_block) => continue,
            }
        }
    }

    fn poll_flush(self: Pin<&mut Self>, _cx: &mut TaskContext<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut TaskContext<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }
}

#[cfg(test)]
mod tests {
    use super::af_header;

    #[test]
    fn header_follows_the_ip_version() {
        let v4 = [0x45u8, 0, 0, 20];
        let v6 = [0x60u8, 0, 0, 0];
        assert_eq!(af_header(&v4), (libc::AF_INET as u32).to_be_bytes());
        assert_eq!(af_header(&v6), (libc::AF_INET6 as u32).to_be_bytes());
    }
}
