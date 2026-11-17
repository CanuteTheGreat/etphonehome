#!/usr/bin/env python3
"""Unit tests for gen-sbom.py (stdlib only, no network)."""
import json, os, shutil, subprocess, sys, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
GEN = os.path.join(HERE, "gen-sbom.py")


CARGO_LOCK = """version = 4

[[package]]
name = "etph"
version = "0.9.3"

[[package]]
name = "serde"
version = "1.0.200"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "aa"
"""


class SbomTest(unittest.TestCase):
    def setUp(self):
        # Cargo.lock is not committed: use a synthetic one next to the real Gradle lockfile.
        self.root = tempfile.mkdtemp()
        os.makedirs(os.path.join(self.root, "desktop"))
        os.makedirs(os.path.join(self.root, "android", "app"))
        open(os.path.join(self.root, "desktop", "Cargo.lock"), "w").write(CARGO_LOCK)
        shutil.copy(os.path.join(ROOT, "android", "app", "gradle.lockfile"),
                    os.path.join(self.root, "android", "app", "gradle.lockfile"))

    def gen(self):
        out = os.path.join(tempfile.mkdtemp(), "s.json")
        r = subprocess.run([sys.executable, GEN, "--repo-root", self.root, "--min-components", "10", "--version", "v9.9.9",
                            "--timestamp", "2026-01-01T00:00:00Z", "-o", out], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
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
        self.assertIn("pkg:cargo/serde@1.0.200", refs)
        self.assertIn("pkg:cargo/etph@0.9.3", refs)
        n_gradle = sum(1 for l in open(os.path.join(ROOT, "android", "app", "gradle.lockfile"))
                       if l.strip() and not l.startswith("#") and "=" in l and l.count(":") == 2)
        self.assertGreaterEqual(sum(r.startswith("pkg:maven/") for r in refs), n_gradle)

    def test_deterministic(self):
        self.assertEqual(self.gen(), self.gen())


if __name__ == "__main__":
    unittest.main()
