# CI triggers (etphonehome)

CI work runs **when its inputs change**, not on a clock (owner principle 2026-11-06: "if there is something to do, do it").

- Heavy workflows (nightly-*, live-iso-*, mutation, test-depth, ...) trigger on a push to `main` with `paths:` filters naming what they build or test.
  Docs/README/site-only pushes start none of them.
- Bursts coalesce: they use a `replace:` concurrency group, so the ci-supersede-sweeper keeps every started run and only the NEWEST unstarted one.
  A started run is never cancelled by a push.
- A cron stays only where the thing it checks has no change signal (upstream package trees/base images, external services, elapsed time).
  Each such cron has a comment in its workflow saying why. Priority (not the clock) handles contention: heavy workflows are matched by file name
  in the sweeper's -20 class (ctg-infrastructure, ci-supersede-sweeper).
- File names such as `nightly-*.yml` are kept on purpose (the sweeper's priority rule matches them); they are not nightly any more.

| workflow file | name | triggers |
|---|---|---|
| `android-ci.yml` | Android CI | push main (all paths); pull_request |
| `android-emulator.yml` | Android emulator (real app, real internet) | push main (paths: 4 filters); cron 7 23 * * 1 (safety net, see comment in file); dispatch |
| `claims.yml` | Claims ledger | push main (all paths); pull_request |
| `desktop-os.yml` | Desktop FreeBSD | push main (paths: 9 filters); dispatch |
| `desktop-windows.yml` | Desktop Windows (native e2e) | push main tags (paths: 5 filters); dispatch |
| `mutation.yml` | Mutation testing (nightly incremental, weekly full) | push main (paths: 5 filters); dispatch |
| `nightly.yml` | Nightly (deep tunnel, platforms, upgrade) | push main (paths: 9 filters); dispatch |
| `openwrt.yml` | OpenWrt router packages | push main (paths: 5 filters); dispatch |
| `play-listing.yml` | Play store listing | push  tags (all paths) |
| `publish-play.yml` | Publish to Google Play | push  tags (all paths); dispatch |
| `release.yml` | Release | push  tags (all paths) |
| `rust-ci.yml` | Rust CI | push main (paths: 13 filters); pull_request |
| `store-screenshots.yml` | Store screenshots (real app, API 36 emulator) | dispatch |
