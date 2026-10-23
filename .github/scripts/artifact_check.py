#!/usr/bin/env python3
"""Strict check of an artifact handed from a fetch job to a publish job.

The scheduled data workflows (macro-calendar.yml, market-data.yml, exchange-live-check.yml) run in two jobs: the
fetch job talks to third-party servers without write permissions and uploads its result as an artifact; the
publish job (write permissions, no third-party requests) checks out github.sha afresh, downloads the artifact and
runs this script before it commits, uploads or manages an issue. Nothing from the artifact is executed.

Usage: python3 -I artifact_check.py --dir DIR --profile macro|mirror|mirror_manifest|exchange
       python3 -I artifact_check.py --self-test

Checks: only expected file names (no subdirectories, no links, regular files), required files present, size per
file, content shape (JSON, rough schema). Exit 1 with an annotation on the first problem.
Profile mirror_manifest: the signed manifest that the publish job of market-data.yml writes itself (mirror_data.py
--manifest) into a separate directory, checked once more before the upload; the artifact of the fetch job may not
contain manifest.json or its signature (profile mirror).
"""
import argparse
import json
import os
import re
import stat
import sys
import tempfile

MB = 1024 * 1024
ISO_UTC = re.compile(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ\Z")
# Same event types as macro_calendar.py TYPES (kept in sync by hand; the self-test there does not import this)
MACRO_TYPES = ("CPI", "PPI", "NFP", "FOMC", "PCE")
MIRROR_HEADER = re.compile(r"CCDM1 \d{1,16}\Z")
SHA256_HEX = re.compile(r"[0-9a-f]{64}\Z")
MIRROR_FILE_NAME = re.compile(r"[A-Za-z0-9._-]{1,64}\Z")
BASE64 = re.compile(rb"[A-Za-z0-9+/]{4,}={0,2}\Z")


class Invalid(Exception):
    pass


def load_json(data, name):
    try:
        return json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise Invalid(f"{name}: not valid UTF-8 JSON ({exc.__class__.__name__})")


def need(condition, message):
    if not condition:
        raise Invalid(message)


def macro_events(data, name):
    doc = load_json(data, name)
    need(isinstance(doc, dict) and set(doc) == {"version", "generated", "source", "events"}, f"{name}: unexpected keys")
    need(doc["version"] == 1 and isinstance(doc["source"], str), f"{name}: unexpected version/source")
    need(isinstance(doc["generated"], str) and ISO_UTC.match(doc["generated"]), f"{name}: unexpected generated")
    need(isinstance(doc["events"], list) and 0 < len(doc["events"]) <= 2000, f"{name}: unexpected events")
    for e in doc["events"]:
        need(isinstance(e, dict) and set(e) == {"type", "time"}, f"{name}: unexpected event")
        need(e["type"] in MACRO_TYPES and isinstance(e["time"], str) and ISO_UTC.match(e["time"]),
             f"{name}: unexpected event")


def macro_status(data, name):
    doc = load_json(data, name)
    need(isinstance(doc, dict) and set(doc) == {"consecutiveFailures"}, f"{name}: unexpected keys")
    failures = doc["consecutiveFailures"]
    need(isinstance(failures, dict) and len(failures) <= 20, f"{name}: unexpected counters")
    for key, value in failures.items():
        need(re.fullmatch(r"[A-Z]{2,10}", key) is not None, f"{name}: unexpected source")
        need(isinstance(value, int) and not isinstance(value, bool) and 0 <= value < 100000, f"{name}: unexpected count")


def mirror_wrapped(data, name):
    head, sep, body = data.partition(b"\n")
    need(sep == b"\n", f"{name}: header missing")
    try:
        need(MIRROR_HEADER.match(head.decode("ascii")) is not None, f"{name}: unexpected header")
    except UnicodeDecodeError:
        raise Invalid(f"{name}: unexpected header")
    need(isinstance(load_json(body, name), (dict, list)), f"{name}: unexpected content")


def mirror_coin_meta(data, name):
    doc = load_json(data, name)
    need(isinstance(doc, dict) and isinstance(doc.get("binance"), list) and isinstance(doc.get("alpha"), list),
         f"{name}: unexpected content")


def mirror_status(data, name):
    doc = load_json(data, name)
    need(isinstance(doc, dict) and set(doc) == {"lastRun", "files"}, f"{name}: unexpected keys")
    need(isinstance(doc["lastRun"], str) and ISO_UTC.match(doc["lastRun"]), f"{name}: unexpected lastRun")
    need(isinstance(doc["files"], dict) and all(isinstance(k, str) and isinstance(v, str) and len(v) <= 300
                                                for k, v in doc["files"].items()), f"{name}: unexpected files")


def mirror_manifest(data, name):
    doc = load_json(data, name)
    need(isinstance(doc, dict) and set(doc) == {"v", "created", "files"} and doc["v"] == 1, f"{name}: unexpected keys")
    need(isinstance(doc["created"], int) and not isinstance(doc["created"], bool) and doc["created"] > 0,
         f"{name}: unexpected created")
    need(isinstance(doc["files"], dict) and 0 < len(doc["files"]) <= 20, f"{name}: unexpected files")
    need(all(isinstance(k, str) and MIRROR_FILE_NAME.match(k) and isinstance(v, str) and SHA256_HEX.match(v)
             for k, v in doc["files"].items()), f"{name}: unexpected files")


def mirror_signature(data, name):
    need(BASE64.match(data) is not None and len(data) % 4 == 0, f"{name}: not Base64")


def exchange_result(data, name):
    doc = load_json(data, name)
    need(isinstance(doc, dict) and set(doc) == {"failed", "body"}, f"{name}: unexpected keys")
    failed = doc["failed"]
    need(isinstance(failed, int) and not isinstance(failed, bool) and 0 <= failed < 10000, f"{name}: unexpected failed")
    need(isinstance(doc["body"], str), f"{name}: unexpected body")


# name: (max bytes, required, check)
PROFILES = {
    "macro": {
        "events.json": (1 * MB, True, macro_events),
        "status.json": (64 * 1024, True, macro_status),
    },
    "mirror": {
        "fng.txt": (2 * MB, False, mirror_wrapped),
        "onchain.txt": (16 * MB, False, mirror_wrapped),
        "btcprice.txt": (16 * MB, False, mirror_wrapped),
        "altseason.txt": (64 * 1024, False, mirror_wrapped),
        "coin_meta.json": (16 * MB, False, mirror_coin_meta),
        "status.json": (64 * 1024, True, mirror_status),
    },
    "mirror_manifest": {
        "manifest.json": (64 * 1024, True, mirror_manifest),
        "manifest.json.sig": (1024, False, mirror_signature),
    },
    "exchange": {
        "exchange-result.json": (512 * 1024, True, exchange_result),
    },
}


def check(directory, profile):
    """Raises Invalid on the first problem; returns the checked file names."""
    expected = PROFILES[profile]
    need(os.path.isdir(directory) and not os.path.islink(directory), "artifact directory missing")
    names = sorted(os.listdir(directory))
    for name in names:
        need(name in expected, f"unexpected file in artifact: {name!r}")
        info = os.lstat(os.path.join(directory, name))
        need(stat.S_ISREG(info.st_mode), f"{name}: not a regular file")
        need(info.st_size <= expected[name][0], f"{name}: too large ({info.st_size} bytes)")
    for name, (_, required, _) in expected.items():
        need(not required or name in names, f"required file missing: {name}")
    for name in names:
        limit, _, content = expected[name]
        with open(os.path.join(directory, name), "rb") as f:
            data = f.read(limit + 1)
        need(len(data) <= limit, f"{name}: too large")
        content(data, name)
    return names


def escape(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def self_test():
    def write(directory, name, data):
        with open(os.path.join(directory, name), "wb") as f:
            f.write(data)

    events = b'{"version":1,"generated":"2026-10-17T11:44:24Z","source":"x","events":[{"type":"CPI","time":"2026-10-14T12:30:00Z"}]}'
    with tempfile.TemporaryDirectory() as d:
        write(d, "events.json", events)
        write(d, "status.json", b'{"consecutiveFailures":{"BLS":0,"FOMC":2}}')
        assert check(d, "macro") == ["events.json", "status.json"]
        write(d, "evil.sh", b"echo")
        try:
            check(d, "macro")
            raise AssertionError("extra file accepted")
        except Invalid:
            pass
    with tempfile.TemporaryDirectory() as d:
        write(d, "events.json", events.replace(b'"CPI"', b'"XYZ"'))
        write(d, "status.json", b'{"consecutiveFailures":{}}')
        try:
            check(d, "macro")
            raise AssertionError("bad event accepted")
        except Invalid:
            pass
    with tempfile.TemporaryDirectory() as d:
        write(d, "status.json", b'{"lastRun":"2026-10-17T11:44:24Z","files":{"fng.txt":"ok"}}')
        write(d, "fng.txt", b'CCDM1 1760000000000\n{"data":[1]}')
        assert check(d, "mirror") == ["fng.txt", "status.json"]
        write(d, "fng.txt", b'<html>')
        try:
            check(d, "mirror")
            raise AssertionError("unwrapped mirror accepted")
        except Invalid:
            pass
    with tempfile.TemporaryDirectory() as d:
        write(d, "manifest.json", b'{"created":1,"files":{"fng.txt":"' + b"a" * 64 + b'"},"v":1}')
        assert check(d, "mirror_manifest") == ["manifest.json"]
        write(d, "manifest.json.sig", b"MEUCIQ" + b"A" * 90)
        assert check(d, "mirror_manifest") == ["manifest.json", "manifest.json.sig"]
        write(d, "manifest.json.sig", b"-----BEGIN")
        try:
            check(d, "mirror_manifest")
            raise AssertionError("bad signature accepted")
        except Invalid:
            pass
        write(d, "status.json", b'{"lastRun":"2026-10-17T11:44:24Z","files":{}}')
        try:
            check(d, "mirror")
            raise AssertionError("manifest in the fetch artifact accepted")
        except Invalid:
            pass
    with tempfile.TemporaryDirectory() as d:
        try:
            check(d, "exchange")
            raise AssertionError("missing result accepted")
        except Invalid:
            pass
        write(d, "exchange-result.json", b'{"failed":true,"body":""}')
        try:
            check(d, "exchange")
            raise AssertionError("bool count accepted")
        except Invalid:
            pass
        write(d, "exchange-result.json", b'{"failed":0,"body":"ok"}')
        assert check(d, "exchange") == ["exchange-result.json"]
    print("artifact_check self-test ok")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--dir")
    ap.add_argument("--profile", choices=sorted(PROFILES))
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    if not args.dir or not args.profile:
        ap.error("--dir and --profile are required")
    try:
        names = check(args.dir, args.profile)
    except (Invalid, OSError) as exc:
        print(f"::error title=Artifact check::{escape(str(exc))}")
        return 1
    print(f"Artifact check ({args.profile}): {', '.join(names)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
