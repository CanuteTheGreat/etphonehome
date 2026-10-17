#!/usr/bin/env python3
"""Mutation scores per core module, checked against a ratchet file.

  mutation-score.py pit   <mutations.xml>        <thresholds.json>
  mutation-score.py cargo <mutants.out/outcomes.json> <thresholds.json>

Score = killed / (killed + survived) per module, where "killed" includes
timeouts (the mutant hung the tests) and "survived" includes mutants no test
even reaches (PIT NO_COVERAGE). Unviable mutants (don't compile) are
ignored. A module below its minimum fails the run; the minimums only ever go
up (raise them in ci/mutation-thresholds.json when a run beats them). Every
surviving mutant in a core module is a missing assertion: the survivors are
listed so the next test to write is obvious.
"""
import json
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict


def kotlin_module(cls):
    # com.etphonehome.relay.UserspaceRelay$TcpFlow -> relay
    parts = cls.split(".")
    return parts[2] if len(parts) > 3 else "other"


def rust_module(path):
    # common/src/tls/certpin.rs -> tls/certpin.rs
    return path.split("/src/", 1)[-1]


def from_pit(path):
    stats = defaultdict(lambda: [0, 0])
    survivors = defaultdict(list)
    for m in ET.parse(path).getroot().iter("mutation"):
        status = m.get("status")
        mod = kotlin_module(m.findtext("mutatedClass"))
        if status in ("KILLED", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR"):
            stats[mod][0] += 1
        elif status in ("SURVIVED", "NO_COVERAGE"):
            stats[mod][1] += 1
            survivors[mod].append(
                f"{m.findtext('sourceFile')}:{m.findtext('lineNumber')} "
                f"{m.findtext('mutatedMethod')}: {m.findtext('description')} [{status}]"
            )
    return stats, survivors


def from_cargo(path):
    stats = defaultdict(lambda: [0, 0])
    survivors = defaultdict(list)
    for o in json.load(open(path))["outcomes"]:
        sc = o.get("scenario")
        if not isinstance(sc, dict) or "Mutant" not in sc:
            continue  # the baseline build
        mut = sc["Mutant"]
        mod = rust_module(mut["source_file"] if isinstance(mut.get("source_file"), str) else mut["file"])
        summary = o["summary"]
        if summary in ("CaughtMutant", "Timeout"):
            stats[mod][0] += 1
        elif summary == "MissedMutant":
            stats[mod][1] += 1
            span = mut.get("span", {}).get("start", {})
            survivors[mod].append(
                f"{mod}:{span.get('line', '?')} {mut.get('function', {}).get('function_name', '')}: "
                f"replace {mut.get('genre', '')} -> {mut.get('replacement', '')}"
            )
    return stats, survivors


def main():
    kind, report, thresholds_path = sys.argv[1:4]
    stats, survivors = (from_pit if kind == "pit" else from_cargo)(report)
    mins = json.load(open(thresholds_path)).get("kotlin" if kind == "pit" else "rust", {})
    if not stats:
        print(f"no mutants were scored in {report}: the run measured nothing")
        sys.exit(1)
    bad = 0
    print(f"{'module':28} {'killed':>7} {'survived':>8} {'score':>7} {'minimum':>8}")
    for mod in sorted(stats):
        k, s = stats[mod]
        score = k / (k + s) if k + s else 1.0
        need = mins.get(mod)
        verdict = ""
        if need is not None and score + 1e-9 < need:
            verdict = "  FAIL (below minimum)"
            bad += 1
        elif need is not None and score > need + 0.02:
            verdict = "  (beats the minimum: raise it)"
        print(f"{mod:28} {k:>7} {s:>8} {score:>7.1%} {('-' if need is None else f'{need:.0%}'):>8}{verdict}")
    for mod in sorted(set(mins) - set(stats)):
        print(f"{mod:28} no mutants found: FAIL (module renamed? update the thresholds)")
        bad += 1
    print()
    for mod in sorted(survivors):
        print(f"--- surviving mutants in {mod} ({len(survivors[mod])}) ---")
        for line in survivors[mod][:60]:
            print("  " + line)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
