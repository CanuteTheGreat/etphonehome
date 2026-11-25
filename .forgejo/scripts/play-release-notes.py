#!/usr/bin/env python3
"""Play release notes ("What's new", <= 500 chars) from the newest released CHANGELOG section:
its bold bullet titles, one per line. usage: play-release-notes.py CHANGELOG.md

Google Play payments policy: Play-distributed text must not point users at another way to pay for
digital goods. Bullets about the direct (non-Play) ETPH Pro purchase are therefore left out: any bullet
marked `<!-- not-play -->`, or that mentions the direct-purchase page / licence keys (safety net). When
the newest section has nothing left for Play, the bullets of the next older released sections are used
(under the newest version number), so a release that only changed the direct flow still gets notes.
Tests: test_play_release_notes.py."""
import re
import sys

NOT_PLAY = re.compile(
    r"<!--\s*not-play\s*-->|errant\.solutions/etphonehome/pro|licen[cs]e key|not installed from Google Play|Stripe",
    re.I,
)


def play_bullets(body):
    out = []
    section = ""
    for head, bullet in re.findall(r"^(?:### (.+)|- (.+))$", body, re.M):
        if head:
            section = head.strip()
            continue
        if NOT_PLAY.search(bullet):
            continue
        b = re.match(r"\*\*(.+?)\*\*", bullet)
        title = b.group(1) if b else re.split(r": |\. ", bullet)[0]
        prefix = "Fixed: " if section == "Fixed" else ""
        out.append("- " + prefix + title.replace("`", "").rstrip(".:")[:120])
    return out


def notes(text):
    sections = re.findall(r"^## \[(\d[^\]]*)\][^\n]*\n(.*?)(?=^## \[|\Z)", text, re.M | re.S)
    if not sections:
        raise SystemExit("no released section in the changelog")
    version = sections[0][0]
    lines = []
    for _, body in sections:
        lines = play_bullets(body)
        if lines:
            break
    out = f"Version {version}\n"
    for line in lines:
        if len(out) + len(line) + 1 > 500:
            break
        out += line + "\n"
    return out.strip()


if __name__ == "__main__":
    print(notes(open(sys.argv[1], encoding="utf-8").read()))
