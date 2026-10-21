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
  warning required store text empty or missing (title / name / description), no screenshots
          android/docs/PLAY_STORE.md / ios/APP_STORE.md (private guides, only if present): version or
            versionCode / build number named there differs from android/app/build.gradle.kts

The folder review_information (contact data for the App Review) is never read or listed.
Usage: python3 -I release_readiness.py <repository root> <findings file>
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


# Version mentions in the private store guides (not in the public repository). Only the current
# version is named like this; history lines read «Build 91: …» and are not matched.
DOC_PATTERNS = {
    "android/docs/PLAY_STORE.md": (
        r"versionCode\W{0,4}(\d+)",
        r"Version \**(\d+\.\d+\.\d+)\b",
        r"versionName\W{0,4}(\d+\.\d+\.\d+)",
    ),
    "ios/APP_STORE.md": (
        r"Version \**\d+\.\d+\.\d+\**\s*(?:\(|,|/)\s*Build\s+(\d+)",
        r"Identity › Build\*\s*=\s*`(\d+)`",
        r"geprüften Build (\d+)",
        r"Version \**(\d+\.\d+\.\d+)\b",
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
            continue
        wrong = []
        with open(path, encoding="utf-8") as f:
            for no, line in enumerate(f, 1):
                for pattern in patterns:
                    for m in re.finditer(pattern, line):
                        value = m.group(1)
                        expected = name if "." in value else code
                        if value != expected:
                            wrong.append(f"line {no}: {value} (project: {expected})")
        if wrong:
            add("warning", f"{rel} names another version or build than the project ({name}, build {code}); "
                           "update the guide before the upload:\n- " + "\n- ".join(wrong[:12]), rel)
        else:
            add("notice", f"{rel}: version {name} / build {code} consistent with the project")


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    repo, out = sys.argv[1:3]
    check_versions(repo)
    check_store_docs(repo)
    check_play(repo)
    check_apple(repo)
    with open(out, "a", encoding="utf-8") as f:
        for line in findings:
            f.write(line + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
