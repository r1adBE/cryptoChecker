#!/usr/bin/env python3
"""Prints the iOS unit-test line coverage as ONE notice annotation; with «--min-logic P» also a gate.

Input: the JSON of «xcrun xccov view --report --json <xcresult>» (targets[].files[] with path, coveredLines,
executableLines). Test bundles (*.xctest) are left out. A file compiled into several targets (app and widget
extension) counts once, with its best-covered entry: the tests run in the app, the extension stays at 0 %.

Two numbers: «total» (every source file of the app and its extensions) and «logic» (files under Shared/ that do not
define a SwiftUI view, i.e. contain no «some View»). Without the source file on disk the folder alone decides.

«--min-logic P»: exit 1 with an error annotation when «logic» is below P percent or the report is missing/unreadable.

Usage: python3 -I xccov_summary.py [--min-logic 78] report.json
       python3 -I xccov_summary.py --self-test
"""
import contextlib
import io
import json
import os
import sys
import tempfile

UI_MARKERS = ("some View",)


def is_logic(path):
    """True for a file under Shared/ without a SwiftUI view."""
    if "/Shared/" not in path.replace(os.sep, "/"):
        return False
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            text = f.read()
    except OSError:
        return True
    return not any(marker in text for marker in UI_MARKERS)


def best_files(report):
    """{path: (covered, executable)} over all non-test targets, best entry per path."""
    files = {}
    for target in report.get("targets", []):
        if str(target.get("name", "")).endswith(".xctest"):
            continue
        for entry in target.get("files", []):
            path = entry.get("path") or entry.get("name") or ""
            covered, executable = int(entry.get("coveredLines", 0)), int(entry.get("executableLines", 0))
            if path not in files or covered > files[path][0]:
                files[path] = (covered, executable)
    return files


def percent(covered, executable):
    return f"{100.0 * covered / executable:.1f} %" if executable else "n/a"


def logic_numbers(report):
    """(covered, executable) lines of «logic» (Shared/ without SwiftUI views)."""
    logic = [v for p, v in best_files(report).items() if is_logic(p)]
    return sum(c for c, _ in logic), sum(e for _, e in logic)


def logic_gate(report, minimum):
    """(ok, message) of the gate «logic coverage at least [minimum] percent»; no data = not ok."""
    covered, executable = logic_numbers(report)
    if not executable:
        return False, f"no iOS logic coverage data (gate: at least {minimum:g} %)"
    value = 100.0 * covered / executable
    if value < minimum:
        return False, f"iOS logic coverage {value:.1f} % is below the gate of {minimum:g} %"
    return True, f"iOS logic coverage {value:.1f} % ≥ gate {minimum:g} %"


def summary(report):
    files = best_files(report)
    covered = sum(c for c, _ in files.values())
    executable = sum(e for _, e in files.values())
    logic = [v for p, v in files.items() if is_logic(p)]
    logic_covered = sum(c for c, _ in logic)
    logic_executable = sum(e for _, e in logic)
    return (f"iOS line coverage (unit tests): total {percent(covered, executable)} ({covered}/{executable})"
            f" · logic {percent(logic_covered, logic_executable)} ({logic_covered}/{logic_executable})"
            f" · logic = Shared/ without SwiftUI views")


def escape(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def self_test():
    with tempfile.TemporaryDirectory() as tmp:
        shared = os.path.join(tmp, "ios", "Shared")
        os.makedirs(os.path.join(shared, "Util"))
        calc, arrow = os.path.join(shared, "Calc.swift"), os.path.join(shared, "Util", "Arrow.swift")
        with open(calc, "w", encoding="utf-8") as f:
            f.write("enum Calc {}\n")
        with open(arrow, "w", encoding="utf-8") as f:
            f.write("struct Arrow: View { var body: some View { EmptyView() } }\n")
        screen = os.path.join(tmp, "ios", "App", "Screen.swift")
        gone = os.path.join(tmp, "ios", "Shared", "Gone.swift")
        report = {"targets": [
            {"name": "CryptoChecker.app", "files": [
                {"path": calc, "coveredLines": 8, "executableLines": 10},
                {"path": arrow, "coveredLines": 1, "executableLines": 10},
                {"path": screen, "coveredLines": 0, "executableLines": 20},
                {"path": gone, "coveredLines": 3, "executableLines": 4}]},
            {"name": "CryptoCheckerWidgetsExtension.appex", "files": [
                {"path": calc, "coveredLines": 0, "executableLines": 10}]},
            {"name": "CryptoCheckerTests.xctest", "files": [
                {"path": os.path.join(tmp, "ios", "Tests", "CalcTests.swift"), "coveredLines": 50,
                 "executableLines": 50}]}]}
        text = summary(report)
        assert text == ("iOS line coverage (unit tests): total 27.3 % (12/44) · logic 78.6 % (11/14)"
                        " · logic = Shared/ without SwiftUI views"), text
        assert summary({}).startswith("iOS line coverage (unit tests): total n/a (0/0) · logic n/a"), summary({})
        path = os.path.join(tmp, "report.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(report, f)
        # Ausgabe abfangen: der Selbsttest darf keine Annotationen auf der Lauf-Seite erzeugen
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            assert main([path]) == 0
            assert main([os.path.join(tmp, "missing.json")]) == 0
        assert "::notice title=iOS coverage::iOS line coverage" in out.getvalue(), out.getvalue()
        assert "no coverage report (FileNotFoundError)" in out.getvalue(), out.getvalue()
        # Gate: 78.6 % Logik im Beispiel; ohne Bericht rot
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            assert main(["--min-logic", "78", path]) == 0
            assert main(["--min-logic", "80", path]) == 1
            assert main(["--min-logic", "78", os.path.join(tmp, "missing.json")]) == 1
        assert "≥ gate 78 %" in out.getvalue() and "below the gate of 80 %" in out.getvalue(), out.getvalue()
    print("xccov_summary self-test ok")
    return 0


def main(argv):
    if argv == ["--self-test"]:
        return self_test()
    args, minimum = list(argv), None
    if len(args) == 3 and args[0] == "--min-logic":
        minimum = float(args[1])
        args = args[2:]
    if len(args) != 1:
        print(__doc__)
        return 0 if minimum is None else 1
    try:
        with open(args[0], encoding="utf-8") as f:
            report = json.load(f)
    except (OSError, ValueError) as error:
        level = "notice" if minimum is None else "error"
        print(f"::{level} title=iOS coverage::no coverage report ({error.__class__.__name__})")
        return 0 if minimum is None else 1
    text = summary(report)
    print(text)
    print(f"::notice title=iOS coverage::{escape(text)}")
    if minimum is None:
        return 0
    ok, message = logic_gate(report, minimum)
    print(f"::{'notice' if ok else 'error'} title=iOS coverage gate::{message}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
