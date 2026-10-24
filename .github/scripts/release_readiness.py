#!/usr/bin/env python3
"""Store readiness checks without a build (release-readiness.yml). Appends findings for annotate.py.

  error   store text over the limit
            Play (android/fastlane/metadata/android/<lang>/): title 30, short_description 80,
                 full_description 4000, changelogs/*.txt 500
            App Store (ios/fastlane/metadata/<lang>/): name 30, subtitle 30, keywords 100 (bytes, as
                 App Store Connect counts them), promotional_text 170, description 4000,
                 release_notes 4000 (also ios/fastlane/release_notes_later/<lang>/)
          privacy_url / support_url / marketing_url not https (when filled in)
          version differs: android/app/build.gradle.kts vs ios/tools/gen_xcodeproj.py vs ios/project.yml
          tag run (GITHUB_REF refs/tags/v*): tag != v<versionName>-<versionCode> of android/app/build.gradle.kts
  warning required store text empty or missing (title / name / description), no screenshots
          android/docs/PLAY_STORE.md / ios/APP_STORE.md (private guides, only if present): version or
            versionCode / build number named there (also tag examples and archive name) differs from
            android/app/build.gradle.kts; history lines («Build N: …» with N < versionCode, «früher» /
            «previously» at the line start or in parentheses) are skipped
          ROADMAP.md / TESTPLAN.md (private, only if present): "Build N" in the header (first lines) differs
            from versionCode or is missing
  notice  private guide missing (public repository): named as "not checked" instead of skipped silently

The folder review_information (contact data for the App Review) is never read or listed.
Usage: python3 -I release_readiness.py <repository root> <findings file>
       python3 -I release_readiness.py --self-test   (tag check, document headers)
"""
import os
import re
import sys

SKIP_DIRS = {"review_information"}
PLAY_LIMITS = {"title.txt": 30, "short_description.txt": 80, "full_description.txt": 4000}
PLAY_REQUIRED = ("title.txt", "short_description.txt", "full_description.txt")
APPLE_LIMITS = {"name.txt": 30, "subtitle.txt": 30, "promotional_text.txt": 170, "description.txt": 4000,
                "release_notes.txt": 4000}
APPLE_BYTE_LIMITS = {"keywords.txt": 100}
# Privacy and support URL are mandatory per language in App Store Connect (the upload fails without them)
APPLE_REQUIRED = ("name.txt", "description.txt", "keywords.txt", "privacy_url.txt", "support_url.txt")
URL_FILES = ("privacy_url.txt", "support_url.txt", "marketing_url.txt")
IMAGE_EXT = (".png", ".jpg", ".jpeg")

findings = []


def add(level, message, file=""):
    findings.append("\t".join((level, file, "", message.replace("\t", " ").replace("\n", "\\n"))))


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read().strip()


def lang_dirs(base):
    """Language folders below base, never review_information."""
    if not os.path.isdir(base):
        return []
    return sorted(d for d in os.listdir(base)
                  if d not in SKIP_DIRS and os.path.isdir(os.path.join(base, d)))


def has_images(base):
    for root, dirs, files in os.walk(base):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        if any(f.lower().endswith(IMAGE_EXT) for f in files):
            return True
    return False


def check_play(repo):
    base = os.path.join(repo, "android", "fastlane", "metadata", "android")
    langs = lang_dirs(base)
    if not langs:
        add("notice", "No Play store texts (android/fastlane/metadata/android) in this repository: skipped")
        return
    over, empty = [], []
    for lang in langs:
        d = os.path.join(base, lang)
        for name in PLAY_REQUIRED:
            p = os.path.join(d, name)
            if not os.path.isfile(p) or not read(p):
                empty.append(f"{lang}/{name}")
        for name, limit in PLAY_LIMITS.items():
            p = os.path.join(d, name)
            if os.path.isfile(p) and len(read(p)) > limit:
                over.append((os.path.relpath(p, repo), f"{len(read(p))} > {limit} characters"))
        cl = os.path.join(d, "changelogs")
        if os.path.isdir(cl):
            for name in sorted(os.listdir(cl)):
                p = os.path.join(cl, name)
                if name.endswith(".txt") and os.path.isfile(p) and len(read(p)) > 500:
                    over.append((os.path.relpath(p, repo), f"{len(read(p))} > 500 characters"))
    for path, why in over:
        add("error", f"Play store text too long: {why}", path)
    if empty:
        add("warning", "Play store text empty or missing: " + ", ".join(empty[:20]))
    if not has_images(base):
        add("warning", "No Play screenshots in android/fastlane/metadata/android/<lang>/images "
                       "(upload them in the Play Console by hand)")
    add("notice", f"Play store texts checked: {len(langs)} language(s)")


def check_apple(repo):
    base = os.path.join(repo, "ios", "fastlane", "metadata")
    langs = lang_dirs(base)
    if not langs:
        add("notice", "No App Store texts (ios/fastlane/metadata) in this repository: skipped")
        return
    over, empty, urls = [], [], []
    dirs = [(lang, os.path.join(base, lang)) for lang in langs]
    later = os.path.join(repo, "ios", "fastlane", "release_notes_later")
    dirs += [(f"release_notes_later/{lang}", os.path.join(later, lang)) for lang in lang_dirs(later)]
    for lang, d in dirs:
        in_metadata = not lang.startswith("release_notes_later/")
        if in_metadata:
            for name in APPLE_REQUIRED:
                p = os.path.join(d, name)
                if not os.path.isfile(p) or not read(p):
                    empty.append(f"{lang}/{name}")
        for name, limit in APPLE_LIMITS.items():
            p = os.path.join(d, name)
            if os.path.isfile(p) and len(read(p)) > limit:
                over.append((os.path.relpath(p, repo), f"{len(read(p))} > {limit} characters"))
        for name, limit in APPLE_BYTE_LIMITS.items():
            p = os.path.join(d, name)
            if os.path.isfile(p) and len(read(p).encode("utf-8")) > limit:
                over.append((os.path.relpath(p, repo), f"{len(read(p).encode('utf-8'))} > {limit} bytes"))
        for name in URL_FILES:
            p = os.path.join(d, name)
            if os.path.isfile(p):
                value = read(p)
                if value and not value.startswith("https://"):
                    urls.append((os.path.relpath(p, repo), value))
    for path, why in over:
        add("error", f"App Store text too long: {why}", path)
    for path, value in urls:
        add("error", f"Store URL must use https: {value}", path)
    if empty:
        add("warning", "App Store text empty or missing: " + ", ".join(empty[:20]))
    if not has_images(os.path.join(repo, "ios", "fastlane", "screenshots")):
        add("warning", "No App Store screenshots in ios/fastlane/screenshots (upload them in App Store Connect "
                       "by hand)")
    add("notice", f"App Store texts checked: {len(langs)} language(s)")


def first(pattern, path):
    try:
        with open(path, encoding="utf-8") as f:
            m = re.search(pattern, f.read(), re.MULTILINE)
        return m.group(1) if m else None
    except OSError:
        return None


def check_versions(repo):
    gradle = os.path.join(repo, "android", "app", "build.gradle.kts")
    gen = os.path.join(repo, "ios", "tools", "gen_xcodeproj.py")
    yml = os.path.join(repo, "ios", "project.yml")
    sources = {
        "android/app/build.gradle.kts": (first(r'^\s*versionName\s*=\s*"([^"]+)"', gradle),
                                         first(r"^\s*versionCode\s*=\s*(\d+)", gradle)),
        "ios/tools/gen_xcodeproj.py": (first(r'^MARKETING_VERSION\s*=\s*"([^"]+)"', gen),
                                       first(r'^CURRENT_PROJECT_VERSION\s*=\s*"([^"]+)"', gen)),
    }
    if os.path.isfile(yml):
        sources["ios/project.yml"] = (first(r'^\s*MARKETING_VERSION:\s*"?([^"\s#]+)"?', yml),
                                      first(r'^\s*CURRENT_PROJECT_VERSION:\s*"?([^"\s#]+)"?', yml))
    missing = [k for k, v in sources.items() if None in v]
    if missing:
        add("error", "Version or build number not found in: " + ", ".join(missing))
    values = {v for k, v in sources.items() if None not in v}
    if len(values) > 1:
        add("error", "Version / build number differ between the platforms:\n"
            + "\n".join(f"- {k}: {v[0]} ({v[1]})" for k, v in sources.items()))
    elif values:
        name, code = next(iter(values))
        add("notice", f"Version {name} (build {code}) identical in " + ", ".join(sources))


def expected_tag_problem(ref, name, code):
    """Error text when a release tag run (refs/tags/v*) does not carry v<versionName>-<versionCode>, else None.
    Other refs (branches, pull requests, tags without the v prefix) are not release tags."""
    if not ref.startswith("refs/tags/v"):
        return None
    tag = ref[len("refs/tags/"):]
    if not name or not code:
        return f"Release tag {tag}: versionName / versionCode not found in android/app/build.gradle.kts"
    expected = f"v{name}-{code}"
    if tag != expected:
        return (f"Release tag {tag} does not match the project version: expected {expected} "
                "(versionName-versionCode of android/app/build.gradle.kts)")
    return None


def check_tag(repo):
    # REL3-R1: Tag-Lauf nur mit passendem Tag
    ref = os.environ.get("GITHUB_REF", "")
    if not ref.startswith("refs/tags/"):
        return
    gradle = os.path.join(repo, "android", "app", "build.gradle.kts")
    name = first(r'^\s*versionName\s*=\s*"([^"]+)"', gradle)
    code = first(r"^\s*versionCode\s*=\s*(\d+)", gradle)
    problem = expected_tag_problem(ref, name, code)
    if problem:
        add("error", problem, "android/app/build.gradle.kts")
    elif ref.startswith("refs/tags/v"):
        add("notice", f"Release tag {ref[len('refs/tags/'):]} matches versionName-versionCode")


def self_test():
    assert expected_tag_problem("refs/tags/v16.2.2-105", "16.2.2", "105") is None
    assert "expected v16.2.2-105" in expected_tag_problem("refs/tags/v16.2.2-104", "16.2.2", "105")
    assert expected_tag_problem("refs/tags/v16.2.3-105", "16.2.2", "105") is not None
    assert expected_tag_problem("refs/tags/v16.2.2-105-rc", "16.2.2", "105") is not None
    assert expected_tag_problem("refs/tags/v16.2.2-105", None, "105") is not None
    assert expected_tag_problem("refs/heads/main", "16.2.2", "105") is None
    assert expected_tag_problem("refs/pull/7/merge", "16.2.2", "105") is None
    assert expected_tag_problem("refs/tags/other", "16.2.2", "105") is None
    # REL4-02: Build-Nummer im Kopf von ROADMAP/TESTPLAN
    head = "# Liste\n\nStand: 22. Oktober 2026 · Version 16.2.2 (Build {} – steigt)\n"
    assert head_build_problem(head.format(105), "105") is None
    assert "Build 104" in head_build_problem(head.format(104), "105")
    assert head_build_problem("# Liste\n\nohne Nummer\n", "105") is not None
    # Nur der Kopf zählt: Verlauf weiter unten («Build 91: …») nicht
    assert head_build_problem(head.format(105) + "\n" * 10 + "Build 91: alt\n", "105") is None
    assert head_build_problem("# Liste\n" + "\n" * 10 + "Build 105\n", "105") is not None
    # Store guides: tag examples and archive name are checked, history lines are not
    play, apple = DOC_PATTERNS["android/docs/PLAY_STORE.md"], DOC_PATTERNS["ios/APP_STORE.md"]
    ok = ["Tag `v16.2.2-105` setzen", "Archiv: CryptoChecker-105.xcarchive", "Version **16.2.2** (Build 105)",
          "versionCode = 105", "`git tag v16.2.2-105 && git push origin v16.2.2-105`"]
    assert doc_mismatches(ok, apple, "16.2.2", "105") == [], doc_mismatches(ok, apple, "16.2.2", "105")
    assert doc_mismatches(ok, play, "16.2.2", "105") == [], doc_mismatches(ok, play, "16.2.2", "105")
    assert doc_mismatches(["Tag `v16.2.2-104` setzen"], play, "16.2.2", "105") == ["line 1: 104 (project: 105)"]
    assert doc_mismatches(["git tag v16.2.1-105"], apple, "16.2.2", "105") == ["line 1: 16.2.1 (project: 16.2.2)"]
    assert doc_mismatches(["x", "CryptoChecker-99.xcarchive"], apple, "16.2.2", "105") == ["line 2: 99 (project: 105)"]
    assert doc_mismatches(["v16.2.2-105-rc"], play, "16.2.2", "105") == []          # no word end after the build
    history = ["Build 91: Tag v16.2.0-91 und CryptoChecker-91.xcarchive", "- **Build 90:** versionCode 90",
               "früher Version 16.1.0 (Build 80)", "Frühere Tag-Form v16.0.0-70", "previously v15.0.0-60",
               "  Build 89: versionCode = 89", "- Previously: v16.1.0-80", "Tag v16.2.2-105 (früher v16.0.0-70)",
               "Tag v16.2.2-105 (previously v16.0.0-70)"]
    assert doc_mismatches(history, apple + play, "16.2.2", "105") == [], doc_mismatches(history, apple, "16.2.2", "105")
    assert is_history("Build 91: x", "105") and not is_history("Version 16.2.2 (Build 105)", "105")
    assert not is_history("Build 105", "105")
    # «Build N:» only for older builds: the current or a newer build is checked like any other line
    assert not is_history("Build 105: Tag v16.2.2-104", "105")
    assert doc_mismatches(["Build 105: Tag v16.2.2-104"], play, "16.2.2", "105") == ["line 1: 104 (project: 105)"]
    assert not is_history("- **Build 106:** versionCode 104", "105")
    assert is_history("- **Build 104:** versionCode 104", "105")
    # «früher» / «previously» elsewhere in the sentence does not hide a wrong version
    assert not is_history("Tag v16.2.2-104 setzen, wie früher", "105")
    assert doc_mismatches(["Tag v16.2.2-104 setzen, wie früher"], play, "16.2.2", "105") == ["line 1: 104 (project: 105)"]
    assert not is_history("Version 16.2.1 previously announced", "105")
    assert not is_history("Die frühere Tag-Form v16.0.0-70", "105")
    assert is_history("(früher v16.0.0-70)", "105") and is_history("**Früher:** v16.0.0-70", "105")
    print("release_readiness.py self-test passed")
    return 0


# Version mentions in the private store guides (not in the public repository): headers, versionCode / build
# fields, tag examples (v<version>-<build>) and the archive name. Only the current version is named like this.
# Lines that are recognisably history are skipped as a whole (is_history): they start with «Build N:» (also as a
# list item or in bold) with N below versionCode — «Build 105:» for the current build is checked — or carry
# «früher» / «previously» (also «frühere», «früheren») at the line start or inside parentheses («(früher …)»);
# the word elsewhere in a sentence does not hide the line. Other lines that name an old version («seit Build 47»
# without a version pattern) are not matched by the patterns anyway.
HISTORY_LINE = re.compile(r"^\s*(?:[-*+]\s+|\d+\.\s+)?\**Build (\d+)\**\s*:")
HISTORY_WORD = re.compile(r"^\s*(?:[-*+]\s+|\d+\.\s+)?\**(?:früher|previously)|\([^)]*\b(?:früher|previously)",
                          re.IGNORECASE)


def is_history(line, code):
    """True for a history line of a store guide (see HISTORY_LINE / HISTORY_WORD); code = versionCode."""
    m = HISTORY_LINE.match(line)
    if m and int(m.group(1)) < int(code):
        return True
    return bool(HISTORY_WORD.search(line))


def doc_mismatches(lines, patterns, name, code):
    """[«line N: value (project: expected)»] for version mentions that differ from name / code; history skipped."""
    wrong = []
    for no, line in enumerate(lines, 1):
        if is_history(line, code):
            continue
        for pattern in patterns:
            for m in re.finditer(pattern, line):
                value = m.group(1)
                expected = name if "." in value else code
                if value != expected:
                    wrong.append(f"line {no}: {value} (project: {expected})")
    return wrong
DOC_PATTERNS = {
    "android/docs/PLAY_STORE.md": (
        r"versionCode\W{0,4}(\d+)",
        r"Version \**(\d+\.\d+\.\d+)\b",
        r"versionName\W{0,4}(\d+\.\d+\.\d+)",
        r"\bv\d+\.\d+\.\d+-(\d+)\b",
        r"\bv(\d+\.\d+\.\d+)-\d+\b",
    ),
    "ios/APP_STORE.md": (
        r"Version \**\d+\.\d+\.\d+\**\s*(?:\(|,|/)\s*Build\s+(\d+)",
        r"Identity › Build\*\s*=\s*`(\d+)`",
        r"geprüften Build (\d+)",
        r"Version \**(\d+\.\d+\.\d+)\b",
        r"\bv\d+\.\d+\.\d+-(\d+)\b",
        r"\bv(\d+\.\d+\.\d+)-\d+\b",
        r"CryptoChecker-(\d+)\.xcarchive",
    ),
}


def check_store_docs(repo):
    gradle = os.path.join(repo, "android", "app", "build.gradle.kts")
    name = first(r'^\s*versionName\s*=\s*"([^"]+)"', gradle)
    code = first(r"^\s*versionCode\s*=\s*(\d+)", gradle)
    if not name or not code:
        return
    for rel, patterns in DOC_PATTERNS.items():
        path = os.path.join(repo, *rel.split("/"))
        if not os.path.isfile(path):
            # Sichtbar statt still: im öffentlichen Repository fehlen die privaten Anleitungen
            add("notice", f"{rel}: not checked (private file, not in this repository)")
            continue
        with open(path, encoding="utf-8") as f:
            wrong = doc_mismatches(f.read().splitlines(), patterns, name, code)
        if wrong:
            add("warning", f"{rel} names another version or build than the project ({name}, build {code}); "
                           "update the guide before the upload:\n- " + "\n- ".join(wrong[:12]), rel)
        else:
            add("notice", f"{rel}: version {name} / build {code} consistent with the project")


# Private working documents (not in the public repository) whose header names the current build.
HEAD_DOCS = ("ROADMAP.md", "TESTPLAN.md")
HEAD_LINES = 5


def head_build_problem(text, code):
    """Problem text when the header (first HEAD_LINES lines) names no or another «Build N» than versionCode, else None."""
    head = "\n".join(text.splitlines()[:HEAD_LINES])
    found = re.findall(r"\bBuild (\d+)", head)
    if not found:
        return f"no «Build N» in the first {HEAD_LINES} lines (project: build {code})"
    wrong = [n for n in found if n != code]
    if wrong:
        return f"header names Build {wrong[0]}, project is build {code}"
    return None


def check_head_docs(repo):
    # REL4-02: «Build-Nummer siehe Kopf» — der Kopf muss zum versionCode passen
    code = first(r"^\s*versionCode\s*=\s*(\d+)", os.path.join(repo, "android", "app", "build.gradle.kts"))
    if not code:
        return
    for rel in HEAD_DOCS:
        path = os.path.join(repo, rel)
        if not os.path.isfile(path):
            add("notice", f"{rel}: not checked (private file, not in this repository)")
            continue
        problem = head_build_problem(read(path), code)
        if problem:
            add("warning", f"{rel}: {problem}; update the header before the upload", rel)
        else:
            add("notice", f"{rel}: header build {code} consistent with the project")


def main():
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    repo, out = sys.argv[1:3]
    check_versions(repo)
    check_tag(repo)
    check_store_docs(repo)
    check_head_docs(repo)
    check_play(repo)
    check_apple(repo)
    with open(out, "a", encoding="utf-8") as f:
        for line in findings:
            f.write(line + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
