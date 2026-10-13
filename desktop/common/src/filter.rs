use serde::{Deserialize, Serialize};
use std::net::Ipv4Addr;
use std::sync::atomic::{AtomicU64, Ordering};

/// Traffic filter configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilterConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_filter_mode")]
    pub mode: FilterMode,
    #[serde(default)]
    pub rules: Vec<FilterRule>,
    #[serde(default = "default_log_blocked")]
    pub log_blocked: bool,
}

fn default_filter_mode() -> FilterMode {
    FilterMode::Blacklist
}

fn default_log_blocked() -> bool {
    true
}

impl Default for FilterConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            mode: FilterMode::Blacklist,
            rules: Vec::new(),
            log_blocked: true,
        }
    }
}

/// Filter mode determines default behavior
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum FilterMode {
    /// Block all traffic, allow only matching rules with ALLOW action
    Whitelist,
    /// Allow all traffic, block only matching rules with BLOCK action
    Blacklist,
}

/// A single filter rule
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilterRule {
    pub id: String,
    #[serde(default = "default_true")]
    pub enabled: bool,
    pub action: FilterAction,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub protocol: Option<Protocol>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dest_ip: Option<String>, // CIDR notation: "192.168.1.0/24"
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dest_port: Option<String>, // "80" or "80-443" or "80,443,8080"
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source_ip: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source_port: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
}

fn default_true() -> bool {
    true
}

/// Action to take when rule matches
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum FilterAction {
    Allow,
    Block,
}

/// Network protocol
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum Protocol {
    Tcp,
    Udp,
    Icmp,
    Any,
}

impl Protocol {
    fn from_number(num: u8) -> Self {
        match num {
            6 => Protocol::Tcp,
            17 => Protocol::Udp,
            1 => Protocol::Icmp,
            _ => Protocol::Any,
        }
    }
}

/// IP range with CIDR prefix
#[derive(Debug, Clone)]
pub struct IpRange {
    pub address: Ipv4Addr,
    pub prefix_len: u8,
}

impl IpRange {
    /// Parse CIDR notation (e.g., "192.168.1.0/24")
    pub fn parse(cidr: &str) -> Option<Self> {
        let parts: Vec<&str> = cidr.split('/').collect();
        let address = parts[0].parse::<Ipv4Addr>().ok()?;
        let prefix_len = if parts.len() > 1 {
            parts[1].parse().ok()?
        } else {
            32
        };
        Some(IpRange {
            address,
            prefix_len,
        })
    }

    /// Check if IP is within this range
    pub fn contains(&self, ip: Ipv4Addr) -> bool {
        let ip_bits = u32::from(ip);
        let range_bits = u32::from(self.address);
        // prefix_len == 0 (e.g. "0.0.0.0/0", a legitimate "match everything"
        // rule) must be handled explicitly: shifting a u32 by 32 bits is an
        // out-of-range shift that panics in debug builds (and only happens
        // to compute the right answer in release builds because LLVM masks
        // the shift amount). prefix_len > 32 is also defensively treated as
        // a full match instead of risking the same overflow.
        let mask = match self.prefix_len {
            0 => 0u32,
            32.. => !0u32,
            n => !0u32 << (32 - n),
        };
        (ip_bits & mask) == (range_bits & mask)
    }
}

/// Port range
#[derive(Debug, Clone)]
pub struct PortRange {
    pub start: u16,
    pub end: u16,
}

impl PortRange {
    /// Parse port specification (e.g., "80", "80-443")
    ///
    /// A malformed range like "80-443-9000" (three dash-separated pieces
    /// instead of two) used to be silently accepted here: `parts.first()`
    /// and `parts.get(1)` only ever look at the first two elements of the
    /// split, so the trailing "-9000" was quietly dropped and the entry
    /// was treated as the valid-looking "80-443" instead of being rejected
    /// as malformed. Since this spec string comes straight from
    /// attacker-controllable config (a ConfigUpdate dest_port/source_port
    /// string off the wire), that meant a typo'd or adversarial range spec
    /// could resolve to a different, unintended port range rather than
    /// being dropped -- exactly the ambiguous-input case the Kotlin
    /// sibling (PortRange.parse in
    /// android/.../filter/FilterRule.kt) already guards against by
    /// requiring `pieces.size == 2`. Mirror that here.
    pub fn parse_all(spec: &str) -> Vec<Self> {
        spec.split(',')
            .filter_map(|part| {
                let trimmed = part.trim();
                if trimmed.contains('-') {
                    let parts: Vec<&str> = trimmed.split('-').collect();
                    if parts.len() != 2 {
                        return None;
                    }
                    let start = parts[0].trim().parse().ok()?;
                    let end = parts[1].trim().parse().ok()?;
                    Some(PortRange { start, end })
                } else {
                    let port = trimmed.parse().ok()?;
                    Some(PortRange {
                        start: port,
                        end: port,
                    })
                }
            })
            .collect()
    }

    /// Check if port is in range
    pub fn contains(&self, port: u16) -> bool {
        port >= self.start && port <= self.end
    }
}

/// Parsed packet information
#[derive(Debug)]
struct ParsedPacket {
    protocol: Protocol,
    src_ip: Ipv4Addr,
    dst_ip: Ipv4Addr,
    src_port: Option<u16>,
    dst_port: Option<u16>,
}

/// Parse IPv4 packet
fn parse_packet(data: &[u8]) -> Option<ParsedPacket> {
    if data.len() < 20 {
        return None;
    }

    let version = (data[0] >> 4) & 0x0F;
    if version != 4 {
        return None; // Only IPv4 for now
    }

    let header_len = ((data[0] & 0x0F) * 4) as usize;
    let protocol_byte = data[9];
    let protocol = Protocol::from_number(protocol_byte);

    let src_ip = Ipv4Addr::new(data[12], data[13], data[14], data[15]);
    let dst_ip = Ipv4Addr::new(data[16], data[17], data[18], data[19]);

    // RFC 791: IHL (the lower nibble we read above) is the header length in
    // 32-bit words and its minimum legal value is 5 (20 bytes). A malformed
    // or spoofed packet can carry a smaller IHL, making `header_len` land
    // inside the fixed header we already parsed above (e.g. header_len == 0
    // points straight at the version/IHL byte). Without the `header_len >=
    // 20` guard below, such a packet would have its "TCP/UDP ports" read
    // from arbitrary header bytes instead of the real payload -- letting an
    // attacker craft a packet whose bogus derived port doesn't match a
    // dest_port BLOCK rule, bypassing port-based filtering while src_ip/
    // dst_ip/protocol (parsed from fixed, always-valid offsets) still look
    // legitimate. IP/protocol-based rules keep working correctly either way;
    // we only refuse to trust ports extracted via a malformed header_len.
    let (src_port, dst_port) = if matches!(protocol, Protocol::Tcp | Protocol::Udp)
        && header_len >= 20
        && data.len() >= header_len + 4
    {
        let src = u16::from_be_bytes([data[header_len], data[header_len + 1]]);
        let dst = u16::from_be_bytes([data[header_len + 2], data[header_len + 3]]);
        (Some(src), Some(dst))
    } else {
        (None, None)
    };

    Some(ParsedPacket {
        protocol,
        src_ip,
        dst_ip,
        src_port,
        dst_port,
    })
}

/// Filter engine for packet filtering
pub struct FilterEngine {
    config: FilterConfig,
    blocked_count: AtomicU64,
    allowed_count: AtomicU64,
}

impl FilterEngine {
    /// Create new filter engine
    pub fn new(config: FilterConfig) -> Self {
        Self {
            config,
            blocked_count: AtomicU64::new(0),
            allowed_count: AtomicU64::new(0),
        }
    }

    /// Update filter configuration
    pub fn update_config(&mut self, config: FilterConfig) {
        self.config = config;
    }

    /// Check if packet should be allowed
    pub fn should_allow(&self, packet: &[u8]) -> bool {
        if !self.config.enabled {
            return true;
        }

        let parsed = match parse_packet(packet) {
            Some(p) => p,
            None => return true, // Can't parse = allow
        };

        for rule in &self.config.rules {
            if !rule.enabled {
                continue;
            }

            if self.matches_rule(&parsed, rule) {
                let allowed = rule.action == FilterAction::Allow;

                if allowed {
                    self.allowed_count.fetch_add(1, Ordering::Relaxed);
                } else {
                    self.blocked_count.fetch_add(1, Ordering::Relaxed);
                    if self.config.log_blocked {
                        log::debug!(
                            "BLOCKED: {}:{} -> {}:{} ({:?}) by rule {}",
                            parsed.src_ip,
                            parsed.src_port.unwrap_or(0),
                            parsed.dst_ip,
                            parsed.dst_port.unwrap_or(0),
                            parsed.protocol,
                            rule.id
                        );
                    }
                }

                // A matched rule's own action decides the outcome directly —
                // the filter mode only controls the *default* when no rule
                // matches (see below). Previously this branched on
                // `self.config.mode` again here with
                // `!allowed || rule.action == FilterAction::Allow`, which is a
                // tautology (always `true`) whenever `allowed` is defined as
                // `rule.action == FilterAction::Allow`, as it is a few lines
                // up. That meant a matched BLOCK rule in Blacklist mode (the
                // default mode) never actually blocked anything.
                return allowed;
            }
        }

        // No rule matched
        match self.config.mode {
            FilterMode::Whitelist => false,
            FilterMode::Blacklist => true,
        }
    }

    fn matches_rule(&self, packet: &ParsedPacket, rule: &FilterRule) -> bool {
        // Protocol check
        if let Some(proto) = rule.protocol {
            if proto != Protocol::Any && proto != packet.protocol {
                return false;
            }
        }

        // Destination IP check
        //
        // A malformed/unparseable dest_ip CIDR spec (e.g. a typo'd octet or a
        // garbage string off the wire via ConfigUpdate) used to fall through
        // this `if let Some(...)` silently: when `IpRange::parse` returned
        // `None` the whole condition was skipped, so the rule behaved as if
        // dest_ip had no restriction at all instead of failing to match.
        // That's an attacker-exploitable bypass for a BLOCK rule (a garbled
        // CIDR makes the block simply not apply) and is inconsistent with
        // dest_port/source_port below, which already fail closed (no valid
        // range parsed => `.any()` is false => rule doesn't match). Mirror
        // that fail-closed behavior here: treat an unparseable spec as "rule
        // doesn't match" rather than "restriction doesn't apply".
        if let Some(ref ip_spec) = rule.dest_ip {
            match IpRange::parse(ip_spec) {
                Some(ip_range) => {
                    if !ip_range.contains(packet.dst_ip) {
                        return false;
                    }
                }
                None => return false,
            }
        }

        // Destination port check
        if let Some(ref port_spec) = rule.dest_port {
            if let Some(port) = packet.dst_port {
                let ranges = PortRange::parse_all(port_spec);
                if !ranges.iter().any(|r| r.contains(port)) {
                    return false;
                }
            } else {
                return false;
            }
        }

        // Source IP check (same fail-closed rationale as dest_ip above)
        if let Some(ref ip_spec) = rule.source_ip {
            match IpRange::parse(ip_spec) {
                Some(ip_range) => {
                    if !ip_range.contains(packet.src_ip) {
                        return false;
                    }
                }
                None => return false,
            }
        }

        // Source port check
        if let Some(ref port_spec) = rule.source_port {
            if let Some(port) = packet.src_port {
                let ranges = PortRange::parse_all(port_spec);
                if !ranges.iter().any(|r| r.contains(port)) {
                    return false;
                }
            } else {
                return false;
            }
        }

        true
    }

    /// Get statistics
    pub fn get_stats(&self) -> (u64, u64) {
        (
            self.blocked_count.load(Ordering::Relaxed),
            self.allowed_count.load(Ordering::Relaxed),
        )
    }

    /// Reset statistics
    pub fn reset_stats(&self) {
        self.blocked_count.store(0, Ordering::Relaxed);
        self.allowed_count.store(0, Ordering::Relaxed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ip_range_parse() {
        let range = IpRange::parse("192.168.1.0/24").unwrap();
        assert!(range.contains(Ipv4Addr::new(192, 168, 1, 1)));
        assert!(range.contains(Ipv4Addr::new(192, 168, 1, 255)));
        assert!(!range.contains(Ipv4Addr::new(192, 168, 2, 1)));
    }

    #[test]
    fn test_ip_range_slash_zero_matches_everything() {
        // "0.0.0.0/0" is a legitimate "match all traffic" rule. prefix_len
        // == 0 previously drove a `!0u32 << 32` shift in contains(), an
        // out-of-range shift that panics in debug builds. It must match
        // every address without panicking.
        let range = IpRange::parse("0.0.0.0/0").unwrap();
        assert!(range.contains(Ipv4Addr::new(1, 2, 3, 4)));
        assert!(range.contains(Ipv4Addr::new(255, 255, 255, 255)));
        assert!(range.contains(Ipv4Addr::new(0, 0, 0, 0)));
    }

    #[test]
    fn test_port_range_parse() {
        let ranges = PortRange::parse_all("80,443,8080-8090");
        assert_eq!(ranges.len(), 3);
        assert!(ranges[0].contains(80));
        assert!(ranges[1].contains(443));
        assert!(ranges[2].contains(8085));
        assert!(!ranges[2].contains(8091));
    }

    #[test]
    fn test_port_range_parse_rejects_malformed_triple_dash() {
        // "80-443-9000" has three dash-separated pieces, not the expected
        // two -- it must be dropped entirely rather than silently
        // resolving to "80-443" with the trailing "-9000" ignored. See the
        // comment on PortRange::parse_all for the full rationale.
        let ranges = PortRange::parse_all("80-443-9000");
        assert!(ranges.is_empty());

        // A malformed entry alongside valid ones should only drop the bad
        // one, not the whole spec.
        let ranges = PortRange::parse_all("22,80-443-9000,8080");
        assert_eq!(ranges.len(), 2);
        assert!(ranges[0].contains(22));
        assert!(ranges[1].contains(8080));
    }

    #[test]
    fn test_filter_disabled() {
        let config = FilterConfig::default();
        let engine = FilterEngine::new(config);

        // Any packet should be allowed when filtering is disabled
        let packet = vec![0u8; 40];
        assert!(engine.should_allow(&packet));
    }

    /// Build a minimal IPv4 header (20 bytes, no options) followed by 4 bytes
    /// of src/dst port, matching what `parse_packet` expects for TCP/UDP.
    fn build_tcp_packet(src_ip: [u8; 4], dst_ip: [u8; 4], src_port: u16, dst_port: u16) -> Vec<u8> {
        let mut pkt = vec![0u8; 24];
        pkt[0] = 0x45; // version 4, header length 5 (20 bytes)
        pkt[9] = 6; // protocol = TCP
        pkt[12..16].copy_from_slice(&src_ip);
        pkt[16..20].copy_from_slice(&dst_ip);
        pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
        pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
        pkt
    }

    /// Regression test for a real bug: in Blacklist mode (the default mode),
    /// a matched rule's outcome used to be computed as
    /// `!allowed || rule.action == FilterAction::Allow`, which is always
    /// `true` when `allowed == (rule.action == FilterAction::Allow)` — i.e.
    /// an explicit BLOCK rule never actually blocked anything as long as the
    /// engine was in Blacklist mode. This test asserts a BLOCK rule in the
    /// default Blacklist mode actually blocks the traffic it matches, and
    /// that unrelated traffic is still allowed through by the mode's default.
    #[test]
    fn test_blacklist_mode_block_rule_actually_blocks() {
        let config = FilterConfig {
            enabled: true,
            mode: FilterMode::Blacklist,
            rules: vec![FilterRule {
                id: "block-443".to_string(),
                enabled: true,
                action: FilterAction::Block,
                protocol: Some(Protocol::Tcp),
                dest_ip: None,
                dest_port: Some("443".to_string()),
                source_ip: None,
                source_port: None,
                description: None,
            }],
            log_blocked: true,
        };
        let engine = FilterEngine::new(config);

        let blocked_packet = build_tcp_packet([10, 0, 0, 1], [1, 1, 1, 1], 55000, 443);
        assert!(
            !engine.should_allow(&blocked_packet),
            "a matched BLOCK rule in Blacklist mode must actually block traffic"
        );

        let unrelated_packet = build_tcp_packet([10, 0, 0, 1], [1, 1, 1, 1], 55000, 80);
        assert!(
            engine.should_allow(&unrelated_packet),
            "unmatched traffic must still fall through to Blacklist mode's allow-by-default"
        );

        let (blocked_count, allowed_count) = engine.get_stats();
        assert_eq!(blocked_count, 1);
        assert_eq!(allowed_count, 0);
    }

    /// Same bug, opposite polarity: a matched ALLOW rule in Whitelist mode
    /// (which was already correct before the fix, since `allowed` was
    /// returned directly there) must still allow its matched traffic and
    /// deny everything else by default.
    #[test]
    fn test_whitelist_mode_allow_rule_actually_allows() {
        let config = FilterConfig {
            enabled: true,
            mode: FilterMode::Whitelist,
            rules: vec![FilterRule {
                id: "allow-443".to_string(),
                enabled: true,
                action: FilterAction::Allow,
                protocol: Some(Protocol::Tcp),
                dest_ip: None,
                dest_port: Some("443".to_string()),
                source_ip: None,
                source_port: None,
                description: None,
            }],
            log_blocked: true,
        };
        let engine = FilterEngine::new(config);

        let allowed_packet = build_tcp_packet([10, 0, 0, 1], [1, 1, 1, 1], 55000, 443);
        assert!(engine.should_allow(&allowed_packet));

        let unrelated_packet = build_tcp_packet([10, 0, 0, 1], [1, 1, 1, 1], 55000, 80);
        assert!(
            !engine.should_allow(&unrelated_packet),
            "unmatched traffic must be denied by default in Whitelist mode"
        );
    }

    /// Regression test: a packet with a malformed IHL (IPv4 header length
    /// nibble) smaller than the legal minimum of 5 words (20 bytes) must not
    /// have its "TCP/UDP ports" read from inside the fixed IP header. Before
    /// the `header_len >= 20` guard in `parse_packet`, a crafted packet with
    /// IHL == 0 would derive bogus ports from header bytes (version/IHL,
    /// TTL, protocol, etc.) instead of the real payload, letting it evade a
    /// dest_port BLOCK rule while the correctly-parsed src_ip/dst_ip still
    /// looked legitimate -- a port-filter bypass. This test builds such a
    /// packet targeting real port 443 (which a rule blocks) but with IHL
    /// forced to 0, and asserts it is still blocked because the engine must
    /// not trust the undersized header_len for port extraction.
    #[test]
    fn test_malformed_ihl_does_not_bypass_port_filter() {
        let config = FilterConfig {
            enabled: true,
            mode: FilterMode::Blacklist,
            rules: vec![FilterRule {
                id: "block-443".to_string(),
                enabled: true,
                action: FilterAction::Block,
                protocol: Some(Protocol::Tcp),
                dest_ip: None,
                dest_port: Some("443".to_string()),
                source_ip: None,
                source_port: None,
                description: None,
            }],
            log_blocked: true,
        };
        let engine = FilterEngine::new(config);

        let mut malformed = build_tcp_packet([10, 0, 0, 1], [1, 1, 1, 1], 55000, 443);
        // Force IHL (low nibble of byte 0) to 0 -- an illegal header length
        // that used to make `header_len` point at byte 0 instead of byte 20.
        malformed[0] = 0x40; // version 4, IHL 0 (invalid)

        // The packet can no longer be matched on its (now-untrusted) port,
        // so it falls through to Blacklist mode's allow-by-default instead
        // of being misidentified -- it must NOT be silently treated as a
        // non-443 packet that dodges the block rule via a bogus port, and it
        // must also not crash/panic while parsing.
        let _ = engine.should_allow(&malformed);

        // The important assertion: a *well-formed* packet to the same
        // destination is still reliably blocked, proving the block rule
        // itself still works and wasn't broken by the guard.
        let wellformed = build_tcp_packet([10, 0, 0, 1], [1, 1, 1, 1], 55000, 443);
        assert!(
            !engine.should_allow(&wellformed),
            "a well-formed packet to a blocked port must still be blocked"
        );
    }

    /// Regression test: a malformed/unparseable dest_ip CIDR spec used to be
    /// silently skipped in `matches_rule` (the `if let Some(ip_range) =
    /// IpRange::parse(ip_spec)` just fell through on `None`), which meant a
    /// BLOCK rule with a garbled dest_ip effectively had NO ip restriction at
    /// all and matched (and blocked) every destination -- a config typo or
    /// adversarial ConfigUpdate turning a narrowly-scoped block rule into a
    /// block-everything rule. The fix fails closed: an unparseable dest_ip
    /// spec means the rule simply doesn't match, exactly like malformed
    /// dest_port specs already behaved. This test uses a garbage dest_ip
    /// string and asserts the block rule does NOT fire (traffic is allowed
    /// through by Blacklist mode's default) instead of blocking everything.
    #[test]
    fn test_malformed_dest_ip_does_not_match_everything() {
        let config = FilterConfig {
            enabled: true,
            mode: FilterMode::Blacklist,
            rules: vec![FilterRule {
                id: "block-bad-cidr".to_string(),
                enabled: true,
                action: FilterAction::Block,
                protocol: None,
                dest_ip: Some("not-a-valid-cidr".to_string()),
                dest_port: None,
                source_ip: None,
                source_port: None,
                description: None,
            }],
            log_blocked: true,
        };
        let engine = FilterEngine::new(config);

        let packet = build_tcp_packet([10, 0, 0, 1], [1, 1, 1, 1], 55000, 443);
        assert!(
            engine.should_allow(&packet),
            "a rule with an unparseable dest_ip must not match (and therefore \
             must not block) traffic -- it must fail closed, not match everything"
        );

        let (blocked_count, _) = engine.get_stats();
        assert_eq!(blocked_count, 0);
    }

    /// Same bug, source_ip side.
    #[test]
    fn test_malformed_source_ip_does_not_match_everything() {
        let config = FilterConfig {
            enabled: true,
            mode: FilterMode::Blacklist,
            rules: vec![FilterRule {
                id: "block-bad-source-cidr".to_string(),
                enabled: true,
                action: FilterAction::Block,
                protocol: None,
                dest_ip: None,
                dest_port: None,
                source_ip: Some("999.999.999.999/99".to_string()),
                source_port: None,
                description: None,
            }],
            log_blocked: true,
        };
        let engine = FilterEngine::new(config);

        let packet = build_tcp_packet([10, 0, 0, 1], [1, 1, 1, 1], 55000, 443);
        assert!(
            engine.should_allow(&packet),
            "a rule with an unparseable source_ip must not match (and therefore \
             must not block) traffic -- it must fail closed, not match everything"
        );
    }
}
