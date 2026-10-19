# Play Data safety declaration

`store/data-safety.csv` is the declaration in Play's own CSV import format (same layout as
SSHerpa's: one row per response, `TRUE`/`FALSE` in "Response value"). It is submitted through
the Android Publisher API (`applications.dataSafety`) by the `data-safety` job in
`.forgejo/workflows/play-listing.yml`:

    git tag set-play-data-safety-<n> && git push origin set-play-data-safety-<n>

Play has no read-back endpoint: success is a 2xx from the API. If Play rejects the file, its error
names the exact missing or forbidden question; fix that row and push a new tag.
`.forgejo/scripts/check-store-listing.py` (claims workflow, every push) fails if the release build
gains an analytics/crash-reporting/ads SDK or a network call home while this file still says
"no data collected".

## Answers

| Question | Answer | Why |
|---|---|---|
| Collects or shares any required user data types? | No | The app has no account, no ads, no analytics and no crash reporting; it never contacts an Errant Solutions server. Its only dependencies are AndroidX, Material, Kotlin coroutines and Bouncy Castle (TLS). |
| Account creation methods | My app does not allow users to create an account | There is no account. |
| Every data-type row | blank | Nothing is collected, so Play asks none of them. |

Why the relayed traffic is not "collected": the app forwards the user's own computer's
connections to the destinations that computer chose, on the user's request, and keeps nothing.
Nothing reaches the developer. Pairing tokens (WiFi/Bluetooth) are generated and stored on the
phone and only compared with what the desktop presents.

Permissions in the release manifest and why none of them collects data: INTERNET / network state
(relay), WiFi state (shows the phone's WiFi address), Bluetooth / Bluetooth connect + advertise
(Bluetooth mode), foreground service (special use: tethering), wake lock, notifications. No
location permission (removed in 0.7.0, nothing used it).

Privacy policy: https://errant.solutions/privacy.html (section "ETPH (ET Phone Home)").
