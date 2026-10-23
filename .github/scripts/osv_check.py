#!/usr/bin/env python3
"""Checks the resolved Android release runtime classpath against the OSV database (api.osv.dev).

Input: the output of «./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath» (dependency tree).
Every resolved Maven coordinate (group:artifact:version, after conflict resolution «->») is sent to the OSV batch
API; for every hit the vulnerability details are fetched (summary, severity, fixed versions of this package).

Output: one finding per line in the format of annotate.py (level, file, line, message):
  - known vulnerability WITH a fixed version: error with --strict (main, release tags, weekly run), else warning
  - known vulnerability without a fixed version: warning
  - OSV not reachable or tree not readable: warning (the check must not turn CI red on its own outage)
IDs listed in the optional ignore file (one per line, «ID  # reason») become notices.

No secrets, no third-party code: only the public OSV API over HTTPS (urllib).
Usage: python3 -I osv_check.py [--strict] [--ignore FILE] <dependency tree> <findings file>
       python3 -I osv_check.py --self-test
"""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

OSV_BATCH = "https://api.osv.dev/v1/querybatch"
OSV_VULN = "https://api.osv.dev/v1/vulns/"
BATCH_SIZE = 500
TIMEOUT = 30

# Tree prefix («+--- », «|    \--- ») in front of a coordinate
TREE_PREFIX = re.compile(r"^[\s|+\\-]*")
STRICTLY = re.compile(r"\{(?:strictly|require|prefer) ([^}]*)\}")
COORD = re.compile(
    r"^(?P<group>[A-Za-z0-9_.\-]+):(?P<artifact>[A-Za-z0-9_.\-]+)(?::(?P<version>[^\s]+))?"
    r"(?: -> (?P<resolved>[^\s]+))?(?P<rest>.*)$"
)


def parse_tree(text):
    """Resolved coordinates {(group:artifact, version)} from a Gradle dependency tree; constraints (c) and
    unresolved (n) entries are skipped, «project :x» lines too."""
    coords = set()
    for raw in text.splitlines():
        # Tree lines start with «+», «\», «|» or the indentation below a last child; headers and the legend do not
        if not raw.strip() or not raw.startswith(("+", "\\", "|", " ")):
            continue
        line = TREE_PREFIX.sub("", raw, count=1)
        if line.startswith("project "):
            continue
        line = STRICTLY.sub(lambda m: m.group(1).replace(" ", ""), line)
        m = COORD.match(line)
        if not m:
            continue
        rest = m.group("rest")
        if "(c)" in rest or "(n)" in rest or "FAILED" in rest:
            continue
        version = m.group("resolved") or m.group("version")
        if not version or version.startswith(("{", "[", "(")) or "+" in version:
            continue
        coords.add((f"{m.group('group')}:{m.group('artifact')}", version))
    return coords


def post_json(url, payload):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return json.loads(resp.read().decode("utf-8"))


def get_json(url):
    with urllib.request.urlopen(url, timeout=TIMEOUT) as resp:
        return json.loads(resp.read().decode("utf-8"))


def query_osv(coords):
    """{(package, version): [vuln ids]} for all coordinates with at least one known vulnerability."""
    ordered = sorted(coords)
    hits = {}
    for start in range(0, len(ordered), BATCH_SIZE):
        chunk = ordered[start:start + BATCH_SIZE]
        queries = [{"package": {"name": name, "ecosystem": "Maven"}, "version": version} for name, version in chunk]
        results = post_json(OSV_BATCH, {"queries": queries}).get("results", [])
        for (name, version), result in zip(chunk, results):
            ids = [v.get("id") for v in (result or {}).get("vulns", []) if v.get("id")]
            if ids:
                hits[(name, version)] = ids
    return hits


def fixed_versions(vuln, package):
    """Fixed versions of [package] named in the OSV entry (ECOSYSTEM ranges)."""
    fixed = []
    for affected in vuln.get("affected", []):
        pkg = affected.get("package", {})
        if pkg.get("ecosystem") != "Maven" or pkg.get("name") != package:
            continue
        for rng in affected.get("ranges", []):
            for event in rng.get("events", []):
                if event.get("fixed") and event["fixed"] not in fixed:
                    fixed.append(event["fixed"])
    return fixed


def severity_of(vuln):
    sev = (vuln.get("database_specific") or {}).get("severity")
    if isinstance(sev, str) and sev:
        return sev.upper()
    for entry in vuln.get("severity", []):
        if entry.get("score"):
            return entry.get("type", "CVSS") + " " + entry["score"]
    return "unknown severity"


def read_ignore(path):
    ignored = set()
    if path and os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                token = line.split("#", 1)[0].strip()
                if token:
                    ignored.add(token)
    return ignored


def findings_for(hits, details, strict, ignored):
    out = []
    for (package, version), ids in sorted(hits.items()):
        for vid in ids:
            vuln = details.get(vid) or {"id": vid}
            names = {vid, *vuln.get("aliases", [])}
            summary = (vuln.get("summary") or vuln.get("details") or "").strip().splitlines()
            summary = summary[0][:200] if summary else ""
            fixed = fixed_versions(vuln, package)
            text = f"{package}:{version} – {vid} ({severity_of(vuln)})"
            if summary:
                text += f": {summary}"
            if names & ignored:
                out.append(("notice", f"{text} [ignored via osv-ignore.txt]"))
            elif fixed:
                out.append(("error" if strict else "warning", f"{text}. Fixed in: {', '.join(fixed)}"))
            else:
                out.append(("warning", f"{text}. No fixed version known yet"))
    return out


def write_findings(path, findings):
    with open(path, "a", encoding="utf-8") as f:
        for level, message in findings:
            f.write(f"{level}\t\t\t{message.replace(chr(9), ' ').replace(chr(10), ' ')}\n")


def run(args):
    with open(args.tree, encoding="utf-8", errors="replace") as f:
        coords = parse_tree(f.read())
    if not coords:
        write_findings(args.findings, [("warning", "OSV check: no resolved dependencies found in the Gradle output")])
        return 0
    try:
        hits = query_osv(coords)
        details = {}
        for vid in sorted({v for ids in hits.values() for v in ids}):
            details[vid] = get_json(OSV_VULN + vid)
    except (urllib.error.URLError, TimeoutError, ValueError, OSError) as exc:
        write_findings(args.findings, [("warning", f"OSV check skipped: api.osv.dev not reachable ({exc.__class__.__name__})")])
        return 0
    findings = findings_for(hits, details, args.strict, read_ignore(args.ignore))
    print(f"OSV: {len(coords)} coordinates checked, {len(hits)} with known vulnerabilities")
    if not findings:
        findings = [("notice", f"OSV: no known vulnerabilities in {len(coords)} release dependencies")]
    write_findings(args.findings, findings)
    return 0


SAMPLE_TREE = """
releaseRuntimeClasspath - Runtime classpath of '/release'.
+--- project :marketdata
|    \\--- org.json:json:20231013 (n)
+--- androidx.core:core-ktx:1.19.1
|    +--- androidx.annotation:annotation:1.8.1 -> 1.9.1 (*)
|    \\--- org.jetbrains.kotlin:kotlin-stdlib:{strictly 2.4.21} -> 2.4.21 (c)
+--- androidx.compose:compose-bom:2026.09.00
|    \\--- androidx.compose.material3:material3:1.4.0 (c)
+--- androidx.compose.material3:material3 -> 1.4.0
\\--- com.squareup.okhttp3:okhttp:5.5.0
     \\--- com.squareup.okio:okio:3.16.0 FAILED
"""


def self_test():
    coords = parse_tree(SAMPLE_TREE)
    expected = {
        ("androidx.core:core-ktx", "1.19.1"),
        ("androidx.annotation:annotation", "1.9.1"),
        ("androidx.compose:compose-bom", "2026.09.00"),
        ("androidx.compose.material3:material3", "1.4.0"),
        ("com.squareup.okhttp3:okhttp", "5.5.0"),
    }
    assert coords == expected, coords
    vuln = {
        "id": "GHSA-x", "aliases": ["CVE-1"], "summary": "Bad thing\nmore",
        "database_specific": {"severity": "high"},
        "affected": [{"package": {"ecosystem": "Maven", "name": "a:b"},
                      "ranges": [{"type": "ECOSYSTEM", "events": [{"introduced": "0"}, {"fixed": "1.2"}]}]}],
    }
    hits = {("a:b", "1.0"): ["GHSA-x"]}
    assert findings_for(hits, {"GHSA-x": vuln}, True, set())[0][0] == "error"
    assert findings_for(hits, {"GHSA-x": vuln}, False, set())[0][0] == "warning"
    assert findings_for(hits, {"GHSA-x": vuln}, True, {"CVE-1"})[0][0] == "notice"
    nofix = dict(vuln, affected=[])
    level, message = findings_for(hits, {"GHSA-x": nofix}, True, set())[0]
    assert level == "warning" and "No fixed version" in message, message
    assert "HIGH" in message and "Bad thing" in message and "more" not in message
    print("osv_check self-test ok")
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--strict", action="store_true", help="vulnerabilities with a fix are errors")
    parser.add_argument("--ignore", help="optional file with ignored OSV/CVE/GHSA ids")
    parser.add_argument("tree", nargs="?")
    parser.add_argument("findings", nargs="?")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.tree or not args.findings:
        parser.error("tree and findings are required")
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
