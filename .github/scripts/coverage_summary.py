#!/usr/bin/env python3
"""Prints the Kover line coverage per module and in total as ONE notice annotation; with «--min-logic P» also a gate.

Two numbers each: «total» (every class in the report) and «logic» (without UI and generated code):
  - UI: a source file containing «@Composable» (screens, components, Compose-generated lambdas and
    ComposableSingletons live in the same file), «RemoteViews» or «androidx.glance» (widget rendering), and
    activities (file name «…Activity.kt»/«…Activity.java»). ViewModels, controllers and state in ui/ packages
    without @Composable count as logic.
  - generated: a source file that is not in the module's src/main (Hilt_*, *_Factory, *_MembersInjector, Room *_Impl,
    BuildConfig, R, …).
  Without source folders (sources not checked out) the classification falls back to names: packages «…/ui/…» are UI,
  names of generated classes are generated.

Each argument is «name=module dir»; the first Kover XML report (JaCoCo format) found under
<module dir>/build/reports/kover/ is read (debug variant preferred), the sources under <module dir>/src/main/{java,kotlin}.
«--merged NAME»: the report of NAME also contains the other modules (Kover «dependencies { kover(project(…)) }»);
the total comes from it, files are attributed to the module whose sources contain them, and the other modules' own
reports are listed as «own tests only». Without «--min-logic», missing reports are only listed: a missing merged
report then falls back to the modules' own reports (display only).

«--min-logic P»: exit 1 with an error annotation when the total «logic» coverage is below P percent, when there is no
coverage data at all, or when «--merged NAME» is given and NAME's report is missing (no silent fallback to the
modules' own reports - a missing report must never pass the gate). Without it: display only, always exit 0.

Usage: python3 -I coverage_summary.py --merged app [--min-logic 58] app=app marketdata=marketdata
       python3 -I coverage_summary.py --self-test
"""
import contextlib
import glob
import io
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET

UI_MARKERS = ("@Composable", "RemoteViews", "androidx.glance")
GENERATED_NAME = re.compile(
    r"^(Hilt_.*|.*_Factory|.*_MembersInjector|.*_HiltModules.*|.*_HiltComponents.*|.*_GeneratedInjector"
    r"|.*_ComponentTreeDeps|.*_Impl|.*_Provide.*|BuildConfig|R|ComposableSingletons.*)\.(kt|java)$"
)
GENERATED_PACKAGE = re.compile(r"^(dagger|hilt_aggregated_deps)(/|$)")


def find_report(module_dir):
    candidates = sorted(glob.glob(os.path.join(module_dir, "build", "reports", "kover", "*.xml")))
    debug = [c for c in candidates if "debug" in os.path.basename(c).lower()]
    return (debug or candidates or [None])[0]


def source_roots(module_dir):
    roots = [os.path.join(module_dir, "src", "main", d) for d in ("java", "kotlin")]
    return [r for r in roots if os.path.isdir(r)]


def line_counter(element):
    for counter in element.findall("counter"):
        if counter.get("type") == "LINE":
            return int(counter.get("missed", "0")), int(counter.get("covered", "0"))
    return 0, 0


def report_files(path):
    """[(package, file name, missed, covered)] per source file; one pseudo entry if the report has none."""
    root = ET.parse(path).getroot()
    files = []
    for package in root.findall("package"):
        for source in package.findall("sourcefile"):
            missed, covered = line_counter(source)
            files.append((package.get("name", ""), source.get("name", ""), missed, covered))
    if not files:
        missed, covered = line_counter(root)
        files.append(("", "", missed, covered))
    return files


def locate(package, name, modules):
    """(module name, source path) of the first module whose sources contain the file, else (None, None)."""
    for module, roots in modules:
        for root in roots:
            path = os.path.join(root, *package.split("/"), name)
            if os.path.isfile(path):
                return module, path
    return None, None


def category(package, name, path, have_sources):
    """«logic», «ui» or «generated» (see module doc)."""
    if not name:
        return "logic"
    if path:
        if re.search(r"Activity\.(kt|java)$", name):
            return "ui"
        try:
            with open(path, encoding="utf-8", errors="replace") as f:
                text = f.read()
        except OSError:
            text = ""
        return "ui" if any(marker in text for marker in UI_MARKERS) else "logic"
    if have_sources or GENERATED_NAME.match(name) or GENERATED_PACKAGE.match(package):
        return "generated"
    if re.search(r"(^|/)ui(/|$)", package) or re.search(r"Activity\.(kt|java)$", name):
        return "ui"
    return "logic"


class Tally:
    def __init__(self):
        self.missed = self.covered = self.logic_missed = self.logic_covered = 0

    def add(self, kind, missed, covered):
        self.missed += missed
        self.covered += covered
        if kind == "logic":
            self.logic_missed += missed
            self.logic_covered += covered

    def text(self):
        return (f"total {percent(self.missed, self.covered)} ({self.covered}/{self.missed + self.covered})"
                f" · logic {percent(self.logic_missed, self.logic_covered)}"
                f" ({self.logic_covered}/{self.logic_missed + self.logic_covered})")


def percent(missed, covered):
    total = missed + covered
    return f"{100.0 * covered / total:.1f} %" if total else "n/a"


def tally(report, owner, sources):
    """{module name: Tally} of one report; files outside every module's sources go to [owner]."""
    have_sources = any(roots for _, roots in sources)
    result = {}
    for package, name, missed, covered in report_files(report):
        module, path = locate(package, name, sources) if name else (None, None)
        kind = category(package, name, path, have_sources)
        result.setdefault(module or owner, Tally()).add(kind, missed, covered)
    return result


def summary(modules, merged=None):
    head, lines, _ = summary_tally(modules, merged)
    return head, lines


def logic_gate(total, minimum):
    """(ok, message) of the gate «logic coverage at least [minimum] percent»; no data = not ok."""
    lines = total.logic_missed + total.logic_covered
    if not lines:
        return False, f"no logic coverage data (gate: at least {minimum:g} %)"
    value = 100.0 * total.logic_covered / lines
    if value < minimum:
        return False, f"logic coverage {value:.1f} % is below the gate of {minimum:g} %"
    return True, f"logic coverage {value:.1f} % ≥ gate {minimum:g} %"


def summary_tally(modules, merged=None):
    sources = [(name, source_roots(module_dir)) for name, module_dir in modules]
    dirs = dict(modules)
    merged_report = find_report(dirs[merged]) if merged in dirs else None
    lines, total = [], Tally()
    if merged_report:
        per_module = tally(merged_report, merged, sources)
        for name, _ in modules:
            part = per_module.get(name)
            if part is None:
                lines.append(f"{name}: not in the merged report")
                continue
            for attr in ("missed", "covered", "logic_missed", "logic_covered"):
                setattr(total, attr, getattr(total, attr) + getattr(part, attr))
            note = "" if name == merged else f" (incl. tests of {merged})"
            lines.append(f"{name}: {part.text()}{note}")
        for name, module_dir in modules:
            if name == merged:
                continue
            report = find_report(module_dir)
            if report:
                own = tally(report, name, [s for s in sources if s[0] == name]).get(name, Tally())
                lines.append(f"{name} own tests only: {own.text()}")
        head = f"Line coverage (unit tests, debug, {merged} merged): {total.text()}"
    else:
        for name, module_dir in modules:
            report = find_report(module_dir)
            if not report:
                lines.append(f"{name}: no Kover report")
                continue
            part = tally(report, name, [s for s in sources if s[0] == name]).get(name, Tally())
            for attr in ("missed", "covered", "logic_missed", "logic_covered"):
                setattr(total, attr, getattr(total, attr) + getattr(part, attr))
            lines.append(f"{name}: {part.text()}")
        head = f"Line coverage (unit tests, debug): {total.text()}"
    lines.append("logic = without UI (files with @Composable, widget rendering, activities) and generated code")
    return head, lines, total


def escape(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def _write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def _report(entries):
    packages = {}
    for package, name, missed, covered in entries:
        packages.setdefault(package, []).append(
            f'<sourcefile name="{name}"><line nr="1" mi="0" ci="1"/>'
            f'<counter type="LINE" missed="{missed}" covered="{covered}"/></sourcefile>')
    body = "".join(f'<package name="{p}">{"".join(s)}<counter type="LINE" missed="0" covered="0"/></package>'
                   for p, s in packages.items())
    return f'<report name="x">{body}<counter type="LINE" missed="1" covered="1"/></report>'


def self_test():
    with tempfile.TemporaryDirectory() as tmp:
        app, lib = os.path.join(tmp, "app"), os.path.join(tmp, "lib")
        src = os.path.join(app, "src", "main", "java", "com", "x", "app")
        _write(os.path.join(src, "ui", "Screen.kt"), "@Composable\nfun Screen() {}\n")
        _write(os.path.join(src, "ui", "FooViewModel.kt"), "class FooViewModel\n")
        _write(os.path.join(src, "ui", "MainActivity.kt"), "class MainActivity : ComponentActivity()\n")
        _write(os.path.join(src, "widget", "PriceRenderer.kt"), "import android.widget.RemoteViews\n")
        _write(os.path.join(src, "domain", "Calc.kt"), "object Calc\n")
        _write(os.path.join(lib, "src", "main", "java", "com", "x", "md", "Parser.kt"), "object Parser\n")
        merged = [("com/x/app/ui", "Screen.kt", 90, 10), ("com/x/app/ui", "FooViewModel.kt", 5, 5),
                  ("com/x/app/ui", "MainActivity.kt", 3, 0), ("com/x/app/widget", "PriceRenderer.kt", 7, 0),
                  ("com/x/app/domain", "Calc.kt", 2, 8), ("com/x/app", "Hilt_App.java", 10, 0),
                  ("com/x/md", "Parser.kt", 4, 16)]
        _write(os.path.join(app, "build", "reports", "kover", "reportDebug.xml"), _report(merged))
        _write(os.path.join(app, "build", "reports", "kover", "reportRelease.xml"), _report([("p", "A.kt", 1, 0)]))
        _write(os.path.join(lib, "build", "reports", "kover", "reportDebug.xml"),
               _report([("com/x/md", "Parser.kt", 18, 2)]))

        head, lines = summary([("app", app), ("lib", lib)], merged="app")
        assert head == "Line coverage (unit tests, debug, app merged): total 24.4 % (39/160) · logic 72.5 % (29/40)", head
        assert lines[0] == "app: total 16.4 % (23/140) · logic 65.0 % (13/20)", lines
        assert lines[1] == "lib: total 80.0 % (16/20) · logic 80.0 % (16/20) (incl. tests of app)", lines
        assert lines[2] == "lib own tests only: total 10.0 % (2/20) · logic 10.0 % (2/20)", lines
        assert lines[3].startswith("logic = "), lines

        # Without --merged: each module's own report, summed (as before the merge)
        head, lines = summary([("app", app), ("lib", lib), ("none", os.path.join(tmp, "none"))])
        assert head.startswith("Line coverage (unit tests, debug): total "), head
        assert lines[1] == "lib: total 10.0 % (2/20) · logic 10.0 % (2/20)", lines
        assert lines[2] == "none: no Kover report", lines

        # Merged report missing: the display falls back to the modules' own reports
        head, lines = summary([("none", os.path.join(tmp, "none")), ("lib", lib)], merged="none")
        assert lines == ["none: no Kover report", "lib: total 10.0 % (2/20) · logic 10.0 % (2/20)", lines[-1]], lines

        # Without sources: names decide (ui/ package = UI, Hilt_/BuildConfig = generated)
        bare = os.path.join(tmp, "bare")
        _write(os.path.join(bare, "build", "reports", "kover", "reportDebug.xml"), _report(
            [("com/x/ui", "Screen.kt", 1, 1), ("com/x", "BuildConfig.java", 1, 0), ("com/x", "Hilt_App.java", 1, 0),
             ("com/x/data", "Repo.kt", 1, 3)]))
        _, lines = summary([("bare", bare)])
        assert lines[0] == "bare: total 50.0 % (4/8) · logic 75.0 % (3/4)", lines

        # Report without source files: top-level counter, all of it counts as logic
        flat = os.path.join(tmp, "flat")
        _write(os.path.join(flat, "build", "reports", "kover", "reportDebug.xml"),
               '<report name="x"><counter type="INSTRUCTION" missed="5" covered="5"/>'
               '<counter type="LINE" missed="30" covered="10"/></report>')
        _, lines = summary([("flat", flat)])
        assert lines[0] == "flat: total 25.0 % (10/40) · logic 25.0 % (10/40)", lines
    gate = Tally()
    assert logic_gate(gate, 58)[0] is False and "no logic coverage data" in logic_gate(gate, 58)[1]
    gate.logic_covered, gate.logic_missed = 60, 40
    assert logic_gate(gate, 58) == (True, "logic coverage 60.0 % ≥ gate 58 %"), logic_gate(gate, 58)
    assert logic_gate(gate, 61)[0] is False and "below the gate of 61 %" in logic_gate(gate, 61)[1]
    # Gate through main: no report = red (never silently green); output captured (no annotations in the self-test)
    out = io.StringIO()
    with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(out):
        assert main(["--min-logic", "58", f"none={os.path.join(tmp, 'none')}"]) == 1
        assert main([f"none={os.path.join(tmp, 'none')}"]) == 0
    assert "::error title=Coverage gate::no logic coverage data" in out.getvalue(), out.getvalue()
    # Gate with --merged: the merged report is missing, but the other module's own report would pass the gate on
    # its own - still red (no silent fallback); display only (without --min-logic) stays exit 0
    out = io.StringIO()
    with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(out):
        good = os.path.join(tmp, "good")
        _write(os.path.join(good, "build", "reports", "kover", "reportDebug.xml"), _report([("p", "A.kt", 0, 10)]))
        mods = [f"none={os.path.join(tmp, 'none')}", f"good={good}"]
        assert main(["--min-logic", "50", "good=" + good]) == 0
        assert main(["--merged", "none", "--min-logic", "50"] + mods) == 1
        assert main(["--merged", "absent", "--min-logic", "50", "good=" + good]) == 1
        assert main(["--merged", "none"] + mods) == 0
    text = out.getvalue()
    assert text.count("::error title=Coverage gate::merged report of «none» missing") == 1, text
    assert text.count("::error title=Coverage gate::merged report of «absent» missing") == 1, text
    print("coverage_summary self-test ok")
    return 0


def main(argv):
    if argv == ["--self-test"]:
        return self_test()
    merged, minimum, modules, args = None, None, [], list(argv)
    while args:
        arg = args.pop(0)
        if arg == "--merged" and args:
            merged = args.pop(0)
            continue
        if arg == "--min-logic" and args:
            minimum = float(args.pop(0))
            continue
        name, _, module_dir = arg.partition("=")
        modules.append((name, module_dir or name))
    head, lines, total = summary_tally(modules, merged)
    print(head)
    print("\n".join(lines))
    print(f"::notice title=Coverage::{escape(head + chr(10) + chr(10).join(lines))}")
    if minimum is None:
        return 0
    dirs = dict(modules)
    if merged is not None and not (merged in dirs and find_report(dirs[merged])):
        message = (f"merged report of «{merged}» missing (gate: at least {minimum:g} %); "
                   "the modules' own reports do not replace it")
        print(f"::error title=Coverage gate::{escape(message)}")
        return 1
    ok, message = logic_gate(total, minimum)
    print(f"::{'notice' if ok else 'error'} title=Coverage gate::{message}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
