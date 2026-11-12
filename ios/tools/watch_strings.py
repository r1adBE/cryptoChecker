#!/usr/bin/env python3
"""Small string catalogue for the Apple Watch app and its complications.

The watch targets look texts up with `Bundle.main.localizedString(forKey:value:table:)` (table nil =
`Localizable`), so each watch bundle needs a catalogue named `Localizable.xcstrings`. Shipping the whole
catalogue of the iPhone app there costs megabytes for a few dozen keys. `convert_strings.py` therefore writes
`Watch/Device/Localizable.xcstrings` (compiled into the watch app and the watch widgets) with exactly the keys
listed in `tools/watch_string_keys.txt`, every language of the full catalogue.

The key list is explicit; this module checks it against the watch sources (Watch/, WatchCore/, WatchWidgets/):
  * a key of the full catalogue that appears as a quoted literal in the watch sources must be listed
    (plain substring search for "<key>", so nested interpolation or ternaries cannot hide it)
  * a listed key must exist in the full catalogue and must be used by the watch sources
  * every `t(…)` lookup takes a literal key or a ternary of two literal keys (no key built at run time), and every
    such literal - like a snake_case literal handed to a SwiftUI initialiser that looks it up (`Text("watch_x")`,
    `.navigationTitle("…")` …) - must be in the full catalogue and in the list; otherwise the watch would show the
    key name (a typo, or a text created only in Swift)
  * no other lookup API in the watch sources (they would need a different list rule first)
Used by convert_strings.py (errors make it exit 1); `python3 tools/watch_strings.py --self-test` checks the rules.
"""
import os
import re
import sys

WATCH_SOURCE_DIRS = ("Watch", "WatchCore", "WatchWidgets")
KEY_LIST = "watch_string_keys.txt"
WATCH_OUT = os.path.join("Watch", "Device")
# The only file that may call the Foundation lookup directly (it defines `WatchText.t`)
LOOKUP_FILE = os.path.join("Watch", "Device", "WatchText.swift")
OTHER_APIS = re.compile(r"LocalizedStringResource|String\(localized|NSLocalizedString|LocalizedStringKey\(|"
                        r"AttributedString\(localized")
LITERAL = r'"[A-Za-z0-9_]+"'
KEY_ARGUMENT = re.compile(r"\s*(?:" + LITERAL + r"|[^?\"]+\?\s*" + LITERAL + r"\s*:\s*" + LITERAL + r")\s*")
# SwiftUI initialisers and modifiers that take a LocalizedStringKey: a snake_case literal there is a key lookup
SWIFTUI_KEY = re.compile(r"\b(?:Text|Label|Button|Toggle|Section|Picker|navigationTitle|accessibilityLabel|"
                         r"accessibilityHint|accessibilityValue|configurationDisplayName|description)"
                         r"\(\s*\"([a-z][a-z0-9]*(?:_[a-z0-9]+)+)\"")


def read_key_list(path):
    """Keys of the list file, one per line; `#` starts a comment."""
    with open(path, encoding="utf-8") as f:
        lines = [line.split("#", 1)[0].strip() for line in f]
    return [line for line in lines if line]


def read_sources(ios_root):
    """{relative path: text} of every Swift file of the watch targets."""
    sources = {}
    for folder in WATCH_SOURCE_DIRS:
        for base, _, files in os.walk(os.path.join(ios_root, folder)):
            for name in sorted(files):
                if name.endswith(".swift"):
                    path = os.path.join(base, name)
                    with open(path, encoding="utf-8") as f:
                        sources[os.path.relpath(path, ios_root)] = f.read()
    return sources


def first_argument(text, start):
    """Text of the first call argument that begins at `start` (up to the top-level `,` or `)`)."""
    depth, quoted, i = 0, False, start
    while i < len(text):
        c = text[i]
        if quoted:
            if c == "\\":
                i += 1
            elif c == '"':
                quoted = False
        elif c == '"':
            quoted = True
        elif c in "([":
            depth += 1
        elif c in ")]":
            if depth == 0:
                break
            depth -= 1
        elif c == "," and depth == 0:
            break
        i += 1
    return text[start:i]


def check(listed, catalog_keys, sources):
    """Problems of the key list against the catalogue and the watch sources (empty list = fine)."""
    errors = []
    if len(set(listed)) != len(listed):
        errors.append(f"{KEY_LIST}: duplicate key(s) {sorted({k for k in listed if listed.count(k) > 1})}")
    if listed != sorted(listed):
        errors.append(f"{KEY_LIST}: keys must be sorted")
    errors += [f"{KEY_LIST}: {k} is not in the full catalogue" for k in listed if k not in catalog_keys]
    used = {}
    for rel, text in sorted(sources.items()):
        for key in catalog_keys:
            if '"' + key + '"' in text:
                used.setdefault(key, rel)
        looked_up = [m.group(1) for m in SWIFTUI_KEY.finditer(text)]
        for m in re.finditer(r"(?<!func )\bt\(", text):
            arg = first_argument(text, m.end())
            if not KEY_ARGUMENT.fullmatch(arg):
                errors.append(f"{rel}: t({arg.strip()} …) – the key must be a literal or a ternary of two literals")
            else:
                looked_up += [literal.strip('"') for literal in re.findall(LITERAL, arg)]
        for key in sorted(set(looked_up)):
            if key not in catalog_keys:
                errors.append(f"{rel}: \"{key}\" is looked up by the watch but not in the full catalogue "
                              "(the watch would show the key name)")
            elif key not in listed:
                used.setdefault(key, rel)
        lookups = text.count("localizedString(forKey")
        if lookups and (rel != LOOKUP_FILE or lookups != 1):
            errors.append(f"{rel}: localizedString(forKey:) only once, in {LOOKUP_FILE} (use WatchText.t)")
        if OTHER_APIS.search(text):
            errors.append(f"{rel}: {OTHER_APIS.search(text).group(0)} is not covered by the watch key list rule "
                          "(use WatchText.t or extend tools/watch_strings.py)")
    errors += [f"{rel}: \"{k}\" is used by the watch but missing in tools/{KEY_LIST}"
               for k, rel in sorted(used.items()) if k not in listed]
    errors += [f"{KEY_LIST}: {k} is not used by the watch sources (remove it)"
               for k in listed if k in catalog_keys and k not in used]
    return errors


def subset(catalog, keys):
    """Catalogue with only `keys` (same entries, same languages)."""
    return {"sourceLanguage": catalog["sourceLanguage"],
            "strings": {k: catalog["strings"][k] for k in keys if k in catalog["strings"]},
            "version": catalog["version"]}


def self_test():
    keys = {"watch_a", "watch_b", "watch_c", "other"}
    src = {LOOKUP_FILE: 'static func t(_ key: String) { Bundle.main.localizedString(forKey: key, value: nil, table: nil) }\n'
                        'let x = "\\(title), \\(t("watch_a"))"\nlet y = t(on ? "watch_b" : "watch_c", pair.exchange)\n'}
    assert check(["watch_a", "watch_b", "watch_c"], keys, src) == [], check(["watch_a", "watch_b", "watch_c"], keys, src)
    assert any("missing" in e for e in check(["watch_a", "watch_b"], keys, src))
    assert any("not used" in e for e in check(["other", "watch_a", "watch_b", "watch_c"], keys, src))
    assert any("not in the full catalogue" in e for e in check(["watch_a", "watch_b", "watch_c", "watch_z"], keys, src))
    assert any("sorted" in e for e in check(["watch_b", "watch_a", "watch_c"], keys, src))
    dynamic = {"Watch/A.swift": 'let a = WatchText.t(key)\nlet b = WatchText.t("watch_" + name)\n'}
    assert len([e for e in check([], keys, dynamic) if "literal" in e]) == 2
    assert any("LocalizedStringResource" in e for e in check([], keys, {"Watch/B.swift": "let t: LocalizedStringResource"}))
    assert any("only once" in e for e in check([], keys, {"Watch/C.swift": "Bundle.main.localizedString(forKey: k)"}))
    assert first_argument('f(a(b, "x,)"), c)', 2) == 'a(b, "x,)")'
    # A key that is in neither the catalogue nor the list (typo, or a text only created in Swift) fails the run
    for typo in ('let a = WatchText.t("watch_typo")\n', 'let b = t(on ? "watch_a" : "watch_typo", x)\n',
                 'Text("watch_typo").font(.caption)\n'):
        found = check(["watch_a"], keys, {"Watch/D.swift": typo})
        assert any("watch_typo" in e and "not in the full catalogue" in e for e in found), (typo, found)
    # A catalogue key looked up by SwiftUI must be listed; a sentence literal is not a key
    assert any("missing" in e for e in check([], keys, {"Watch/E.swift": 'Text("watch_b")\n'}))
    assert check([], keys, {"Watch/F.swift": '.navigationTitle("Crypto Checker")\n'}) == []
    cat = {"sourceLanguage": "en", "version": "1.0", "strings": {"watch_a": {"x": 1}, "other": {}}}
    assert subset(cat, ["watch_a"])["strings"] == {"watch_a": {"x": 1}}
    print("watch_strings.py self-test OK")


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        self_test()
    else:
        sys.exit("usage: python3 tools/watch_strings.py --self-test (the catalogue is written by convert_strings.py)")
