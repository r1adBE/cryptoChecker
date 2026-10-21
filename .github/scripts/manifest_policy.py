#!/usr/bin/env python3
"""Policy check of the merged (packaged) release manifest of the Android app.

Appends findings for annotate.py (see there) and writes a plain-text summary of the manifest
(permissions, components, exported, foregroundServiceType) for the release evidence.

Errors (each one blocks a store release):
  - forbidden permissions: USE_EXACT_ALARM, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, QUERY_ALL_PACKAGES,
    AD_ID, MANAGE_EXTERNAL_STORAGE, SYSTEM_ALERT_WINDOW and every typed FOREGROUND_SERVICE_*
    (the app declares no foreground service type; Play would demand a declaration per type)
  - any permission outside the allow-list EXPECTED (the 11 permissions of build 94, each justified in
    android/docs/PLAY_STORE.md §6): a new permission must be a conscious decision (Data safety,
    store text), not a side effect of a library update
  - a service with android:foregroundServiceType
  - application debuggable, testOnly or usesCleartextTraffic="true"
  - an exported component without a permission, except MainActivity, BootReceiver and the
    launcher activity-aliases (MAIN/LAUNCHER)

Usage: python3 -I manifest_policy.py <AndroidManifest.xml> <findings file> <summary file>
"""
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"

FORBIDDEN = {
    "android.permission.USE_EXACT_ALARM": "only for alarm clock / calendar apps (Play policy)",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS": "Play policy: needs an exemption",
    "android.permission.QUERY_ALL_PACKAGES": "Play policy: needs a declaration",
    "com.google.android.gms.permission.AD_ID": "app has no ads; Data safety says no advertising ID",
    "android.permission.MANAGE_EXTERNAL_STORAGE": "Play policy: All files access",
    "android.permission.SYSTEM_ALERT_WINDOW": "not used by the app",
}
# Allow-list: every permission of the release manifest, with the reason (also in PLAY_STORE.md §6).
# "{package}" is replaced by the package (the AndroidX core library declares that permission itself).
EXPECTED = {
    "android.permission.ACCESS_NETWORK_STATE": "online check before fetching prices",
    "android.permission.FOREGROUND_SERVICE": "expedited price update runs briefly as untyped foreground service up to Android 11",
    "android.permission.INTERNET": "fetching prices from the exchanges",
    "android.permission.POST_NOTIFICATIONS": "price alarms and price notifications (Android 13+)",
    "android.permission.RECEIVE_BOOT_COMPLETED": "re-plan alarms and background updates after a reboot",
    "android.permission.SCHEDULE_EXACT_ALARM": "opt-in «check punctually» exact alarm (user-granted, revocable)",
    "android.permission.USE_BIOMETRIC": "app lock with biometrics (androidx.biometric)",
    "android.permission.USE_FINGERPRINT": "app lock on Android 8/9 (androidx.biometric)",
    "android.permission.VIBRATE": "vibration of alarm notifications",
    "android.permission.WAKE_LOCK": "WorkManager keeps the CPU awake while a job runs",
    "{package}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION": "AndroidX core: non-exported dynamic receivers",
}
# Exported on purpose: the start activity (target of the launcher aliases, also opened by
# notifications and widgets) and the boot receiver (protected system broadcasts only).
ALLOWED_EXPORTED = {"com.cryptochecker.app.ui.MainActivity", "com.cryptochecker.app.work.BootReceiver"}
COMPONENTS = ("activity", "activity-alias", "service", "receiver", "provider")


def full_name(name, package):
    if not name:
        return "?"
    if name.startswith("."):
        return package + name
    if "." not in name:
        return package + "." + name
    return name


def is_launcher(comp):
    for f in comp.findall("intent-filter"):
        actions = {a.get(A + "name") for a in f.findall("action")}
        cats = {c.get(A + "name") for c in f.findall("category")}
        if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in cats:
            return True
    return False


def is_true(value):
    return (value or "").strip().lower() == "true"


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    manifest, findings_path, summary_path = sys.argv[1:4]
    findings = []

    def add(level, message):
        findings.append(f"{level}\t\t\t{message.replace(chr(10), chr(92) + 'n')}")

    try:
        root = ET.parse(manifest).getroot()
    except (ET.ParseError, OSError) as e:
        add("error", f"Merged release manifest unreadable ({manifest}): {e}")
        root = None

    summary = [f"manifest: {manifest}"]
    if root is not None:
        package = root.get("package", "")
        app = root.find("application")
        sdk = root.find("uses-sdk")
        summary.append(f"package={package} versionCode={root.get(A + 'versionCode')} "
                       f"versionName={root.get(A + 'versionName')} "
                       f"minSdk={sdk.get(A + 'minSdkVersion') if sdk is not None else '?'} "
                       f"targetSdk={sdk.get(A + 'targetSdkVersion') if sdk is not None else '?'}")

        perms = sorted({p.get(A + "name", "?") for tag in ("uses-permission", "uses-permission-sdk-23")
                        for p in root.findall(tag)})
        summary.append("permissions:")
        summary += [f"  {p}" for p in perms]
        bad = []
        for p in perms:
            if p in FORBIDDEN:
                bad.append(f"{p} ({FORBIDDEN[p]})")
            elif p.startswith("android.permission.FOREGROUND_SERVICE_"):
                bad.append(f"{p} (typed foreground service permission: needs a Play Console declaration)")
        expected = {k.replace("{package}", package) for k in EXPECTED}
        unexpected = [p for p in perms if p not in expected and p not in FORBIDDEN
                      and not p.startswith("android.permission.FOREGROUND_SERVICE_")]
        if unexpected:
            add("error", "Unexpected permission(s) in the merged release manifest (not in the allow-list of "
                         "manifest_policy.py). Find the source in app/build/outputs/logs/"
                         "manifest-merger-release-report.txt; remove it (tools:node=\"remove\") or, if it is "
                         "really needed, add it to EXPECTED with a reason, to PLAY_STORE.md §6 and check the "
                         "Data safety form:\n- " + "\n- ".join(unexpected))
        if bad:
            add("error", "Forbidden permission(s) in the merged release manifest (check which library adds "
                         "them: app/build/outputs/logs/manifest-merger-release-report.txt):\n- " + "\n- ".join(bad))

        if app is None:
            add("error", "Merged release manifest has no <application> element")
        else:
            for attr, why in (("debuggable", "debuggable release build"),
                              ("testOnly", "testOnly APK cannot be uploaded to Play"),
                              ("usesCleartextTraffic", "cleartext HTTP allowed")):
                if is_true(app.get(A + attr)):
                    add("error", f"application android:{attr}=\"true\" in the release manifest ({why})")
            if app.get(A + "networkSecurityConfig"):
                add("notice", "android:networkSecurityConfig is set: check cleartextTrafficPermitted in "
                              f"{app.get(A + 'networkSecurityConfig')} by hand")
            app_permission = app.get(A + "permission")

            exported_open, fgs_types, exported_list = [], [], []
            summary.append("components:")
            for tag in COMPONENTS:
                for comp in app.findall(tag):
                    name = full_name(comp.get(A + "name"), package)
                    has_filter = comp.find("intent-filter") is not None
                    exported_attr = comp.get(A + "exported")
                    # Without the attribute: exported if it has an intent filter (providers: not exported)
                    exported = is_true(exported_attr) if exported_attr is not None else (
                        has_filter and tag != "provider")
                    permission = comp.get(A + "permission") or app_permission
                    if tag == "provider":
                        permission = permission or (comp.get(A + "readPermission") and comp.get(A + "writePermission"))
                    fgs = comp.get(A + "foregroundServiceType")
                    enabled = comp.get(A + "enabled", "true")
                    summary.append(f"  {tag} {name} exported={exported} permission={permission or '-'} "
                                   f"enabled={enabled}" + (f" foregroundServiceType={fgs}" if fgs else ""))
                    if fgs:
                        fgs_types.append(f"{name}: {fgs}")
                    if exported:
                        exported_list.append(name)
                        allowed = name in ALLOWED_EXPORTED or (tag == "activity-alias" and is_launcher(comp))
                        if not permission and not allowed:
                            exported_open.append(f"{tag} {name}")
            if exported_open:
                add("error", "Exported component(s) without permission in the release manifest (set "
                             "android:exported=\"false\" or protect them):\n- " + "\n- ".join(exported_open))
            if fgs_types:
                add("error", "foregroundServiceType declared in the release manifest (the app declares no "
                             "foreground service; Play needs a declaration per type):\n- " + "\n- ".join(fgs_types))
            add("notice", f"Release manifest: {len(perms)} permission(s): "
                          + ", ".join(p.split(".")[-1] for p in perms)
                          + f"\nExported: {', '.join(n.split('.')[-1] for n in exported_list) or '-'}")

    with open(findings_path, "a", encoding="utf-8") as f:
        for line in findings:
            f.write(line + "\n")
    with open(summary_path, "w", encoding="utf-8") as f:
        f.write("\n".join(summary) + "\n")
    print("\n".join(summary))
    return 0


if __name__ == "__main__":
    sys.exit(main())
