#!/usr/bin/env python3
"""Unit tests for ci/osv_gate.py (run: python3 ci/test_osv_gate.py)."""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import osv_gate  # noqa: E402


def run_gate(results, toml):
    with tempfile.TemporaryDirectory() as d:
        r, t = os.path.join(d, "r.json"), os.path.join(d, "o.toml")
        with open(r, "w") as f:
            f.write(json.dumps(results))
        with open(t, "w") as f:
            f.write(toml)
        p = subprocess.run([sys.executable, os.path.join(HERE, "osv_gate.py"), r, t], capture_output=True, text=True)
        return p.returncode, p.stdout


def pkg(vid, severity=None, label=None, max_sev=None):
    v = {"id": vid}
    if severity:
        v["severity"] = [{"type": "CVSS_V3", "score": severity}]
    if label:
        v["database_specific"] = {"severity": label}
    g = {"ids": [vid]}
    if max_sev is not None:
        g["max_severity"] = max_sev
    return {"results": [{"source": {"path": "/scan/a/gradle.lockfile"}, "packages": [
        {"package": {"name": "g:a", "version": "1.0", "ecosystem": "Maven"}, "vulnerabilities": [v], "groups": [g]}]}]}


CLEAN = {"results": [{"source": {"path": "/scan/gradle.lockfile"}, "packages": [{"package": {"name": "g:a", "version": "1"}}]}]}


class GateTest(unittest.TestCase):
    def test_cvss3_scores(self):
        self.assertEqual(osv_gate.cvss3_base("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"), 9.8)
        self.assertEqual(osv_gate.cvss3_base("CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N"), 6.1)
        self.assertEqual(osv_gate.cvss3_base("CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:N/I:N/A:N"), 0.0)

    def test_clean_passes(self):
        rc, out = run_gate(CLEAN, "")
        self.assertEqual(rc, 0, out)

    def test_critical_fails(self):
        rc, out = run_gate(pkg("GHSA-x", "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"), "")
        self.assertEqual(rc, 1, out)
        self.assertIn("GHSA-x", out)

    def test_ghsa_high_label_fails(self):
        self.assertEqual(run_gate(pkg("GHSA-y", label="HIGH"), "")[0], 1)

    def test_medium_passes_but_is_listed(self):
        rc, out = run_gate(pkg("GHSA-m", "CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N"), "")
        self.assertEqual(rc, 0, out)
        self.assertIn("not gating", out)

    def test_unrated_fails(self):
        self.assertEqual(run_gate(pkg("CVE-1"), "")[0], 1)

    def test_no_findings_passes(self):
        self.assertEqual(run_gate({"results": []}, "")[0], 0)

    def test_ignore_needs_reason_and_date(self):
        toml = '[[IgnoredVulns]]\nid = "CVE-1"\nreason = "short"\n'
        rc, out = run_gate(CLEAN, toml)
        self.assertEqual(rc, 1)
        self.assertIn("ignoreUntil", out)
        self.assertIn("reason", out)
        good = '[[IgnoredVulns]]\nid = "CVE-1"\nignoreUntil = 2027-01-31\nreason = "not reachable: only the AES block primitive is linked"\n'
        self.assertEqual(run_gate(CLEAN, good)[0], 0)


if __name__ == "__main__":
    unittest.main(verbosity=1)
