#!/usr/bin/env python3
"""Unit tests for gen-sbom.py (stdlib only, no network)."""
import json, os, subprocess, sys, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
GEN = os.path.join(HERE, "gen-sbom.py")


class SbomTest(unittest.TestCase):
    def gen(self):
        out = os.path.join(tempfile.mkdtemp(), "s.json")
        subprocess.run([sys.executable, GEN, "--repo-root", ROOT, "--version", "v9.9.9",
                        "--timestamp", "2026-01-01T00:00:00Z", "-o", out], check=True, capture_output=True)
        return open(out).read()

    def test_shape_and_both_ecosystems(self):
        b = json.loads(self.gen())
        self.assertEqual(b["bomFormat"], "CycloneDX")
        self.assertEqual(b["metadata"]["component"]["version"], "9.9.9")
        refs = [c["bom-ref"] for c in b["components"]]
        self.assertEqual(len(refs), len(set(refs)))
        self.assertTrue(any(r.startswith("pkg:cargo/") for r in refs))
        self.assertTrue(any(r.startswith("pkg:maven/") for r in refs))

    def test_covers_every_lock_entry(self):
        b = json.loads(self.gen())
        refs = {c["bom-ref"] for c in b["components"]}
        n_cargo = sum(1 for l in open(os.path.join(ROOT, "desktop", "Cargo.lock")) if l.startswith("[[package]]"))
        self.assertGreaterEqual(sum(r.startswith("pkg:cargo/") for r in refs), n_cargo)

    def test_deterministic(self):
        self.assertEqual(self.gen(), self.gen())


if __name__ == "__main__":
    unittest.main()
