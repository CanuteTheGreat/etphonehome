#!/usr/bin/env python3
"""Write a CycloneDX 1.5 SBOM (JSON) for ETPhoneHome from the committed lockfiles.

Inputs (both are the exact graphs the release is built from):
  desktop/Cargo.lock           Rust crates (desktop client, GUI, tray, router agent). Cargo.lock is not
                               committed in this workspace, so the release job runs `cargo generate-lockfile`
                               (a resolve, no compile) right before this script.
  android/app/gradle.lockfile  Android release runtime classpath (Maven coordinates)

Usage: gen-sbom.py --version 0.9.3 [--repo-root .] [--timestamp ISO8601] -o etphonehome-v0.9.3.cdx.json

Pure standard library, no network, deterministic for a given input and timestamp
(SOURCE_DATE_EPOCH is honoured), so it needs no build toolchain.
"""
import argparse, datetime, hashlib, json, os, re, sys, urllib.parse

try:
    import tomllib
except ImportError:  # python < 3.11
    tomllib = None


def rust_components(path):
    text = open(path, encoding="utf-8").read()
    if tomllib:
        pkgs = tomllib.loads(text).get("package", [])
    else:  # minimal fallback parser for Cargo.lock
        pkgs = []
        for block in text.split("[[package]]")[1:]:
            d = {}
            for key in ("name", "version", "source", "checksum"):
                m = re.search(r'^%s = "([^"]*)"' % key, block, re.M)
                if m:
                    d[key] = m.group(1)
            pkgs.append(d)
    out = []
    for p in pkgs:
        name, ver = p["name"], p["version"]
        src = p.get("source", "")
        purl = "pkg:cargo/%s@%s" % (urllib.parse.quote(name), ver)
        c = {"type": "library", "bom-ref": purl, "name": name, "version": ver, "purl": purl}
        if p.get("checksum"):
            c["hashes"] = [{"alg": "SHA-256", "content": p["checksum"]}]
        if not src:  # workspace member: our own code
            c["properties"] = [{"name": "etph:first-party", "value": "true"}]
        elif not src.startswith("registry+"):
            c["properties"] = [{"name": "cargo:source", "value": src}]
        out.append(c)
    return out


def gradle_components(path):
    out = {}
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#") or line == "empty=":
            continue
        coord = line.split("=", 1)[0]
        parts = coord.split(":")
        if len(parts) != 3:
            continue
        group, art, ver = parts
        purl = "pkg:maven/%s/%s@%s" % (group, art, ver)
        out[purl] = {"type": "library", "bom-ref": purl, "group": group, "name": art,
                     "version": ver, "purl": purl}
    return list(out.values())


def build(root, version, ts):
    comps = rust_components(os.path.join(root, "desktop", "Cargo.lock"))
    comps += gradle_components(os.path.join(root, "android", "app", "gradle.lockfile"))
    seen, uniq = set(), []
    for c in sorted(comps, key=lambda c: c["bom-ref"]):
        if c["bom-ref"] in seen:
            continue
        seen.add(c["bom-ref"])
        uniq.append(c)
    serial = "urn:uuid:" + str(__import__("uuid").UUID(
        hashlib.sha256(("etphonehome-%s-%s" % (version, ",".join(sorted(seen)))).encode()).hexdigest()[:32]))
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": serial,
        "version": 1,
        "metadata": {
            "timestamp": ts,
            "tools": {"components": [{"type": "application", "name": "etph gen-sbom.py"}]},
            "component": {
                "type": "application", "bom-ref": "etphonehome", "name": "etphonehome", "version": version,
                "licenses": [{"license": {"id": "MIT"}}],
                "supplier": {"name": "Errant Solutions"},
            },
        },
        "components": uniq,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True)
    ap.add_argument("--repo-root", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    ap.add_argument("--timestamp")
    ap.add_argument("--min-components", type=int, default=50)
    ap.add_argument("-o", "--output", required=True)
    a = ap.parse_args()
    ts = a.timestamp
    if not ts:
        epoch = os.environ.get("SOURCE_DATE_EPOCH")
        now = datetime.datetime.fromtimestamp(int(epoch), datetime.timezone.utc) if epoch \
            else datetime.datetime.now(datetime.timezone.utc)
        ts = now.strftime("%Y-%m-%dT%H:%M:%SZ")
    bom = build(a.repo_root, a.version.lstrip("v"), ts)
    if len(bom["components"]) < a.min_components:
        sys.exit("gen-sbom: only %d components found, refusing to write a hollow SBOM" % len(bom["components"]))
    with open(a.output, "w", encoding="utf-8") as f:
        json.dump(bom, f, indent=2, sort_keys=True)
        f.write("\n")
    print("wrote %s: %d components" % (a.output, len(bom["components"])))


if __name__ == "__main__":
    main()
