# ETPH Pro direct purchase: Stripe test-mode journey + real-phone check

- `journey.py`: against a TEST-mode errant-pro (ctg-infrastructure `services/errant-pro`, `LICENSE_MODE=test`):
  Buy -> Checkout Session -> paid with Stripe's test card -> real `checkout.session.completed` delivered signed
  (and replayed) -> key page shown once -> key email in the agent inbox; `--refund-pi/--refund-id`: Stripe
  refund -> `charge.refunded` -> key on the signed revocation list.
- `pro-direct-realphone.sh`: the CI debug APK sideloaded on the test phone (DIRECT channel), key activated via
  the `etph://activate` link and by pasting, Pro across restarts, screenshots light + dark.
Run 2026-11-24: journey 8/8 + refund 2/2, real phone 14/14 (debug APK cff4973); v0.9.5 RELEASE APK with a live comp key: activation + live signed revocation list 5/5, server revoke -> refused 1/1 (`release-realphone.sh A|B`).
