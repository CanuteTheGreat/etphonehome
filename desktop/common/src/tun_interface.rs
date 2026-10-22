//! TUN devices. Linux, Android and Windows (Wintun) use the `tun` crate;
//! macOS (utun) and the BSDs (tun(4)) use [`crate::bsdtun`].

use anyhow::{Context, Result};
#[cfg(any(target_os = "linux", target_os = "android", windows))]
use log::{debug, info};
#[cfg(any(target_os = "linux", target_os = "android", windows))]
use std::io::{Read, Write};
#[cfg(any(target_os = "linux", target_os = "android", windows))]
use tun::Device;

/// The async TUN device the session forwards packets through.
#[cfg(any(target_os = "linux", target_os = "android", windows))]
pub type AsyncTun = tun::AsyncDevice;
#[cfg(not(any(target_os = "linux", target_os = "android", windows)))]
pub type AsyncTun = crate::bsdtun::AsyncTun;

/// TUN manager (blocking; tests and tools).
#[cfg(any(target_os = "linux", target_os = "android", windows))]
pub struct TunInterface {
    device: tun::platform::Device,
}

/// Create a TUN device for the async forwarding loop
/// ([`crate::session::forward`]). Unlike the blocking [`TunInterface`], the
/// returned device can be split into independent read and write halves, so
/// an idle uplink never blocks delivery of downlink packets.
#[cfg(any(target_os = "linux", target_os = "android", windows))]
pub fn create_async(name: &str, address: &str, netmask: &str) -> Result<tun::AsyncDevice> {
    info!("Creating TUN interface: {} ({}/{})", name, address, netmask);
    let mut config = tun::Configuration::default();
    config
        .name(name)
        .address(address)
        .netmask(netmask)
        .mtu(1500)
        .up();
    #[cfg(target_os = "linux")]
    config.platform(|config| {
        config.packet_information(false);
    });
    tun::create_as_async(&config).context("Failed to create TUN device")
}

/// Create the session's TUN link: `local` here, `peer` the phone end.
/// Returns the device and the interface name the system gave it (macOS
/// picks `utunN` itself).
pub fn create_link(
    name: &str,
    local: &str,
    peer: &str,
    netmask: &str,
) -> Result<(AsyncTun, String)> {
    #[cfg(any(target_os = "linux", target_os = "android", windows))]
    {
        let _ = peer;
        let dev = create_async(name, local, netmask)?;
        let actual = dev.get_ref().name().unwrap_or_else(|_| name.to_string());
        Ok((dev, actual))
    }
    #[cfg(not(any(target_os = "linux", target_os = "android", windows)))]
    {
        let dev = crate::bsdtun::create(name, local, peer, netmask, 1500)
            .context("Failed to create TUN device")?;
        let actual = dev.name().to_string();
        Ok((dev, actual))
    }
}

#[cfg(any(target_os = "linux", target_os = "android", windows))]
impl TunInterface {
    /// Create a new TUN interface
    pub fn create(name: &str, address: &str, netmask: &str) -> Result<Self> {
        info!("Creating TUN interface: {}", name);

        let mut config = tun::Configuration::default();
        config
            .name(name)
            .address(address)
            .netmask(netmask)
            .mtu(1500)
            .up();

        #[cfg(target_os = "linux")]
        config.platform(|config| {
            config.packet_information(false);
        });

        let device = tun::create(&config).context("Failed to create TUN device")?;

        info!("TUN interface created successfully");
        info!(
            "  Name: {}",
            device.name().unwrap_or_else(|_| "unknown".to_string())
        );
        info!("  Address: {}", address);
        info!("  Netmask: {}", netmask);

        Ok(Self { device })
    }

    /// Read a packet from the TUN interface
    pub fn read_packet(&mut self) -> Result<Vec<u8>> {
        let mut buf = vec![0u8; 2048];
        let len = self
            .device
            .read(&mut buf)
            .context("Failed to read from TUN device")?;

        buf.truncate(len);
        debug!("Read {} bytes from TUN", len);

        Ok(buf)
    }

    /// Write a packet to the TUN interface
    pub fn write_packet(&mut self, packet: &[u8]) -> Result<()> {
        self.device
            .write_all(packet)
            .context("Failed to write to TUN device")?;

        debug!("Wrote {} bytes to TUN", packet.len());
        Ok(())
    }

    /// Get the device name
    pub fn name(&self) -> Result<String> {
        self.device.name().context("Failed to get device name")
    }
}

#[cfg(any(target_os = "linux", target_os = "android", windows))]
impl Drop for TunInterface {
    fn drop(&mut self) {
        info!("Closing TUN interface");
    }
}

// These need CAP_NET_ADMIN and /dev/net/tun, so they are #[ignore]d for the
// plain `cargo test` run and executed explicitly with `--ignored` by the
// `e2e` job in CI rust-ci.yml inside a container started with
// --cap-add NET_ADMIN --device /dev/net/tun.
#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::*;
    use std::net::UdpSocket;

    /// Send a UDP datagram into the TUN subnet and check it comes out of the
    /// device as a well-formed IPv4/UDP packet.
    #[test]
    #[ignore = "needs CAP_NET_ADMIN + /dev/net/tun (run by the CI e2e job)"]
    fn test_tun_creation() {
        let mut tun = TunInterface::create("etphtest0", "10.98.0.1", "255.255.255.0").unwrap();
        assert_eq!(tun.name().unwrap(), "etphtest0");
        let sock = UdpSocket::bind("10.98.0.1:0").unwrap();
        sock.send_to(b"hello-tun", "10.98.0.2:5555").unwrap();
        loop {
            let pkt = tun.read_packet().unwrap();
            if pkt[0] >> 4 != 4 || pkt[9] != 17 {
                continue; // e.g. IPv6 router solicitations
            }
            assert_eq!(&pkt[16..20], &[10, 98, 0, 2]);
            assert_eq!(u16::from_be_bytes([pkt[22], pkt[23]]), 5555);
            assert!(pkt.ends_with(b"hello-tun"));
            break;
        }
    }

    #[tokio::test]
    #[ignore = "needs CAP_NET_ADMIN + /dev/net/tun (run by the CI e2e job)"]
    async fn test_async_tun_reads_and_writes() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let dev = create_async("etphtest1", "10.97.0.1", "255.255.255.0").unwrap();
        let (mut r, mut w) = tokio::io::split(dev);
        let sock = tokio::net::UdpSocket::bind("10.97.0.1:7777").await.unwrap();
        sock.send_to(b"ping", "10.97.0.2:7778").await.unwrap();
        let mut buf = vec![0u8; 2048];
        let pkt = loop {
            let n = r.read(&mut buf).await.unwrap();
            if buf[0] >> 4 == 4 && buf[9] == 17 {
                break buf[..n].to_vec();
            }
        };
        assert!(pkt.ends_with(b"ping"));
        // Reflect it back with addresses/ports swapped: the kernel must
        // deliver it to our socket (UDP checksum 0 = none, valid for IPv4).
        let mut reply = pkt.clone();
        reply[12..16].copy_from_slice(&pkt[16..20]);
        reply[16..20].copy_from_slice(&pkt[12..16]);
        reply[20..22].copy_from_slice(&pkt[22..24]);
        reply[22..24].copy_from_slice(&pkt[20..22]);
        reply[26] = 0;
        reply[27] = 0;
        reply[10] = 0;
        reply[11] = 0;
        let ck = crate::tun_interface::tests::ipv4_checksum(&reply[..20]);
        reply[10..12].copy_from_slice(&ck.to_be_bytes());
        w.write_all(&reply).await.unwrap();
        let mut got = [0u8; 16];
        let (n, from) =
            tokio::time::timeout(std::time::Duration::from_secs(5), sock.recv_from(&mut got))
                .await
                .expect("reflected packet never delivered")
                .unwrap();
        assert_eq!(&got[..n], b"ping");
        assert_eq!(from, "10.97.0.2:7778".parse().unwrap());
    }

    pub(super) fn ipv4_checksum(h: &[u8]) -> u16 {
        let mut sum: u32 = 0;
        for c in h.chunks(2) {
            sum += u16::from_be_bytes([c[0], c[1]]) as u32;
        }
        while sum > 0xffff {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        !(sum as u16)
    }
}
