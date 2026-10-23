#!/usr/bin/env python3
"""Counts the Android lint warnings by issue id and prints ONE notice annotation (top 20 ids with counts).

Only for visibility (warnings are reduced step by step); never fails. Each --report names one lint report as
comma-separated alternatives, the first existing file is read: the XML report (lint-results-*.xml) or the text
report (lint-results-*.txt). Findings of several reports (debug, release, marketdata) are counted once per
(id, file, line, message).

With --details a second notice lists every warning as «id  file:line  message» (paths shortened to the part
after «android/», messages cut to 100 characters, at most 60 000 characters), so the findings can be read from the
check annotations without downloading the report.

Usage: python3 -I lint_warnings.py --report A.xml,A.txt [--report B.xml,B.txt ...] [--details]
       python3 -I lint_warnings.py --self-test
"""
import argparse
import collections
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET

TOP = 20
LEVELS = ("Warning", "Information")
# Text report: «/path/File.kt:12: Warning: Message [IssueId]» (line optional)
TEXT_LINE = re.compile(r"^(?P<file>.+?)(?::(?P<line>\d+))?: (?P<sev>Warning|Information): (?P<msg>.*) \[(?P<id>[A-Za-z0-9_]+)\]$")


def from_xml(path):
    items = set()
    root = ET.parse(path).getroot()
    for issue in root.iter("issue"):
        if issue.get("severity") not in LEVELS:
            continue
        loc = issue.find("location")
        file = loc.get("file", "") if loc is not None else ""
        line = loc.get("line", "") if loc is not None else ""
        items.add((issue.get("id", "?"), file, line, issue.get("message", "")))
    return items


def from_text(path):
    items = set()
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            m = TEXT_LINE.match(raw.rstrip("\n"))
            if m:
                items.add((m.group("id"), m.group("file"), m.group("line") or "", m.group("msg")))
    return items


def collect(reports):
    items, read = set(), []
    for alternatives in reports:
        for path in alternatives.split(","):
            path = path.strip()
            if path and os.path.isfile(path):
                try:
                    items |= from_xml(path) if path.endswith(".xml") else from_text(path)
                except ET.ParseError as exc:
                    print(f"Unreadable lint report {path}: {exc}")
                    continue
                read.append(path)
                break
    return items, read


def escape(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def summary(items):
    counts = collections.Counter(item[0] for item in items)
    lines = [f"{count:>4}  {issue_id}" for issue_id, count in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:TOP]]
    head = f"{len(items)} lint warnings in {len(counts)} ids"
    if len(counts) > TOP:
        head += f" (top {TOP} shown)"
    return head, lines


DETAILS_MAX = 60000


def short_path(path):
    path = path.replace("\\", "/")
    marker = "/android/"
    return path.split(marker, 1)[1] if marker in path else path


def details(items):
    rows = []
    for issue_id, file, line, msg in sorted(items, key=lambda i: (i[0], short_path(i[1]), int(i[2] or 0))):
        where = short_path(file) + (f":{line}" if line else "")
        rows.append(f"{issue_id}  {where}  {msg[:100]}")
    out, size = [], 0
    for row in rows:
        if size + len(row) + 1 > DETAILS_MAX:
            out.append(f"... {len(rows) - len(out)} more")
            break
        out.append(row)
        size += len(row) + 1
    return out


def self_test():
    with tempfile.TemporaryDirectory() as tmp:
        xml_path = os.path.join(tmp, "r.xml")
        with open(xml_path, "w", encoding="utf-8") as f:
            f.write('<issues format="6"><issue id="A" severity="Warning" message="m"><location file="/x/F.kt" line="1"/></issue>'
                    '<issue id="A" severity="Warning" message="m2"><location file="/x/F.kt" line="2"/></issue>'
                    '<issue id="E" severity="Error" message="e"><location file="/x/F.kt"/></issue></issues>')
        txt_path = os.path.join(tmp, "r.txt")
        with open(txt_path, "w", encoding="utf-8") as f:
            f.write("/x/F.kt:1: Warning: m [A]\n/x/G.xml: Warning: other [B]\n/x/F.kt:3: Error: e [E]\n")
        items, read = collect([os.path.join(tmp, "missing.xml") + "," + xml_path, txt_path])
        assert read == [xml_path, txt_path], read
        head, lines = summary(items)
        # A twice from XML, A line 1 again from text (same message: counted once), B once
        assert head.startswith("3 lint warnings in 2 ids"), head
        assert lines[0].split() == ["2", "A"] and lines[1].split() == ["1", "B"], lines
        rows = details({("A", "/w/r/android/app/F.kt", "12", "m" * 150)})
        assert rows == ["A  app/F.kt:12  " + "m" * 100], rows
    print("lint_warnings self-test ok")
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", action="append", default=[])
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--details", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    items, read = collect(args.report)
    if not read:
        print("No lint report found; nothing to count.")
        return 0
    head, lines = summary(items)
    print(head)
    print("\n".join(lines))
    if items:
        print(f"::notice title=Lint warnings::{escape(head + chr(10) + chr(10).join(lines))}")
        if args.details:
            print(f"::notice title=Lint warning details::{escape(chr(10).join(details(items)))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
