#!/usr/bin/env python3
"""Check the translations of Crypto Checker (Android string resources and iOS string catalog).

Run from the repository root (no dependencies besides Python 3.9+):

    python3 .github/scripts/check_strings.py [--root PATH]

Android (android/app/src/main/res):
  * every values-xx/strings*.xml parses and has every translatable key of values/
  * same printf placeholders (%s, %1$d, ...) as English
  * <plurals>: at least the CLDR integer quantities of the language, same placeholders
    in every quantity; English needs one + other
  * no unescaped apostrophes, no keys that only exist in a translation
  * every R.string / R.plurals / R.array and @string/ reference in the code exists
iOS (ios/):
  * tools/ios_extra_strings.json: every key in every language, same placeholders
  * tools/convert_strings.py validates and regenerates the string catalogs; the result must
    equal the committed Shared/Resources/*.xcstrings (otherwise: run convert_strings.py)
  * every L("key") used in the Swift code exists in Localizable.xcstrings

Exit code 0 = OK, 1 = problems found (listed on stdout).
"""
import argparse
import glob
import json
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

PH = re.compile(r"%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]")
APOSTROPHE = re.compile(r"(?<!\\)'")
# CLDR plural categories for integers per Android values folder; fallback one + other
PLURAL_CATS = {
    "ar": {"zero", "one", "two", "few", "many", "other"},
    "cs": {"one", "few", "many", "other"}, "pl": {"one", "few", "many", "other"},
    "ru": {"one", "few", "many", "other"}, "uk": {"one", "few", "many", "other"},
    "ro": {"one", "few", "other"}, "iw": {"one", "two", "other"},
    "fr": {"one", "many", "other"}, "es": {"one", "many", "other"}, "it": {"one", "many", "other"},
    "pt": {"one", "many", "other"}, "pt-rBR": {"one", "many", "other"},
}
for _l in ["in", "ja", "ko", "th", "vi", "zh"]:
    PLURAL_CATS[_l] = {"other"}

problems = []


def problem(*parts):
    problems.append(" ".join(str(p) for p in parts))


def plural_cats(folder):
    return PLURAL_CATS.get(folder.replace("values-", ""), {"one", "other"})


def phs(text):
    return sorted(m.group(0) for m in PH.finditer(text) if m.group(0) != "%%")


def load_xml(path):
    """name -> (kind, value, translatable); kind = string | plurals | array"""
    out = {}
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        problem("XML ERROR", path, e)
        return out
    for el in root:
        name = el.get("name")
        translatable = el.get("translatable") != "false"
        if el.tag == "string":
            out[name] = ("string", "".join(el.itertext()), translatable)
        elif el.tag == "plurals":
            out[name] = ("plurals", {i.get("quantity"): "".join(i.itertext()) for i in el}, translatable)
        elif el.tag == "string-array":
            out[name] = ("array", ["".join(i.itertext()) for i in el], translatable)
    return out


def raw_apostrophes(path, label):
    text = open(path, encoding="utf-8").read()
    for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', text, re.S):
        if APOSTROPHE.search(m.group(2)):
            problem("APOSTROPHE", label, m.group(1))
    for m in re.finditer(r'<item quantity="([a-z]+)">(.*?)</item>', text, re.S):
        if APOSTROPHE.search(m.group(2)):
            problem("APOSTROPHE (plural)", label, m.group(1), repr(m.group(2)[:60]))


def check_plural(label, key, value, want):
    if not isinstance(value, dict):
        problem("NOT PLURALS", label, key)
        return
    missing = plural_cats(label) - set(value)
    if missing:
        problem("PLURAL QUANTITIES", label, key, sorted(missing))
    for q, t in value.items():
        if phs(t) != want:
            problem("PLURAL PLACEHOLDER", label, key, q, repr(t))


def check_android(android):
    main = os.path.join(android, "app", "src", "main")
    res = os.path.join(main, "res")
    if not os.path.isdir(res):
        problem("MISSING", res)
        return 0
    base = {}
    for f in sorted(glob.glob(os.path.join(res, "values", "*.xml"))):
        base.update(load_xml(f))
    # resources of library modules (e.g. marketdata) for the reference check
    all_res = dict(base)
    for f in sorted(glob.glob(os.path.join(android, "*", "src", "main", "res", "values", "*.xml"))):
        if not f.startswith(res + os.sep):
            all_res.update(load_xml(f))
    for f in sorted(glob.glob(os.path.join(res, "values", "strings*.xml"))):
        raw_apostrophes(f, "values")
    # English plurals: one + other, identical placeholders
    for k, (kind, val, _) in base.items():
        if kind == "plurals":
            if not {"one", "other"} <= set(val):
                problem("PLURAL QUANTITIES values", k, sorted(val))
            want = phs(val.get("other", ""))
            if any(phs(t) != want for t in val.values()):
                problem("PLURAL PLACEHOLDER values", k)

    string_files = {os.path.basename(p) for p in glob.glob(os.path.join(res, "values", "strings*.xml"))}
    base_strings = {}
    for nf in string_files:
        base_strings.update(load_xml(os.path.join(res, "values", nf)))

    locales = sorted(os.path.basename(d) for d in glob.glob(os.path.join(res, "values-*"))
                     if glob.glob(os.path.join(d, "strings*.xml")))
    for loc in locales:
        tr = {}
        for f in sorted(glob.glob(os.path.join(res, loc, "strings*.xml"))):
            tr.update(load_xml(f))
            raw_apostrophes(f, loc)
            for k in load_xml(f):
                if k not in base:
                    problem("EXTRA KEY", loc, os.path.basename(f), k)
        for k, (kind, val, translatable) in base_strings.items():
            if not translatable or k == "app_name":
                continue
            if k not in tr:
                problem("MISSING TRANSLATION", loc, k)
                continue
            tkind, tval, _ = tr[k]
            if kind == "string":
                if tkind != "string":
                    problem("PLURALS INSTEAD OF STRING", loc, k)
                elif phs(val) != phs(tval):
                    problem("PLACEHOLDER", loc, k, phs(val), phs(tval))
            elif kind == "plurals":
                check_plural(loc, k, tval if tkind == "plurals" else None, phs(val.get("other", "")))
            elif kind == "array" and tkind == "array" and len(val) != len(tval):
                problem("ARRAY LENGTH", loc, k, len(val), len(tval))

    # References from code and layouts
    for kt in glob.glob(os.path.join(android, "**", "src", "main", "java", "**", "*.kt"), recursive=True):
        src = open(kt, encoding="utf-8").read()
        for kind, name in re.findall(r"(?<![\w.])R\.(string|plurals|array)\.(\w+)", src):
            if name not in all_res or all_res[name][0] != kind:
                problem("MISSING RESOURCE", f"R.{kind}.{name}", os.path.relpath(kt, android))
    for x in glob.glob(os.path.join(main, "**", "*.xml"), recursive=True):
        if os.sep + "values" in x:
            continue
        for name in re.findall(r"@string/(\w+)", open(x, encoding="utf-8").read()):
            if name not in all_res:
                problem("MISSING @string", name, os.path.relpath(x, android))
    return len(locales)


def check_ios(ios, android):
    tools = os.path.join(ios, "tools")
    extra_path = os.path.join(tools, "ios_extra_strings.json")
    if not os.path.isfile(extra_path):
        problem("MISSING", extra_path)
        return 0
    extra = json.load(open(extra_path, encoding="utf-8"))
    langs = set().union(*[set(v) for v in extra.values()]) if extra else set()
    for k, v in extra.items():
        if set(v) != langs:
            problem("IOS EXTRA MISSING LANGUAGE", k, sorted(langs - set(v)))
        if "en" not in v:
            problem("IOS EXTRA NO ENGLISH", k)
            continue
        want = phs(v["en"])
        for lang, s in v.items():
            if phs(s) != want:
                problem("IOS EXTRA PLACEHOLDER", k, lang, repr(s))

    # Regenerate the catalogs into a temp folder and compare with the committed ones
    res_dir = os.path.join(ios, "Shared", "Resources")
    with tempfile.TemporaryDirectory() as tmp:
        cmd = [sys.executable, os.path.join(tools, "convert_strings.py"),
               "--android-res", os.path.join(android, "app", "src", "main", "res"),
               "--extra", extra_path, "--out", tmp]
        run = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
        if run.returncode != 0:
            problem("convert_strings.py FAILED:\n" + run.stdout + run.stderr)
        else:
            for name in ("Localizable.xcstrings", "InfoPlist.xcstrings"):
                gen, committed = os.path.join(tmp, name), os.path.join(res_dir, name)
                if not os.path.isfile(committed):
                    problem("MISSING", os.path.relpath(committed, ios))
                elif open(gen, encoding="utf-8").read() != open(committed, encoding="utf-8").read():
                    problem("OUT OF DATE", "ios/Shared/Resources/" + name,
                            "- run: python3 ios/tools/convert_strings.py --android-res android/app/src/main/res")

    catalog_path = os.path.join(res_dir, "Localizable.xcstrings")
    if os.path.isfile(catalog_path):
        catalog = json.load(open(catalog_path, encoding="utf-8"))["strings"]
        for f in glob.glob(os.path.join(ios, "**", "*.swift"), recursive=True):
            for key in set(re.findall(r'\bL\("([A-Za-z0-9_]+)"', open(f, encoding="utf-8").read())):
                if key not in catalog:
                    problem("MISSING IN CATALOG", key, os.path.relpath(f, ios))
    return len(langs)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", default=os.path.normpath(os.path.join(here, "..", "..")),
                    help="repository root containing android/ and ios/")
    args = ap.parse_args()
    android, ios = os.path.join(args.root, "android"), os.path.join(args.root, "ios")
    n_android = check_android(android)
    n_ios = check_ios(ios, android) if os.path.isdir(ios) else 0
    for p in problems:
        print(p)
    print(f"Android: {n_android} translations · iOS extras: {n_ios} languages · problems: {len(problems)}")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
