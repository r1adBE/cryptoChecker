"""Line limit: every production source file has at most 400 lines; build numbers in code comments are capped.

Usage: python3 -I line_limit.py [repository root, default: current directory]
       python3 -I line_limit.py --self-test   (checks the annotation escaping and the comment rules)

Checks Kotlin under android/<module>/src/main and Swift under ios/App, ios/Shared, ios/Widgets and the watch folders; tests
(src/test, src/androidTest, ios/Tests) are not checked. Same rule as the unit test FileLengthTest, but without
Gradle: the result is an error annotation per file and exit 1. A missing ios/ directory (Android-only checkout)
is fine. File names and texts in annotations are escaped like annotate.py (a crafted file name cannot inject
further workflow commands).

Build numbers («Build NNN») and review tags («(Review D13)», «(D1)») in the same files and in the build and CI
files (.github workflows and scripts, ios/project.yml, ios/tools, android/tools, Gradle scripts, the version
catalog, gradle.properties, ProGuard rules; .yml/.py/.toml/.sh/.kts/.properties/.pro): release history belongs in
CHANGELOG/DEVELOPMENT, not in the code. Version lines (CURRENT_PROJECT_VERSION, versionCode, …),
release_readiness.py (it checks build numbers in the docs) and this script are skipped (their self-tests need
them); Markdown is never scanned. BUILD_REFS_CEILING is 0: any build number or review tag there is an error.
"""
import os
import re
import sys
import tempfile

LIMIT = 400
BUILD_REFS_CEILING = 0
BUILD_REF = re.compile(r"\b[Bb]uild \d{2,3}\b")
# Kennung aus einem Review («(Review D13)», «(D1)», «(W3)»); erlaubt: Normlicht D65 und der Ton A5 (keine Kennungen)
REVIEW_TAG = re.compile(r"\((Review )?[A-Z]\d{1,2}\)")
REVIEW_TAG_ALLOWED = {"(D65)", "(A5)"}
VERSION_LINE = re.compile(r"CURRENT_PROJECT_VERSION|MARKETING_VERSION|CFBundleVersion|versionCode|versionName")
CONFIG_EXTENSIONS = (".yml", ".yaml", ".py", ".toml", ".sh", ".kts", ".properties", ".pro")
# Pfade relativ zur Wurzel; .github liegt im öffentlichen Repository oben, im Arbeitsstand unter public/
CONFIG_DIRS = (".github", "public/.github", "ios/tools", "android/tools", "android/gradle")
CONFIG_FILES = ("ios/project.yml", "android/build.gradle.kts", "android/settings.gradle.kts", "android/gradle.properties")
CONFIG_SKIPPED = ("release_readiness.py", "line_limit.py")
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


def history_ref(line):
    """Build number or review tag in a line (version lines and allowed tokens excepted)."""
    if VERSION_LINE.search(line):
        return False
    if BUILD_REF.search(line):
        return True
    return any(m.group(0) not in REVIEW_TAG_ALLOWED for m in REVIEW_TAG.finditer(line))


def self_test():
    """Escaping like annotate.py: no raw line break, %, : or , can leave its field; comment rules."""
    line = annotation("a\nb%c", file="x,y:z\r\n::error ::boom.kt")
    expected = "::error file=x%2Cy%3Az%0D%0A%3A%3Aerror %3A%3Aboom.kt,title=Line limit::a%0Ab%25c"
    assert line == expected, line
    assert "\n" not in line and "\r" not in line
    assert annotation("plain") == "::error title=Line limit::plain"
    for text in ("// Neu in Build 133", "# timeout (build 149)", "// %-Änderung (Review D13)", "// Gebühr (D1): klein",
                 "    // ---- Herkunft (W3) ----"):
        assert history_ref(text), text
    for text in ("CURRENT_PROJECT_VERSION: 168", "versionCode = 168 // Build 168", "/** CIELAB (D65) */",
                 "# Puls: drei Töne (A5)", "build 1", "// Rebuild 2024", "val x = f(A)", "// D13 ohne Klammern"):
        assert not history_ref(text), text
    with tempfile.TemporaryDirectory() as root:
        def write(rel, text):
            os.makedirs(os.path.dirname(os.path.join(root, rel)), exist_ok=True)
            with open(os.path.join(root, rel), "w", encoding="utf-8") as f:
                f.write(text)

        write(".github/workflows/a.yml", "# step (build 140)\n")
        write(".github/scripts/release_readiness.py", "# Build 91: Tag\n")
        write("ios/tools/gen.py", "# fine\n")
        write("ios/project.yml", "CURRENT_PROJECT_VERSION: 168\n")
        write("android/gradle/libs.versions.toml", "# Wear (Build 154)\n")
        write("android/tools/notes.md", "Build 150\n")
        found = sorted(os.path.relpath(p, root) for p in config_files(root) if any(history_ref(x) for x in open(p)))
        assert found == [os.path.join(".github", "workflows", "a.yml"),
                         os.path.join("android", "gradle", "libs.versions.toml")], found
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


def config_files(root):
    """Build and CI files whose comments follow the same rule (no build numbers, no review tags)."""
    seen = set()
    for rel in CONFIG_DIRS:
        directory = os.path.join(root, rel)
        if os.path.isdir(directory):
            for path in sources(directory, CONFIG_EXTENSIONS):
                if os.path.basename(path) not in CONFIG_SKIPPED and path not in seen:
                    seen.add(path)
                    yield path
    android = os.path.join(root, "android")
    if os.path.isdir(android):
        for module in sorted(os.listdir(android)):
            for name in ("build.gradle.kts", "proguard-rules.pro", "consumer-rules.pro"):
                path = os.path.join(android, module, name)
                if os.path.isfile(path):
                    yield path
    for rel in CONFIG_FILES:
        path = os.path.join(root, rel)
        if os.path.isfile(path):
            yield path


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
            build_refs += [os.path.relpath(path, root) for line in f if history_ref(line)]
    for path in config_files(root):
        with open(path, encoding="utf-8", errors="replace") as f:
            build_refs += [os.path.relpath(path, root) for line in f if history_ref(line)]
    if checked == 0:
        print(annotation(f"no production sources found under {os.path.abspath(root)}"))
        return 1
    for path, lines in too_long:
        print(annotation(f"{lines} lines, at most {LIMIT} allowed - split the file (extension or helper file next "
                         "to it)", file=path))
    if len(build_refs) > BUILD_REFS_CEILING:
        print(annotation(f"{len(build_refs)} build numbers or review tags in code and build files, at most "
                         f"{BUILD_REFS_CEILING} - describe "
                         "the behaviour instead (history goes to CHANGELOG/DEVELOPMENT): " + ", ".join(sorted(set(build_refs)))))
        return 1
    if too_long:
        return 1
    print(f"Line limit: {checked} files, none over {LIMIT} lines; build numbers in code {len(build_refs)} "
          f"(ceiling {BUILD_REFS_CEILING})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
