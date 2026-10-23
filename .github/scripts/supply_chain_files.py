#!/usr/bin/env python3
"""Helper of the workflow «Supply chain update» (.github/workflows/supply-chain-update.yml).

--seed FILE   writes the starting gradle/verification-metadata.xml (configuration only: verify metadata, no
              signatures, sources/javadoc jars trusted for the IDE); Gradle adds the sha256 entries.
--check DIR   strict check of the handed-over artifact before the publish job commits it: only the expected file
              names (paths relative to android/), regular files, size limits, verification metadata is well-formed
              XML with the expected root and exactly the <configuration> of the seed (verify metadata on, the same
              trusted artifacts, no trusted or ignored keys), lock files contain only lock lines. Normalises CRLF to LF in place.
              Exit 1 with an annotation on the first problem. Nothing from the artifact is executed.

Usage: python3 -I supply_chain_files.py --seed FILE | --check DIR | --self-test
"""
import os
import re
import stat
import sys
import tempfile
import xml.etree.ElementTree as ET

METADATA = "gradle/verification-metadata.xml"
LOCKFILES = ("gradle.lockfile", "app/gradle.lockfile", "marketdata/gradle.lockfile", "baselineprofile/gradle.lockfile")
REQUIRED = (METADATA, "app/gradle.lockfile", "marketdata/gradle.lockfile")
MAX_BYTES = {METADATA: 20 * 1024 * 1024}
MAX_LOCK_BYTES = 2 * 1024 * 1024
NS = "{https://schema.gradle.org/dependency-verification}"
# Configuration names may contain «-» (AGP: _internal-unified-test-platform-…)
LOCK_LINE = re.compile(r"(#.*|empty=[A-Za-z0-9_,\-]*|[A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+:[A-Za-z0-9_.+\-]+=[A-Za-z0-9_,\-]*)\Z")

SEED = """<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="https://schema.gradle.org/dependency-verification https://schema.gradle.org/dependency-verification/dependency-verification-1.3.xsd">
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>false</verify-signatures>
      <trusted-artifacts>
         <trust file=".*-javadoc[.]jar" regex="true" reason="Android Studio downloads documentation, not part of the build"/>
         <trust file=".*-sources[.]jar" regex="true" reason="Android Studio downloads sources, not part of the build"/>
         <trust group="gradle" name="gradle" file="gradle-.*-src[.]zip" regex="true" reason="Android Studio downloads the Gradle sources, not part of the build"/>
      </trusted-artifacts>
   </configuration>
   <components>
   </components>
</verification-metadata>
"""


class Invalid(Exception):
    pass


def canonical(element):
    """Element als vergleichbares Tupel: Name, Attribute sortiert, Text ohne Leerraum, Kinder in Reihenfolge."""
    return (element.tag, tuple(sorted(element.attrib.items())), (element.text or "").strip(),
            tuple(canonical(child) for child in element))


def seed_configuration():
    return canonical(ET.fromstring(SEED.encode("utf-8")).find(NS + "configuration"))


def check_configuration(rel, root):
    # Nur die Konfiguration des SEED: keine abgeschaltete Prüfung, keine zusätzlichen vertrauten Dateien oder Schlüssel
    configs = root.findall(NS + "configuration")
    if len(configs) != 1:
        raise Invalid(f"{rel}: expected exactly one <configuration>")
    verify = configs[0].find(NS + "verify-metadata")
    if verify is None or (verify.text or "").strip() != "true":
        raise Invalid(f"{rel}: verify-metadata must be true")
    if canonical(configs[0]) != seed_configuration():
        raise Invalid(f"{rel}: <configuration> differs from the seed (trusted artifacts or keys changed)")


def seed(path):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(SEED)


def listing(directory):
    found = []
    for base, dirs, files in os.walk(directory):
        for name in dirs + files:
            full = os.path.join(base, name)
            mode = os.lstat(full).st_mode
            rel = os.path.relpath(full, directory).replace(os.sep, "/")
            if stat.S_ISLNK(mode):
                raise Invalid(f"{rel}: links are not allowed")
            if stat.S_ISREG(mode):
                found.append(rel)
            elif not stat.S_ISDIR(mode):
                raise Invalid(f"{rel}: not a regular file")
    return sorted(found)


def check(directory):
    files = listing(directory)
    allowed = {METADATA, *LOCKFILES}
    for rel in files:
        if rel not in allowed:
            raise Invalid(f"unexpected file in artifact: {rel}")
    for rel in REQUIRED:
        if rel not in files:
            raise Invalid(f"missing file: {rel}")
    for rel in files:
        path = os.path.join(directory, rel)
        limit = MAX_BYTES.get(rel, MAX_LOCK_BYTES)
        if os.path.getsize(path) > limit:
            raise Invalid(f"{rel}: larger than {limit} bytes")
        with open(path, "rb") as f:
            data = f.read()
        try:
            text = data.decode("utf-8").replace("\r\n", "\n")
        except UnicodeDecodeError:
            raise Invalid(f"{rel}: not UTF-8")
        if rel == METADATA:
            try:
                root = ET.fromstring(text.encode("utf-8"))
            except ET.ParseError as exc:
                raise Invalid(f"{rel}: not well-formed XML ({exc})")
            if root.tag != NS + "verification-metadata":
                raise Invalid(f"{rel}: unexpected root element {root.tag}")
            check_configuration(rel, root)
            components = root.find(NS + "components")
            if components is None or len(components) == 0:
                raise Invalid(f"{rel}: no components (Gradle wrote no checksums)")
        else:
            lines = [line for line in text.split("\n") if line]
            if not any(not line.startswith("#") for line in lines):
                raise Invalid(f"{rel}: no lock entries")
            for number, line in enumerate(lines, 1):
                if not LOCK_LINE.match(line):
                    raise Invalid(f"{rel}:{number}: unexpected line")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
    return files


def self_test():
    with tempfile.TemporaryDirectory() as tmp:
        seed(os.path.join(tmp, METADATA))
        lock = "# Gradle lock file\nandroidx.core:core:1.19.1=releaseRuntimeClasspath,_internal-utp-x\nempty=\n"
        for rel in ("app/gradle.lockfile", "marketdata/gradle.lockfile"):
            os.makedirs(os.path.join(tmp, os.path.dirname(rel)), exist_ok=True)
            with open(os.path.join(tmp, rel), "w", encoding="utf-8", newline="") as f:
                f.write(lock.replace("\n", "\r\n"))
        try:
            check(tmp)
            raise AssertionError("seed without components must be rejected")
        except Invalid as exc:
            assert "no components" in str(exc), exc
        with open(os.path.join(tmp, METADATA), "w", encoding="utf-8") as f:
            f.write(SEED.replace("<components>", '<components><component group="a" name="b" version="1"/>'))
        assert check(tmp) == ["app/gradle.lockfile", METADATA, "marketdata/gradle.lockfile"]
        with open(os.path.join(tmp, "app/gradle.lockfile"), "rb") as f:
            assert b"\r" not in f.read()
        with open(os.path.join(tmp, "app/build.gradle.kts"), "w", encoding="utf-8") as f:
            f.write("x")
        try:
            check(tmp)
            raise AssertionError("unexpected file must be rejected")
        except Invalid as exc:
            assert "unexpected file" in str(exc), exc
        os.remove(os.path.join(tmp, "app/build.gradle.kts"))
        good = SEED.replace("<components>", '<components><component group="a" name="b" version="1"/>')
        # Gleiche Konfiguration mit anderem Leerraum und anderer Attributreihenfolge bleibt gültig
        same = good.replace('<trust file=".*-javadoc[.]jar" regex="true"', '<trust   regex="true" file=".*-javadoc[.]jar"')
        configuration_cases = [
            (same, None),
            (good.replace("<verify-metadata>true", "<verify-metadata>false"), "verify-metadata must be true"),
            (good.replace("   <configuration>\n      <verify-metadata>true</verify-metadata>\n", "   <configuration>\n"),
             "verify-metadata must be true"),
            (good.replace("</trusted-artifacts>", '<trust group="com.evil"/></trusted-artifacts>'), "differs from the seed"),
            (good.replace(".*-sources[.]jar", ".*"), "differs from the seed"),
            (good.replace("</configuration>", '<trusted-keys><trusted-key id="ABCD" group="x"/></trusted-keys>'
                          "</configuration>"), "differs from the seed"),
            (good.replace("</configuration>", '<ignored-keys><ignored-key id="ABCD"/></ignored-keys></configuration>'),
             "differs from the seed"),
            (good.replace("<verify-signatures>false", "<verify-signatures>true"), "differs from the seed"),
            (good.replace("<components>", "<configuration/><components>"), "exactly one <configuration>"),
        ]
        for xml, expected in configuration_cases:
            with open(os.path.join(tmp, METADATA), "w", encoding="utf-8") as f:
                f.write(xml)
            try:
                check(tmp)
                assert expected is None, f"configuration must be rejected: {expected}"
            except Invalid as exc:
                assert expected is not None and expected in str(exc), exc
        with open(os.path.join(tmp, METADATA), "w", encoding="utf-8") as f:
            f.write(good)
        with open(os.path.join(tmp, "marketdata/gradle.lockfile"), "a", encoding="utf-8") as f:
            f.write("$(curl evil)\n")
        try:
            check(tmp)
            raise AssertionError("bad lock line must be rejected")
        except Invalid as exc:
            assert "unexpected line" in str(exc), exc
    print("supply_chain_files self-test ok")
    return 0


def main(argv):
    if argv == ["--self-test"]:
        return self_test()
    if len(argv) == 2 and argv[0] == "--seed":
        seed(argv[1])
        return 0
    if len(argv) == 2 and argv[0] == "--check":
        try:
            files = check(argv[1])
        except Invalid as exc:
            print(f"::error title=Supply chain artifact::{exc}")
            return 1
        print("Checked: " + ", ".join(files))
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
