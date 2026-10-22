"""Line limit: every production source file has at most 400 lines (since build 103).

Usage: python3 -I line_limit.py [repository root, default: current directory]

Checks Kotlin under android/<module>/src/main and Swift under ios/App, ios/Shared and ios/Widgets; tests
(src/test, src/androidTest, ios/Tests) are not checked. Same rule as the unit test FileLengthTest, but without
Gradle: the result is an error annotation per file and exit 1. A missing ios/ directory (Android-only checkout)
is fine.
"""
import os
import sys

LIMIT = 400
IOS_AREAS = ("App", "Shared", "Widgets")


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
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    checked = 0
    too_long = []
    for path in production_files(root):
        checked += 1
        lines = line_count(path)
        if lines > LIMIT:
            too_long.append((os.path.relpath(path, root), lines))
    if checked == 0:
        print(f"::error title=Line limit::no production sources found under {os.path.abspath(root)}")
        return 1
    for path, lines in too_long:
        print(f"::error file={path},title=Line limit::{lines} lines, at most {LIMIT} allowed - split the file "
              "(extension or helper file next to it)")
    if too_long:
        return 1
    print(f"Line limit: {checked} files, none over {LIMIT} lines")
    return 0


if __name__ == "__main__":
    sys.exit(main())
