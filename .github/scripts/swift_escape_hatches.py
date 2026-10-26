"""Swift escape hatches: counts the ways around the strict concurrency checks in the iOS production code.

Usage: python3 -I swift_escape_hatches.py [repository root, default: current directory]
       python3 -I swift_escape_hatches.py --self-test   (checks the scanner and the annotation escaping)

The app builds in the Swift 6 language mode with strict concurrency «complete»; the compiler does not check
`@unchecked Sendable`, `nonisolated(unsafe)` and `DispatchSemaphore` (blocking a thread while waiting for
async work). Each kind is counted in ios/App, ios/Shared and ios/Widgets (tests in ios/Tests are not counted);
only code counts — mentions in comments and string literals do not. A kind above its ceiling in CEILINGS is
an error annotation and exit 1: a new escape hatch needs a deliberate raise of the ceiling in this file (in the
same pull request, with the reason). A kind below its ceiling is a notice: lower the ceiling so it cannot grow
back unnoticed. File names and texts in annotations are escaped like annotate.py.
"""
import os
import re
import sys

IOS_AREAS = ("App", "Shared", "Widgets")

# Allowed number per kind (count in code, without comments). Raise only deliberately; lower when the code shrinks.
CEILINGS = {
    "@unchecked Sendable": 103,  # +2 BitunixFutures, EcbReference (adapters like all exchanges, build 133)
    "nonisolated(unsafe)": 13,
    "DispatchSemaphore": 0,
}

PATTERNS = {
    "@unchecked Sendable": re.compile(r"@unchecked\s+Sendable\b"),
    "nonisolated(unsafe)": re.compile(r"\bnonisolated\s*\(\s*unsafe\s*\)"),
    "DispatchSemaphore": re.compile(r"\bDispatchSemaphore\b"),
}

TITLE = "Swift escape hatches"


def escape_data(text):
    """Message part of a workflow command - same as annotate.py (run with -I, so no import)."""
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(text):
    """Property value (file=, title=) - same as annotate.py."""
    return escape_data(text).replace(":", "%3A").replace(",", "%2C")


def annotation(level, message, file=None):
    """One ::error / ::notice line with escaped file and message."""
    props = (f"file={escape_property(file)}," if file else "") + "title=" + escape_property(TITLE)
    return f"::{level} {props}::{escape_data(message)}"


def code_only(source):
    """Swift source with comments and string literals replaced by spaces (line breaks kept).

    Handles // and nested /* */ comments, "…" with escapes, multi-line \"\"\"…\"\"\" and raw strings (#"…"#).
    Interpolations inside strings are dropped with the string (an escape hatch cannot appear there).
    """
    out = []
    i, n = 0, len(source)
    while i < n:
        c = source[i]
        if source.startswith("//", i):
            end = source.find("\n", i)
            end = n if end < 0 else end
            out.append(" " * (end - i))
            i = end
        elif source.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth > 0:
                if source.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif source.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            out.append(re.sub(r"[^\n]", " ", source[i:j]))
            i = j
        elif c == '"' or (c == "#" and re.match(r'#+"', source[i:])):
            hashes = len(re.match(r"#*", source[i:]).group(0))
            start = i
            i += hashes
            multi = source.startswith('"""', i)
            quote = '"""' if multi else '"'
            i += len(quote)
            closing = quote + "#" * hashes
            while i < n:
                if hashes == 0 and source[i] == "\\":
                    i += 2
                    continue
                if source.startswith(closing, i):
                    i += len(closing)
                    break
                if not multi and source[i] == "\n":
                    break
                i += 1
            out.append(re.sub(r"[^\n]", " ", source[start:i]))
        else:
            out.append(c)
            i += 1
    return "".join(out)


def count_in(source):
    code = code_only(source)
    return {kind: len(pattern.findall(code)) for kind, pattern in PATTERNS.items()}


def swift_files(root):
    for area in IOS_AREAS:
        directory = os.path.join(root, "ios", area)
        for dirpath, dirnames, files in os.walk(directory):
            dirnames.sort()
            for name in sorted(files):
                if name.endswith(".swift"):
                    yield os.path.join(dirpath, name)


def evaluate(totals, per_file, ceilings):
    """Annotation lines and exit code for the counted totals."""
    lines, failed = [], False
    for kind, ceiling in ceilings.items():
        count = totals.get(kind, 0)
        if count > ceiling:
            failed = True
            files = sorted(((n.get(kind, 0), path) for path, n in per_file.items() if n.get(kind, 0)), reverse=True)
            top = ", ".join(f"{path} {k}" for k, path in files[:8])
            lines.append(annotation("error", f"{kind}: {count} in the iOS code, ceiling {ceiling}. Prefer a "
                                    "checked alternative (OSAllocatedUnfairLock with state, actor, Sendable value); "
                                    "if it is really needed, raise CEILINGS in .github/scripts/swift_escape_hatches.py "
                                    f"with the reason. Most: {top}"))
        elif count < ceiling:
            lines.append(annotation("notice", f"{kind}: {count}, ceiling {ceiling} - lower CEILINGS in "
                                    f".github/scripts/swift_escape_hatches.py to {count}"))
    summary = ", ".join(f"{kind} {totals.get(kind, 0)}/{ceiling}" for kind, ceiling in ceilings.items())
    lines.append(f"Swift escape hatches (count/ceiling): {summary}")
    return lines, (1 if failed else 0)


def self_test():
    sample = '\n'.join([
        'final class A: @unchecked Sendable { // @unchecked Sendable: comment does not count',
        '    nonisolated(unsafe) static var x = 0',
        '    /* nonisolated(unsafe) /* nested @unchecked Sendable */ DispatchSemaphore */',
        '    let s = "nonisolated(unsafe) in a string \\" DispatchSemaphore"',
        '    let m = """',
        '    @unchecked Sendable in a multi-line string',
        '    """',
        '    let r = #"raw "DispatchSemaphore" text"#',
        '    let url = "https://example.com" ; let sem = DispatchSemaphore(value: 0)',
        '    nonisolated ( unsafe ) static let y = 1',
        '}',
        'final class B: Base, @unchecked  Sendable {}',
    ])
    counts = count_in(sample)
    assert counts == {"@unchecked Sendable": 2, "nonisolated(unsafe)": 2, "DispatchSemaphore": 1}, counts
    assert code_only("a\n/* x\ny */b").count("\n") == 2
    assert count_in("let a = 1 // DispatchSemaphore")["DispatchSemaphore"] == 0
    assert count_in("x = DispatchSemaphoreLike()")["DispatchSemaphore"] == 0

    line = annotation("error", "a\nb%c", file="x,y:z\r\n::error ::boom.swift")
    expected = ("::error file=x%2Cy%3Az%0D%0A%3A%3Aerror %3A%3Aboom.swift,title=Swift escape hatches::"
                "a%0Ab%25c")
    assert line == expected, line
    assert "\n" not in line and "\r" not in line

    ceilings = {"@unchecked Sendable": 2, "nonisolated(unsafe)": 2, "DispatchSemaphore": 0}
    lines, code = evaluate(counts, {"a.swift": counts}, ceilings)
    assert code == 1 and any(x.startswith("::error ") and "DispatchSemaphore: 1" in x for x in lines), lines
    lines, code = evaluate({"@unchecked Sendable": 2, "nonisolated(unsafe)": 1}, {}, ceilings)
    assert code == 0 and any(x.startswith("::notice ") and "to 1" in x for x in lines), lines
    assert not any(x.startswith("::error ") for x in lines), lines
    print("swift_escape_hatches self-test ok")
    return 0


def main():
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    totals = {kind: 0 for kind in PATTERNS}
    per_file = {}
    for path in swift_files(root):
        with open(path, encoding="utf-8") as f:
            counts = count_in(f.read())
        per_file[os.path.relpath(path, root)] = counts
        for kind, n in counts.items():
            totals[kind] += n
    if not per_file:
        print(annotation("error", f"no Swift sources found under {os.path.abspath(root)}/ios"))
        return 1
    lines, code = evaluate(totals, per_file, CEILINGS)
    print(f"{len(per_file)} Swift files in ios/{', ios/'.join(IOS_AREAS)}")
    for line in lines:
        print(line)
    return code


if __name__ == "__main__":
    sys.exit(main())
