#!/usr/bin/env python3
"""UI tests on main and release tags: a retry is allowed only for Simulator hiccups.

xcodebuild retries a failed UI test once (-retry-tests-on-failure). On main and on release tags a test that needed
the retry still fails the job, unless every failure of that test was a hiccup of the CI Simulator rather than of
the app: the app could not be launched or terminated, a UI query timed out, the test runner restarted, or the test
hit the per-test time allowance (a hung launch). Anything else (an assertion, an accessibility finding, a test case
that failed without an error line of its own) counts.

Usage:
  ui_retry_policy.py <xcodebuild log>    exit 1 if a retried test failed for a reason other than a hiccup
  ui_retry_policy.py --self-test
"""
import re
import sys

INFRA = (
    "Failed to launch",
    "Failed to terminate",
    "Timed out while evaluating UI query",
    "Failed to resolve query",
    "Restarting after unexpected exit",
    "exceeded execution time allowance",
)
ERROR = re.compile(r"error: -\[(\S+) (\S+)\] : (.*)$")
FAILED = re.compile(r"Test Case '-\[(\S+) (\S+)\]' failed")


def judge(lines):
    """Returns (hiccups, real): per failed test case its failure messages, split by kind."""
    messages, failed = {}, set()
    for line in lines:
        m = ERROR.search(line)
        if m:
            messages.setdefault(f"{m.group(1)} {m.group(2)}", []).append(m.group(3).strip())
            continue
        m = FAILED.search(line)
        if m:
            failed.add(f"{m.group(1)} {m.group(2)}")
    hiccups, real = {}, {}
    for test in sorted(failed | set(messages)):
        found = messages.get(test, [])
        if found and all(any(p in msg for p in INFRA) for msg in found):
            hiccups[test] = found
        else:
            real[test] = found or ["failed without an error line of its own"]
    return hiccups, real


def main(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        hiccups, real = judge(f)
    for test, found in hiccups.items():
        print(f"::warning title=UI test retried after a Simulator hiccup::{test}: {found[0]}")
    for test, found in real.items():
        print(f"::error title=UI test needed a retry::{test}: {found[0]}")
    print(f"retried UI tests: {len(hiccups)} after a Simulator hiccup, {len(real)} for another reason")
    return 1 if real else 0


def self_test():
    ok = [
        "x.swift:64: error: -[UITests.Audit testA] : Failed to launch <XCUIApplicationImpl: 0x1 com.app>",
        "Test Case '-[UITests.Audit testA]' failed (64.2 seconds).",
        "Test Case '-[UITests.Audit testA]' passed (90.0 seconds).",
        "x.swift:116: error: -[UITests.Audit testB] : Failed to resolve query: Timed out while evaluating UI query.",
        "Test Case '-[UITests.Audit testB]' failed (30.0 seconds).",
        "Test Case '-[UITests.Main testC]' passed (10.0 seconds).",
    ]
    hiccups, real = judge(ok)
    assert set(hiccups) == {"UITests.Audit testA", "UITests.Audit testB"} and not real, (hiccups, real)
    bad = ok + [
        "x.swift:168: error: -[UITests.Audit testD] : Dynamic Type font sizes are partially unsupported",
        "Test Case '-[UITests.Audit testD]' failed (105.9 seconds).",
        "Test Case '-[UITests.Main testE]' failed (12.0 seconds).",
        "x.swift:64: error: -[UITests.Main testF] : Failed to launch <app>",
        "x.swift:70: error: -[UITests.Main testF] : XCTAssertTrue failed - watchlist not loaded",
        "Test Case '-[UITests.Main testF]' failed (70.0 seconds).",
    ]
    hiccups, real = judge(bad)
    assert set(real) == {"UITests.Audit testD", "UITests.Main testE", "UITests.Main testF"}, real
    assert real["UITests.Main testE"] == ["failed without an error line of its own"]
    assert judge(["Test Case '-[A b]' passed (1.0 seconds)."]) == ({}, {})
    print("ui_retry_policy self-test ok")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1]))
