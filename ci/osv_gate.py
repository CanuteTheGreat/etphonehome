#!/usr/bin/env python3
"""Severity gate over OSV-Scanner's JSON output (see ci/osv-scan.sh).

Fails (exit 1) when any finding that osv-scanner did not already ignore is High/Critical (CVSS base score >= 7.0,
or a GHSA severity of HIGH/CRITICAL) or has no severity at all (unrated is treated as possibly critical).
Medium/Low findings are listed but do not fail. Also fails when an osv-scanner.toml [[IgnoredVulns]] entry lacks a
non-empty `reason` or an `ignoreUntil` date: every ignore must say why and expire (osv-scanner stops honouring an
entry after its ignoreUntil, so an expired ignore turns the gate red again by itself).

Stdlib only (the CI job container's python may predate tomllib).
Usage: osv_gate.py RESULTS.json osv-scanner.toml
"""
import json
import math
import re
import sys

THRESHOLD = 7.0


def roundup(x):
    i = round(x * 100000)
    return i / 100000.0 if i % 10000 == 0 else (math.floor(i / 10000) + 1) / 10.0


def cvss3_base(vector):
    """CVSS v3.x base score from a vector string (FIRST spec)."""
    m = dict(p.split(":", 1) for p in vector.split("/")[1:] if ":" in p)
    try:
        av = {"N": 0.85, "A": 0.62, "L": 0.55, "P": 0.2}[m["AV"]]
        ac = {"L": 0.77, "H": 0.44}[m["AC"]]
        ui = {"N": 0.85, "R": 0.62}[m["UI"]]
        scope_changed = m["S"] == "C"
        pr = {"N": 0.85, "L": 0.68 if scope_changed else 0.62, "H": 0.5 if scope_changed else 0.27}[m["PR"]]
        c, i, a = ({"H": 0.56, "L": 0.22, "N": 0.0}[m[k]] for k in ("C", "I", "A"))
    except KeyError:
        return None
    iss = 1 - (1 - c) * (1 - i) * (1 - a)
    impact = 7.52 * (iss - 0.029) - 3.25 * (iss - 0.02) ** 15 if scope_changed else 6.42 * iss
    if impact <= 0:
        return 0.0
    expl = 8.22 * av * ac * pr * ui
    return roundup(min(1.08 * (impact + expl), 10)) if scope_changed else roundup(min(impact + expl, 10))


def cvss4_bucket(vector):
    """Conservative v4 estimate (no full MacroVector table): any High impact on the vulnerable or subsequent
    system with network/adjacent reach counts as High; the gate errs on the failing side."""
    m = dict(p.split(":", 1) for p in vector.split("/")[1:] if ":" in p)
    high = any(m.get(k) == "H" for k in ("VC", "VI", "VA", "SC", "SI", "SA"))
    if high and m.get("AV") in ("N", "A"):
        return 8.0
    if high:
        return 7.0
    return 5.0


def score_of(vuln):
    best = None
    for s in vuln.get("severity") or []:
        v = s.get("score", "")
        sc = None
        if v.startswith("CVSS:3"):
            sc = cvss3_base(v)
        elif v.startswith("CVSS:4"):
            sc = cvss4_bucket(v)
        else:
            try:
                sc = float(v)
            except ValueError:
                pass
        if sc is not None:
            best = sc if best is None else max(best, sc)
    label = ((vuln.get("database_specific") or {}).get("severity") or "").upper()
    label_score = {"CRITICAL": 9.0, "HIGH": 7.5, "MODERATE": 5.0, "MEDIUM": 5.0, "LOW": 2.0}.get(label)
    if label_score is not None:
        best = label_score if best is None else max(best, label_score)
    return best


def check_ignores(toml_path):
    text = open(toml_path, encoding="utf-8").read()
    problems = []
    blocks = re.split(r"(?m)^\s*\[\[IgnoredVulns\]\]\s*$", text)[1:]
    for b in blocks:
        b = re.split(r"(?m)^\s*\[", b)[0]
        vid = re.search(r'(?m)^\s*id\s*=\s*"([^"]+)"', b)
        vid = vid.group(1) if vid else "?"
        if not re.search(r"(?m)^\s*ignoreUntil\s*=\s*\d{4}-\d{2}-\d{2}", b):
            problems.append(f"{vid}: ignoreUntil date missing")
        r = re.search(r'(?m)^\s*reason\s*=\s*"(.*)"', b)
        if not r or len(r.group(1).strip()) < 20:
            problems.append(f"{vid}: reason missing or too short (say why it is not exploitable / not fixable, and the fix plan)")
    return len(blocks), problems


def main():
    results, toml_path = sys.argv[1], sys.argv[2]
    n_ignores, problems = check_ignores(toml_path)
    raw = open(results, encoding="utf-8").read().strip()
    data = json.loads(raw) if raw else {"results": []}
    failing, info, scanned = [], [], 0
    for res in data.get("results") or []:
        src = (res.get("source") or {}).get("path", "?")
        for pkg in res.get("packages") or []:
            scanned += 1
            p = pkg.get("package") or {}
            name = p.get("name") or "?"
            ver = p.get("version") or (p.get("commit") or "")[:12]
            vulns = {v["id"]: v for v in pkg.get("vulnerabilities") or []}
            for g in pkg.get("groups") or [{"ids": [i]} for i in vulns]:
                ids = g.get("ids") or []
                scores = [score_of(vulns[i]) for i in ids if i in vulns]
                scores = [s for s in scores if s is not None]
                ms = g.get("max_severity")
                try:
                    if ms not in (None, ""):
                        scores.append(float(ms))
                except ValueError:
                    pass
                sc = max(scores) if scores else None
                row = f"{name}@{ver}  {','.join(ids)}  severity={'unrated' if sc is None else sc}  ({src.split('/scan/')[-1]})"
                (failing if sc is None or sc >= THRESHOLD else info).append(row)
    print(f"osv gate: {scanned} package(s) with findings, {n_ignores} documented ignore(s) in {toml_path}")
    for r in info:
        print(f"  medium/low (not gating): {r}")
    for r in failing:
        print(f"::error::HIGH/CRITICAL or unrated: {r}")
    for p in problems:
        print(f"::error::osv-scanner.toml ignore entry invalid: {p}")
    if failing or problems:
        print("Fix: upgrade the dependency (then refresh the lockfiles), or add a [[IgnoredVulns]] entry to osv-scanner.toml "
              "with id, ignoreUntil (a date at most ~3 months out) and a reason (why it is not exploitable here + fix plan).")
        return 1
    print("osv gate: PASS (no un-ignored High/Critical/unrated findings)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
