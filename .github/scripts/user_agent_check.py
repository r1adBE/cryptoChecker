#!/usr/bin/env python3
"""Neutral User-Agent of the address queries (release-readiness.yml). Appends findings for annotate.py.

The apps send a common full browser value to every host that sees a wallet address, so the host cannot tie the
address to the app (HttpUserAgent.NEUTRAL in android/app/src/main/java/com/cryptochecker/app/data/remote/
HttpUserAgent.kt, HttpUserAgent.neutral in ios/Shared/Core/HttpUserAgent.swift, shared test value «userAgent» in
android/testdata/parity/address_privacy.json). A browser value only blends in while it is current.

  error   the value differs between Android, iOS and the parity fixture, or is missing
          not of the form «Mozilla/5.0 (…; rv:N.0) Gecko/20100101 Firefox/N.0» with the same N twice
          contains the app name (forbiddenParts of the fixture) or the app version (versionName of
            android/app/build.gradle.kts, the app User-Agent «cryptoChecker-Android/<version>»)
  warning the Firefox version is estimated to be older than MAX_AGE_DAYS: update the value on both platforms and
            in the fixture (android/testdata/parity and its copy ios/Tests/Parity)
  notice  estimated age (always), so the run page shows when the next update is due

Age estimate (no network): Firefox ships a new major version about every 4 weeks (RELEASE_CYCLE_DAYS). Reference
point from the release calendar: Firefox 128 on 9 July 2024 plus 25 four-week cycles, so Firefox 153 ≈ June 2026
(REFERENCE_VERSION / REFERENCE_DATE). Estimated release of
version N = REFERENCE_DATE + (N - 153) × 28 days. If the reference turns out to be off, adjust REFERENCE_DATE only.

Usage: python3 -I user_agent_check.py <repository root> <findings file> [--today YYYY-MM-DD]
       python3 -I user_agent_check.py --self-test
"""
import datetime as dt
import json
import os
import re
import sys
import tempfile

ANDROID = "android/app/src/main/java/com/cryptochecker/app/data/remote/HttpUserAgent.kt"
IOS = "ios/Shared/Core/HttpUserAgent.swift"
FIXTURE = "android/testdata/parity/address_privacy.json"
BUILD_GRADLE = "android/app/build.gradle.kts"
REFERENCE_VERSION = 153
REFERENCE_DATE = dt.date(2026, 6, 9)
RELEASE_CYCLE_DAYS = 28
MAX_AGE_DAYS = 183  # about 6 months
FIREFOX = re.compile(r"Mozilla/5\.0 \([^()]*; rv:(\d+)\.0\) Gecko/20100101 Firefox/(\d+)\.0\Z")
KOTLIN = re.compile(r'const val NEUTRAL\s*=\s*"([^"]*)"')
SWIFT = re.compile(r'static let neutral\s*=\s*"([^"]*)"')
VERSION_NAME = re.compile(r'versionName\s*=\s*"([^"]+)"')


def estimated_release(version):
    return REFERENCE_DATE + dt.timedelta(days=(version - REFERENCE_VERSION) * RELEASE_CYCLE_DAYS)


def read(root, rel):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as f:
        return f.read()


def check(root, today):
    """[(level, file, message)] for the repository at root."""
    out = []
    values = {}
    for rel, pattern in ((ANDROID, KOTLIN), (IOS, SWIFT)):
        text = read(root, rel)
        if text is None:
            if rel == IOS and not os.path.isdir(os.path.join(root, "ios")):
                out.append(("notice", rel, "ios/ missing: iOS value not checked"))
                continue
            out.append(("error", rel, "file missing: neutral User-Agent not found"))
            continue
        match = pattern.search(text)
        if not match:
            out.append(("error", rel, "neutral User-Agent constant not found"))
            continue
        values[rel] = match.group(1)
    fixture_text = read(root, FIXTURE)
    fixture = json.loads(fixture_text) if fixture_text is not None else None
    if fixture is None:
        out.append(("error", FIXTURE, "parity fixture missing"))
    else:
        values[FIXTURE] = fixture.get("userAgent", "")
    if len(set(values.values())) > 1:
        listed = "; ".join(f"{rel}: {value}" for rel, value in values.items())
        out.append(("error", ANDROID, f"neutral User-Agent differs between the platforms: {listed}"))
    if not values:
        return out
    agent = values.get(ANDROID) or next(iter(values.values()))
    forbidden = list(fixture.get("forbiddenParts", [])) if fixture else []
    gradle = read(root, BUILD_GRADLE) or ""
    version = VERSION_NAME.search(gradle)
    if version:
        forbidden += [version.group(1), "cryptoChecker-Android/" + version.group(1)]
    for part in forbidden:
        if part and part.lower() in agent.lower():
            out.append(("error", ANDROID, f"neutral User-Agent contains «{part}» (app name or version)"))
    match = FIREFOX.match(agent)
    if not match or match.group(1) != match.group(2):
        out.append(("error", ANDROID, "neutral User-Agent is not a plain Firefox value (rv:N.0 … Firefox/N.0)"))
        return out
    firefox = int(match.group(2))
    released = estimated_release(firefox)
    age = (today - released).days
    message = (f"Firefox {firefox}: estimated release {released.isoformat()} (4-week cycle, reference Firefox "
               f"{REFERENCE_VERSION} ≈ {REFERENCE_DATE:%B %Y}), about {max(age, 0) // 30} months old")
    if age > MAX_AGE_DAYS:
        out.append(("warning", ANDROID, message + f" - older than {MAX_AGE_DAYS} days: update HttpUserAgent.NEUTRAL "
                    "(Android), HttpUserAgent.neutral (iOS) and address_privacy.json to the current Firefox"))
    else:
        out.append(("notice", ANDROID, message))
    return out


def tsv(level, file, message):
    return "\t".join((level, file, "", message.replace("\t", " ").replace("\n", "\\n")))


def self_test():
    assert estimated_release(153) == dt.date(2026, 6, 9)
    assert estimated_release(159) == dt.date(2026, 11, 24)
    assert estimated_release(150) == dt.date(2026, 3, 17)
    ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:{v}.0) Gecko/20100101 Firefox/{v}.0"
    with tempfile.TemporaryDirectory() as root:
        def write(rel, text):
            os.makedirs(os.path.dirname(os.path.join(root, rel)), exist_ok=True)
            with open(os.path.join(root, rel), "w", encoding="utf-8") as f:
                f.write(text)

        def setup(android, ios, fixture, version="16.2.2"):
            write(ANDROID, f'object X {{\n    const val NEUTRAL = "{android}"\n}}\n')
            write(IOS, f'enum X {{\n    static let neutral = "{ios}"\n}}\n')
            write(FIXTURE, json.dumps({"userAgent": fixture, "forbiddenParts": ["cryptoChecker", "okhttp"]}))
            write(BUILD_GRADLE, f'versionCode = 1\nversionName = "{version}"\n')

        def levels(today):
            return [level for level, _, _ in check(root, today)]

        current = ua.format(v=153)
        setup(current, current, current)
        assert levels(dt.date(2026, 7, 3)) == ["notice"]
        # Six months after the estimated release: still a notice; one day later a warning
        assert levels(dt.date(2026, 12, 9)) == ["notice"]
        assert levels(dt.date(2026, 12, 10)) == ["warning"]
        # Platforms differ: error (also when only the fixture differs)
        setup(current, ua.format(v=152), current)
        assert "error" in levels(dt.date(2026, 7, 3))
        setup(current, current, ua.format(v=154))
        assert "error" in levels(dt.date(2026, 7, 3))
        # App name or version in the value
        setup(current + " cryptoChecker", current + " cryptoChecker", current + " cryptoChecker")
        assert "error" in levels(dt.date(2026, 7, 3))
        named = current.replace("Win64", "16.2.2")
        setup(named, named, named)
        assert "error" in levels(dt.date(2026, 7, 3))
        # Not a Firefox value, or rv and Firefox version differ
        chrome = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0 Safari/537.36"
        setup(chrome, chrome, chrome)
        assert levels(dt.date(2026, 7, 3)) == ["error"]
        odd = current.replace("rv:153.0", "rv:150.0")
        setup(odd, odd, odd)
        assert levels(dt.date(2026, 7, 3)) == ["error"]
        # Constant missing
        setup(current, current, current)
        write(IOS, "enum X {}\n")
        assert "error" in levels(dt.date(2026, 7, 3))
        assert tsv("warning", "a", "x\ty\nz") == "warning\ta\t\tx y\\nz"
    print("user_agent_check self-test ok")
    return 0


def main(argv):
    if argv == ["--self-test"]:
        return self_test()
    if len(argv) not in (2, 4) or (len(argv) == 4 and argv[2] != "--today"):
        print(__doc__)
        return 2
    today = dt.date.fromisoformat(argv[3]) if len(argv) == 4 else dt.datetime.now(dt.timezone.utc).date()
    findings = check(argv[0], today)
    with open(argv[1], "a", encoding="utf-8") as f:
        for level, file, message in findings:
            f.write(tsv(level, file, message) + "\n")
    for level, file, message in findings:
        print(f"{level}: {file}: {message}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
