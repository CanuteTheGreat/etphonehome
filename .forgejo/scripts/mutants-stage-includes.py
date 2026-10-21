#!/usr/bin/env python3
"""Stage the files desktop/ tests include_str!/include_bytes! from outside desktop/.

cargo-mutants copies desktop/ to $TMPDIR/cargo-mutants-*.tmp/, so a test's
include_str!("../../../android/...") resolves relative to $TMPDIR instead of
the repo root. Copy every such target to $TMPDIR/<repo-relative path>, found
by scanning the sources (multi-line macro calls included), so a new
cross-tree include can't fail the unmutated baseline again (it did on
2026-10-21: common/src/aoa/mod.rs -> accessory_filter.xml).

usage (from desktop/): mutants-stage-includes.py "$TMPDIR"
"""
import os
import re
import shutil
import sys

INCLUDE = re.compile(r'include_(?:str|bytes)!\(\s*"([^"]+)"\s*\)')


def main() -> int:
    dest_root = sys.argv[1]
    desktop = os.path.abspath(".")
    repo = os.path.dirname(desktop)
    staged = 0
    for top, dirs, files in os.walk(desktop):
        dirs[:] = [d for d in dirs if d not in ("target", "mutants.out", ".git")]
        for name in files:
            if not name.endswith(".rs"):
                continue
            path = os.path.join(top, name)
            with open(path, encoding="utf-8", errors="replace") as f:
                src = f.read()
            for rel in INCLUDE.findall(src):
                target = os.path.normpath(os.path.join(top, rel))
                if os.path.commonpath([target, desktop]) == desktop:
                    continue  # inside desktop/: cargo-mutants copies it
                if os.path.commonpath([target, repo]) != repo:
                    print(f"{path}: include {rel} leaves the repo", file=sys.stderr)
                    return 1
                if not os.path.isfile(target):
                    print(f"{path}: include {rel} -> missing {target}", file=sys.stderr)
                    return 1
                out = os.path.join(dest_root, os.path.relpath(target, repo))
                os.makedirs(os.path.dirname(out), exist_ok=True)
                shutil.copyfile(target, out)
                staged += 1
                print(f"staged {os.path.relpath(target, repo)} (from {os.path.relpath(path, repo)})")
    print(f"{staged} out-of-tree include(s) staged under {dest_root}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
