#!/usr/bin/env python3
"""Check the translations of Crypto Checker (Android string resources and iOS string catalog).

Run from the repository root (no dependencies besides Python 3.9+):

    python3 .github/scripts/check_strings.py [--root PATH]

Android (android/app/src/main/res and the watch app android/wear/src/main/res):
  * every values-xx/strings*.xml parses and has every translatable key of values/
  * same printf placeholders (%s, %1$d, ...) as English
  * <plurals>: at least the CLDR integer quantities of the language, same placeholders
    in every quantity; English needs one + other
  * no unescaped apostrophes, no keys that only exist in a translation
  * every R.string / R.plurals / R.array and @string/ reference in the code exists
  * Serbian Latin (values-b+sr+Latn, iOS sr-Latn) is exactly the transliteration of Serbian
    Cyrillic (android/tools/sr_latn.py --check), so the two cannot drift apart
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
    "ca": {"one", "many", "other"}, "lt": {"one", "few", "many", "other"}, "sk": {"one", "few", "many", "other"},
    "hr": {"one", "few", "other"}, "bs": {"one", "few", "other"}, "sr": {"one", "few", "other"},
    "b+sr+Latn": {"one", "few", "other"},
    "sl": {"one", "two", "few", "other"}, "lv": {"zero", "one", "other"},
    "ga": {"one", "two", "few", "many", "other"},
}
for _l in ["in", "ja", "ko", "ms", "th", "vi", "zh", "b+zh+Hant", "km", "lo", "my", "b+yue"]:
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


def check_res(res, label):
    """Translations of one module (values/ against values-xx/); returns (base, locale count)."""
    base = {}
    for f in sorted(glob.glob(os.path.join(res, "values", "*.xml"))):
        base.update(load_xml(f))
    for f in sorted(glob.glob(os.path.join(res, "values", "strings*.xml"))):
        raw_apostrophes(f, label + "values")
    # English plurals: one + other, identical placeholders
    for k, (kind, val, _) in base.items():
        if kind == "plurals":
            if not {"one", "other"} <= set(val):
                problem("PLURAL QUANTITIES", label + "values", k, sorted(val))
            want = phs(val.get("other", ""))
            if any(phs(t) != want for t in val.values()):
                problem("PLURAL PLACEHOLDER", label + "values", k)

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
            raw_apostrophes(f, label + loc)
            for k in load_xml(f):
                if k not in base:
                    problem("EXTRA KEY", label + loc, os.path.basename(f), k)
        for k, (kind, val, translatable) in base_strings.items():
            if not translatable or k == "app_name":
                continue
            if k not in tr:
                problem("MISSING TRANSLATION", label + loc, k)
                continue
            tkind, tval, _ = tr[k]
            if kind == "string":
                if tkind != "string":
                    problem("PLURALS INSTEAD OF STRING", label + loc, k)
                elif phs(val) != phs(tval):
                    problem("PLACEHOLDER", label + loc, k, phs(val), phs(tval))
            elif kind == "plurals":
                check_plural(loc, k, tval if tkind == "plurals" else None, phs(val.get("other", "")))
            elif kind == "array" and tkind == "array" and len(val) != len(tval):
                problem("ARRAY LENGTH", label + loc, k, len(val), len(tval))
    return base, len(locales)


def module_values(android, module):
    out = {}
    for f in sorted(glob.glob(os.path.join(android, module, "src", "main", "res", "values", "*.xml"))):
        out.update(load_xml(f))
    return out


def check_references(android, module, known):
    """R.string / R.plurals / R.array in the module's Kotlin and @string/ in its XML (layouts, manifest, xml/)."""
    main = os.path.join(android, module, "src", "main")
    for kt in glob.glob(os.path.join(main, "java", "**", "*.kt"), recursive=True):
        src = open(kt, encoding="utf-8").read()
        for kind, name in re.findall(r"(?<![\w.])R\.(string|plurals|array)\.(\w+)", src):
            if name not in known or known[name][0] != kind:
                problem("MISSING RESOURCE", f"R.{kind}.{name}", os.path.relpath(kt, android))
    for x in glob.glob(os.path.join(main, "**", "*.xml"), recursive=True):
        if os.sep + "values" in x:
            continue
        for name in re.findall(r"@string/(\w+)", open(x, encoding="utf-8").read()):
            if name not in known:
                problem("MISSING @string", name, os.path.relpath(x, android))


def check_android(android):
    res = os.path.join(android, "app", "src", "main", "res")
    if not os.path.isdir(res):
        problem("MISSING", res)
        return 0
    _, locales = check_res(res, "")
    # The watch app (:wear) has its own texts in the same 75 languages
    wear_res = os.path.join(android, "wear", "src", "main", "res")
    if os.path.isdir(wear_res):
        check_res(wear_res, "wear/")
    # References: every module sees its own resources and those of the library modules (marketdata, domain,
    # data); :wear only its own and :marketdata (it does not depend on :app, :domain or :data)
    modules = sorted(m for m in os.listdir(android) if os.path.isdir(os.path.join(android, m, "src", "main")))
    values = {m: module_values(android, m) for m in modules}
    phone = {}
    for m in modules:
        if m != "wear":
            phone.update(values[m])
    for m in modules:
        known = {**values.get("marketdata", {}), **values["wear"]} if m == "wear" else phone
        check_references(android, m, known)
    return locales


def check_sr_latn(root):
    """values-b+sr+Latn and the iOS sr-Latn texts are generated from values-sr; they must be up to date."""
    tool = os.path.join(root, "android", "tools", "sr_latn.py")
    if not os.path.isfile(tool):
        problem("MISSING", os.path.relpath(tool, root))
        return
    run = subprocess.run([sys.executable, "-I", tool, "--check", "--root", root],
                         capture_output=True, text=True, encoding="utf-8")
    if run.returncode != 0:
        problem("SERBIAN LATIN OUT OF DATE (run: python3 -I android/tools/sr_latn.py):\n" + run.stdout + run.stderr)


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

    # iOS-specific versions (tools/ios_overrides.json): key exists on Android, same placeholders as English
    overrides_path = os.path.join(tools, "ios_overrides.json")
    if os.path.isfile(overrides_path):
        base = {}
        for f in sorted(glob.glob(os.path.join(android, "app", "src", "main", "res", "values", "strings*.xml"))):
            base.update(load_xml(f))
        overrides = json.load(open(overrides_path, encoding="utf-8"))
        for lang, per_key in overrides.items():
            if lang.startswith("_"):
                continue
            for k, v in per_key.items():
                if k not in base:
                    problem("IOS OVERRIDE UNKNOWN KEY", lang, k)
                elif isinstance(v, dict):
                    if base[k][0] != "plurals":
                        problem("IOS OVERRIDE NOT PLURAL", lang, k)
                    else:
                        want = phs(base[k][1].get("other", ""))
                        for q, t in v.items():
                            if phs(t) != want:
                                problem("IOS OVERRIDE PLACEHOLDER", lang, k, q, repr(t))
                elif base[k][0] != "string":
                    problem("IOS OVERRIDE UNKNOWN KEY", lang, k)
                elif phs(v) != phs(base[k][1]):
                    problem("IOS OVERRIDE PLACEHOLDER", lang, k, repr(v))

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
    check_sr_latn(args.root)
    n_ios = check_ios(ios, android) if os.path.isdir(ios) else 0
    for p in problems:
        print(p)
    print(f"Android: {n_android} translations · iOS extras: {n_ios} languages · problems: {len(problems)}")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
