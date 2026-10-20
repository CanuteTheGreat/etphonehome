# Play Console "App content" answers for Errant Solutions ETPH (solutions.errant.etph)

These declarations have no API (except Data safety, pushed by `set-play-data-safety-<n>`), so they
are entered once in Play Console > Policy and programs > App content. Each answer is what the
release build really does (AndroidManifest.xml, build.gradle, store/data-safety.md).

| Section | Answer |
|---|---|
| Privacy policy | `https://errant.solutions/privacy.html` |
| App access | "All functionality is available without special access" (no account, no login) |
| Ads | "No, my app does not contain ads" |
| Content rating | IARC questionnaire, category **Utility, Productivity, Communication, or Other**; email support@errant.solutions; answer **No** to every question (no violence, sexuality, language, controlled substances, gambling; users cannot interact or share content with each other through the app; no location sharing; no digital purchases) -> rated Everyone / PEGI 3 |
| Target audience and content | Age groups **18 and over** only; "Could your store listing unintentionally appeal to children?" **No** |
| News app | No |
| COVID-19 contact tracing / status | My app is not a publicly available COVID-19 contact tracing or status app |
| Data safety | Already submitted through the API (`store/data-safety.csv`: no data collected or shared) |
| Government apps | No |
| Financial features | "My app doesn't provide any financial features" |
| Health | "My app does not have any health features" |
| Advertising ID | "No" (the app doesn't use the advertising ID; no AD_ID permission) |
| Foreground service permissions | Type **Special use**. Description: "ETPH shares the phone's internet connection with a computer or router the user connects over USB, Wi-Fi or Bluetooth (tethering without root or VPN). The foreground service keeps the relay running while the user's computer uses the connection; it starts only when the user taps Start and stops when they tap Stop or disconnect, with a persistent notification the whole time." Video link: optional, leave empty unless Play asks |

After these and the store listing (title, descriptions, icon, feature graphic, 4+ phone
screenshots: `set-play-listing-<n>`) are complete, Publishing overview > **Send changes for review**
publishes the internal-testing release; until then testers' opt-in link answers 404 and Internal
App Sharing uploads fail with `NOT_PUBLISHED`.
