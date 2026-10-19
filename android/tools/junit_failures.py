#!/usr/bin/env python3
"""Gibt fehlgeschlagene JUnit-Tests (XML-Berichte unter <Ordner>) als GitHub-Annotationen (::error) aus.

Aufruf (im Workflow «Record exchange fixtures»): python3 android/tools/junit_failures.py android/app/build/test-results
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def escape(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main():
    count = 0
    for path in sorted(glob.glob(os.path.join(sys.argv[1], "**", "*.xml"), recursive=True)):
        for case in ET.parse(path).getroot().iter("testcase"):
            for problem in list(case.findall("failure")) + list(case.findall("error")):
                if count >= 40:
                    return
                count += 1
                text = (problem.get("message") or "") + "\n" + (problem.text or "")[:1500]
                print(f"::error title={case.get('name')}::{escape(text[:4000])}")


if __name__ == "__main__":
    main()
