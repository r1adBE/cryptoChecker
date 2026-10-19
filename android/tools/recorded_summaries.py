#!/usr/bin/env python3
"""Gibt je aufgezeichnetem Markt einen GitHub-Hinweis (::notice) mit den ersten 900 Zeichen jeder Antwort aus.

Aufruf (im Workflow «Record exchange fixtures»): python3 android/tools/recorded_summaries.py <von> <bis>
– bearbeitet die Dateien recorded_*.json in alphabetischer Reihenfolge von Index <von> bis <bis> (ohne).
"""
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FOLDER = os.path.join(HERE, "..", "testdata", "exchanges", "recorded")


def escape(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main():
    start, end = int(sys.argv[1]), int(sys.argv[2])
    files = sorted(glob.glob(os.path.join(FOLDER, "recorded_*.json")))[start:end]
    for path in files:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        parts = []
        for case in data.get("cases", []):
            body = case.get("body") or case.get("response") or ""
            if not isinstance(body, str):
                body = json.dumps(body)
            parts.append(f"[{case.get('kind') or case.get('name')}] {case.get('request', '')}\n{body[:900]}")
        notes = "; ".join(data.get("notes", [])) if isinstance(data.get("notes"), list) else ""
        message = "\n\n".join(parts) or f"keine Fälle {notes}"
        print(f"::notice title={os.path.basename(path)}::{escape(message[:6000])}")


if __name__ == "__main__":
    main()
