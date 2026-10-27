#!/usr/bin/env python3
"""Audit of the iOS Release build (ios.yml). Appends findings for annotate.py.

Subcommands:

  settings <showBuildSettings.json> <android build.gradle.kts> <findings>
      Release build settings of the app and the widget extension:
        error   DEBUG in SWIFT_ACTIVE_COMPILATION_CONDITIONS or DEBUG=1 in GCC_PREPROCESSOR_DEFINITIONS,
                ENABLE_TESTABILITY = YES, MARKETING_VERSION / CURRENT_PROJECT_VERSION != Android
        warning SWIFT_OPTIMIZATION_LEVEL != -O, VALIDATE_PRODUCT != YES,
                DEBUG_INFORMATION_FORMAT != dwarf-with-dsym, settings unreadable

  build-log <xcodebuild log> <exit status> <findings>
      Result of the unsigned device build: compile errors -> error; a failure with only signing
      errors (or none recognisable) -> warning (environment, not the app).

  app <App.app> <widget target name> <android build.gradle.kts> <ios source dir> <log or -> <findings>
      The built product:
        error   Info.plist version/build != Android or unresolved $(...), BGTaskScheduler identifiers
                without UIBackgroundModes fetch/processing, ITSAppUsesNonExemptEncryption missing,
                PrivacyInfo.xcprivacy missing in app or widget extension, widget extension missing,
                UI test hooks (UITesting / UITestMarket, Debug only) or XCTest linked into the binary
        warning BGTaskScheduler identifier not found in the Swift sources,
                ITSAppUsesNonExemptEncryption = true, compiler warnings (first 8)
        notice  compiler warning count (error above MAX_COMPILER_WARNINGS)
      Also runs the privacy check below against the manifest in the app bundle.

  privacy <ios source dir> <findings>
      SEC3-01: Required Reason APIs in the Swift sources of App, Shared and Widgets
      (system boot time, disk space, active keyboards, file timestamps, user defaults) against the
      NSPrivacyAccessedAPITypes of ios/App/PrivacyInfo.xcprivacy (used by app and widget):
        error   API used but its category missing in the manifest (App Store Connect rejects the
                upload with ITMS-91053), manifest missing or unreadable
        notice  categories found / declared

  --self-test
      Checks the privacy scan with sample sources and manifests (no files needed).

Usage: python3 -I ios_release_audit.py <subcommand> ...
"""
import json
import os
import plistlib
import re
import shutil
import subprocess
import sys

TARGETS = ("CryptoChecker", "CryptoCheckerWidgetsExtension", "CryptoCheckerWatch", "CryptoCheckerWatchWidgets")
SIGNING = re.compile(r"(development team|code ?sign|signing|provisioning profile|certificate|"
                     r"No Accounts|requires a provisioning)", re.IGNORECASE)
COMPILE_ERROR = re.compile(r"^(/[^:]+):(\d+):(?:\d+:)? error: (.*)$")
WARNING = re.compile(r"^(/[^:]+):(\d+):(?:\d+:)? warning: (.*)$")
# Ceiling for unique compiler warnings of the Release build: 0 (was 94 until build 115). Any new warning -> error, so it
# is fixed right away; the annotations list every warning.
MAX_COMPILER_WARNINGS = 0

# Required Reason APIs (Apple, «Describing use of required reason API»), as they appear in Swift: category -> patterns.
# Comments are removed before the scan, so a comment naming an API does not count (string-aware:
# "//" or "/*" inside a string literal opens no comment, SEC4-01).
REQUIRED_REASON_APIS = {
    "NSPrivacyAccessedAPICategorySystemBootTime": (
        r"\bsystemUptime\b", r"\bmach_absolute_time\b", r"kern\.boottime", r"\bKERN_BOOTTIME\b"),
    "NSPrivacyAccessedAPICategoryDiskSpace": (
        r"\bvolumeAvailableCapacity\w*", r"\bvolumeTotalCapacity\w*", r"\.systemFreeSize\b", r"\.systemSize\b",
        r"\bNSFileSystemFreeSize\b", r"\bNSFileSystemSize\b", r"\bstatv?fs\s*\(", r"\bfstatv?fs\s*\("),
    "NSPrivacyAccessedAPICategoryActiveKeyboards": (r"\bactiveInputModes\b",),
    "NSPrivacyAccessedAPICategoryFileTimestamp": (
        r"\.creationDate\b", r"\.modificationDate\b", r"\bcreationDateKey\b", r"\bcontentModificationDate\w*",
        r"\bfileModificationDate\b", r"\bf?getattrlist(?:bulk|at)?\s*\(", r"\bf?l?stat\s*\(", r"\bfstatat\s*\("),
    "NSPrivacyAccessedAPICategoryUserDefaults": (r"\bUserDefaults\b", r"\bNSUserDefaults\b"),
}
SOURCE_AREAS = ("App", "Shared", "Widgets", "WatchCore", "Watch", "WatchWidgets")

findings = []


def add(level, message, file="", line=""):
    findings.append("\t".join((level, file, str(line), message.replace("\t", " ").replace("\n", "\\n"))))


def flush(path):
    with open(path, "a", encoding="utf-8") as f:
        for line in findings:
            f.write(line + "\n")
    for line in findings:
        print(line.replace("\t", " | "))


def rel(path):
    ws = os.environ.get("GITHUB_WORKSPACE", "")
    return path[len(ws) + 1:] if ws and path.startswith(ws + "/") else path


def android_version(gradle):
    try:
        with open(gradle, encoding="utf-8") as f:
            text = f.read()
    except OSError:
        return None, None
    code = re.search(r"^\s*versionCode\s*=\s*(\d+)", text, re.MULTILINE)
    name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', text, re.MULTILINE)
    return (name.group(1) if name else None), (code.group(1) if code else None)


def cmd_settings(json_path, gradle, out):
    try:
        with open(json_path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError) as e:
        add("warning", f"Release build settings unreadable ({e}); settings audit skipped")
        flush(out)
        return 0
    a_name, a_code = android_version(gradle)
    seen = set()
    for entry in data:
        target = entry.get("target", "")
        if target not in TARGETS or target in seen:
            continue
        seen.add(target)
        s = entry.get("buildSettings", {})
        conds = s.get("SWIFT_ACTIVE_COMPILATION_CONDITIONS", "").split()
        if "DEBUG" in conds:
            add("error", f"{target} Release: SWIFT_ACTIVE_COMPILATION_CONDITIONS contains DEBUG "
                         "(debug-only code and UI test hooks would ship)")
        if re.search(r"(^|\s)DEBUG=1(\s|$)", s.get("GCC_PREPROCESSOR_DEFINITIONS", "")):
            add("error", f"{target} Release: GCC_PREPROCESSOR_DEFINITIONS contains DEBUG=1")
        if s.get("ENABLE_TESTABILITY", "NO") == "YES":
            add("error", f"{target} Release: ENABLE_TESTABILITY = YES")
        if s.get("SWIFT_OPTIMIZATION_LEVEL") != "-O":
            add("warning", f"{target} Release: SWIFT_OPTIMIZATION_LEVEL = {s.get('SWIFT_OPTIMIZATION_LEVEL')!r} "
                           "(expected -O, set in ios/tools/gen_xcodeproj.py)")
        if s.get("VALIDATE_PRODUCT") != "YES":
            add("warning", f"{target} Release: VALIDATE_PRODUCT = {s.get('VALIDATE_PRODUCT')!r} (expected YES)")
        if s.get("DEBUG_INFORMATION_FORMAT") != "dwarf-with-dsym":
            add("warning", f"{target} Release: DEBUG_INFORMATION_FORMAT = {s.get('DEBUG_INFORMATION_FORMAT')!r} "
                           "(expected dwarf-with-dsym, crash reports need the dSYM)")
        mv, pv = s.get("MARKETING_VERSION"), s.get("CURRENT_PROJECT_VERSION")
        if a_name and mv != a_name:
            add("error", f"{target}: MARKETING_VERSION {mv} != Android versionName {a_name}")
        if a_code and pv != a_code:
            add("error", f"{target}: CURRENT_PROJECT_VERSION {pv} != Android versionCode {a_code}")
    missing = [t for t in TARGETS if t not in seen]
    if missing:
        add("warning", "Release build settings: target(s) not in xcodebuild -showBuildSettings output: "
                       + ", ".join(missing))
    else:
        add("notice", "Release build settings checked for " + ", ".join(TARGETS))
    flush(out)
    return 0


def cmd_build_log(log, status, out):
    try:
        with open(log, encoding="utf-8", errors="replace") as f:
            lines = f.read().splitlines()
    except OSError:
        lines = []
    if status == "0":
        add("notice", "Release build for generic/platform=iOS (device, unsigned) succeeded")
        flush(out)
        return 0
    errors = sorted({l.strip() for l in lines if " error: " in l or l.startswith("error: ")})
    compile_errors = [e for e in errors if COMPILE_ERROR.match(e) and not SIGNING.search(e)]
    other = [e for e in errors if not SIGNING.search(e)]
    if compile_errors or other:
        for e in (compile_errors or other)[:8]:
            m = COMPILE_ERROR.match(e)
            if m:
                add("error", "Device Release build: " + m.group(3), rel(m.group(1)), m.group(2))
            else:
                add("error", "Device Release build: " + e)
        if len(compile_errors or other) > 8:
            add("error", f"Device Release build: {len(compile_errors or other) - 8} more error(s), see log")
    else:
        detail = "\n".join(errors[:4]) if errors else "no error line recognised (tail of the log in the step output)"
        add("warning", "Device Release build (unsigned) failed without compile errors, treated as a signing / "
                       "environment issue, not an app error:\n" + detail)
    flush(out)
    return 0


def read_plist(path):
    with open(path, "rb") as f:
        return plistlib.load(f)


def binary_hits(binary):
    """UI test hooks and XCTest in a Mach-O binary: type descriptor names and load command paths
    are plain strings, so a byte search works without nm/otool (stripped binaries too)."""
    hits = []
    try:
        with open(binary, "rb") as f:
            data = f.read()
    except OSError as e:
        return [f"binary unreadable: {e}"]
    for needle, label in ((b"UITestMarket", "UITestMarket (UI test exchange stub)"),
                          (b"\x00UITesting\x00", "UITesting (UI test hooks)"),
                          (b"/XCTest.framework/", "XCTest.framework"),
                          (b"libXCTestSwiftSupport", "libXCTestSwiftSupport"),
                          (b"XCUIAutomation.framework", "XCUIAutomation.framework")):
        if needle in data:
            hits.append(label)
    nm = shutil.which("nm")
    if nm:
        try:
            res = subprocess.run([nm, "-a", binary], capture_output=True, text=True, timeout=120)
            syms = [s for s in res.stdout.splitlines() if "UITestMarket" in s or "9UITesting" in s]
            if syms:
                hits.append("symbols: " + "; ".join(s.split()[-1] for s in syms[:3]))
        except (OSError, subprocess.SubprocessError):
            pass
    return hits


def strip_comments(text):
    """Swift comments (line, nested block) -> spaces, newlines kept (line numbers stay). SEC4-01: a
    small tokenizer: string literals (plain, multi-line, raw #"..."# with escapes and interpolation) are skipped,
    so "a//b" or "/0/*" no longer hides the code after it. REL5-R1: extended regex literals #/.../#
    (any number of #, multi-line when #/ ends the line, spaces/tabs/CR allowed) are skipped too, so #/a//b/# hides nothing. Bare /.../ literals
    are not recognised on purpose: telling them apart from division needs a parser - a wrong guess would blank real
    code. The app builds in Swift 6 mode, where bare /.../ literals are possible; it does not use any - keep using
    #/.../# or Regex(...) so this audit stays exact."""
    out = list(text)
    n = len(text)

    def blank(a, b):
        for k in range(a, b):
            if out[k] != "\n":
                out[k] = " "

    def string(i):
        hashes = 0
        while text[i] == "#":
            hashes += 1
            i += 1
        multi = text.startswith('"""', i)
        i += 3 if multi else 1
        close = ('"""' if multi else '"') + "#" * hashes
        escape = "\\" + "#" * hashes
        while i < n:
            if text.startswith(escape, i):
                j = i + len(escape)
                i = code(j + 1, True) if j < n and text[j] == "(" else j + 1
            elif text.startswith(close, i):
                return i + len(close)
            elif not multi and text[i] == "\n":
                return i
            else:
                i += 1
        return n

    def regex(i):
        hashes = 0
        while text[i] == "#":
            hashes += 1
            i += 1
        i += 1
        # Multi-line when only spaces/tabs (and a CR of CRLF) follow #/ up to the line end (REL6-R1)
        j = i
        while j < n and text[j] in " \t":
            j += 1
        if j < n and text[j] == "\r":
            j += 1
        multi = j < n and text[j] == "\n"
        close = "/" + "#" * hashes
        while i < n:
            if text[i] == "\\":
                i += 2
            elif text.startswith(close, i):
                return i + len(close)
            elif not multi and text[i] == "\n":
                return i
            else:
                i += 1
        return n

    def code(i, until_paren):
        depth = 0
        while i < n:
            if text.startswith("//", i):
                j = text.find("\n", i)
                j = n if j < 0 else j
                blank(i, j)
                i = j
            elif text.startswith("/*", i):
                nested, k = 0, i
                while k < n:
                    if text.startswith("/*", k):
                        nested, k = nested + 1, k + 2
                    elif text.startswith("*/", k):
                        nested, k = nested - 1, k + 2
                        if nested == 0:
                            break
                    else:
                        k += 1
                blank(i, k)
                i = k
            elif text[i] == '"' or (text[i] == "#" and re.match(r'#+"', text[i:i + 8])):
                i = string(i)
            elif text[i] == "#" and re.match(r"#+/", text[i:]):
                i = regex(i)
            else:
                if until_paren and text[i] == "(":
                    depth += 1
                elif until_paren and text[i] == ")":
                    if depth == 0:
                        return i + 1
                    depth -= 1
                i += 1
        return n

    code(0, False)
    return "".join(out)


def api_uses(sources):
    """{category: [(file, line, api)]} for (file, text) pairs; first hit per category and file."""
    uses = {}
    for path, text in sources:
        lines = strip_comments(text).splitlines()
        for category, patterns in REQUIRED_REASON_APIS.items():
            for no, line in enumerate(lines, 1):
                m = next((m for m in (re.search(p, line) for p in patterns) if m), None)
                if m:
                    uses.setdefault(category, []).append((path, no, m.group(0)))
                    break
    return uses


def declared_categories(manifest):
    """Categories in NSPrivacyAccessedAPITypes (manifest = parsed plist dict); a category without a reason
    does not count."""
    out = set()
    for entry in manifest.get("NSPrivacyAccessedAPITypes") or []:
        if isinstance(entry, dict) and entry.get("NSPrivacyAccessedAPITypeReasons"):
            out.add(str(entry.get("NSPrivacyAccessedAPIType", "")))
    return out


def privacy_check(sources, manifest, manifest_name):
    """Findings for used but undeclared categories (sources = [(file, text)], manifest = dict)."""
    uses = api_uses(sources)
    declared = declared_categories(manifest)
    for category in sorted(uses):
        if category not in declared:
            path, no, api = uses[category][0]
            more = f" (+{len(uses[category]) - 1} more file(s))" if len(uses[category]) > 1 else ""
            add("error", f"{api} is a Required Reason API ({category}) but {manifest_name} does not declare the "
                         f"category: add it with a reason or use another API{more} (upload fails with ITMS-91053)",
                rel(path), no)
    add("notice", "Required Reason APIs used: " + (", ".join(sorted(uses)) or "none")
        + "; declared: " + (", ".join(sorted(declared)) or "none"))


def swift_sources(src):
    sources = []
    for d in SOURCE_AREAS:
        for root, _, files in os.walk(os.path.join(src, d)):
            for name in sorted(files):
                if name.endswith(".swift"):
                    path = os.path.join(root, name)
                    try:
                        with open(path, encoding="utf-8", errors="replace") as f:
                            sources.append((path, f.read()))
                    except OSError:
                        pass
    return sources


def privacy_from_files(src, manifest_path):
    try:
        manifest = read_plist(manifest_path)
    except (OSError, plistlib.InvalidFileException) as e:
        add("error", f"Privacy manifest unreadable ({e}); Required Reason APIs not checked", rel(manifest_path))
        return
    privacy_check(swift_sources(src), manifest, os.path.basename(manifest_path))


def cmd_privacy(src, out):
    privacy_from_files(src, os.path.join(src, "App", "PrivacyInfo.xcprivacy"))
    flush(out)
    return 0


def self_test():
    """Privacy scan with samples: undeclared API -> error, declared or only in a comment -> none."""
    manifest = {"NSPrivacyAccessedAPITypes": [
        {"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryUserDefaults",
         "NSPrivacyAccessedAPITypeReasons": ["CA92.1"]},
        {"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryFileTimestamp",
         "NSPrivacyAccessedAPITypeReasons": ["C617.1"]},
        {"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryDiskSpace", "NSPrivacyAccessedAPITypeReasons": []},
    ]}
    cases = [
        ("let t = ProcessInfo.processInfo.systemUptime", ["NSPrivacyAccessedAPICategorySystemBootTime"]),
        ("let t = mach_absolute_time()", ["NSPrivacyAccessedAPICategorySystemBootTime"]),
        ("let v = try url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])",
         ["NSPrivacyAccessedAPICategoryDiskSpace"]),  # declared without reason -> still missing
        ("let m = UITextInputMode.activeInputModes", ["NSPrivacyAccessedAPICategoryActiveKeyboards"]),
        ("let d = attrs[.modificationDate] as? Date\nlet u = UserDefaults.standard", []),
        ("// statt `systemUptime` (seit Build 105)\nlet s = clock_gettime_nsec_np(CLOCK_MONOTONIC)", []),
        ("/* mach_absolute_time()\n systemUptime */ let x = 1", []),
        ('let u = "https://example.com" // systemUptime', []),
        # SEC4-01: comment markers inside strings hide nothing; more FileTimestamp functions
        ('let p = "a//b"; let t = ProcessInfo.processInfo.systemUptime', ["NSPrivacyAccessedAPICategorySystemBootTime"]),
        ('let a = ["/0/*"]\nlet t = ProcessInfo.processInfo.systemUptime\nlet b = "*/"',
         ["NSPrivacyAccessedAPICategorySystemBootTime"]),
        ('let s = "x\\(f("//"))y"; let t = ProcessInfo.processInfo.systemUptime', ["NSPrivacyAccessedAPICategorySystemBootTime"]),
        ('let r = #"a"//b"#; let t = mach_absolute_time()', ["NSPrivacyAccessedAPICategorySystemBootTime"]),
        ('let r = fstatat(fd, name, &st, 0)\nlet n = getattrlistbulk(fd, &list, buf, size, 0)',
         ["NSPrivacyAccessedAPICategoryFileTimestamp"], {"NSPrivacyAccessedAPITypes": []}),
        ("let n = fgetattrlist(fd, &list, buf, size, 0)", ["NSPrivacyAccessedAPICategoryFileTimestamp"],
         {"NSPrivacyAccessedAPITypes": []}),
        ("/* a /* nested */ systemUptime */ let x = 1", []),
        # REL5-R1: extended regex literals hide nothing; a regex in a comment or string stays inert
        ("let r = #/a//b/#; let t = mach_absolute_time()", ["NSPrivacyAccessedAPICategorySystemBootTime"]),
        ("let r = #/https?://x/#; let t = ProcessInfo.processInfo.systemUptime",
         ["NSPrivacyAccessedAPICategorySystemBootTime"]),
        ("let r = ##/a/#/*b/##\nlet t = mach_absolute_time()", ["NSPrivacyAccessedAPICategorySystemBootTime"]),
        ("let r = #/\n  a // b /* c\n  \\/# d\n/#\nlet t = mach_absolute_time()",
         ["NSPrivacyAccessedAPICategorySystemBootTime"]),
        ("let r = #/x\\/#\"/#; let t = mach_absolute_time()", ["NSPrivacyAccessedAPICategorySystemBootTime"]),
        ("// docs/#/en-us mach_absolute_time()\nlet x = 1", []),
        ('let u = "docs/#/en"; let t = mach_absolute_time()', ["NSPrivacyAccessedAPICategorySystemBootTime"]),
        # REL6-R1: multi-line also with spaces/tabs or CRLF after #/
        ("let r = #/ \t\n a /* c\n/#\nlet t = mach_absolute_time()", ["NSPrivacyAccessedAPICategorySystemBootTime"]),
        ("let r = #/\r\n a /* c\r\n/#\r\nlet t = mach_absolute_time()", ["NSPrivacyAccessedAPICategorySystemBootTime"]),
        ("let r = #/ \r\n a /* c\n/#\nlet t = mach_absolute_time()", ["NSPrivacyAccessedAPICategorySystemBootTime"]),
        ("let r = #/ a /#\nlet t = mach_absolute_time()", ["NSPrivacyAccessedAPICategorySystemBootTime"]),
    ]
    for case in cases:
        text, expected = case[0], case[1]
        findings.clear()
        privacy_check([("/src/App/X.swift", text)], case[2] if len(case) > 2 else manifest, "PrivacyInfo.xcprivacy")
        errors = [f for f in findings if f.startswith("error\t")]
        got = sorted(c for c in REQUIRED_REASON_APIS if any(f"({c})" in e for e in errors))
        assert got == expected, (text, got, errors)
    findings.clear()
    parts = warning_details(["/w/ios/App/A.swift:3:5: warning: x" + str(i) for i in range(400)], lambda p: p[3:],
                            max_chars=200, max_parts=3)
    assert len(parts) == 3 and parts[0][0] == "ios/App/A.swift:3 x0" and parts[-1][-1].startswith("..."), parts
    print("ios_release_audit.py self-test passed")
    return 0


def cmd_app(app, widget, gradle, src, log, out):
    if not os.path.isdir(app):
        add("error", f"Release app not found: {app}")
        flush(out)
        return 0
    a_name, a_code = android_version(gradle)
    try:
        info = read_plist(os.path.join(app, "Info.plist"))
    except (OSError, plistlib.InvalidFileException) as e:
        add("error", f"Info.plist of the Release app unreadable: {e}")
        info = {}
    ver, build = str(info.get("CFBundleShortVersionString", "")), str(info.get("CFBundleVersion", ""))
    if "$(" in ver or "$(" in build or not ver or not build:
        add("error", f"Info.plist: version/build not resolved ({ver!r} / {build!r})")
    if a_name and ver != a_name:
        add("error", f"iOS version {ver} != Android versionName {a_name}")
    if a_code and build != a_code:
        add("error", f"iOS build {build} != Android versionCode {a_code}")

    bg_ids = info.get("BGTaskSchedulerPermittedIdentifiers") or []
    modes = info.get("UIBackgroundModes") or []
    if bg_ids and not ({"fetch", "processing"} & set(modes)):
        add("error", f"Info.plist: BGTaskSchedulerPermittedIdentifiers {bg_ids} but UIBackgroundModes {modes} "
                     "has neither fetch nor processing (submit() fails at run time)")
    if bg_ids:
        sources = []
        for d in ("App", "Shared", "Widgets"):
            for root, _, files in os.walk(os.path.join(src, d)):
                for name in files:
                    if name.endswith(".swift"):
                        try:
                            with open(os.path.join(root, name), encoding="utf-8", errors="replace") as f:
                                sources.append(f.read())
                        except OSError:
                            pass
        text = "\n".join(sources)
        unused = [i for i in bg_ids if i not in text]
        if unused:
            add("warning", "BGTaskSchedulerPermittedIdentifiers not found in the Swift sources: " + ", ".join(unused))
    if "ITSAppUsesNonExemptEncryption" not in info:
        add("error", "Info.plist: ITSAppUsesNonExemptEncryption missing (export compliance question on every upload)")
    elif info.get("ITSAppUsesNonExemptEncryption") is True:
        add("warning", "Info.plist: ITSAppUsesNonExemptEncryption = true (export compliance documents needed)")

    if not os.path.isfile(os.path.join(app, "PrivacyInfo.xcprivacy")):
        add("error", "PrivacyInfo.xcprivacy missing in the app bundle")
    else:
        # SEC3-01: required-reason APIs in the sources against the manifest in the bundle
        privacy_from_files(src, os.path.join(app, "PrivacyInfo.xcprivacy"))
    appex = os.path.join(app, "PlugIns", f"{widget}.appex")
    binaries = [os.path.join(app, str(info.get("CFBundleExecutable", "CryptoChecker")))]
    if not os.path.isdir(appex):
        found = os.listdir(os.path.join(app, "PlugIns")) if os.path.isdir(os.path.join(app, "PlugIns")) else []
        add("error", f"Widget extension {widget}.appex not embedded in the app (PlugIns: {found or 'none'})")
    else:
        if not os.path.isfile(os.path.join(appex, "PrivacyInfo.xcprivacy")):
            add("error", f"PrivacyInfo.xcprivacy missing in {widget}.appex")
        try:
            winfo = read_plist(os.path.join(appex, "Info.plist"))
            binaries.append(os.path.join(appex, str(winfo.get("CFBundleExecutable", widget))))
            wv, wb = str(winfo.get("CFBundleShortVersionString", "")), str(winfo.get("CFBundleVersion", ""))
            if (wv, wb) != (ver, build):
                add("warning", f"{widget}: version/build {wv}/{wb} != app {ver}/{build} (App Store Connect "
                               "warns ITMS-90473)")
        except (OSError, plistlib.InvalidFileException) as e:
            add("error", f"Info.plist of {widget}.appex unreadable: {e}")

    # Apple-Watch-App (build 155): Watch/CryptoCheckerWatch.app with its complication extension, each with a privacy
    # manifest and the same version/build as the app
    watch = os.path.join(app, "Watch", "CryptoCheckerWatch.app")
    if not os.path.isdir(watch):
        add("error", "Watch app CryptoCheckerWatch.app not embedded in the app (Watch/)")
    else:
        for bundle, name in ((watch, "CryptoCheckerWatch"),
                             (os.path.join(watch, "PlugIns", "CryptoCheckerWatchWidgets.appex"), "CryptoCheckerWatchWidgets")):
            if not os.path.isdir(bundle):
                add("error", f"{name} missing in the app bundle")
                continue
            if not os.path.isfile(os.path.join(bundle, "PrivacyInfo.xcprivacy")):
                add("error", f"PrivacyInfo.xcprivacy missing in {name}")
            try:
                binfo = read_plist(os.path.join(bundle, "Info.plist"))
                binaries.append(os.path.join(bundle, str(binfo.get("CFBundleExecutable", name))))
                bv, bb = str(binfo.get("CFBundleShortVersionString", "")), str(binfo.get("CFBundleVersion", ""))
                if (bv, bb) != (ver, build):
                    add("warning", f"{name}: version/build {bv}/{bb} != app {ver}/{build}")
            except (OSError, plistlib.InvalidFileException) as e:
                add("error", f"Info.plist of {name} unreadable: {e}")

    for b in binaries:
        hits = binary_hits(b)
        if hits:
            add("error", f"{os.path.basename(b)} (Release) contains test code: " + ", ".join(hits))
    if os.path.isdir(os.path.join(app, "Frameworks", "XCTest.framework")):
        add("error", "XCTest.framework embedded in the Release app")

    if log and log != "-":
        try:
            with open(log, encoding="utf-8", errors="replace") as f:
                warns = sorted({l.strip() for l in f if WARNING.match(l.strip())})
        except OSError:
            warns = []
        if len(warns) > MAX_COMPILER_WARNINGS:
            add("error", f"Release build: {len(warns)} unique compiler warning(s), more than the ceiling of "
                         f"{MAX_COMPILER_WARNINGS} (MAX_COMPILER_WARNINGS in ios_release_audit.py): fix the new ones")
        else:
            add("notice", f"Release build: {len(warns)} unique compiler warning(s) (ceiling {MAX_COMPILER_WARNINGS})")
        for w in warns[:8]:
            m = WARNING.match(w)
            add("warning", m.group(3), rel(m.group(1)), m.group(2))
        for n, part in enumerate(warning_details(warns, rel), 1):
            add("notice", f"Release warnings {n}: " + "\n".join(part))

    add("notice", f"Release app {os.path.basename(app)}: version {ver} ({build}), background modes {modes}, "
                  f"BGTask ids {bg_ids}")
    flush(out)
    return 0


def warning_details(warns, rel_path, max_chars=3800, max_parts=5):
    """All unique warnings as «file:line message» rows, split into at most max_parts notices under GitHub's
    4096-character cut (so the full list can be read from the check annotations)."""
    rows = []
    for w in warns:
        m = WARNING.match(w)
        if m:
            rows.append(f"{rel_path(m.group(1))}:{m.group(2)} {m.group(3)[:160]}")
    parts, cur, size = [], [], 0
    for row in rows:
        if cur and size + len(row) + 1 > max_chars:
            parts.append(cur)
            cur, size = [], 0
        cur.append(row)
        size += len(row) + 1
    if cur:
        parts.append(cur)
    if len(parts) > max_parts:
        parts = parts[:max_parts]
        parts[-1].append(f"... more in the build log ({len(rows)} in total)")
    return parts


def main():
    args = sys.argv[1:]
    if args[:1] == ["settings"] and len(args) == 4:
        return cmd_settings(*args[1:])
    if args[:1] == ["build-log"] and len(args) == 4:
        return cmd_build_log(*args[1:])
    if args[:1] == ["app"] and len(args) == 7:
        return cmd_app(*args[1:])
    if args[:1] == ["privacy"] and len(args) == 3:
        return cmd_privacy(*args[1:])
    if args == ["--self-test"]:
        return self_test()
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
