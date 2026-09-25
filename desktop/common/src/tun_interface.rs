use anyhow::{Context, Result};
use log::{debug, info};
use std::io::{Read, Write};
use tun::Device;

/// TUN interface manager
pub struct TunInterface {
    device: tun::platform::Device,
}

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

impl Drop for TunInterface {
    fn drop(&mut self) {
        info!("Closing TUN interface");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    #[ignore] // Requires root/admin privileges
    fn test_tun_creation() {
        let tun = TunInterface::create("etph0", "10.0.0.1", "255.255.255.0");
        assert!(tun.is_ok());
    }
}
