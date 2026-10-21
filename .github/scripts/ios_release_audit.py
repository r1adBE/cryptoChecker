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

Usage: python3 -I ios_release_audit.py <subcommand> ...
"""
import json
import os
import plistlib
import re
import shutil
import subprocess
import sys

TARGETS = ("CryptoChecker", "CryptoCheckerWidgetsExtension")
SIGNING = re.compile(r"(development team|code ?sign|signing|provisioning profile|certificate|"
                     r"No Accounts|requires a provisioning)", re.IGNORECASE)
COMPILE_ERROR = re.compile(r"^(/[^:]+):(\d+):(?:\d+:)? error: (.*)$")
WARNING = re.compile(r"^(/[^:]+):(\d+):(?:\d+:)? warning: (.*)$")
# Ceiling for unique compiler warnings of the Release build: the count of Build 99 (94). Above it the build got noisier
# -> error, so new warnings are fixed instead of piling up; lower the ceiling whenever warnings are removed.
MAX_COMPILER_WARNINGS = 94

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

    add("notice", f"Release app {os.path.basename(app)}: version {ver} ({build}), background modes {modes}, "
                  f"BGTask ids {bg_ids}")
    flush(out)
    return 0


def main():
    args = sys.argv[1:]
    if args[:1] == ["settings"] and len(args) == 4:
        return cmd_settings(*args[1:])
    if args[:1] == ["build-log"] and len(args) == 4:
        return cmd_build_log(*args[1:])
    if args[:1] == ["app"] and len(args) == 7:
        return cmd_app(*args[1:])
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
