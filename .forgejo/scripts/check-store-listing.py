#!/usr/bin/env python3
"""Google Play metadata in store/ is consistent with the app that ships.

Fails when
  - the Play title is over 30 chars or differs from the app's name (strings.xml app_name),
    the short description is over 80, the full description over 4000 (Play's limits);
  - the contact email isn't an @errant.solutions address or the privacy policy URL is missing;
  - the applicationId differs from the package the Play workflows publish to;
  - store/data-safety.csv is malformed, or says "no data collected" while the release build
    has an analytics / crash-reporting / ads SDK, the advertising-ID permission, or a location
    permission;
  - the full description grows a section nobody reviewed (every ALL-CAPS heading must be one
    of the known ones; a new one means re-checking its claims against the app first).
usage: check-store-listing.py [repo root]
"""
import csv
import os
import re
import sys

os.chdir(sys.argv[1] if len(sys.argv) > 1 else ".")
bad = 0


def check(ok, msg):
    global bad
    print(("ok   " if ok else "FAIL ") + msg)
    bad += 0 if ok else 1


def rd(p):
    return open(p, encoding="utf-8").read()


title = rd("store/listing-en-US-title.txt").rstrip("\n")
short = rd("store/listing-en-US-short-description.txt").rstrip("\n")
full = rd("store/listing-en-US-full-description.txt").rstrip("\n")
app_name = re.search(r'<string name="app_name">([^<]+)</string>', rd("android/app/src/main/res/values/strings.xml")).group(1)
check(0 < len(title) <= 30, f"title {title!r} is {len(title)} chars (Play max 30)")
check(title == app_name, f"Play title {title!r} == app_name {app_name!r}")
check(0 < len(short) <= 80, f"short description is {len(short)} chars (Play max 80)")
check(0 < len(full) <= 4000, f"full description is {len(full)} chars (Play max 4000)")
for heading in re.findall(r"^([A-Z][A-Z ]{3,})$", full, re.M):
    check(heading in {"FREE AND PRO", "HOW IT WORKS", "CONNECTION MODES", "ON THE PHONE", "PRIVACY", "OPEN SOURCE"},
          f"full description section {heading!r} is a known one")

contact = dict(l.split("=", 1) for l in rd("store/contact.txt").splitlines() if l and not l.startswith("#"))
check(contact.get("contactEmail", "").endswith("@errant.solutions"), f"contact email {contact.get('contactEmail')}")
check(contact.get("privacyPolicyUrl") == "https://errant.solutions/privacy.html", "privacy policy URL")
check(contact.get("contactEmail", "") in full, "full description names the support address")

gradle = rd("android/app/build.gradle")
app_id = re.search(r'applicationId "([^"]+)"', gradle).group(1)
for wf in (".forgejo/workflows/publish-play.yml", ".forgejo/workflows/play-listing.yml"):
    m = re.search(r"PLAY_PACKAGE: (\S+)", rd(wf))
    check(m is not None and m.group(1) == app_id, f"{wf} publishes to {m and m.group(1)} (applicationId {app_id})")

rows = list(csv.reader(open("store/data-safety.csv", encoding="utf-8", newline="")))
check(rows[0][:3] == ["Question ID (machine readable)", "Response ID (machine readable)", "Response value"],
      "data-safety.csv has Play's header")
check(all(len(r) == 5 for r in rows), "every data-safety.csv row has 5 columns")
check(all(r[2] in ("", "TRUE", "FALSE") or r[2].startswith("https://") for r in rows[1:]),
      "data-safety.csv answers are TRUE/FALSE/URL/blank")
collects = [r[2] for r in rows if r[0] == "PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA"]
check(collects in (["TRUE"], ["FALSE"]), f"collects-data answered once ({collects})")
if collects == ["FALSE"]:
    # With "no data collected", Play skips every follow-up question and rejects the file if any of
    # them is answered ("You cannot answer PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", run 5045): the
    # account-creation, account/data-deletion and outside-account questions all sit behind the
    # collection answer. So the collection question must be the ONLY answered row.
    extra = [r[0] + (":" + r[1] if r[1] else "") for r in rows[1:]
             if r[2] and r[0] != "PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA"]
    check(not extra, f"no other question answered while declaring no collection (Play rejects them: {extra[:3]})")
    deps = " ".join(re.findall(r"^\s*implementation[^\n]*", gradle, re.M)).lower()
    sdk = [s for s in ("firebase", "crashlytics", "analytics", "sentry", "bugsnag", "play-services-ads",
                       "appsflyer", "adjust", "mixpanel", "amplitude") if s in deps]
    check(not sdk, f"release build has no data-collecting SDK while declaring none ({sdk})")
    manifest = rd("android/app/src/main/AndroidManifest.xml")
    perms = re.findall(r'uses-permission android:name="([^"]+)"', manifest)
    risky = [p for p in perms if p.endswith(("AD_ID", "_LOCATION", "READ_CONTACTS", "READ_PHONE_STATE"))]
    check(not risky, f"no data-type permissions declared while declaring no collection ({risky})")

print(f"{bad} problem(s)")
sys.exit(1 if bad else 0)
