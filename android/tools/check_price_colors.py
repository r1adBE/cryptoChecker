#!/usr/bin/env python3
"""
Prüft die festen Kursfarben «Frisch», «Traditionell» und «Farbsehschwäche» gegen die Regeln
aus public/DEVELOPMENT.md (Abschnitt Kursfarben). Reines Python, keine Abhängigkeiten.

Regeln (normal und hoher Kontrast, hell und dunkel):
  1. Farbton (HSL): Frisch steigend 140–165°, fallend 350–10°; Traditionell steigend 115–135°,
     fallend 0–8°; Traditionell je ΔE2000 ≥ 10 zur selben Farbe von Frisch.
  2. Kontrast als Schrift: normal ≥ 4.5:1, hoher Kontrast ≥ 7:1 auf allen hellen/warmen bzw.
     dunklen Flächen.
  3. Steigend und fallend in der Helligkeit getrennt: Leuchtdichte-Verhältnis ≥ 1.4
     (Farbsehschwäche ≥ 1.5, hoher Kontrast ≥ 1.25 wie «Ruhig»). Hell ist fallend die hellere,
     kräftigere Farbe (echtes Rot/Orange auf Papier), dunkel die dunklere (Entscheid 18. Oktober 2026).
  4. Farbsehschwäche: steigend/fallend ΔE2000 ≥ 40 unter Protanopie, Deuteranopie und
     Tritanopie (Machado 2009, volle Ausprägung); ΔE2000 ≥ 15 zu jedem Akzent desselben Modus
     ausser dem derselben Farbfamilie (steigend Blau, fallend Orange).
  5. Frisch/Traditionell: ΔE2000 ≥ 10 zu jedem Akzent desselben Modus ausser dem derselben
     Farbfamilie (steigend Grün/Marrs Green, fallend Rot); bei Deuteranopie Leuchtdichte-
     Verhältnis steigend/fallend wie Regel 3.
Abstand zum Akzent: gegen AccentColor.primary(dark) desselben Modus, auch für hohen Kontrast.
Hoher Kontrast: Farbsehschwäche-Simulation ΔE2000 ≥ 35 (siehe MIN_CVD_DELTA_HC).

Aufruf: python3 android/tools/check_price_colors.py  (Exit-Code 1 bei Verstoss)
Werte wie PriceColorScheme.kt / ios/Shared/Util/Theme.swift (vom Skript nicht gelesen).
"""
import math
import sys

# Schema → (steigend, fallend), je (hell, dunkel, hell hoher Kontrast, dunkel hoher Kontrast)
SCHEMES = {
    "Frisch": ((0x005A30, 0x18EA96, 0x00421E, 0x81FFBA), (0xCC0015, 0xFF6F69, 0x960000, 0xFFB1AB)),
    "Traditionell": ((0x033C00, 0x87FF7E, 0x002100, 0xE4FFE4), (0x990000, 0xFF9C99, 0x630000, 0xF9D2CD)),
    "Farbsehschwäche": ((0x003372, 0x96D8FF, 0x002A5A, 0xC9E7FF), (0xAE4200, 0xED810C, 0x7B3300, 0xFFB469)),
}

# Akzent (primary): hell, dunkel, hell hoher Kontrast, dunkel hoher Kontrast (AccentColor.kt / Theme.swift)
ACCENTS = {
    "Orange": (0xA64827, 0xED835E, 0x78341C, 0xF5B7A2),
    "Rot": (0xBE2B38, 0xFF7173, 0x891F28, 0xFFAFB1),
    "Blau": (0x2B5FC8, 0x73A3FC, 0x1F4490, 0xA8C6FD),
    "Grün": (0x107348, 0x4ABE83, 0x0C5334, 0x88D4AD),
    "Marrs Green": (0x22706B, 0x5FB8B1, 0x184E4B, 0x95D0CC),
}

LIGHT_SURFACES = [0xFFFFFF, 0xF9F9F9, 0xF3F3F3, 0xEEEEEE, 0xE8E8E8, 0xE4E4E4, 0xE2E2E2,
                  0xFFFDFA, 0xFBF9F6, 0xF6F3EE, 0xF1EEE9, 0xEBE8E2, 0xE7E4DD, 0xE5E2DB]
DARK_SURFACES = [0x0E0E0E, 0x121212, 0x131313, 0x1B1B1B, 0x1F1F1F, 0x2A2A2A, 0x333333, 0x353535]

HUE = {  # (steigend, fallend) als (von, bis) in Grad, über 0° hinweg erlaubt
    "Frisch": ((140, 165), (350, 10)),
    "Traditionell": ((115, 135), (0, 8)),
}
MIN_RATIO = {"Frisch": 1.4, "Traditionell": 1.4, "Farbsehschwäche": 1.5}
MIN_RATIO_HC = 1.25  # hoher Kontrast: wie «Ruhig»
# Akzente derselben Farbfamilie zählen nicht (Entscheid 18. Oktober 2026: Börsenfarben bleiben
# rot/grün bzw. blau/orange, auch wenn der Akzent dieselbe Farbe hat).
SAME_FAMILY = {
    "Frisch": ({"Grün", "Marrs Green"}, {"Rot"}),
    "Traditionell": ({"Grün", "Marrs Green"}, {"Rot"}),
    "Farbsehschwäche": ({"Blau"}, {"Orange"}),
}
MIN_ACCENT = {"Frisch": 10.0, "Traditionell": 10.0, "Farbsehschwäche": 15.0}
MIN_CVD_DELTA = 40.0
# Hoher Kontrast dunkel: ≥ 7:1 auf #353535 und 1.25:1 Abstand lassen nur Pastelltöne zu, dort
# erreicht Blau/Orange höchstens ≈ 38.7 (Suche über alle Töne); daher 35.
MIN_CVD_DELTA_HC = 35.0
MIN_TRAD_VS_FRESH = 10.0
VARIANTS = ("hell", "dunkel", "hell HK", "dunkel HK")

# Machado, Oliveira, Fernandes 2009, Ausprägung 1.0 (lineares sRGB)
CVD = {
    "Protanopie": ((0.152286, 1.052583, -0.204868), (0.114503, 0.786281, 0.099216), (-0.003882, -0.048116, 1.051998)),
    "Deuteranopie": ((0.367322, 0.860646, -0.227968), (0.280085, 0.672501, 0.047413), (-0.011820, 0.042940, 0.968881)),
    "Tritanopie": ((1.255528, -0.076749, -0.178779), (-0.078411, 0.930809, 0.147602), (0.004733, 0.691367, 0.303900)),
}


def channels(rgb):
    return (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF


def linear(c):
    s = c / 255.0
    return s / 12.92 if s <= 0.04045 else ((s + 0.055) / 1.055) ** 2.4


def lin_rgb(rgb):
    return tuple(linear(c) for c in channels(rgb))


def luminance_lin(r, g, b):
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def luminance(rgb):
    return luminance_lin(*lin_rgb(rgb))


def ratio_lum(la, lb):
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def contrast(a, b):
    return ratio_lum(luminance(a), luminance(b))


def simulate(rgb, kind):
    """Lineares sRGB nach Simulation [kind] (Machado 2009), auf 0…1 begrenzt, ohne Rundung auf 8 Bit."""
    r, g, b = lin_rgb(rgb)
    return tuple(min(1.0, max(0.0, row[0] * r + row[1] * g + row[2] * b)) for row in CVD[kind])


def sim_ratio(a, b, kind):
    return ratio_lum(luminance_lin(*simulate(a, kind)), luminance_lin(*simulate(b, kind)))


def sim_delta(a, b, kind):
    return delta_e_lab(lab_lin(*simulate(a, kind)), lab_lin(*simulate(b, kind)))


def lab(rgb):  # wie ColorDifference.kt
    return lab_lin(*lin_rgb(rgb))


def lab_lin(r, g, b):
    x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
    y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883

    def f(t):
        return t ** (1 / 3) if t > 216 / 24389 else (24389 / 27 * t + 16) / 116

    fx, fy, fz = f(x), f(y), f(z)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def delta_e(c1, c2):
    return delta_e_lab(lab(c1), lab(c2))


def delta_e_lab(lab1, lab2):
    l1, a1, b1 = lab1
    l2, a2, b2 = lab2
    p25 = 25.0 ** 7
    c_bar = (math.hypot(a1, b1) + math.hypot(a2, b2)) / 2
    g = 0.5 * (1 - math.sqrt(c_bar ** 7 / (c_bar ** 7 + p25)))
    a1p, a2p = (1 + g) * a1, (1 + g) * a2
    c1p, c2p = math.hypot(a1p, b1), math.hypot(a2p, b2)

    def hue(b, a):
        if a == 0 and b == 0:
            return 0.0
        h = math.degrees(math.atan2(b, a))
        return h + 360 if h < 0 else h

    h1, h2 = hue(b1, a1p), hue(b2, a2p)
    dl, dc = l2 - l1, c2p - c1p
    prod = c1p * c2p
    dh = h2 - h1
    if prod == 0:
        dh = 0.0
    elif dh > 180:
        dh -= 360
    elif dh < -180:
        dh += 360
    d_h = 2 * math.sqrt(prod) * math.sin(math.radians(dh / 2))
    l_bar = (l1 + l2) / 2
    c_bar_p = (c1p + c2p) / 2
    if prod == 0:
        h_bar = h1 + h2
    elif abs(h1 - h2) <= 180:
        h_bar = (h1 + h2) / 2
    elif h1 + h2 < 360:
        h_bar = (h1 + h2 + 360) / 2
    else:
        h_bar = (h1 + h2 - 360) / 2
    t = (1 - 0.17 * math.cos(math.radians(h_bar - 30)) + 0.24 * math.cos(math.radians(2 * h_bar))
         + 0.32 * math.cos(math.radians(3 * h_bar + 6)) - 0.20 * math.cos(math.radians(4 * h_bar - 63)))
    d_theta = 30 * math.exp(-(((h_bar - 275) / 25) ** 2))
    rc = 2 * math.sqrt(c_bar_p ** 7 / (c_bar_p ** 7 + p25))
    sl = 1 + 0.015 * (l_bar - 50) ** 2 / math.sqrt(20 + (l_bar - 50) ** 2)
    sc = 1 + 0.045 * c_bar_p
    sh = 1 + 0.015 * c_bar_p * t
    rt = -math.sin(math.radians(2 * d_theta)) * rc
    return math.sqrt((dl / sl) ** 2 + (dc / sc) ** 2 + (d_h / sh) ** 2 + rt * (dc / sc) * (d_h / sh))


def hsl_hue(rgb):
    r, g, b = (c / 255.0 for c in channels(rgb))
    mx, mn = max(r, g, b), min(r, g, b)
    d = mx - mn
    if d == 0:
        return 0.0
    if mx == r:
        h = ((g - b) / d) % 6
    elif mx == g:
        h = (b - r) / d + 2
    else:
        h = (r - g) / d + 4
    return h * 60


def in_hue(h, rng):
    lo, hi = rng
    return lo <= h <= hi if lo <= hi else (h >= lo or h <= hi)


def accent_distance(color, i, skip):
    """Kleinster ΔE2000 zum Akzent (primary, ohne [skip]) desselben Modus hell/dunkel; dazu der Name."""
    return min((delta_e(color, a[i % 2]), n) for n, a in ACCENTS.items() if n not in skip)


def check():
    failures = []
    rows = []

    def need(ok, text):
        if not ok:
            failures.append(text)

    for name, (rise, fall) in SCHEMES.items():
        for i, variant in enumerate(VARIANTS):
            dark = i % 2 == 1
            hc = i >= 2
            up, down = rise[i], fall[i]
            key = f"{name} {variant}"
            # 1 Farbton
            if name in HUE:
                need(in_hue(hsl_hue(up), HUE[name][0]), f"{key}: Farbton steigend {hsl_hue(up):.0f}°")
                need(in_hue(hsl_hue(down), HUE[name][1]), f"{key}: Farbton fallend {hsl_hue(down):.0f}°")
            if name == "Traditionell":
                f_rise, f_fall = SCHEMES["Frisch"]
                for c, other, side in ((up, f_rise[i], "steigend"), (down, f_fall[i], "fallend")):
                    d = delta_e(c, other)
                    need(d >= MIN_TRAD_VS_FRESH, f"{key}: {side} ΔE {d:.1f} zu Frisch")
            # 2 Kontrast
            surfaces = DARK_SURFACES if dark else LIGHT_SURFACES
            floor = 7.0 if hc else 4.5
            min_up = min(contrast(up, s) for s in surfaces)
            min_down = min(contrast(down, s) for s in surfaces)
            need(min_up >= floor, f"{key}: Kontrast steigend {min_up:.2f}")
            need(min_down >= floor, f"{key}: Kontrast fallend {min_down:.2f}")
            # 3 Helligkeit getrennt
            min_ratio = MIN_RATIO_HC if hc else MIN_RATIO[name]
            ratio = contrast(up, down)
            need(ratio >= min_ratio, f"{key}: Leuchtdichte steigend/fallend {ratio:.2f}")
            # Hell: fallend die hellere, kräftigere Farbe (Rot/Orange auf Papier); dunkel: fallend dunkler
            if dark:
                need(luminance(down) < luminance(up), f"{key}: fallend nicht dunkler als steigend")
            else:
                need(luminance(down) > luminance(up), f"{key}: fallend nicht heller als steigend")
            # 4/5 Simulation
            sim_ratios = {k: sim_ratio(up, down, k) for k in CVD}
            sim_deltas = {k: sim_delta(up, down, k) for k in CVD}
            if name == "Farbsehschwäche":
                for k, d in sim_deltas.items():
                    need(d >= (MIN_CVD_DELTA_HC if hc else MIN_CVD_DELTA), f"{key}: {k} ΔE {d:.1f}")
                    need(sim_ratios[k] >= min_ratio, f"{key}: {k} Leuchtdichte {sim_ratios[k]:.2f}")
            else:
                need(sim_ratios["Deuteranopie"] >= min_ratio,
                     f"{key}: Deuteranopie Leuchtdichte {sim_ratios['Deuteranopie']:.2f}")
            # Abstand zum Akzent desselben Modus (hoher Kontrast: Akzent mit hohem Kontrast)
            acc_up, near_up = accent_distance(up, i, SAME_FAMILY[name][0])
            acc_down, near_down = accent_distance(down, i, SAME_FAMILY[name][1])
            need(acc_up >= MIN_ACCENT[name], f"{key}: steigend ΔE {acc_up:.1f} zu Akzent {near_up}")
            need(acc_down >= MIN_ACCENT[name], f"{key}: fallend ΔE {acc_down:.1f} zu Akzent {near_down}")
            rows.append((key, up, down, min(min_up, min_down), ratio, sim_ratios["Deuteranopie"],
                         min(sim_deltas.values()), acc_up, acc_down))

    print(f"{'Schema':<24}{'steigend':>9}{'fallend':>9}{'Kontr.':>8}{'L-Verh.':>8}{'Deut.':>7}"
          f"{'ΔE CVD':>8}{'ΔE Akz.↑':>10}{'ΔE Akz.↓':>10}")
    for key, up, down, c, r, dr, cvd, au, ad in rows:
        print(f"{key:<24}{'#%06X' % up:>9}{'#%06X' % down:>9}{c:>8.2f}{r:>8.2f}{dr:>7.2f}{cvd:>8.1f}{au:>10.1f}{ad:>10.1f}")
    print("Hinweis: Kontr. = knappster Kontrast auf allen Flächen; ΔE CVD = kleinster Abstand "
          "steigend/fallend unter Protanopie, Deuteranopie, Tritanopie.")
    if failures:
        print("\nVerstösse:")
        for f in failures:
            print("  - " + f)
        return 1
    print("\nAlle Regeln erfüllt.")
    return 0


if __name__ == "__main__":
    sys.exit(check())
