#!/usr/bin/env python3
"""Frame raw app screenshots for Google Play and the App Store.

Input:  <raw>/<locale>/<NN>.png          (e.g. raw/de-DE/01.png)
Output: <out>/<platform>/<locale>/<NN>.png  at the store's target size

Each output gets the brand background (Crypto Checker orange), the caption and
subline from captions.json on top and the screenshot below it, scaled to fit,
with rounded corners and a soft shadow. No device frames of real brands.

Only needs Python 3 and Pillow (with libraqm for Arabic, Persian, Hebrew, Hindi
and Thai shaping – Pillow wheels from PyPI include it).

Examples
  python3 tools/frame_screenshots.py                         # all locales in raw/, phone + iPhone 6.9"
  python3 tools/frame_screenshots.py --platforms play-phone ios-ipad13 --locales de-DE en-US
  python3 tools/frame_screenshots.py --font /path/NotoSans-Bold.ttf --align center
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFilter, ImageFont, features
except ImportError:  # pragma: no cover
    sys.exit("Pillow fehlt: pip install Pillow")

REPO = Path(__file__).resolve().parents[1]
KIT = REPO / "docs" / "store" / "screenshots"

# Target sizes (portrait). Google Play: phone 9:16 with >= 1080 px short side;
# tablets optional. Apple: iPhone 6.9" is required, iPad 13" if iPad is enabled.
PLATFORMS = {
    "play-phone": (1080, 1920),
    "play-tablet7": (1200, 1920),
    "play-tablet10": (1600, 2560),
    "ios-iphone69": (1320, 2868),
    "ios-ipad13": (2064, 2752),
}
DEFAULT_PLATFORMS = ["play-phone", "ios-iphone69"]

# App Store / other folder names -> key in captions.json
ALIASES = {
    "ar-SA": "ar", "cs": "cs-CZ", "da": "da-DK", "de": "de-DE", "el": "el-GR", "en": "en-US",
    "en-GB": "en-US", "es": "es-ES", "es-MX": "es-ES", "fi": "fi-FI", "fr": "fr-FR", "fr-CA": "fr-FR",
    "he": "iw-IL", "iw": "iw-IL", "he-IL": "iw-IL", "hi": "hi-IN", "hu": "hu-HU", "in": "id",
    "id-ID": "id", "it": "it-IT", "ja": "ja-JP", "ko": "ko-KR", "nb": "no-NO", "no": "no-NO",
    "nb-NO": "no-NO", "nl": "nl-NL", "pl": "pl-PL", "pt": "pt-PT", "ro-RO": "ro", "ru": "ru-RU",
    "sq-AL": "sq", "sv": "sv-SE", "th-TH": "th", "tr": "tr-TR", "uk-UA": "uk", "vi-VN": "vi",
    "zh": "zh-CN", "zh-Hans": "zh-CN", "fa-IR": "fa",
}
RTL = {"ar", "fa", "iw", "he"}

# Brand colours (app accent orange #BE532C, light #DD6F48)
BG_TOP = (232, 129, 92)
BG_MID = (221, 111, 72)
BG_BOTTOM = (176, 70, 33)
TEXT = (255, 255, 255)
SUBTEXT = (255, 240, 233)


# ---------------------------------------------------------------- fonts

def _lang(locale: str) -> str:
    return locale.replace("_", "-").split("-")[0].lower()


SCRIPT_FAMILIES = {
    # language -> preferred families (first installed one wins)
    "ja": ["Noto Sans CJK JP", "Noto Sans JP", "Source Han Sans JP", "Hiragino Sans", "IPAGothic"],
    "ko": ["Noto Sans CJK KR", "Noto Sans KR", "Source Han Sans KR", "Apple SD Gothic Neo"],
    "zh": ["Noto Sans CJK SC", "Noto Sans SC", "Source Han Sans SC", "PingFang SC", "WenQuanYi Zen Hei"],
    "ar": ["Noto Sans Arabic", "Noto Naskh Arabic", "Vazirmatn", "DejaVu Sans", "FreeSerif"],
    "fa": ["Vazirmatn", "Noto Sans Arabic", "Noto Naskh Arabic", "DejaVu Sans", "FreeSerif"],
    "iw": ["Noto Sans Hebrew", "Rubik", "DejaVu Sans", "FreeSans"],
    "he": ["Noto Sans Hebrew", "Rubik", "DejaVu Sans", "FreeSans"],
    "hi": ["Noto Sans Devanagari", "Mukta", "Lohit Devanagari", "FreeSans", "FreeSerif"],
    "th": ["Noto Sans Thai", "Noto Sans Thai Looped", "Sarabun", "Loma", "Garuda"],
}
LATIN_FAMILIES = ["Inter", "Noto Sans", "Roboto", "DejaVu Sans", "Liberation Sans", "FreeSans"]

# Last resort when fontconfig is missing (macOS / Windows)
FALLBACK_FILES = [
    "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
    "/Library/Fonts/Arial Unicode.ttf",
    "/System/Library/Fonts/Helvetica.ttc",
    "C:/Windows/Fonts/arialbd.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]


def _fc_match(family: str, weight: str) -> tuple[str, int] | None:
    """Font file for an installed family via fontconfig – None if not really installed."""
    if not shutil.which("fc-match"):
        return None
    try:
        res = subprocess.run(["fc-match", "-f", "%{family}|%{file}|%{index}", f"{family}:{weight}"],
                             capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    if "|" not in res:
        return None
    fams, path, index = res.rsplit("|", 2)
    # fc-match always answers – accept only if the family really matches
    if family.lower() not in [f.strip().lower() for f in fams.split(",")]:
        return None
    return path, int(index or 0)


def resolve_fonts(locale: str, font: str | None, font_bold: str | None):
    """(bold, regular) as (path, index) for the given locale."""
    if font or font_bold:
        b = font_bold or font
        r = font or font_bold
        return (b, 0), (r, 0)
    fams = SCRIPT_FAMILIES.get(_lang(locale), []) + LATIN_FAMILIES
    for fam in fams:
        bold = _fc_match(fam, "bold") or _fc_match(fam, "semibold")
        reg = _fc_match(fam, "medium") or _fc_match(fam, "regular")
        if bold:
            return bold, reg or bold
    for path in FALLBACK_FILES:
        if os.path.exists(path):
            return (path, 0), (path, 0)
    sys.exit(f"Keine passende Schrift für {locale} gefunden – bitte --font angeben.")


def load(spec, size):
    path, index = spec
    return ImageFont.truetype(path, size=size, index=index,
                              layout_engine=ImageFont.Layout.RAQM if RAQM else ImageFont.Layout.BASIC)


RAQM = features.check("raqm")


# ---------------------------------------------------------------- text

def text_kwargs(locale: str) -> dict:
    if not RAQM:
        return {}
    kw = {"language": locale.split("-")[0] if _lang(locale) != "iw" else "he"}
    if _lang(locale) in RTL:
        kw["direction"] = "rtl"
    return kw


def text_width(draw, s, font, kw):
    l, _, r, _ = draw.textbbox((0, 0), s, font=font, **kw)
    return r - l


def wrap(draw, s, font, max_w, kw, lang):
    """Greedy wrap; CJK breaks between characters, other scripts at spaces."""
    if text_width(draw, s, font, kw) <= max_w:
        return [s]
    cjk = lang in ("ja", "zh")
    tokens = list(s) if cjk else s.split(" ")
    sep = "" if cjk else " "
    lines, cur = [], ""
    for t in tokens:
        cand = (cur + sep + t) if cur else t
        if text_width(draw, cand, font, kw) <= max_w or not cur:
            cur = cand
        else:
            lines.append(cur)
            cur = t
    if cur:
        lines.append(cur)
    return lines


def fit_block(draw, s, spec, size, max_w, max_lines, kw, lang):
    """Prefer one line (shrinking to 80 %), then up to max_lines (down to 62 %)."""
    for lines_allowed, min_scale in ((1, 0.80), (max_lines, 0.62)):
        step = 0
        while True:
            sz = int(size * (1 - step * 0.02))
            if sz < size * min_scale:
                break
            f = load(spec, sz)
            lines = wrap(draw, s, f, max_w, kw, lang)
            if len(lines) <= lines_allowed and all(text_width(draw, l, f, kw) <= max_w for l in lines):
                return f, lines
            step += 1
    f = load(spec, int(size * 0.62))
    return f, wrap(draw, s, f, max_w, kw, lang)


# ---------------------------------------------------------------- drawing

CHART = [(0, .93), (.12, .9), (.23, .92), (.33, .86), (.44, .89), (.54, .81), (.64, .84), (.76, .74),
         (.86, .77), (1, .66)]


def _gradient(w, h):
    img = Image.new("RGB", (w, h), BG_MID)
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = min(1.0, max(0.0, (y / h) * 0.85 + (x / w) * 0.15))
            if t < 0.45:
                a, b, k = BG_TOP, BG_MID, t / 0.45
            else:
                a, b, k = BG_MID, BG_BOTTOM, (t - 0.45) / 0.55
            px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * k) for i in range(3))
    return img


def background(w, h):
    """Brand gradient (computed small, then scaled) with a faint chart line."""
    img = _gradient(max(2, w // 8), max(2, h // 8)).resize((w, h), Image.BICUBIC)
    d = ImageDraw.Draw(img, "RGBA")
    d.line([(x * w, y * h) for x, y in CHART], fill=(255, 255, 255, 34), width=max(3, w // 260), joint="curve")
    return img


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.width - 1, img.height - 1], radius=radius, fill=255)
    return mask


def frame_one(raw_path, out_path, size, caption, subline, locale, fonts, align):
    W, H = size
    canvas = background(W, H)
    draw = ImageDraw.Draw(canvas)
    lang = _lang(locale)
    kw = text_kwargs(locale)
    rtl = lang in RTL
    margin = int(W * 0.08)
    max_w = W - 2 * margin
    bold, regular = fonts

    cap_font, cap_lines = fit_block(draw, caption, bold, int(W * 0.074), max_w, 2, kw, lang)
    sub_font, sub_lines = fit_block(draw, subline, regular, int(W * 0.038), max_w, 2, kw, lang)

    y = int(H * 0.055)

    def put(lines, font, fill, gap):
        nonlocal y
        asc, desc = font.getmetrics()
        lh = int((asc + desc) * 1.12)
        for line in lines:
            tw = text_width(draw, line, font, kw)
            if align == "center":
                x = (W - tw) // 2
            elif rtl:
                x = W - margin - tw
            else:
                x = margin
            draw.text((x, y), line, font=font, fill=fill, **kw)
            y += lh
        y += gap

    put(cap_lines, cap_font, TEXT, int(H * 0.008))
    put(sub_lines, sub_font, SUBTEXT, 0)
    top = y + int(H * 0.035)

    # screenshot: fit into the remaining box, bottom may run off the canvas a little
    shot = Image.open(raw_path).convert("RGB")
    box_w = int(W * 0.80)
    box_h = H - top - int(H * 0.035)
    scale = min(box_w / shot.width, box_h / shot.height)
    sw, sh = max(1, int(shot.width * scale)), max(1, int(shot.height * scale))
    shot = shot.resize((sw, sh), Image.LANCZOS)
    radius = int(sw * 0.06)
    sx = (W - sw) // 2
    sy = top

    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    off = int(W * 0.012)
    sd.rounded_rectangle([sx, sy + off, sx + sw, sy + sh + off], radius=radius, fill=(70, 20, 0, 110))
    shadow = shadow.filter(ImageFilter.GaussianBlur(int(W * 0.02)))
    canvas = Image.alpha_composite(canvas.convert("RGBA"), shadow)

    # thin light border so dark screenshots separate from the background
    border = Image.new("RGBA", (sw + 6, sh + 6), (255, 255, 255, 0))
    ImageDraw.Draw(border).rounded_rectangle([0, 0, sw + 5, sh + 5], radius=radius + 3, fill=(255, 255, 255, 90))
    canvas.alpha_composite(border, (sx - 3, sy - 3))
    canvas.paste(shot, (sx, sy), rounded(shot, radius))

    out_path.parent.mkdir(parents=True, exist_ok=True)
    canvas.convert("RGB").save(out_path, optimize=True)  # RGB: App Store rejects alpha


# ---------------------------------------------------------------- main

def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--raw", type=Path, default=KIT / "raw", help="Ordner mit <locale>/<NN>.png")
    p.add_argument("--out", type=Path, default=KIT / "out", help="Zielordner")
    p.add_argument("--captions", type=Path, default=KIT / "captions.json")
    p.add_argument("--platforms", nargs="+", default=DEFAULT_PLATFORMS, choices=sorted(PLATFORMS))
    p.add_argument("--locales", nargs="*", help="nur diese Ordner unter raw/")
    p.add_argument("--font", help="Schriftdatei (wird für Titel und Untertitel genutzt)")
    p.add_argument("--font-bold", help="eigene Schriftdatei nur für den Titel")
    p.add_argument("--align", choices=["start", "center"], default="start",
                   help="start = links, bei ar/fa/he rechts (Standard); center = zentriert")
    a = p.parse_args(argv)

    data = json.loads(a.captions.read_text(encoding="utf-8"))["locales"]
    if not a.raw.is_dir():
        sys.exit(f"Ordner fehlt: {a.raw}")
    locales = a.locales or sorted(d.name for d in a.raw.iterdir() if d.is_dir())
    if not RAQM:
        print("Hinweis: Pillow ohne libraqm – Arabisch/Persisch/Hebräisch/Hindi/Thai werden falsch gesetzt.",
              file=sys.stderr)

    count = 0
    for loc in locales:
        key = loc if loc in data else ALIASES.get(loc)
        if key not in data:
            print(f"! {loc}: keine Texte in captions.json – übersprungen", file=sys.stderr)
            continue
        fonts = resolve_fonts(key, a.font, a.font_bold)
        shots = sorted((a.raw / loc).glob("[0-9][0-9].png"))
        if not shots:
            print(f"! {loc}: keine Bilder NN.png", file=sys.stderr)
        for shot in shots:
            scene = shot.stem
            txt = data[key].get(scene)
            if not txt:
                print(f"! {loc}/{shot.name}: keine Texte für Szene {scene}", file=sys.stderr)
                continue
            for plat in a.platforms:
                out = a.out / plat / loc / shot.name
                frame_one(shot, out, PLATFORMS[plat], txt["caption"], txt["subline"], key, fonts, a.align)
                count += 1
                print(f"  {out}")
    print(f"{count} Bild(er) erzeugt.")
    return 0 if count else 1


if __name__ == "__main__":
    sys.exit(main())
