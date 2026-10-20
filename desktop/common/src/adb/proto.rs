//! ADB wire format (system/core/adb/protocol.txt): a 24-byte little-endian
//! header (command, arg0, arg1, data length, data checksum, command ^
//! 0xffffffff) followed by the payload.

use std::io;

pub const A_SYNC: u32 = 0x434e5953;
pub const A_CNXN: u32 = 0x4e584e43;
pub const A_AUTH: u32 = 0x48545541;
pub const A_OPEN: u32 = 0x4e45504f;
pub const A_OKAY: u32 = 0x59414b4f;
pub const A_CLSE: u32 = 0x45534c43;
pub const A_WRTE: u32 = 0x45545257;

/// Protocol version with "skip the payload checksum" (adb >= 7.0); we still
/// send checksums so older adbd (Android 8 accepts both) is happy.
pub const VERSION: u32 = 0x0100_0001;
/// The largest payload we accept (and announce).
pub const MAX_PAYLOAD: usize = 256 * 1024;
pub const HOST_BANNER: &[u8] = b"host::features=shell_v2,cmd\0";

pub const AUTH_TOKEN: u32 = 1;
pub const AUTH_SIGNATURE: u32 = 2;
pub const AUTH_RSAPUBLICKEY: u32 = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Header {
    pub command: u32,
    pub arg0: u32,
    pub arg1: u32,
    pub data_length: u32,
    pub data_check: u32,
}

pub fn checksum(data: &[u8]) -> u32 {
    data.iter().map(|&b| b as u32).fold(0u32, u32::wrapping_add)
}

impl Header {
    pub fn new(command: u32, arg0: u32, arg1: u32, data: &[u8]) -> Self {
        Self {
            command,
            arg0,
            arg1,
            data_length: data.len() as u32,
            data_check: checksum(data),
        }
    }

    pub fn encode(&self) -> [u8; 24] {
        let mut b = [0u8; 24];
        for (i, v) in [
            self.command,
            self.arg0,
            self.arg1,
            self.data_length,
            self.data_check,
            self.command ^ 0xffff_ffff,
        ]
        .iter()
        .enumerate()
        {
            b[i * 4..i * 4 + 4].copy_from_slice(&v.to_le_bytes());
        }
        b
    }

    pub fn decode(b: &[u8]) -> io::Result<Self> {
        if b.len() < 24 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "short adb header",
            ));
        }
        let w = |i: usize| u32::from_le_bytes([b[i * 4], b[i * 4 + 1], b[i * 4 + 2], b[i * 4 + 3]]);
        let h = Self {
            command: w(0),
            arg0: w(1),
            arg1: w(2),
            data_length: w(3),
            data_check: w(4),
        };
        if w(5) != h.command ^ 0xffff_ffff {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "bad adb header magic",
            ));
        }
        if h.data_length as usize > MAX_PAYLOAD {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "adb payload too large",
            ));
        }
        Ok(h)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn header_roundtrip_and_magic() {
        let h = Header::new(A_CNXN, VERSION, MAX_PAYLOAD as u32, HOST_BANNER);
        let b = h.encode();
        // "CNXN" in little-endian is the ASCII bytes in order.
        assert_eq!(&b[0..4], b"CNXN");
        assert_eq!(Header::decode(&b).unwrap(), h);
        assert_eq!(h.data_check, checksum(HOST_BANNER));
        let mut bad = b;
        bad[20] ^= 1;
        assert!(Header::decode(&bad).is_err());
        assert!(Header::decode(&b[..10]).is_err());
        for (c, s) in [
            (A_OPEN, b"OPEN"),
            (A_OKAY, b"OKAY"),
            (A_WRTE, b"WRTE"),
            (A_CLSE, b"CLSE"),
            (A_AUTH, b"AUTH"),
            (A_SYNC, b"SYNC"),
        ] {
            assert_eq!(&c.to_le_bytes(), s);
        }
    }
}
