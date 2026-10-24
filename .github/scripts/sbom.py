#!/usr/bin/env python3
"""Software bill of materials (CycloneDX 1.5 JSON) for the Android and the iOS app.

Android: every Maven coordinate the release build ships (configuration «releaseRuntimeClasspath» in the committed
lock files android/<module>/gradle.lockfile, the exact versions Gradle enforces) with its SHA-256 from
android/gradle/verification-metadata.xml (the checksums Gradle verifies on every build) and a package URL.
iOS: the app uses no third-party packages (no Swift Package Manager, CocoaPods or Carthage) - the iOS document lists
only the app itself, and the script fails if a package manifest appears, so the statement cannot go stale.

Deterministic: no timestamp, no random serial number - the same sources give byte-identical files (the
reproducible-build check compares them too). No network, no third-party code.

Usage: python3 -I sbom.py <repo root> <output dir>     writes sbom-android.cdx.json and sbom-ios.cdx.json
       python3 -I sbom.py --self-test
"""
import json
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET

CONFIGURATION = "releaseRuntimeClasspath"
NS = "{https://schema.gradle.org/dependency-verification}"
APP_NAME = "Crypto Checker"
LICENSE = "MIT"
# Package managers that would bring third-party code into the iOS app
IOS_PACKAGE_FILES = ("Package.swift", "Package.resolved", "Podfile", "Podfile.lock", "Cartfile", "Cartfile.resolved")


class SbomError(Exception):
    pass


def lock_coordinates(lockfile_text, configuration=CONFIGURATION):
    """group:name:version entries of the lock file that belong to configuration (sorted, unique)."""
    out = set()
    for line in lockfile_text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        coordinate, configs = line.split("=", 1)
        if configuration not in configs.split(","):
            continue
        parts = coordinate.split(":")
        if len(parts) != 3 or not all(parts):
            raise SbomError(f"unexpected lock line: {line}")
        out.add(tuple(parts))
    return sorted(out)


def verification_hashes(metadata_text):
    """{(group, name, version): {artifact file: sha256}} from verification-metadata.xml."""
    root = ET.fromstring(metadata_text.encode("utf-8"))
    hashes = {}
    for component in root.iter(NS + "component"):
        key = (component.get("group"), component.get("name"), component.get("version"))
        files = {}
        for artifact in component.findall(NS + "artifact"):
            sha = artifact.find(NS + "sha256")
            if sha is not None and sha.get("value"):
                files[artifact.get("name")] = sha.get("value")
        hashes[key] = files
    return hashes


def shipped_hash(files, name, version):
    """SHA-256 of the binary that ends up in the app: .aar first, then the .jar (not -sources/-javadoc). The usual file
    name is <name>-<version>.<ext>; Gradle module metadata may name the variant file differently (Compose «ui.aar»,
    DataStore «datastore-release.aar»), then the only such binary of the component counts."""
    for ext in (".aar", ".jar"):
        value = files.get(f"{name}-{version}{ext}")
        if value:
            return value
    for ext in (".aar", ".jar"):
        binaries = sorted(f for f in files if f.endswith(ext) and not f.endswith(("-sources.jar", "-javadoc.jar")))
        if len(binaries) == 1:
            return files[binaries[0]]
    return None


def version_info(build_gradle_text):
    name = re.search(r'versionName\s*=\s*"([^"]+)"', build_gradle_text)
    code = re.search(r"versionCode\s*=\s*(\d+)", build_gradle_text)
    if not name or not code:
        raise SbomError("versionName/versionCode not found in android/app/build.gradle.kts")
    return name.group(1), int(code.group(1))


def document(app_ref, app_version, platform, components):
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "bom-ref": app_ref,
                "name": APP_NAME,
                "version": app_version,
                "description": f"{APP_NAME} for {platform}",
                "licenses": [{"license": {"id": LICENSE}}],
            },
        },
        "components": components,
        "dependencies": [{"ref": app_ref, "dependsOn": [c["bom-ref"] for c in components]}],
    }


def android_document(root):
    android = os.path.join(root, "android")
    with open(os.path.join(android, "app", "build.gradle.kts"), encoding="utf-8") as f:
        version_name, version_code = version_info(f.read())
    with open(os.path.join(android, "gradle", "verification-metadata.xml"), encoding="utf-8") as f:
        hashes = verification_hashes(f.read())
    coordinates = set()
    for module in sorted(os.listdir(android)):
        lock = os.path.join(android, module, "gradle.lockfile")
        if module != "baselineprofile" and os.path.isfile(lock):  # the profile generator is not shipped
            with open(lock, encoding="utf-8") as f:
                coordinates.update(lock_coordinates(f.read()))
    if not coordinates:
        raise SbomError(f"no {CONFIGURATION} entries in the lock files")
    components, missing = [], []
    for group, name, version in sorted(coordinates):
        purl = f"pkg:maven/{group}/{name}@{version}"
        component = {"type": "library", "bom-ref": purl, "group": group, "name": name, "version": version,
                     "purl": purl}
        files = hashes.get((group, name, version))
        sha = shipped_hash(files, name, version) if files else None
        if sha:
            component["hashes"] = [{"alg": "SHA-256", "content": sha}]
        elif files is None:
            missing.append(f"{group}:{name}:{version}")
        components.append(component)
    if missing:
        raise SbomError("not in verification-metadata.xml (run the supply chain update): " + ", ".join(missing))
    version = f"{version_name} ({version_code})"
    return document("pkg:android/com.cryptochecker.app@" + version_name, version, "Android", components)


def ios_document(root):
    ios = os.path.join(root, "ios")
    found = []
    for base, dirs, files in os.walk(ios):
        dirs[:] = [d for d in dirs if d not in (".build", "DerivedData")]
        found += [os.path.relpath(os.path.join(base, f), root) for f in files if f in IOS_PACKAGE_FILES]
    if found:
        raise SbomError("iOS package manifest found, list its packages in the iOS SBOM: " + ", ".join(sorted(found)))
    with open(os.path.join(ios, "project.yml"), encoding="utf-8") as f:
        text = f.read()
    marketing = re.search(r'MARKETING_VERSION:\s*"?([\d.]+)', text)
    build = re.search(r'CURRENT_PROJECT_VERSION:\s*"?(\d+)', text)
    if not marketing or not build:
        raise SbomError("MARKETING_VERSION/CURRENT_PROJECT_VERSION not found in ios/project.yml")
    version = f"{marketing.group(1)} ({build.group(1)})"
    return document("pkg:ios/com.cryptochecker.app@" + marketing.group(1), version, "iOS", [])


def write(path, doc):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, indent=2, sort_keys=True, ensure_ascii=False)
        f.write("\n")


def main(argv):
    if argv == ["--self-test"]:
        self_test()
        print("sbom self-test ok")
        return 0
    if len(argv) != 2:
        print(__doc__.strip().splitlines()[-2], file=sys.stderr)
        return 2
    root, out = argv
    try:
        android, ios = android_document(root), ios_document(root)
    except (SbomError, OSError, ET.ParseError) as e:
        print(f"::error title=SBOM::{e}")
        return 1
    os.makedirs(out, exist_ok=True)
    write(os.path.join(out, "sbom-android.cdx.json"), android)
    write(os.path.join(out, "sbom-ios.cdx.json"), ios)
    print(f"::notice title=SBOM::Android {android['metadata']['component']['version']}: "
          f"{len(android['components'])} components, "
          f"{sum(1 for c in android['components'] if 'hashes' in c)} with SHA-256 (the rest have no binary of their own: "
          f"Kotlin multiplatform umbrella modules, BOMs); iOS: no third-party packages")
    return 0


def self_test():
    lock = ("# comment\n"
            "a.b:lib:1.0=debugRuntimeClasspath,releaseRuntimeClasspath\n"
            "a.b:testonly:2.0=debugUnitTestRuntimeClasspath\n"
            "c.d:other:3.1=releaseRuntimeClasspath\n"
            "empty=\n")
    assert lock_coordinates(lock) == [("a.b", "lib", "1.0"), ("c.d", "other", "3.1")]
    meta = ('<verification-metadata xmlns="https://schema.gradle.org/dependency-verification"><components>'
            '<component group="a.b" name="lib" version="1.0">'
            '<artifact name="lib-1.0.aar"><sha256 value="aa"/></artifact>'
            '<artifact name="lib-1.0.module"><sha256 value="bb"/></artifact></component>'
            '<component group="c.d" name="other" version="3.1">'
            '<artifact name="other-3.1-sources.jar"><sha256 value="cc"/></artifact>'
            '<artifact name="other-3.1.jar"><sha256 value="dd"/></artifact></component>'
            '</components></verification-metadata>')
    hashes = verification_hashes(meta)
    assert shipped_hash(hashes[("a.b", "lib", "1.0")], "lib", "1.0") == "aa"
    assert shipped_hash(hashes[("c.d", "other", "3.1")], "other", "3.1") == "dd"
    # Variant file named differently (Gradle module metadata): the single binary of the component counts
    assert shipped_hash({"ui.aar": "ee", "ui-1.0.module": "ff"}, "ui-android", "1.0") == "ee"
    assert shipped_hash({"a-release.aar": "11", "b-release.aar": "22"}, "x", "1") is None
    assert version_info('versionCode = 118\n        versionName = "16.2.2"') == ("16.2.2", 118)
    with tempfile.TemporaryDirectory() as d:
        os.makedirs(os.path.join(d, "android", "app"))
        os.makedirs(os.path.join(d, "android", "gradle"))
        os.makedirs(os.path.join(d, "android", "baselineprofile"))
        os.makedirs(os.path.join(d, "ios"))
        with open(os.path.join(d, "android", "app", "build.gradle.kts"), "w") as f:
            f.write('versionCode = 7\nversionName = "1.2"\n')
        with open(os.path.join(d, "android", "app", "gradle.lockfile"), "w") as f:
            f.write(lock)
        with open(os.path.join(d, "android", "baselineprofile", "gradle.lockfile"), "w") as f:
            f.write("x.y:notshipped:1=releaseRuntimeClasspath\n")
        with open(os.path.join(d, "android", "gradle", "verification-metadata.xml"), "w") as f:
            f.write(meta)
        with open(os.path.join(d, "ios", "project.yml"), "w") as f:
            f.write('    MARKETING_VERSION: "1.2"\n    CURRENT_PROJECT_VERSION: "7"\n')
        doc = android_document(d)
        assert [c["purl"] for c in doc["components"]] == ["pkg:maven/a.b/lib@1.0", "pkg:maven/c.d/other@3.1"]
        assert doc["components"][0]["hashes"] == [{"alg": "SHA-256", "content": "aa"}]
        assert doc["metadata"]["component"]["version"] == "1.2 (7)" and "serialNumber" not in doc
        assert "timestamp" not in doc["metadata"]
        out1, out2 = os.path.join(d, "o1"), os.path.join(d, "o2")
        assert main([d, out1]) == 0 and main([d, out2]) == 0
        for name in ("sbom-android.cdx.json", "sbom-ios.cdx.json"):
            assert open(os.path.join(out1, name), "rb").read() == open(os.path.join(out2, name), "rb").read()
        assert json.load(open(os.path.join(out1, "sbom-ios.cdx.json")))["components"] == []
        # A shipped coordinate without checksum entry fails (verification metadata out of date)
        with open(os.path.join(d, "android", "app", "gradle.lockfile"), "a") as f:
            f.write("e.f:unknown:9=releaseRuntimeClasspath\n")
        try:
            android_document(d)
            raise AssertionError("missing checksum not detected")
        except SbomError as e:
            assert "e.f:unknown:9" in str(e)
        # iOS package manager appears: fail instead of claiming «no third-party packages»
        open(os.path.join(d, "ios", "Package.resolved"), "w").close()
        try:
            ios_document(d)
            raise AssertionError("package manifest not detected")
        except SbomError as e:
            assert "Package.resolved" in str(e)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
