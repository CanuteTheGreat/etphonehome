#!/usr/bin/env python3
"""Owner rule 2026-10-30: every mobile app follows the DEVICE's light/dark setting.

Fails on a hard-coded day/night override in the Android app (AppCompatDelegate night mode,
UiModeManager.setApplicationNightMode, forceDarkAllowed=false, UIUserInterfaceStyle, a non-DayNight
theme parent) or when the DayNight resources are missing.
"""
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parents[2]
app = root / "android/app/src/main"
FORBIDDEN = [
    (r"\bsetDefaultNightMode\b|\bMODE_NIGHT_(YES|NO)\b|\bsetApplicationNightMode\b", "forced night mode"),
    (r'android:forceDarkAllowed\s*=\s*"false"|forceDarkAllowed"?>\s*false', "opts out of dark mode"),
    (r"UIUserInterfaceStyle", "iOS style override"),
]
bad = []
for p in app.rglob("*"):
    if p.is_file() and p.suffix in {".kt", ".java", ".xml"}:
        t = p.read_text(errors="ignore")
        for pat, why in FORBIDDEN:
            for m in re.finditer(pat, t):
                bad.append(f"{p.relative_to(root)}:{t.count(chr(10), 0, m.start()) + 1}: {why}: {m.group(0)}")
themes = (app / "res/values/themes.xml").read_text()
if not re.search(r'parent="Theme\.(Material3|MaterialComponents|AppCompat)\.DayNight', themes):
    bad.append("res/values/themes.xml: app theme parent is not a *.DayNight theme")
for need in ["res/values-night/colors.xml"]:
    if not (app / need).exists():
        bad.append(f"{need}: missing (brand colors would not adapt to dark mode)")
# literal colors in layouts/drawables outside colors.xml: hard-coded for one mode only
for p in (app / "res").glob("layout*/*.xml"):
    for m in re.finditer(r'="(#[0-9A-Fa-f]{3,8})"', p.read_text()):
        bad.append(f"{p.relative_to(root)}: literal color {m.group(1)} (use a @color with a values-night variant)")
if bad:
    print("check-no-forced-theme: FAIL")
    print("\n".join(bad))
    sys.exit(1)
print("check-no-forced-theme: OK")
