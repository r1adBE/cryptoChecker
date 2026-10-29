#!/usr/bin/env python3
"""Serbian in Latin script, generated from the Cyrillic texts (values-sr).

Serbian is written in both scripts with a fixed one-to-one letter mapping, so the Latin texts are not translated
but derived: every values-sr/strings*.xml of every Android module (app, wear, ...) becomes
values-b+sr+Latn/<same file>, and the "sr" texts of ios/tools/ios_extra_strings.json (and ios_overrides.json, if it
has any) become "sr-Latn". Never edit the generated files by hand - change values-sr and run this script again.

Only Cyrillic letters change; placeholders (%1$s), escapes (\\n, \\'), XML markup and comments, entities (&amp;),
URLs and every token already in Latin script (brand names, tickers, iOS, BTC) stay as they are. The digraph letters
follow the case of their word: «Љубав» -> «Ljubav», «ЉУБАВ» -> «LJUBAV», a lone «Џ» -> «Dž».

Usage (from anywhere; paths are relative to the repository root):
    python3 -I android/tools/sr_latn.py              regenerate
    python3 -I android/tools/sr_latn.py --check      exit 1 if a generated file differs (check_strings.py runs this)
    python3 -I android/tools/sr_latn.py --self-test  test the transliteration rules
Options: --root PATH (repository root; default: two levels above this script).
"""
import argparse
import glob
import json
import os
import re
import sys

SOURCE = "values-sr"
TARGET = "values-b+sr+Latn"
IOS_SOURCE, IOS_TARGET = "sr", "sr-Latn"
OVERRIDE_SOURCE, OVERRIDE_TARGET = "sr", "b+sr+Latn"
HEADER = "<!-- Generated from values-sr by android/tools/sr_latn.py - do not edit, run the script instead -->"

LOWER = {
    "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "ђ": "đ", "е": "e", "ж": "ž", "з": "z", "и": "i",
    "ј": "j", "к": "k", "л": "l", "љ": "lj", "м": "m", "н": "n", "њ": "nj", "о": "o", "п": "p", "р": "r",
    "с": "s", "т": "t", "ћ": "ć", "у": "u", "ф": "f", "х": "h", "ц": "c", "ч": "č", "џ": "dž", "ш": "š",
}
UPPER = {k.upper(): v.upper() for k, v in LOWER.items()}       # Љ -> LJ (all caps form)
TITLE = {k.upper(): v.capitalize() for k, v in LOWER.items()}  # Љ -> Lj (word start in normal text)

# Left as they are: XML comments and tags, entities, URLs, Android/printf placeholders, backslash escapes
PROTECTED = re.compile(
    r"<!--.*?-->|</?[A-Za-z?!][^<>]*>|&#?[A-Za-z0-9]+;|https?://[^\s<>\"']+|%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%@]|\\.", re.S)
CYRILLIC = re.compile(r"[Ѐ-ӿ]")


def _is_upper_letter(ch):
    return ch.isalpha() and ch.isupper()


def translit_plain(text):
    """Transliterate a run of plain text (no markup inside)."""
    out = []
    for i, ch in enumerate(text):
        if ch in LOWER:
            out.append(LOWER[ch])
        elif ch in UPPER:
            if len(UPPER[ch]) == 1:
                out.append(UPPER[ch])
                continue
            nxt = text[i + 1] if i + 1 < len(text) else ""
            prev = text[i - 1] if i > 0 else ""
            # All caps when the word continues in capitals («ЉУБАВ») or, at its end, began in capitals («ЏЏ»)
            caps = _is_upper_letter(nxt) or (not nxt.isalpha() and _is_upper_letter(prev))
            out.append(UPPER[ch] if caps else TITLE[ch])
        elif CYRILLIC.match(ch):
            raise ValueError(f"not a Serbian Cyrillic letter: {ch!r} (U+{ord(ch):04X}) in {text!r}")
        else:
            out.append(ch)
    return "".join(out)


def translit(text):
    """Transliterate a text or a whole XML file; protected tokens are copied unchanged."""
    out, pos = [], 0
    for m in PROTECTED.finditer(text):
        out.append(translit_plain(text[pos:m.start()]))
        out.append(m.group(0))
        pos = m.end()
    out.append(translit_plain(text[pos:]))
    return "".join(out)


def translit_xml_file(text):
    """A values-sr file -> the values-b+sr+Latn file (with the generated-file header)."""
    body = translit(text)
    first, sep, rest = body.partition("\n")
    if first.startswith("<?xml"):
        return first + "\n" + HEADER + "\n" + rest
    return HEADER + "\n" + body


def dump_json(data):
    return json.dumps(data, ensure_ascii=False, indent=2) + "\n"


def with_after(per_lang, source, target, value):
    """Copy of a {language: text} dict with `target` right after `source` (stable key order in the file)."""
    out = {}
    for k, v in per_lang.items():
        if k == target:
            continue
        out[k] = v
        if k == source:
            out[target] = value
    return out


def expected_outputs(root):
    """{path: expected content} of every generated file; None = the file must not exist."""
    want = {}
    for src_dir in sorted(glob.glob(os.path.join(root, "android", "*", "src", "main", "res", SOURCE))):
        dst_dir = os.path.join(os.path.dirname(src_dir), TARGET)
        names = sorted(os.path.basename(p) for p in glob.glob(os.path.join(src_dir, "strings*.xml")))
        for name in names:
            with open(os.path.join(src_dir, name), encoding="utf-8") as f:
                want[os.path.join(dst_dir, name)] = translit_xml_file(f.read())
        for stale in glob.glob(os.path.join(dst_dir, "*.xml")):
            if os.path.basename(stale) not in names:
                want[stale] = None
    tools = os.path.join(root, "ios", "tools")
    extra_path = os.path.join(tools, "ios_extra_strings.json")
    if os.path.isfile(extra_path):
        with open(extra_path, encoding="utf-8") as f:
            extra = json.load(f)
        out = {}
        for key, per_lang in extra.items():
            if IOS_SOURCE not in per_lang:
                raise SystemExit(f"ios_extra_strings.json: {key} has no {IOS_SOURCE!r} text")
            out[key] = with_after(per_lang, IOS_SOURCE, IOS_TARGET, translit(per_lang[IOS_SOURCE]))
        want[extra_path] = dump_json(out)
    overrides_path = os.path.join(tools, "ios_overrides.json")
    if os.path.isfile(overrides_path):
        with open(overrides_path, encoding="utf-8") as f:
            overrides = json.load(f)
        out = {k: v for k, v in overrides.items() if k != OVERRIDE_TARGET}
        if OVERRIDE_SOURCE in overrides:
            src = overrides[OVERRIDE_SOURCE]
            value = {k: ({q: translit(t) for q, t in v.items()} if isinstance(v, dict) else translit(v))
                     for k, v in src.items()}
            out = with_after(out, OVERRIDE_SOURCE, OVERRIDE_TARGET, value)
        want[overrides_path] = dump_json(out)
    return want


def current(path):
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8", newline="") as f:
        return f.read()


def run(root, check):
    want = expected_outputs(root)
    if not any(p.endswith(".xml") for p in want):
        print(f"no {SOURCE} folder below {os.path.join(root, 'android')}")
        return 1
    differ = [p for p, text in sorted(want.items()) if current(p) != text]
    if check:
        for p in differ:
            print(f"OUT OF DATE {os.path.relpath(p, root)} - run: python3 -I android/tools/sr_latn.py")
        return 1 if differ else 0
    for p in differ:
        if want[p] is None:
            os.remove(p)
            continue
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(want[p])
    print(f"{len(want)} files, {len(differ)} written")
    return 0


def self_test():
    cases = [
        ("Љубав", "Ljubav"), ("ЉУБАВ", "LJUBAV"), ("Његови", "Njegovi"), ("ЊЕГОВИ", "NJEGOVI"),
        ("Џеп", "Džep"), ("ЏЕП", "DŽEP"), ("Џ", "Dž"), ("ПОЉЕ", "POLJE"), ("ЏЏ", "DŽDŽ"),
        ("Ђорђе Ћирић", "Đorđe Ćirić"), ("жута чаша шећера", "žuta čaša šećera"), ("ЖЧШ", "ŽČŠ"),
        ("Парови: %1$d од %2$s", "Parovi: %1$d od %2$s"), ("%1$d%% више", "%1$d%% više"),
        ("Binance-у, iOS-а, BTC/USDT", "Binance-u, iOS-a, BTC/USDT"),
        ("Ипак\\nпромени \\'то\\'", "Ipak\\npromeni \\'to\\'"),
        ("<b>Шта</b> &amp; &lt;ново&gt;", "<b>Šta</b> &amp; &lt;novo&gt;"),
        ("види https://пример.срб/џ?а=б", "vidi https://пример.срб/џ?а=б"),
        ('<string name="x">Њ</string> <!-- Њ bleibt -->', '<string name="x">Nj</string> <!-- Њ bleibt -->'),
        ("Љ-Њ", "Lj-Nj"), ("ЏЕП-ЉУБАВ", "DŽEP-LJUBAV"),
    ]
    failed = 0
    for src, exp in cases:
        got = translit(src)
        if got != exp:
            failed += 1
            print(f"FAIL {src!r}: {got!r} != {exp!r}")
    try:
        translit("ы")
        failed += 1
        print("FAIL: Russian ы must be rejected")
    except ValueError:
        pass
    xml = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <string name="a">Ја</string>\n</resources>\n'
    gen = translit_xml_file(xml)
    if gen.splitlines()[1] != HEADER or '<string name="a">Ja</string>' not in gen or CYRILLIC.search(gen):
        failed += 1
        print("FAIL: XML file", repr(gen))
    order = list(with_after({"sq": 1, "sr": 2, "sv": 3}, "sr", "sr-Latn", 9))
    if order != ["sq", "sr", "sr-Latn", "sv"]:
        failed += 1
        print("FAIL: key order", order)
    print(f"sr_latn self-test: {len(cases) + 3} cases, {failed} failed")
    return 1 if failed else 0


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", default=os.path.normpath(os.path.join(here, "..", "..")))
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    sys.exit(self_test() if args.self_test else run(args.root, args.check))


if __name__ == "__main__":
    main()
