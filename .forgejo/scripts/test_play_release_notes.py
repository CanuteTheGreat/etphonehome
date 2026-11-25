#!/usr/bin/env python3
"""Tests for play-release-notes.py (run: python3 .forgejo/scripts/test_play_release_notes.py)."""
import sys
sys.dont_write_bytecode = True  # check-claims.py reads every file in .forgejo/scripts
import importlib.util
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("prn", os.path.join(HERE, "play-release-notes.py"))
assert spec is not None and spec.loader is not None
prn = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prn)

CL = """# Changelog
## [Unreleased]

## [0.9.5] - 2026-11-24

### Added
- **ETPH Pro for copies not installed from Google Play:** buy on https://errant.solutions/etphonehome/pro/ (Stripe).

## [0.9.4] - 2026-11-24

### Added
- **Monthly data limit (Pro):** warns at 80%.
- **Direct thing:** something <!-- not-play -->

### Fixed
- **Crash on start.** details

## [0.9.2] - 2026-10-22
- **Old:** x
"""


class Notes(unittest.TestCase):
    def test_direct_purchase_never_reaches_play_and_older_section_is_used(self):
        n = prn.notes(CL)
        self.assertTrue(n.startswith("Version 0.9.5\n"), n)
        self.assertNotIn("errant.solutions", n)
        self.assertNotIn("not installed from Google Play", n)
        self.assertNotIn("Direct thing", n)
        self.assertIn("- Monthly data limit (Pro)", n)
        self.assertIn("- Fixed: Crash on start", n)
        self.assertNotIn("Old", n)

    def test_newest_section_used_when_it_has_play_bullets(self):
        n = prn.notes(CL.replace("## [0.9.5] - 2026-11-24\n\n### Added\n", "## [0.9.5] - 2026-11-24\n\n### Added\n- **Faster USB.** yes\n"))
        self.assertEqual(n, "Version 0.9.5\n- Faster USB")

    def test_limit_500(self):
        many = "## [1.0.0] - x\n" + "".join(f"- **Item {i} with a fairly long title here**\n" for i in range(40))
        self.assertLessEqual(len(prn.notes(many)), 500)

    def test_real_changelog(self):
        n = prn.notes(open(os.path.join(HERE, "..", "..", "CHANGELOG.md"), encoding="utf-8").read())
        self.assertLessEqual(len(n), 500)
        self.assertIsNone(prn.NOT_PLAY.search(n), n)


if __name__ == "__main__":
    unittest.main()
