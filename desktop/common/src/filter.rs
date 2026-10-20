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

impl FilterConfig {
    /// The `ConfigUpdate` the desktop sends right after the handshake so the
    /// phone enforces the same rules (wire format documented in
    /// android/core/.../filter/FilterConfig.kt `applyUpdate`).
    pub fn to_config_update(&self) -> crate::protocol::ConfigUpdate {
        fn clean(s: &str) -> String {
            s.chars()
                .map(|c| {
                    if c == '|' || c == '\n' || c == '\r' {
                        '_'
                    } else {
                        c
                    }
                })
                .collect()
        }
        fn opt(s: &Option<String>) -> String {
            s.as_deref().map(clean).unwrap_or_default()
        }
        let mut config = vec![
            ("filter.enabled".to_string(), self.enabled.to_string()),
            (
                "filter.mode".to_string(),
                match self.mode {
                    FilterMode::Whitelist => "whitelist",
                    FilterMode::Blacklist => "blacklist",
                }
                .to_string(),
            ),
            (
                "filter.log_blocked".to_string(),
                self.log_blocked.to_string(),
            ),
            ("filter.rules".to_string(), self.rules.len().to_string()),
        ];
        for (i, r) in self.rules.iter().enumerate() {
            let protocol = match r.protocol {
                None | Some(Protocol::Any) => "any",
                Some(Protocol::Tcp) => "tcp",
                Some(Protocol::Udp) => "udp",
                Some(Protocol::Icmp) => "icmp",
            };
            let action = match r.action {
                FilterAction::Allow => "allow",
                FilterAction::Block => "block",
            };
            config.push((
                format!("filter.rule.{}", i),
                format!(
                    "{}|{}|{}|{}|{}|{}|{}|{}",
                    clean(&r.id),
                    r.enabled,
                    action,
                    protocol,
                    opt(&r.dest_ip),
                    opt(&r.dest_port),
                    opt(&r.source_ip),
                    opt(&r.source_port)
                ),
            ));
        }
        crate::protocol::ConfigUpdate { config }
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
    /// Parse CIDR notation (e.g., "192.168.1.0/24").
    ///
    /// Surrounding whitespace is ignored and more than one '/' is rejected,
    /// the same as the phone's `IpRange.parse` (android/.../FilterRule.kt),
    /// which enforces the same rule set from the `ConfigUpdate`. Before,
    /// " 10.0.0.0/24" never matched here but matched on the phone, and
    /// "10.0.0.0/24/8" was read here as 10.0.0.0/24 (the extra part was
    /// ignored) while the phone dropped the rule.
    pub fn parse(cidr: &str) -> Option<Self> {
        let parts: Vec<&str> = cidr.trim().split('/').collect();
        if parts.len() > 2 {
            return None;
        }
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
    //
    // Fragments: only the first fragment (fragment offset 0) carries the
    // transport header. In a non-first fragment, the 4 bytes at
    // `header_len` are mid-stream payload chosen by the sender, so reading
    // "ports" there lets a crafted fragment match an ALLOW rule (under
    // Whitelist mode) or dodge a BLOCK rule, and lets innocent fragments
    // randomly hit block rules. The fragment offset is the low 13 bits of
    // bytes 6-7 (RFC 791). For non-first fragments we leave the ports as
    // `None`, which `matches_rule` already treats as "no port-scoped rule
    // matches" -- the same handling as ICMP. Matches the Android
    // `PacketParser` fix (c294729).
    let fragment_offset = u16::from_be_bytes([data[6], data[7]]) & 0x1FFF;
    let (src_port, dst_port) = if matches!(protocol, Protocol::Tcp | Protocol::Udp)
        && fragment_offset == 0
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

        // A packet we can't parse (shorter than an IPv4 header, or any
        // non-IPv4 version -- `parse_packet` is IPv4-only, so this includes
        // *all* IPv6 traffic) has no fields any rule could match against, so
        // it gets the mode's no-match default, exactly like a parsed packet
        // that matched no rule. This used to be an unconditional `return
        // true`, which made Whitelist (default-deny) mode fail open: every
        // IPv6 packet and every malformed/truncated packet was forwarded
        // even though no ALLOW rule permitted it. Blacklist mode is
        // unchanged (unparseable traffic is still allowed there).
        let parsed = match parse_packet(packet) {
            Some(p) => p,
            None => return self.default_verdict(),
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
        self.default_verdict()
    }

    /// Verdict for traffic no rule decides: deny in Whitelist mode, allow in
    /// Blacklist mode.
    fn default_verdict(&self) -> bool {
        match self.config.mode {
            FilterMode::Whitelist => false,
            FilterMode::Blacklist => true,
        }
    }

    fn matches_rule(&self, packet: &ParsedPacket, rule: &FilterRule) -> bool {
        // A blank address/port spec (`dest_ip = ""` in the config) means "no
        // restriction", like an absent one. The wire format can't tell them
        // apart (`to_config_update` sends "" for None), so the phone already
        // read a blank field as "any". Here it used to be a spec that parsed
        // to nothing, so the rule never matched: a BLOCK rule with a blank
        // dest_ip blocked on the phone but not on the desktop, and an ALLOW
        // rule with one let traffic through on one side only.
        fn spec(s: &Option<String>) -> Option<&str> {
            s.as_deref().map(str::trim).filter(|t| !t.is_empty())
        }

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
        if let Some(ip_spec) = spec(&rule.dest_ip) {
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
        if let Some(port_spec) = spec(&rule.dest_port) {
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
        if let Some(ip_spec) = spec(&rule.source_ip) {
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
        if let Some(port_spec) = spec(&rule.source_port) {
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
    #[test]
    fn config_update_carries_mode_and_every_rule() {
        let cfg = FilterConfig {
            enabled: true,
            mode: FilterMode::Whitelist,
            log_blocked: false,
            rules: vec![FilterRule {
                id: "web|only\nx".into(),
                enabled: true,
                action: FilterAction::Allow,
                protocol: Some(Protocol::Tcp),
                dest_ip: Some("93.184.0.0/16".into()),
                dest_port: Some("80,443".into()),
                source_ip: None,
                source_port: None,
                description: None,
            }],
        };
        let m: std::collections::HashMap<_, _> =
            cfg.to_config_update().config.into_iter().collect();
        assert_eq!(m["filter.enabled"], "true");
        assert_eq!(m["filter.mode"], "whitelist");
        assert_eq!(m["filter.log_blocked"], "false");
        assert_eq!(m["filter.rules"], "1");
        assert_eq!(
            m["filter.rule.0"],
            "web_only_x|true|allow|tcp|93.184.0.0/16|80,443||"
        );
    }

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
    fn ip_range_parse_trims_and_rejects_a_second_slash_like_the_phone() {
        let range = IpRange::parse(" 192.168.1.0/24\t").expect("surrounding whitespace is ignored");
        assert!(range.contains(Ipv4Addr::new(192, 168, 1, 9)));
        assert!(!range.contains(Ipv4Addr::new(192, 168, 2, 9)));
        assert!(IpRange::parse("10.0.0.0/24/8").is_none());
        assert!(IpRange::parse("10.0.0.0/8/").is_none());
        // One slash is still fine, and a bare address is a /32.
        assert_eq!(IpRange::parse("10.0.0.0/8").unwrap().prefix_len, 8);
        assert_eq!(IpRange::parse("10.0.0.7").unwrap().prefix_len, 32);
    }

    /// One BLOCK rule on port 443 whose four address/port fields are set
    /// from `fields` (dest_ip, dest_port, source_ip, source_port).
    fn engine_with_block_rule(fields: [Option<&str>; 4]) -> FilterEngine {
        let s = |f: Option<&str>| f.map(str::to_string);
        FilterEngine::new(FilterConfig {
            enabled: true,
            mode: FilterMode::Blacklist,
            rules: vec![FilterRule {
                id: "r".to_string(),
                enabled: true,
                action: FilterAction::Block,
                protocol: Some(Protocol::Tcp),
                dest_ip: s(fields[0]),
                dest_port: s(fields[1]),
                source_ip: s(fields[2]),
                source_port: s(fields[3]),
                description: None,
            }],
            log_blocked: false,
        })
    }

    #[test]
    fn a_blank_address_or_port_spec_means_any_like_on_the_phone() {
        let pkt = build_tcp_packet([10, 0, 0, 1], [1, 1, 1, 1], 55000, 443);
        let other_port = build_tcp_packet([10, 0, 0, 1], [1, 1, 1, 1], 55000, 80);
        let to_443 = Some("443");
        for blank in ["", "  "] {
            let b = Some(blank);
            for (name, fields) in [
                ("dest_ip", [b, to_443, None, None]),
                ("source_ip", [None, to_443, b, None]),
                ("source_port", [None, to_443, None, b]),
            ] {
                let engine = engine_with_block_rule(fields);
                assert!(
                    !engine.should_allow(&pkt),
                    "blank {name} {blank:?} must not stop the rule matching"
                );
                assert!(
                    engine.should_allow(&other_port),
                    "blank {name}: the dest_port restriction still applies"
                );
            }
            // A blank dest_port alone: the rule covers every TCP port.
            let engine = engine_with_block_rule([None, b, None, None]);
            assert!(!engine.should_allow(&pkt));
            assert!(!engine.should_allow(&other_port));
        }
        // A non-blank spec that doesn't parse still fails closed (no match).
        for (name, fields) in [
            ("dest_ip", [Some("nope"), to_443, None, None]),
            ("source_ip", [None, to_443, Some("nope"), None]),
            ("source_port", [None, to_443, None, Some("nope")]),
            ("dest_port", [None, Some("nope"), None, None]),
        ] {
            let engine = engine_with_block_rule(fields);
            assert!(
                engine.should_allow(&pkt),
                "an unparseable {name} must not match"
            );
        }
        // Whitespace around a real spec is ignored, not treated as garbage.
        let engine = engine_with_block_rule([
            Some(" 1.1.1.0/24 "),
            to_443,
            Some(" 10.0.0.1 "),
            Some(" 55000 "),
        ]);
        assert!(!engine.should_allow(&pkt));
        assert!(engine.should_allow(&build_tcp_packet([10, 0, 0, 1], [1, 1, 2, 1], 55000, 443)));
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

    fn port_rule(id: &str, action: FilterAction, dest_port: &str) -> FilterRule {
        FilterRule {
            id: id.to_string(),
            enabled: true,
            action,
            protocol: Some(Protocol::Tcp),
            dest_ip: None,
            dest_port: Some(dest_port.to_string()),
            source_ip: None,
            source_port: None,
            description: None,
        }
    }

    /// Regression test: a non-first IPv4 fragment carries payload, not a
    /// TCP header, at `header_len`. Its "ports" must not be parsed, or a
    /// crafted fragment whose payload starts with the right two bytes would
    /// match a port-scoped ALLOW rule under Whitelist (default-deny) mode.
    #[test]
    fn test_non_first_fragment_ports_are_not_parsed() {
        // Offset in the low byte, MF clear (last fragment).
        let mut frag_low = build_tcp_packet([10, 0, 0, 1], [1, 1, 1, 1], 55000, 443);
        frag_low[6] = 0x00;
        frag_low[7] = 0x01;
        // Offset in the high bits of the 13-bit field, MF set.
        let mut frag_high = build_tcp_packet([10, 0, 0, 1], [1, 1, 1, 1], 55000, 443);
        frag_high[6] = 0x20 | 0x10; // MF + offset bit 12
        frag_high[7] = 0x00;

        for pkt in [&frag_low, &frag_high] {
            let parsed = parse_packet(pkt).expect("still a parseable IPv4 packet");
            assert_eq!(parsed.protocol, Protocol::Tcp);
            assert_eq!(parsed.src_port, None);
            assert_eq!(parsed.dst_port, None);
        }

        let whitelist = FilterEngine::new(FilterConfig {
            enabled: true,
            mode: FilterMode::Whitelist,
            rules: vec![port_rule("allow-443", FilterAction::Allow, "443")],
            log_blocked: true,
        });
        assert!(
            !whitelist.should_allow(&frag_low),
            "a non-first fragment must not match a port-scoped ALLOW rule"
        );
        assert!(!whitelist.should_allow(&frag_high));
    }

    /// First fragments (offset 0, MF set) and DF packets carry the real
    /// transport header, so their ports must still be parsed.
    #[test]
    fn test_first_fragment_and_df_ports_still_parsed() {
        let mut first_frag = build_tcp_packet([10, 0, 0, 1], [1, 1, 1, 1], 55000, 443);
        first_frag[6] = 0x20; // MF set, offset 0
        let mut df = build_tcp_packet([10, 0, 0, 1], [1, 1, 1, 1], 55000, 443);
        df[6] = 0x40; // DF set, offset 0

        for pkt in [&first_frag, &df] {
            let parsed = parse_packet(pkt).expect("parseable");
            assert_eq!(parsed.src_port, Some(55000));
            assert_eq!(parsed.dst_port, Some(443));
        }

        let blacklist = FilterEngine::new(FilterConfig {
            enabled: true,
            mode: FilterMode::Blacklist,
            rules: vec![port_rule("block-443", FilterAction::Block, "443")],
            log_blocked: true,
        });
        assert!(!blacklist.should_allow(&first_frag));
        assert!(!blacklist.should_allow(&df));
    }

    /// Regression test: unparseable packets (IPv6, which `parse_packet`
    /// doesn't handle, and truncated IPv4) used to be allowed
    /// unconditionally, so Whitelist (default-deny) mode failed open for
    /// them. They must now get the mode's default: denied in Whitelist,
    /// still allowed in Blacklist.
    #[test]
    fn test_unparseable_packets_follow_mode_default() {
        // Minimal IPv6 header (40 bytes): version 6, next header TCP, then
        // ports 55000 -> 443 right after the fixed header.
        let mut ipv6 = [0u8; 44];
        ipv6[0] = 0x60;
        ipv6[6] = 6;
        ipv6[40..42].copy_from_slice(&55000u16.to_be_bytes());
        ipv6[42..44].copy_from_slice(&443u16.to_be_bytes());
        // Truncated IPv4: version nibble 4 but shorter than a 20-byte header.
        let truncated_v4 = [0x45u8; 12];
        let unparseable: [(&str, &[u8]); 3] = [
            ("ipv6", &ipv6),
            ("truncated ipv4", &truncated_v4),
            ("empty", &[]),
        ];

        let allow_443 = FilterRule {
            id: "allow-443".to_string(),
            enabled: true,
            action: FilterAction::Allow,
            protocol: Some(Protocol::Tcp),
            dest_ip: None,
            dest_port: Some("443".to_string()),
            source_ip: None,
            source_port: None,
            description: None,
        };

        let whitelist = FilterEngine::new(FilterConfig {
            enabled: true,
            mode: FilterMode::Whitelist,
            rules: vec![allow_443.clone()],
            log_blocked: false,
        });
        for (name, pkt) in unparseable {
            assert!(
                !whitelist.should_allow(pkt),
                "Whitelist mode must deny an unparseable {name} packet, not fail open"
            );
        }
        // Parseable traffic is unaffected.
        assert!(whitelist.should_allow(&build_tcp_packet([10, 0, 0, 1], [1, 1, 1, 1], 55000, 443)));

        let blacklist = FilterEngine::new(FilterConfig {
            enabled: true,
            mode: FilterMode::Blacklist,
            rules: vec![FilterRule {
                action: FilterAction::Block,
                id: "block-443".to_string(),
                ..allow_443
            }],
            log_blocked: false,
        });
        for (name, pkt) in unparseable {
            assert!(
                blacklist.should_allow(pkt),
                "Blacklist mode must still allow an unparseable {name} packet"
            );
        }
    }
}
