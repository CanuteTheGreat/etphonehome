#!/usr/bin/env python3
"""Play release notes ("What's new", <= 500 chars) from the newest released CHANGELOG section:
its bold bullet titles, one per line. usage: play-release-notes.py CHANGELOG.md"""
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"^## \[(\d[^\]]*)\][^\n]*\n(.*?)(?=^## \[|\Z)", text, re.M | re.S)
if not m:
    sys.exit("no released section in " + sys.argv[1])
lines = []
section = ""
for head, bullet in re.findall(r"^(?:### (.+)|- (.+))$", m.group(2), re.M):
    if head:
        section = head.strip()
        continue
    b = re.match(r"\*\*(.+?)\*\*", bullet)
    title = b.group(1) if b else re.split(r": |\. ", bullet)[0]
    prefix = "Fixed: " if section == "Fixed" else ""
    lines.append("- " + prefix + title.replace("`", "").rstrip(".")[:120])
out = f"Version {m.group(1)}\n"
for line in lines:
    if len(out) + len(line) + 1 > 500:
        break
    out += line + "\n"
print(out.strip())
