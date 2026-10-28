"""Line limit: every production source file has at most 400 lines; build numbers in code comments are capped.

Usage: python3 -I line_limit.py [repository root, default: current directory]
       python3 -I line_limit.py --self-test   (checks the annotation escaping)

Checks Kotlin under android/<module>/src/main and Swift under ios/App, ios/Shared, ios/Widgets and the watch folders; tests
(src/test, src/androidTest, ios/Tests) are not checked. Same rule as the unit test FileLengthTest, but without
Gradle: the result is an error annotation per file and exit 1. A missing ios/ directory (Android-only checkout)
is fine. File names and texts in annotations are escaped like annotate.py (a crafted file name cannot inject
further workflow commands).

Build numbers («Build 133») in the same files: release history belongs in CHANGELOG/DEVELOPMENT, not in the code.
BUILD_REFS_CEILING is 0: any build number in production code is an error.
"""
import os
import re
import sys

LIMIT = 400
BUILD_REFS_CEILING = 0
BUILD_REF = re.compile(r"\b[Bb]uild \d{2,3}\b")
IOS_AREAS = ("App", "Shared", "Widgets", "WatchCore", "Watch", "WatchWidgets")


def escape_data(text):
    """Message part of a workflow command - same as annotate.py (run with -I, so no import)."""
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(text):
    """Property value (file=, title=) - same as annotate.py."""
    return escape_data(text).replace(":", "%3A").replace(",", "%2C")


def annotation(message, file=None):
    """One ::error line with escaped file and message."""
    props = (f"file={escape_property(file)}," if file else "") + "title=" + escape_property("Line limit")
    return f"::error {props}::{escape_data(message)}"


def self_test():
    """Escaping like annotate.py: no raw line break, %, : or , can leave its field."""
    line = annotation("a\nb%c", file="x,y:z\r\n::error ::boom.kt")
    expected = "::error file=x%2Cy%3Az%0D%0A%3A%3Aerror %3A%3Aboom.kt,title=Line limit::a%0Ab%25c"
    assert line == expected, line
    assert "\n" not in line and "\r" not in line
    assert annotation("plain") == "::error title=Line limit::plain"
    print("line_limit self-test ok")
    return 0


def sources(directory, extension):
    for dirpath, dirnames, files in os.walk(directory):
        dirnames.sort()
        for name in sorted(files):
            if name.endswith(extension):
                yield os.path.join(dirpath, name)


def production_files(root):
    android = os.path.join(root, "android")
    if os.path.isdir(android):
        for module in sorted(os.listdir(android)):
            main = os.path.join(android, module, "src", "main")
            if os.path.isdir(main):
                yield from sources(main, ".kt")
    for area in IOS_AREAS:
        directory = os.path.join(root, "ios", area)
        if os.path.isdir(directory):
            yield from sources(directory, ".swift")


def line_count(path):
    with open(path, encoding="utf-8") as f:
        return sum(1 for _ in f)


def main():
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    checked = 0
    too_long = []
    build_refs = []
    for path in production_files(root):
        checked += 1
        lines = line_count(path)
        if lines > LIMIT:
            too_long.append((os.path.relpath(path, root), lines))
        with open(path, encoding="utf-8") as f:
            build_refs += [os.path.relpath(path, root) for line in f if BUILD_REF.search(line)]
    if checked == 0:
        print(annotation(f"no production sources found under {os.path.abspath(root)}"))
        return 1
    for path, lines in too_long:
        print(annotation(f"{lines} lines, at most {LIMIT} allowed - split the file (extension or helper file next "
                         "to it)", file=path))
    if len(build_refs) > BUILD_REFS_CEILING:
        print(annotation(f"{len(build_refs)} build numbers in production code, at most {BUILD_REFS_CEILING} - describe "
                         "the behaviour instead (history goes to CHANGELOG/DEVELOPMENT): " + ", ".join(sorted(set(build_refs)))))
        return 1
    if too_long:
        return 1
    print(f"Line limit: {checked} files, none over {LIMIT} lines; build numbers in code {len(build_refs)} "
          f"(ceiling {BUILD_REFS_CEILING})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
