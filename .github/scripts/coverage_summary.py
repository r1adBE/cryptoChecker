#!/usr/bin/env python3
"""Prints the Kover line coverage per module and in total as ONE notice annotation (display only, no threshold).

Each argument is «name=module dir»; the first Kover XML report (JaCoCo format) found under
<module dir>/build/reports/kover/ is read (debug variant preferred). Missing reports are listed, never an error.

Usage: python3 -I coverage_summary.py app=app marketdata=marketdata
       python3 -I coverage_summary.py --self-test
"""
import glob
import os
import sys
import tempfile
import xml.etree.ElementTree as ET


def find_report(module_dir):
    candidates = sorted(glob.glob(os.path.join(module_dir, "build", "reports", "kover", "*.xml")))
    debug = [c for c in candidates if "debug" in os.path.basename(c).lower()]
    return (debug or candidates or [None])[0]


def counters(path):
    """{type: (missed, covered)} of the report's top-level counters."""
    root = ET.parse(path).getroot()
    result = {}
    for counter in root.findall("counter"):
        result[counter.get("type")] = (int(counter.get("missed", "0")), int(counter.get("covered", "0")))
    return result


def percent(missed, covered):
    total = missed + covered
    return f"{100.0 * covered / total:.1f} %" if total else "n/a"


def summary(modules):
    lines, total_missed, total_covered = [], 0, 0
    for name, module_dir in modules:
        report = find_report(module_dir)
        if not report:
            lines.append(f"{name}: no Kover report")
            continue
        missed, covered = counters(report).get("LINE", (0, 0))
        total_missed += missed
        total_covered += covered
        lines.append(f"{name}: {percent(missed, covered)} lines ({covered}/{missed + covered})")
    head = f"Line coverage (unit tests, debug): {percent(total_missed, total_covered)} in total"
    return head, lines


def escape(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def self_test():
    with tempfile.TemporaryDirectory() as tmp:
        kover = os.path.join(tmp, "app", "build", "reports", "kover")
        os.makedirs(kover)
        with open(os.path.join(kover, "reportDebug.xml"), "w", encoding="utf-8") as f:
            f.write('<report name="x"><package name="p"><counter type="LINE" missed="99" covered="1"/></package>'
                    '<counter type="INSTRUCTION" missed="5" covered="5"/><counter type="LINE" missed="30" covered="10"/></report>')
        head, lines = summary([("app", os.path.join(tmp, "app")), ("lib", os.path.join(tmp, "lib"))])
        assert head.endswith("25.0 % in total"), head
        assert lines == ["app: 25.0 % lines (10/40)", "lib: no Kover report"], lines
    print("coverage_summary self-test ok")
    return 0


def main(argv):
    if argv == ["--self-test"]:
        return self_test()
    modules = []
    for arg in argv:
        name, _, module_dir = arg.partition("=")
        modules.append((name, module_dir or name))
    head, lines = summary(modules)
    print(head)
    print("\n".join(lines))
    print(f"::notice title=Coverage::{escape(head + chr(10) + chr(10).join(lines))}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
