# Play Data safety declaration

`store/data-safety.csv` is the declaration in Play's own CSV import format (same layout as
SSHerpa's: one row per response, `TRUE`/`FALSE` in "Response value"). It is submitted through
the Android Publisher API (`applications.dataSafety`) by the `data-safety` job in
`CI play-listing.yml`:

    git tag set-play-data-safety-<n> && git push origin set-play-data-safety-<n>

Play has no read-back endpoint: success is a 2xx from the API. If Play rejects the file, its error
names the exact missing or forbidden question; fix that row and push a new tag.
`CI check-store-listing.py` (claims workflow, every push) fails if the release build
gains an analytics/crash-reporting/ads SDK or a network call home while this file still says
"no data collected".

## Answers

| Question | Answer | Why |
|---|---|---|
| Collects or shares any required user data types? | No | The app has no account, no ads, no analytics and no crash reporting; it never contacts an Errant Solutions server. Its only dependencies are AndroidX, Material, Kotlin coroutines and Bouncy Castle (TLS). |
| Every other row (account creation, account/data deletion URL, outside-app accounts, data types) | blank | Nothing is collected, so Play asks none of them, and it rejects the file if any is answered. |

Play quirk: with "No" to the collection question, Play's API refuses ANY other answer, even
"My app does not allow users to create an account" (HTTP 400 "You cannot answer
PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", tag set-play-data-safety-1). SSHerpa answers those rows
only because it does collect data (and there Play requires PSL_DATA_DELETION_URL). The claims
check fails if any row besides the collection question is answered while it says "No".

ETPH Pro (0.9.0): the purchase goes through Google Play Billing. The app checks the purchase on
the phone (signature + content) and sends nothing about it to Errant Solutions (there is no
server); the purchase data Play itself handles as the store is Google's, not data this app
collects. The paired-device list and data usage history never leave the phone (and are excluded
from Android backup). So the answer stays "No data collected".

Why the relayed traffic is not "collected": the app forwards the user's own computer's
connections to the destinations that computer chose, on the user's request, and keeps nothing.
Nothing reaches the developer. Pairing tokens (WiFi/Bluetooth) are generated and stored on the
phone and only compared with what the desktop presents.

Permissions in the release manifest and why none of them collects data: INTERNET / network state
(relay), WiFi state (shows the phone's WiFi address), Bluetooth / Bluetooth connect + advertise
(Bluetooth mode), foreground service (special use: tethering), wake lock, notifications. No
location permission (removed in 0.7.0, nothing used it).

Privacy policy: https://errant.solutions/privacy.html (section "ETPH (ET Phone Home)").
