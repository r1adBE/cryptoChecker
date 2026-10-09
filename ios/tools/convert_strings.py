#!/usr/bin/env python3
"""Convert the Android string resources of cryptoChecker into Apple string catalogs.

Output:
  Shared/Resources/Localizable.xcstrings  (all <string> and <plurals> entries + iOS-only extras)
  Shared/Resources/InfoPlist.xcstrings    (CFBundleDisplayName from app_name,
                                           NSFaceIDUsageDescription from ios_face_id_usage)

Usage:
  python3 tools/convert_strings.py [--android-res PATH] [--extra tools/ios_extra_strings.json]

Rerunnable; output is deterministic (sorted keys). Exits non-zero if validation fails.

<plurals> become plural variations (Swift: L(key, count:, args...)):
  - one placeholder:   "variations": {"plural": {"one": {...}, "other": {...}}}
  - more placeholders: "%#@count@" + "substitutions" (argNum = the first integer placeholder),
                       each plural form holds the whole sentence ("%arg" = the count).
  The count is written as %lld. Every language needs at least the CLDR quantities in
  PLURAL_CATS (fallback one+other); the placeholders of all quantities must match English.
"""
import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
# Android resources next to this project: ../cryptoChecker (local folders) or ../android
# (GitHub repository and ZIP bundle); --android-res overrides.
DEFAULT_RES = next((p for p in (os.path.normpath(os.path.join(ROOT, "..", d, "app", "src", "main", "res"))
                    for d in ("cryptoChecker", "android")) if os.path.isdir(p)),
                   os.path.normpath(os.path.join(ROOT, "..", "android", "app", "src", "main", "res")))
DEFAULT_EXTRA = os.path.join(HERE, "ios_extra_strings.json")
OUT_DIR = os.path.join(ROOT, "Shared", "Resources")

# Android resource qualifier -> Apple language code
LOCALE_MAP = {"iw": "he", "in": "id", "ji": "yi", "zh": "zh-Hans", "zh-rCN": "zh-Hans",
              "zh-rTW": "zh-Hant", "zh-rHK": "zh-Hant", "nb": "nb", "pt-rBR": "pt-BR",
              "pt-rPT": "pt-PT", "es-rUS": "es-US"}

# Info.plist key -> string key (Android XML or tools/ios_extra_strings.json)
INFO_PLIST_KEYS = {
    "CFBundleDisplayName": "app_name",
    "NSFaceIDUsageDescription": "ios_face_id_usage",
}


# CLDR plural categories for integers (Apple language codes); at least these per language
PLURAL_CATS = {
    "ar": {"zero", "one", "two", "few", "many", "other"},
    "cs": {"one", "few", "many", "other"}, "pl": {"one", "few", "many", "other"},
    "ru": {"one", "few", "many", "other"}, "uk": {"one", "few", "many", "other"},
    "ro": {"one", "few", "other"}, "he": {"one", "two", "other"},
    "fr": {"one", "many", "other"}, "es": {"one", "many", "other"}, "it": {"one", "many", "other"},
    "pt": {"one", "many", "other"}, "pt-BR": {"one", "many", "other"},
    "id": {"other"}, "ja": {"other"}, "ko": {"other"}, "th": {"other"}, "vi": {"other"},
    "zh-Hans": {"other"}, "zh-Hant": {"other"},
}
PLURAL_ORDER = ["zero", "one", "two", "few", "many", "other"]


def plural_cats(code):
    return PLURAL_CATS.get(code, {"one", "other"})


def apple_code(qualifier):
    if qualifier in LOCALE_MAP:
        return LOCALE_MAP[qualifier]
    m = re.fullmatch(r"([a-z]{2,3})-r([A-Z]{2})", qualifier)
    if m:
        return f"{m.group(1)}-{m.group(2)}"
    return qualifier


def element_text(el):
    """Raw text of an element including any inline markup (serialized back)."""
    parts = [el.text or ""]
    for child in el:
        # inline tags (e.g. <xliff:g>, <b>) - keep their text only
        parts.append("".join(child.itertext()))
        parts.append(child.tail or "")
    return "".join(parts)


def android_unescape(raw):
    """Apply aapt2-like string processing: backslash escapes, whitespace collapsing,
    removal of quotes around the whole value. Unescaped quotes *inside* a value are kept
    literally (aapt2 would drop them, which is never what the translator intended)."""
    quoted_whole = len(raw.strip()) >= 2 and raw.strip()[0] == '"' and raw.strip()[-1] == '"'
    out = []
    in_quotes = False
    i = 0
    n = len(raw)
    pending_space = False
    while i < n:
        c = raw[i]
        if c == "\\" and i + 1 < n:
            nxt = raw[i + 1]
            i += 2
            if pending_space and out:
                out.append(" ")
            pending_space = False
            if nxt == "n":
                out.append("\n")
            elif nxt == "t":
                out.append("\t")
            elif nxt == "u" and re.fullmatch(r"[0-9a-fA-F]{4}", raw[i:i + 4] or ""):
                out.append(chr(int(raw[i:i + 4], 16)))
                i += 4
            else:  # \' \" \\ \@ \? and anything else -> literal char
                out.append(nxt)
            continue
        if c == '"' and quoted_whole:
            in_quotes = not in_quotes
            i += 1
            continue
        if not in_quotes and c in " \t\r\n":
            pending_space = True
            i += 1
            continue
        if pending_space and out:
            out.append(" ")
        pending_space = False
        out.append(c)
        i += 1
    return "".join(out)


FMT_RE = re.compile(r"%(\d+\$)?([-+ 0#,(]*)(\d+)?(\.\d+)?([a-zA-Z%])")


def convert_format(s, int_spec="ld"):
    def repl(m):
        pos, flags, width, prec, conv = m.groups()
        if conv == "%":
            return "%%"
        pos = pos or ""
        width = width or ""
        prec = prec or ""
        if conv in "sS":
            return f"%{pos}{flags}{width}@"
        if conv == "d":
            return f"%{pos}{flags}{width}{prec}{int_spec}"
        if conv == "x":
            return f"%{pos}{flags}{width}lx"
        if conv == "X":
            return f"%{pos}{flags}{width}lX"
        return m.group(0)  # f, e, g, c ... keep
    return FMT_RE.sub(repl, s)


def specifiers(s):
    """Sorted list of (position, conversion) for comparison across languages."""
    res = []
    seq = 0
    for m in re.finditer(r"%(\d+\$)?[-+ 0#,(]*\d*(?:\.\d+)?(l{0,2}[a-zA-Z@%])", s):
        conv = m.group(2)
        if conv == "%":
            continue
        if m.group(1):
            pos = int(m.group(1)[:-1])
        else:
            seq += 1
            pos = seq
        res.append((pos, conv))
    return sorted(res)


def parse_strings_dir(folder):
    """Alle strings*.xml eines values-Ordners (strings.xml + strings_<feature>.xml)."""
    result = {}
    for fn in sorted(os.listdir(folder)):
        if re.fullmatch(r"strings(_[a-z0-9_]+)?\.xml", fn):
            result.update(parse_strings_file(os.path.join(folder, fn)))
    return result


def parse_strings_file(path):
    tree = ET.parse(path)
    result = {}
    for el in tree.getroot():
        name = el.get("name")
        translatable = el.get("translatable", "true") != "false"
        if el.tag == "string":
            value = convert_format(android_unescape(element_text(el)))
        elif el.tag == "plurals":
            # dict quantity -> text; the count is %lld (Apple plural rules)
            value = {item.get("quantity"): convert_format(android_unescape(element_text(item)), "lld")
                     for item in el if item.tag == "item"}
        else:
            continue
        result[name] = (value, translatable)
    return result


def count_arg(forms):
    """Position of the plural count: the first integer placeholder of the 'other' form."""
    for pos, conv in specifiers(forms["other"]):
        if conv in ("lld", "ld", "d"):
            return pos
    raise ValueError(f"no integer placeholder in {forms['other']!r}")


def plural_localization(forms):
    """xcstrings localization for a plural entry (see module docstring)."""
    def unit(v):
        return {"stringUnit": {"state": "translated", "value": v}}
    cats = [c for c in PLURAL_ORDER if c in forms]
    if len(specifiers(forms["other"])) == 1:
        return {"variations": {"plural": {c: unit(forms[c]) for c in cats}}}
    pos = count_arg(forms)
    count_re = re.compile(r"%" + str(pos) + r"\$lld")
    return {
        "stringUnit": {"state": "translated", "value": "%#@count@"},
        "substitutions": {"count": {
            "argNum": pos,
            "formatSpecifier": "lld",
            "variations": {"plural": {c: unit(count_re.sub("%arg", forms[c])) for c in cats}},
        }},
    }


def localization_values(loc):
    """All texts of one localization (plain or every plural form), for validation."""
    if "variations" in loc:
        return [(q, v["stringUnit"]["value"]) for q, v in loc["variations"]["plural"].items()]
    if "substitutions" in loc:
        sub = loc["substitutions"]["count"]
        back = "%" + str(sub["argNum"]) + "$lld"
        return [(q, v["stringUnit"]["value"].replace("%arg", back))
                for q, v in sub["variations"]["plural"].items()]
    return [(None, loc["stringUnit"]["value"])]


def check_value(key, code, quantity, v, en_spec, escape_re):
    where = f"{key} [{code}{'/' + quantity if quantity else ''}]"
    errors = []
    if specifiers(v) != en_spec:
        errors.append(f"{where}: format specifiers {specifiers(v)} != en {en_spec}")
    if escape_re.search(v):
        errors.append(f"{where}: leftover escape in {v!r}")
    stripped = re.sub(r"%%", "", v)
    for m in re.finditer(r"%", stripped):
        tail = stripped[m.start():]
        if not re.match(r"%(\d+\$)?[-+ 0#,(]*\d*(?:\.\d+)?(l{0,2}[a-zA-Z@])", tail):
            errors.append(f"{where}: stray % in {v!r}")
    if re.search(r"%(\d+\$)?[-+ 0#,]*\d*[sd](?![a-z])", v):
        errors.append(f"{where}: unconverted Android specifier in {v!r}")
    return errors


def write_catalog(path, catalog):
    text = json.dumps(catalog, ensure_ascii=False, indent=2, sort_keys=True, separators=(",", " : "))
    with open(path, "w", encoding="utf-8") as f:
        f.write(text + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--android-res", default=DEFAULT_RES)
    ap.add_argument("--extra", default=DEFAULT_EXTRA)
    ap.add_argument("--out", default=OUT_DIR)
    args = ap.parse_args()

    res = args.android_res
    base = parse_strings_dir(os.path.join(res, "values"))
    langs = {"en": {k: v for k, (v, _) in base.items()}}
    non_translatable = {k for k, (_, t) in base.items() if not t}

    for d in sorted(os.listdir(res)):
        m = re.fullmatch(r"values-(.+)", d)
        p = os.path.join(res, d, "strings.xml")
        if not m or not os.path.isfile(p):
            continue
        code = apple_code(m.group(1))
        parsed = parse_strings_dir(os.path.join(res, d))
        langs[code] = {k: v for k, (v, t) in parsed.items() if t and k not in non_translatable}

    if args.extra and os.path.isfile(args.extra):
        with open(args.extra, encoding="utf-8") as f:
            extra = json.load(f)
        for key, per_lang in extra.items():
            for code, value in per_lang.items():
                langs.setdefault(code, {})[key] = value

    all_langs = sorted(langs)
    keys = sorted(set().union(*[set(v) for v in langs.values()]))

    strings = {}
    for key in keys:
        locs = {}
        for code in all_langs:
            if key in langs[code]:
                value = langs[code][key]
                if isinstance(value, dict):
                    locs[code] = plural_localization(value)
                else:
                    locs[code] = {"stringUnit": {"state": "translated", "value": value}}
        entry = {"extractionState": "manual", "localizations": locs}
        if key in non_translatable:
            entry["shouldTranslate"] = False
        strings[key] = entry
    catalog = {"sourceLanguage": "en", "strings": strings, "version": "1.0"}

    os.makedirs(args.out, exist_ok=True)
    out_path = os.path.join(args.out, "Localizable.xcstrings")
    write_catalog(out_path, catalog)

    # InfoPlist.xcstrings: Info.plist-Schlüssel -> Schlüssel im String-Katalog
    info_strings = {}
    for plist_key, string_key in INFO_PLIST_KEYS.items():
        if string_key not in langs["en"]:
            continue
        locs = {c: {"stringUnit": {"state": "translated", "value": langs[c][string_key]}}
                for c in all_langs if string_key in langs[c]}
        info_strings[plist_key] = {"extractionState": "manual", "localizations": locs}
    if info_strings:
        write_catalog(os.path.join(args.out, "InfoPlist.xcstrings"), {
            "sourceLanguage": "en",
            "strings": info_strings,
            "version": "1.0"})

    # ---------- validation ----------
    errors = []
    with open(out_path, encoding="utf-8") as f:
        reparsed = json.load(f)
    if len(all_langs) != 31:
        errors.append(f"expected 31 languages, found {len(all_langs)}: {all_langs}")
    escape_re = re.compile(r"\\['\"@?nt\\]|&(amp|lt|gt|quot|apos);|<!\[CDATA\[")
    for key, entry in reparsed["strings"].items():
        locs = entry["localizations"]
        if key not in non_translatable:
            missing = [c for c in all_langs if c not in locs]
            if missing:
                errors.append(f"{key}: missing {missing}")
        if "en" not in locs:
            errors.append(f"{key}: no English value")
            continue
        en_plural = isinstance(langs["en"].get(key), dict)
        en_values = localization_values(locs["en"])
        en_spec = specifiers(dict(en_values).get("other", en_values[0][1]))
        for code, loc in locs.items():
            is_plural = "variations" in loc or "substitutions" in loc
            if is_plural != en_plural:
                errors.append(f"{key} [{code}]: plural/non-plural mismatch with en")
                continue
            if is_plural:
                missing_q = plural_cats(code) - {q for q, _ in localization_values(loc)}
                if missing_q:
                    errors.append(f"{key} [{code}]: plural quantities missing {sorted(missing_q)}")
            for quantity, v in localization_values(loc):
                errors.extend(check_value(key, code, quantity, v, en_spec, escape_re))

    print(f"Wrote {out_path}: {len(keys)} keys, {len(all_langs)} languages ({', '.join(all_langs)})")
    if errors:
        print(f"{len(errors)} validation problem(s):")
        for e in errors:
            print("  -", e)
        sys.exit(1)
    print("Validation OK")


if __name__ == "__main__":
    main()
