#!/usr/bin/env python3
"""Prints the iOS unit-test line coverage as ONE notice annotation; with «--min-logic P» also a gate.

Input: the JSON of «xcrun xccov view --report --json <xcresult>» (targets[].files[] with path, coveredLines,
executableLines). Test bundles (*.xctest) are left out. A file compiled into several targets (app and widget
extension) counts once, with its best-covered entry: the tests run in the app, the extension stays at 0 %.

Three numbers: «total» (every source file of the app and its extensions), «logic» (files under Shared/ and App/ that
do not define a SwiftUI view, i.e. contain no «some View») and «shared logic» (the same rule for Shared/ only).
Extensions (Widgets/) only count in «total». Without the source file on disk the folder alone decides.

«--min-logic P»: exit 1 with an error annotation when the gated number is below P percent or the report is
missing/unreadable. «--gate-scope shared|logic» picks the gated number: «shared» = «shared logic» (default),
«logic» = Shared/ and App/.

Usage: python3 -I xccov_summary.py [--min-logic 78 [--gate-scope shared|logic]] report.json
       python3 -I xccov_summary.py --self-test
"""
import contextlib
import io
import json
import os
import sys
import tempfile

UI_MARKERS = ("some View",)
# Folders whose view-free files count as logic, per gate scope
SCOPES = {"logic": ("/Shared/", "/App/"), "shared": ("/Shared/",)}
SCOPE_LABELS = {"logic": "logic", "shared": "shared logic"}


def is_logic(path, scope="logic"):
    """True for a file in one of the folders of [scope] without a SwiftUI view."""
    normalized = path.replace(os.sep, "/")
    if not any(folder in normalized for folder in SCOPES[scope]):
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


def logic_numbers(report, scope="logic"):
    """(covered, executable) lines of the files of [scope] without SwiftUI views."""
    logic = [v for p, v in best_files(report).items() if is_logic(p, scope)]
    return sum(c for c, _ in logic), sum(e for _, e in logic)


def logic_gate(report, minimum, scope="shared"):
    """(ok, message) of the gate «[scope] coverage at least [minimum] percent»; no data = not ok."""
    label = SCOPE_LABELS[scope]
    covered, executable = logic_numbers(report, scope)
    if not executable:
        return False, f"no iOS {label} coverage data (gate: at least {minimum:g} %)"
    value = 100.0 * covered / executable
    if value < minimum:
        return False, f"iOS {label} coverage {value:.1f} % is below the gate of {minimum:g} %"
    return True, f"iOS {label} coverage {value:.1f} % ≥ gate {minimum:g} %"


def summary(report):
    files = best_files(report)
    covered = sum(c for c, _ in files.values())
    executable = sum(e for _, e in files.values())
    logic_covered, logic_executable = logic_numbers(report, "logic")
    shared_covered, shared_executable = logic_numbers(report, "shared")
    return (f"iOS line coverage (unit tests): total {percent(covered, executable)} ({covered}/{executable})"
            f" · logic {percent(logic_covered, logic_executable)} ({logic_covered}/{logic_executable})"
            f" · shared logic {percent(shared_covered, shared_executable)} ({shared_covered}/{shared_executable})"
            f" · logic = Shared/ and App/ without SwiftUI views, shared logic = Shared/ only")


def escape(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def self_test():
    with tempfile.TemporaryDirectory() as tmp:
        shared = os.path.join(tmp, "ios", "Shared")
        services = os.path.join(tmp, "ios", "App", "Services")
        widgets = os.path.join(tmp, "ios", "Widgets")
        for folder in (os.path.join(shared, "Util"), services, widgets):
            os.makedirs(folder)
        calc, arrow = os.path.join(shared, "Calc.swift"), os.path.join(shared, "Util", "Arrow.swift")
        screen, store = os.path.join(tmp, "ios", "App", "Screen.swift"), os.path.join(services, "Store.swift")
        provider = os.path.join(widgets, "Provider.swift")
        sources = {calc: "enum Calc {}\n", store: "final class Store {}\n", provider: "struct Provider {}\n",
                   arrow: "struct Arrow: View { var body: some View { EmptyView() } }\n",
                   screen: "struct Screen: View { var body: some View { Text(\"\") } }\n"}
        for source, text in sources.items():
            with open(source, "w", encoding="utf-8") as f:
                f.write(text)
        gone = os.path.join(tmp, "ios", "Shared", "Gone.swift")
        report = {"targets": [
            {"name": "CryptoChecker.app", "files": [
                {"path": calc, "coveredLines": 8, "executableLines": 10},
                {"path": arrow, "coveredLines": 1, "executableLines": 10},
                {"path": screen, "coveredLines": 0, "executableLines": 20},
                {"path": store, "coveredLines": 6, "executableLines": 10},
                {"path": gone, "coveredLines": 3, "executableLines": 4}]},
            {"name": "CryptoCheckerWidgetsExtension.appex", "files": [
                {"path": calc, "coveredLines": 0, "executableLines": 10},
                {"path": provider, "coveredLines": 0, "executableLines": 6}]},
            {"name": "CryptoCheckerTests.xctest", "files": [
                {"path": os.path.join(tmp, "ios", "Tests", "CalcTests.swift"), "coveredLines": 50,
                 "executableLines": 50}]}]}
        # App/ without views counts in «logic» only; App/ views, Widgets/ and tests never count as logic
        assert is_logic(store) and not is_logic(store, "shared") and not is_logic(screen)
        assert not is_logic(provider) and is_logic(gone) and is_logic(gone, "shared")
        text = summary(report)
        assert text == ("iOS line coverage (unit tests): total 30.0 % (18/60) · logic 70.8 % (17/24)"
                        " · shared logic 78.6 % (11/14)"
                        " · logic = Shared/ and App/ without SwiftUI views, shared logic = Shared/ only"), text
        assert summary({}).startswith("iOS line coverage (unit tests): total n/a (0/0) · logic n/a"), summary({})
        path = os.path.join(tmp, "report.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(report, f)
        # Capture the output: the self-test must not create annotations on the run page
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            assert main([path]) == 0
            assert main([os.path.join(tmp, "missing.json")]) == 0
        assert "::notice title=iOS coverage::iOS line coverage" in out.getvalue(), out.getvalue()
        assert "no coverage report (FileNotFoundError)" in out.getvalue(), out.getvalue()
        # Gate (default scope shared): 78.6 % shared logic in the sample; no report = red
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            assert main(["--min-logic", "78", path]) == 0
            assert main(["--min-logic", "80", path]) == 1
            assert main(["--min-logic", "78", os.path.join(tmp, "missing.json")]) == 1
        assert "shared logic coverage 78.6 % ≥ gate 78 %" in out.getvalue(), out.getvalue()
        assert "below the gate of 80 %" in out.getvalue(), out.getvalue()
        # Gate scope logic: 70.8 % (Shared/ and App/); explicit scope shared as the default
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            assert main(["--min-logic", "78", "--gate-scope", "shared", path]) == 0
            assert main(["--min-logic", "78", "--gate-scope", "logic", path]) == 1
            assert main(["--gate-scope", "logic", "--min-logic", "70", path]) == 0
            assert main(["--min-logic", "70", "--gate-scope", "all", path]) == 1
            assert main(["--min-logic", "70", "--gate-scope", "logic", path, path]) == 1
        assert "iOS logic coverage 70.8 % is below the gate of 78 %" in out.getvalue(), out.getvalue()
        assert "iOS logic coverage 70.8 % ≥ gate 70 %" in out.getvalue(), out.getvalue()
        assert logic_gate({}, 78, "logic") == (False, "no iOS logic coverage data (gate: at least 78 %)")
    print("xccov_summary self-test ok")
    return 0


def parse(argv):
    """(minimum, scope, paths) or None for an unknown/incomplete option."""
    minimum, scope, paths, args = None, "shared", [], list(argv)
    while args:
        arg = args.pop(0)
        if arg in ("--min-logic", "--gate-scope"):
            if not args:
                return None
            value = args.pop(0)
            if arg == "--gate-scope":
                if value not in SCOPES:
                    return None
                scope = value
            else:
                try:
                    minimum = float(value)
                except ValueError:
                    return None
        elif arg.startswith("--"):
            return None
        else:
            paths.append(arg)
    return minimum, scope, paths


def main(argv):
    if argv == ["--self-test"]:
        return self_test()
    parsed = parse(argv)
    if parsed is None or len(parsed[2]) != 1:
        print(__doc__)
        # A gate call with bad arguments must not pass silently
        return 1 if "--min-logic" in argv else 0
    minimum, scope, (path,) = parsed
    try:
        with open(path, encoding="utf-8") as f:
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
    ok, message = logic_gate(report, minimum, scope)
    print(f"::{'notice' if ok else 'error'} title=iOS coverage gate::{message}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
