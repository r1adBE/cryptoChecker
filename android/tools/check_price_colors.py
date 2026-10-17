#!/usr/bin/env python3
"""
Prüft die Kursfarben der Börsen-Schemata «Frisch», «Traditionell», «Farbsehschwäche» und
«Ruhig» dunkel gegen die Regeln aus public/DEVELOPMENT.md (Abschnitt Kursfarben).
Reines Python, keine Abhängigkeiten. Gibt je Farbe den Zielton und den Abstand dazu aus.

Börsen-Schemata (Entscheid 18. Oktober 2026: «Farben wie bei allen Börsen, die Akzentfarbe
soll nicht irritieren»):
  - Ziel: der Ton der Börsen-App, so nah wie möglich (CIEDE2000) — Frisch = Binance
    #0ECB81 / #F6465D, Traditionell = TradingView #089981 / #F23645, Farbsehschwäche =
    Blau/Orange (hell #1565C0 / #E65100, dunkel #3D9BFF / #F7931A). Hoher Kontrast ebenso.
  - Hart: Kontrast als Schrift ≥ 4.5:1 (hoher Kontrast ≥ 7:1) auf allen hellen/warmen bzw.
    dunklen Flächen.
  - Frisch und Traditionell je Farbe ΔE2000 ≥ 8 (sichtbar verschieden).
  - Nur Farbsehschwäche: steigend/fallend ΔE2000 ≥ 35 unter Protanopie, Deuteranopie und
    Tritanopie (Machado 2009, volle Ausprägung) und Leuchtdichte-Verhältnis ≥ 1.4.
  - Kein Abstand zu Akzentfarben, keine Richtung hell/dunkel für Frisch/Traditionell.
«Ruhig» dunkel (fallend, Entscheid 18. Oktober 2026): Lachs-Rot statt Rosa; ≥ 4.5:1 auf allen
dunklen Flächen, heller als steigend mit Leuchtdichte-Verhältnis ≥ 1.5 (auch bei Deuteranopie),
ΔE2000 ≥ 24 zu Blau/Grün/Marrs Green (Rot/Orange nehmen Ersatzfassungen), Karmin (neben Orange)
ΔE2000 ≥ 20 zu Orange und C* ≥ 30.

Aufruf: python3 android/tools/check_price_colors.py  (Exit-Code 1 bei Verstoss)
Werte wie PriceColorScheme.kt / ios/Shared/Util/Theme.swift (vom Skript nicht gelesen).
"""
import math
import sys

VARIANTS = ("hell", "dunkel", "hell HK", "dunkel HK")

# Schema → (steigend, fallend), je (hell, dunkel, hell hoher Kontrast, dunkel hoher Kontrast)
SCHEMES = {
    "Frisch": ((0x127248, 0x0ECB81, 0x025430, 0x30DE92), (0xC3213A, 0xFC6F7D, 0x911127, 0xFFAEB7)),
    "Traditionell": ((0x0A7262, 0x3EAE97, 0x005246, 0x5AD8BE), (0xCC0416, 0xFE7062, 0x960208, 0xFEB2A4)),
    "Farbsehschwäche": ((0x064E93, 0x4F9FFD, 0x00335F, 0xCFEAFF), (0xB73900, 0xFFA73E, 0x842A00, 0xFFB46A)),
}

# Zieltöne (steigend, fallend) je (hell, dunkel); hoher Kontrast nimmt den Zielton des Modus
TARGETS = {
    "Frisch": ((0x0ECB81, 0x0ECB81), (0xF6465D, 0xF6465D)),
    "Traditionell": ((0x089981, 0x089981), (0xF23645, 0xF23645)),
    "Farbsehschwäche": ((0x1565C0, 0x3D9BFF), (0xE65100, 0xF7931A)),
}

# «Ruhig» dunkel: steigend (Grün, Blau neben grünem Akzent), fallend (Rot, Karmin neben Orange)
CALM_RISES_DARK = (0x61AE87, 0x8D9CD3)
CALM_RED_DARK = 0xFFB097
CALM_CRIMSON_DARK = 0xFFAFBB

# Akzent (primary dunkel) für «Ruhig» (AccentColor.kt / Theme.swift)
ACCENTS_DARK = {"Orange": 0xED835E, "Rot": 0xFF7173, "Blau": 0x73A3FC, "Grün": 0x4ABE83, "Marrs Green": 0x5FB8B1}

LIGHT_SURFACES = [0xFFFFFF, 0xF9F9F9, 0xF3F3F3, 0xEEEEEE, 0xE8E8E8, 0xE4E4E4, 0xE2E2E2,
                  0xFFFDFA, 0xFBF9F6, 0xF6F3EE, 0xF1EEE9, 0xEBE8E2, 0xE7E4DD, 0xE5E2DB]
DARK_SURFACES = [0x0E0E0E, 0x121212, 0x131313, 0x1B1B1B, 0x1F1F1F, 0x2A2A2A, 0x333333, 0x353535]

MIN_FRESH_VS_TRAD = 8.0
MIN_CVD_DELTA = 35.0
MIN_CVD_RATIO = 1.4

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


def chroma(rgb):
    _, a, b = lab(rgb)
    return math.hypot(a, b)


def check():
    failures = []

    def need(ok, text):
        if not ok:
            failures.append(text)

    print(f"{'Schema':<24}{'Ziel ↑':>9}{'gewählt':>9}{'ΔE':>6}{'Ziel ↓':>9}{'gewählt':>9}{'ΔE':>6}{'Kontr.':>8}{'Zusatz':>22}")
    for name, (rise, fall) in SCHEMES.items():
        for i, variant in enumerate(VARIANTS):
            dark = i % 2 == 1
            hc = i >= 2
            up, down = rise[i], fall[i]
            t_up, t_down = TARGETS[name][0][i % 2], TARGETS[name][1][i % 2]
            key = f"{name} {variant}"
            surfaces = DARK_SURFACES if dark else LIGHT_SURFACES
            floor = 7.0 if hc else 4.5
            min_c = min(contrast(c, s) for c in (up, down) for s in surfaces)
            need(min_c >= floor, f"{key}: Kontrast {min_c:.2f}")
            extra = ""
            if name == "Traditionell":
                f_rise, f_fall = SCHEMES["Frisch"]
                d_up, d_down = delta_e(up, f_rise[i]), delta_e(down, f_fall[i])
                need(d_up >= MIN_FRESH_VS_TRAD, f"{key}: steigend ΔE {d_up:.1f} zu Frisch")
                need(d_down >= MIN_FRESH_VS_TRAD, f"{key}: fallend ΔE {d_down:.1f} zu Frisch")
                extra = f"zu Frisch {d_up:.1f}/{d_down:.1f}"
            if name == "Farbsehschwäche":
                ratio = contrast(up, down)
                sims = min(sim_delta(up, down, k) for k in CVD)
                need(ratio >= MIN_CVD_RATIO, f"{key}: Leuchtdichte steigend/fallend {ratio:.2f}")
                need(sims >= MIN_CVD_DELTA, f"{key}: ΔE simuliert {sims:.1f}")
                extra = f"L {ratio:.2f} CVD {sims:.1f}"
            print(f"{key:<24}{'#%06X' % t_up:>9}{'#%06X' % up:>9}{delta_e(up, t_up):>6.1f}"
                  f"{'#%06X' % t_down:>9}{'#%06X' % down:>9}{delta_e(down, t_down):>6.1f}{min_c:>8.2f}{extra:>22}")

    print()
    for label, color, accents, min_acc in (
        ("Ruhig fallend dunkel", CALM_RED_DARK, ("Blau", "Grün", "Marrs Green"), 24.0),
        ("Ruhig Karmin dunkel", CALM_CRIMSON_DARK, ("Orange",), 20.0),
    ):
        min_c = min(contrast(color, s) for s in DARK_SURFACES)
        need(min_c >= 4.5, f"{label}: Kontrast {min_c:.2f}")
        rises = CALM_RISES_DARK if color == CALM_RED_DARK else CALM_RISES_DARK[:1]
        ratios = [contrast(color, u) for u in rises]
        deut = [sim_ratio(color, u, "Deuteranopie") for u in rises]
        for u, r, d in zip(rises, ratios, deut):
            need(luminance(color) > luminance(u), f"{label}: nicht heller als #{u:06X}")
            need(r >= 1.5 and d >= 1.5, f"{label}: Leuchtdichte zu #{u:06X} {r:.2f} / Deuteranopie {d:.2f}")
        acc = min(delta_e(color, ACCENTS_DARK[n]) for n in accents)
        need(acc >= min_acc, f"{label}: ΔE {acc:.1f} zum Akzent")
        if color == CALM_CRIMSON_DARK:
            need(chroma(color) >= 30, f"{label}: C* {chroma(color):.1f}")
        print(f"{label:<24}#{color:06X}  HSL {hsl_hue(color):5.1f}°  C* {chroma(color):4.1f}  Kontr. {min_c:.2f}  "
              f"L-Verh. {min(ratios):.3f}  Deut. {min(deut):.3f}  ΔE Akzent {acc:.1f}")

    if failures:
        print("\nVerstösse:")
        for f in failures:
            print("  - " + f)
        return 1
    print("\nAlle Regeln erfüllt.")
    return 0


if __name__ == "__main__":
    sys.exit(check())
