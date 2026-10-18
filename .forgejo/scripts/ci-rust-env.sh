# shellcheck shell=bash
# Source this inside a CI build container (rust:1-bookworm or similar) before
# cargo. It keeps each job's memory and time down (see the build-memory notes
# in the workflow):
#   * mold as the linker (fast, low-overhead links; gcc >= 12 -fuse-ld=mold),
#   * sccache for third-party crates, its disk cache on a per-host named
#     volume mounted at /sccache, so dependencies aren't recompiled every run,
#   * CARGO_INCREMENTAL=0 (incremental state is never reused in CI, it only
#     costs memory and disk, and sccache can't cache incremental crates).
# Both tools are pinned release binaries checked against sha256. If a download
# fails the build still runs (plain linker / no cache) and says so: they are
# speed/memory aids, not correctness inputs.
# Extra rustc flags the repo needs go in CI_RUSTFLAGS (RUSTFLAGS replaces the
# target rustflags from .cargo/config.toml, so pass those through here).
# CI_NO_SCCACHE=1 skips sccache (a job with no persistent /sccache volume).
# CI_RUST_ENV_EXPORT=1 also writes the settings to GITHUB_ENV/GITHUB_PATH for
# the following steps of a job that builds in its own job container.

ci_rust_env() {
  local arch tools mold_v=3.0.0 sccache_v=0.18.0 mold_sha sccache_sha
  arch=$(uname -m)
  case "$arch" in
    x86_64)
      mold_sha=6c90d4a474c7c0409dfb575be03a5345878ac14fdba18de8b40fa58c60121189
      sccache_sha=45f1447fbe231e3037bde351ef70677dd212216c8d62ae7ca409fecc4d6acc89 ;;
    aarch64)
      mold_sha=52c759d3689babaea4c42af2f31062a74ab83e17ffb5a8090232534c67b70577
      sccache_sha=2b3284d5da3b46a47dc4229e75bb7b88ac4aa99c8d754fb7d2f84997e5a4354a ;;
    *) echo "ci-rust-env: no pinned tools for $arch"; return 0 ;;
  esac
  # Tools live next to the cache when the volume is mounted (downloaded once
  # per host), else in /tmp for this job only.
  if [ -d /sccache ] && [ -w /sccache ]; then tools=/sccache/tools; else tools=/tmp/ci-tools; fi
  mkdir -p "$tools"
  fetch() { # url sha dest-dir
    local f; f="$tools/$(basename "$1")"
    if [ ! -d "$3" ]; then
      # Unique temp names: two jobs on one host can share the volume.
      curl -sfL --retry 3 "$1" -o "$f.$$" && echo "$2  $f.$$" | sha256sum -c - >/dev/null &&
        mkdir -p "$3.$$" && tar -xzf "$f.$$" -C "$3.$$" --strip-components=1 && { mv -T "$3.$$" "$3" 2>/dev/null || true; }
      rm -rf "$f.$$" "$3.$$"
    fi
    [ -d "$3" ]
  }
  if fetch "https://github.com/rui314/mold/releases/download/v$mold_v/mold-$mold_v-$arch-linux.tar.gz" "$mold_sha" "$tools/mold-$mold_v"; then
    export PATH="$tools/mold-$mold_v/bin:$PATH"
    export RUSTFLAGS="${CI_RUSTFLAGS:-} -C link-arg=-fuse-ld=mold"
    echo "ci-rust-env: linker mold $mold_v"
  else
    export RUSTFLAGS="${CI_RUSTFLAGS:-}"
    echo "::warning::ci-rust-env: mold download failed, using the default linker"
  fi
  if [ -n "${CI_NO_SCCACHE:-}" ]; then
    echo "ci-rust-env: sccache off (no persistent cache volume in this job)"
  elif fetch "https://github.com/mozilla/sccache/releases/download/v$sccache_v/sccache-v$sccache_v-$arch-unknown-linux-musl.tar.gz" "$sccache_sha" "$tools/sccache-$sccache_v"; then
    # Only crates from the registry/git (third-party dependencies) go
    # through sccache. Workspace crates always compile for real: sccache
    # can't see inputs a proc macro reads on its own (sqlx's offline query
    # data, SQLX_OFFLINE_DIR output, include_str! of untracked files), so a
    # cache hit there could hide a change or skip a side effect CI checks.
    cat > "$tools/rustc-wrapper.$$" <<WRAP
#!/bin/sh
case "\$CARGO_MANIFEST_DIR" in
  */cargo/registry/*|*/.cargo/registry/*|*/cargo/git/*|*/.cargo/git/*) exec "$tools/sccache-$sccache_v/sccache" "\$@" ;;
esac
exec "\$@"
WRAP
    chmod +x "$tools/rustc-wrapper.$$" && mv -f "$tools/rustc-wrapper.$$" "$tools/rustc-wrapper"
    export RUSTC_WRAPPER="$tools/rustc-wrapper"
    export SCCACHE_BIN="$tools/sccache-$sccache_v/sccache"
    export SCCACHE_DIR="${SCCACHE_DIR:-/sccache/cache}"
    [ -d /sccache ] || export SCCACHE_DIR=/tmp/sccache
    export SCCACHE_CACHE_SIZE="${SCCACHE_CACHE_SIZE:-6G}"
    export SCCACHE_IDLE_TIMEOUT=0
    echo "ci-rust-env: sccache $sccache_v, cache $SCCACHE_DIR"
  else
    echo "::warning::ci-rust-env: sccache download failed, building without a cache"
  fi
  export CARGO_INCREMENTAL=0
  # In a job container (no sibling docker run), later steps are separate
  # shells: hand the settings on through GITHUB_ENV / GITHUB_PATH.
  if [ -n "${CI_RUST_ENV_EXPORT:-}" ] && [ -n "${GITHUB_ENV:-}" ]; then
    { echo "RUSTFLAGS=$RUSTFLAGS"; echo "CARGO_INCREMENTAL=0"
      if [ -n "${RUSTC_WRAPPER:-}" ]; then
        echo "RUSTC_WRAPPER=$RUSTC_WRAPPER"; echo "SCCACHE_DIR=$SCCACHE_DIR"
        echo "SCCACHE_CACHE_SIZE=$SCCACHE_CACHE_SIZE"; echo "SCCACHE_IDLE_TIMEOUT=0"; echo "SCCACHE_BIN=$SCCACHE_BIN"
      fi
    } >> "$GITHUB_ENV"
    if [ -d "$tools/mold-$mold_v/bin" ]; then echo "$tools/mold-$mold_v/bin" >> "$GITHUB_PATH"; fi
  fi
  return 0
}
ci_rust_env
