#!/usr/bin/env bash
# Dependency vulnerability gate (CI and local). Runs OSV-Scanner (Apache-2.0, https://github.com/google/osv-scanner)
# from its pinned container image over:
#   - every committed Gradle lockfile (`gradle.lockfile`, see the dependency-locking block in the root build script),
#   - ci/vendored-components.json if present: vendored sources and bundled native binaries Gradle cannot see,
#     in OSV-Scanner's own JSON format (Maven coordinates, or upstream git commits of the exact release tag),
# then ci/osv_gate.py fails the job on any High/Critical (CVSS >= 7.0) or unrated finding that is not listed in
# osv-scanner.toml [[IgnoredVulns]] with a reason and an ignoreUntil date. Medium/Low findings are printed only.
# The free public api.osv.dev is the only service used: no account, no API key.
#
# Usage (repo root): ci/osv-scan.sh            needs docker (the CI job container has the host's docker socket)
# Env: OSV_RESULTS (default osv-results.json) keeps the raw scanner JSON for the job log / artifacts.
set -euo pipefail
IMAGE="ghcr.io/google/osv-scanner:v2.7.1@sha256:a70ffffc2bfd33482953e5d6b64c6fcdb30239717a07b0f0d3ab2859ce3e4cde"
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
OUT=${OSV_RESULTS:-osv-results.json}

mapfile -t LOCKS < <(git ls-files -- '*gradle.lockfile' 'gradle.lockfile')
[ "${#LOCKS[@]}" -gt 0 ] || { echo "::error::no committed gradle.lockfile found; dependency locking is not enabled" >&2; exit 2; }
[ -f osv-scanner.toml ] || { echo "::error::osv-scanner.toml missing at the repo root" >&2; exit 2; }
FILES=("${LOCKS[@]}" osv-scanner.toml)
ARGS=(scan source --format json --config /scan/osv-scanner.toml)
for f in "${LOCKS[@]}"; do ARGS+=(--lockfile "gradle.lockfile:/scan/$f"); done
if [ -f ci/vendored-components.json ]; then
  FILES+=(ci/vendored-components.json)
  ARGS+=(--lockfile "osv-scanner:/scan/ci/vendored-components.json")
fi
echo "Scanning: ${FILES[*]}"

# No bind mount: in CI the job runs in a container talking to the host's docker daemon, so a workspace
# path is not a host path. Copy the inputs into a created (not yet started) scanner container instead.
NAME="osv-scan-$$-$RANDOM"
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT
docker pull -q "$IMAGE" >/dev/null
docker create --name "$NAME" "$IMAGE" "${ARGS[@]}" >/dev/null
tar cf - --transform 's,^,scan/,' "${FILES[@]}" | docker cp - "$NAME:/"
set +e
docker start -a "$NAME" >"$OUT" 2>osv-stderr.log
rc=$?
set -e
# 0 = nothing found, 1 = vulnerabilities found (the gate below decides); anything else is a scanner failure.
if [ "$rc" != 0 ] && [ "$rc" != 1 ]; then
  cat osv-stderr.log >&2
  echo "::error::osv-scanner exited $rc" >&2
  exit 2
fi
grep -E 'Scanned .* found' osv-stderr.log || true
# osv-scanner only lists packages that have findings, so prove every input was really parsed.
total=$(grep -oE 'found [0-9]+ packages?' osv-stderr.log | awk '{s+=$2} END {print s+0}')
nread=$(grep -cE 'Scanned .* found [1-9][0-9]* package' osv-stderr.log || true)
if [ "$nread" -lt "${#LOCKS[@]}" ] || [ "$total" -eq 0 ]; then
  cat osv-stderr.log >&2
  echo "::error::osv-scanner read packages from only $nread input(s) (expected at least ${#LOCKS[@]} lockfiles); refusing a vacuous pass" >&2
  exit 2
fi
echo "osv-scanner: $total packages from $nread input(s)"
python3 ci/osv_gate.py "$OUT" osv-scanner.toml
