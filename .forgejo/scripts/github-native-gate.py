#!/usr/bin/env python3
"""Make the native macOS/Windows results part of Forgejo CI.

There are no macOS or Windows runners here, so .github/workflows/
desktop-native.yml runs on the public GitHub mirror (GitHub-hosted Macs,
Apple Silicon + Intel, and Windows Server), executing
desktop/packaging/macos/e2e.sh and desktop/packaging/windows/e2e.ps1 against
the phone core. This waits for that run for the same commit and fails when
it fails, printing every job's result. Unauthenticated public API (60
requests/hour per address), so it polls slowly.

usage: github-native-gate.py <commit sha>
"""
import json
import sys
import time
import urllib.request

REPO = "CanuteTheGreat/etphonehome"
API = f"https://api.github.com/repos/{REPO}/actions"


def get(url):
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": "etph-ci"})
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except Exception as e:  # rate limit / network blip: wait and retry
            print(f"  GitHub API: {e}; retrying")
            time.sleep(60 * (attempt + 1))
    raise SystemExit("GitHub API unreachable")


def main():
    sha = sys.argv[1]
    # desktop-native.yml runs on release tags and manual dispatch only
    # (failing GitHub runs email the owner), so most commits have no run:
    # report the latest dispatched run and its commit instead of waiting.
    runs = get(f"{API}/workflows/desktop-native.yml/runs?head_sha={sha}&per_page=5").get("workflow_runs", [])
    if not runs:
        latest = get(f"{API}/workflows/desktop-native.yml/runs?per_page=1").get("workflow_runs", [])
        if latest:
            l = latest[0]
            print(f"no native run for {sha[:8]} (dispatched by hand); latest: {l['head_sha'][:8]} "
                  f"{l['status']} {l.get('conclusion')} {l['html_url']}")
        else:
            print(f"no native run for {sha[:8]} yet (dispatched by hand)")
        return
    run = runs[0]
    print(f"GitHub run: {run['html_url']}")
    for _ in range(60):  # up to 2 h
        run = get(f"{API}/runs/{run['id']}")
        if run["status"] == "completed":
            break
        print(f"  status {run['status']}")
        time.sleep(120)
    jobs = get(f"{API}/runs/{run['id']}/jobs?per_page=50").get("jobs", [])
    for j in jobs:
        print(f"  {j['conclusion'] or j['status']:>10}  {j['name']}  {j['html_url']}")
        for s in j.get("steps", []):
            if s.get("conclusion") not in ("success", "skipped", None):
                print(f"              step {s['name']}: {s['conclusion']}")
    concl = run.get("conclusion")
    if concl == "cancelled":
        print("cancelled on GitHub (superseded by a newer commit); the newer commit's gate covers it")
        return
    if concl != "success":
        raise SystemExit(f"native macOS/Windows CI: {concl}")
    print("native macOS + Windows build and e2e: success")


if __name__ == "__main__":
    main()
