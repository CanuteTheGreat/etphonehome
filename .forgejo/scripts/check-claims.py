#!/usr/bin/env python3
"""Claims ledger check (ci/claims.tsv): every README claim maps to a CI test.

Fails when
  - a ledger row's claim text is no longer in its source file (reworded
    claim: update the row so it still points at the test that proves it),
  - an evidence string is missing from its test file (test renamed/removed),
  - an evidence file is not run by any workflow (a test nobody executes
    proves nothing),
  - a "- ✅ **Feature**" line in README.md has no ledger row,
  - a row has no evidence ("-") without the claim itself saying it is
    unverified.

usage: check-claims.py [repo root]
"""
import glob
import os
import re
import sys

root = sys.argv[1] if len(sys.argv) > 1 else "."
os.chdir(root)

workflows = {p: open(p, encoding="utf-8").read() for p in glob.glob(".forgejo/workflows/*.yml")}
scripts = {p: open(p, encoding="utf-8").read() for p in glob.glob(".forgejo/scripts/*")}


def executed_scripts():
    """Scripts a workflow runs, directly or through another such script."""
    reached = set()
    frontier = [t for t in workflows.values()]
    while frontier:
        text = frontier.pop()
        for path, body in scripts.items():
            if path not in reached and os.path.basename(path) in text:
                reached.add(path)
                frontier.append(body)
    return reached


RUN = executed_scripts()
ALL_WF = "\n".join(workflows.values())


def is_executed(path):
    if path.startswith(".forgejo/workflows/"):
        return path in workflows
    if path.startswith(".forgejo/scripts/"):
        return path in RUN
    if path.endswith(".rs") and path.startswith("desktop/"):
        return "cargo test --all" in ALL_WF
    if path.endswith(".kt") and "/src/test/" in path:
        if path.startswith("android/core/"):
            return re.search(r"cd core && \.\./gradlew(?: --?[\w.=-]+)* test\b", ALL_WF) is not None
        # Gradle options (e.g. --max-workers=2) may sit between gradlew and the task.
        return re.search(r"\./gradlew(?: --?[\w.=-]+)* test\b", ALL_WF) is not None
    return False


rows, bad = [], 0
for n, line in enumerate(open("ci/claims.tsv", encoding="utf-8"), 1):
    if not line.strip() or line.startswith("#"):
        continue
    f = line.rstrip("\n").split("\t")
    if len(f) != 5:
        print(f"ci/claims.tsv:{n}: expected 5 tab-separated columns, got {len(f)}")
        bad += 1
        continue
    rows.append((n, *f))

sources = {}
for n, cid, src, claim, ev_file, ev_text in rows:
    text = sources.setdefault(src, open(src, encoding="utf-8").read() if os.path.exists(src) else None)
    if text is None or claim not in text:
        print(f"FAIL [{cid}] claim text not found in {src}: {claim!r}")
        bad += 1
    if ev_file == "-":
        if not re.search(r"not yet verified|unverified|experimental", claim, re.I):
            print(f"FAIL [{cid}] no evidence, and the claim does not say it is unverified")
            bad += 1
        else:
            print(f"note [{cid}] disclaimed in {src}: {ev_text}")
        continue
    if not os.path.exists(ev_file):
        print(f"FAIL [{cid}] evidence file missing: {ev_file}")
        bad += 1
        continue
    if ev_text not in open(ev_file, encoding="utf-8").read():
        print(f"FAIL [{cid}] evidence {ev_text!r} not found in {ev_file}")
        bad += 1
    elif not is_executed(ev_file):
        print(f"FAIL [{cid}] {ev_file} is not run by any workflow")
        bad += 1
    else:
        print(f"ok   [{cid}] {ev_file}: {ev_text[:70]}")

readme = open("README.md", encoding="utf-8").read()
covered = [claim for _, _, src, claim, _, _ in rows if src == "README.md"]
for feat in re.findall(r"^- ✅ \*\*(.+?)\*\*", readme, re.M):
    if not any(feat in c or c in feat for c in covered):
        print(f"FAIL README feature {feat!r} has no row in ci/claims.tsv")
        bad += 1

print(f"\nclaims ledger: {len(rows)} rows, {bad} problem(s)")
sys.exit(1 if bad else 0)
