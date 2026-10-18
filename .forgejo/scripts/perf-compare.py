#!/usr/bin/env python3
"""Compare tunnel-deep.sh's perf.json with the committed baseline.

Throughput keys (*_mibps) fail when below baseline / factor, latency keys
(*_ms) when above max(baseline * factor, baseline + 2 ms). direct_* keys describe the runner, not
the product. When both files carry direct_down_mibps (the same 256 MiB fetched
without the tunnel), throughput limits are scaled by measured/baseline runner
speed, capped at 1 (a fast runner never makes the check stricter) and floored
at RUNNER_SCALE_FLOOR (a runner that slow fails rather than hiding a real
regression). The relay's throughput tracks the runner's own speed closely:
over five green and red nightlies usb_down / direct_down stayed at 0.052-0.055
while direct_down ranged 523-2783 MiB/s. A key missing from the
measurement fails (its section broke); a key missing from the baseline is
reported so it can be added.

usage: perf-compare.py <baseline.json> <measured.json> [factor=2]
"""
import json
import sys

base = json.load(open(sys.argv[1]))
got = json.load(open(sys.argv[2]))
factor = float(sys.argv[3]) if len(sys.argv) > 3 else 2.0
RUNNER_SCALE_FLOOR = 0.2
bad = 0
scale = 1.0
bd, md = base.get("direct_down_mibps"), got.get("direct_down_mibps")
if bd and md:
    scale = max(RUNNER_SCALE_FLOOR, min(1.0, md / bd))
    print(f"runner speed: direct_down {md} MiB/s vs baseline {bd} MiB/s -> throughput limits x{round(scale, 3)}")
else:
    print("runner speed: direct_down_mibps missing on one side, throughput limits unscaled")
print(f"{'metric':24} {'baseline':>10} {'measured':>10} {'limit':>10}  verdict")
for key in sorted(set(base) | set(got)):
    if key.startswith("_"):
        continue
    b, m = base.get(key), got.get(key)
    if key.startswith("direct_"):
        print(f"{key:24} {b!s:>10} {m!s:>10} {'-':>10}  runner context")
        continue
    if m is None:
        print(f"{key:24} {b!s:>10} {'missing':>10} {'-':>10}  FAIL (not measured)")
        bad += 1
        continue
    if b is None:
        print(f"{key:24} {'-':>10} {m:>10} {'-':>10}  new metric, add it to the baseline")
        continue
    if key.endswith("_mibps"):
        limit = b / factor * scale
        ok = m >= limit
    else:
        # Sub-millisecond latencies jitter by more than 2x on a shared
        # runner; 2 ms of absolute slack keeps the check about real
        # regressions (a 40 ms Nagle stall, a polling loop).
        limit = max(b * factor, b + 2.0)
        ok = m <= limit
    print(f"{key:24} {b:>10} {m:>10} {round(limit, 2):>10}  {'ok' if ok else 'FAIL (regression)'}")
    bad += 0 if ok else 1
sys.exit(1 if bad else 0)
