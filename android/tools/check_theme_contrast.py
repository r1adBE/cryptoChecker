#!/usr/bin/env python3
"""
Prüft die Themenfarben (alle Akzentfarben, hell und dunkel, normal und hoher Kontrast) gegen WCAG 2.x:
  - Schrift < 18 pt (bzw. < 14 pt fett): ≥ 4.5:1  (hoher Kontrast: ≥ 7:1)
  - grosse Schrift, Symbole, Bedienelement-Ränder: ≥ 3:1
Paare: Haupt-, Neben- und Warntext, Fehlerfarbe, Platzhalter (beide Plattformen onSurfaceVariant),
Akzent als Schrift auf allen Flächen und auf den hellen Akzent-Containern, Symbole in
`outline`; dazu Schrift und Symbole auf den Auswahl-Tönungen und den iOS-Fehlerflächen (Deckkraft je
Verwendung, über Seite/Blatt/Menü). Deaktivierte Texte (Material: onSurface · 0.38,
iOS: System) sind nach WCAG 1.4.3 ausgenommen und werden nur gemeldet.

Werte wie AppColorSchemes.kt / ErrorColors.kt / AppColors.kt (Android) bzw. ios/Shared/Util/Theme.swift;
primary, Neben-/Haupttext, outline und Fehlerfarbe gleicht das Skript mit dem Quelltext ab ([source_mismatches]).
Unit-Tests: AccentContrastTest, ThemeContrastTest, ErrorContrastTest bzw. iOS ThemeContrastTests, ErrorColorTests.
Kursfarben prüft check_price_colors.py (Formeln von dort).

Aufruf: python3 android/tools/check_theme_contrast.py  (Exit-Code 1 bei Verstoss)
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_price_colors import contrast  # noqa: E402  (gleiche WCAG-Formel)

ACCENTS = ("Orange", "Rot", "Blau", "Grün", "Marrs Green")

# primary je Akzent: hell, dunkel, hell hoher Kontrast, dunkel hoher Kontrast
PRIMARY = {
    "Orange": (0xA64827, 0xED835E, 0x78341C, 0xF5B7A2),
    "Rot": (0xBE2B38, 0xFF7173, 0x891F28, 0xFFAFB1),
    "Blau": (0x2B5FC8, 0x73A3FC, 0x1F4490, 0xA8C6FD),
    "Grün": (0x107348, 0x4ABE83, 0x0C5334, 0x88D4AD),
    "Marrs Green": (0x22706B, 0x5FB8B1, 0x184E4B, 0x92CFCA),
}
# hell: (primaryContainer, onPrimaryContainer, secondaryContainer, onSecondaryContainer)
CONTAINERS_LIGHT = {
    "Orange": (0xFEE1D7, 0x410D00, 0xFFDBCF, 0x301407),
    "Rot": (0xFEE0DE, 0x480008, 0xFEDAD7, 0x311210),
    "Blau": (0xDCE9FE, 0x001A57, 0xDCE1FF, 0x111B33),
    "Grün": (0xD3F1DE, 0x002A16, 0xC6EBD4, 0x012111),
    "Marrs Green": (0xD7EFEC, 0x072826, 0xCBE8E5, 0x071F1E),
}

LIGHT_SURFACES = {
    "Lowest": 0xFFFDFA, "surface": 0xFBF9F6, "Low": 0xF6F3EE, "Container": 0xF1EEE9,
    "High": 0xEBE8E2, "Widget tief": 0xE7E4DD, "Highest": 0xE5E2DB,
}
DARK_SURFACES = {
    "Lowest": 0x0E0E0E, "surface": 0x131313, "Low": 0x1B1B1B, "Container": 0x1F1F1F,
    "High": 0x2A2A2A, "Auswahl": 0x333333, "Highest": 0x353535,
}

ON_SURFACE = (0x1B1B1B, 0xE2E2E2)
ON_SURFACE_VARIANT = (0x474747, 0xC6C6C6)
OUTLINE = (0x777777, 0x919191)
ERROR = (0xB13419, 0xFF5833)
ERROR_CONTAINER = (0xFFDBD0, 0x4A1C10)
ON_ERROR_CONTAINER = (0x4A1C10, 0xFFDBD0)
WARNING_TEXT = (0x8A5300, 0xFFC94D)
WARNING_ICON = (0xB26B00, 0xFFC94D)

# Auswahl-Tönungen (Android `AppColors.accentTint`, iOS `accent.tint`): hell primary · a, dunkel onSurface · a · 0.8.
# Je Verwendung die höchste Deckkraft und was darauf steht: bis 0.16 Schrift (Hinweiskarten, hervorgehobene
# Merkliste-Zeile, Auswahl-Listen), 0.3 gewählte Zeile mit Schrift onSurface und Stern (DropdownField, Coin-Liste:
# voll primary, leer onSurfaceVariant — outline hielt dort < 3:1).
TINT_USES = (
    (0.16, ("onSurface", "onSurfaceVariant"), ("primary", "outline")),
    (0.30, ("onSurface",), ("primary", "onSurfaceVariant")),
)
# iOS-Fehlerflächen: error mit Deckkraft 0.08 … 0.12, darauf Schrift in error («Löschen», Fehlermeldung)
ERROR_FILLS = (0.08, 0.10, 0.12)
# Flächen unter Tönungen und Fehlerflächen: Seite, Blatt, Menü (Lowest … Container)
TINT_BASES = ("Lowest", "surface", "Low", "Container")
# Fehlertext steht dunkel nicht auf der Auswahlfläche #333333 und nicht auf Highest #353535; dort nur als Symbol
# (Snackbar-Rand und -Symbol, ≥ 3:1). Hell steht er auf allen Flächen.
ERROR_SYMBOL_ONLY_DARK = ("Auswahl", "Highest")
DISABLED_ALPHA = 0.38


def over(fg, alpha, bg):
    """[fg] mit Deckkraft [alpha] über dem deckenden [bg] (je Kanal, auf 8 Bit gerundet)."""
    out = 0
    for shift in (16, 8, 0):
        f, b = (fg >> shift) & 0xFF, (bg >> shift) & 0xFF
        out |= int(round(f * alpha + b * (1 - alpha))) << shift
    return out


def tint(accent, dark, alpha, bg):
    return over(ON_SURFACE[1], alpha * 0.8, bg) if dark else over(PRIMARY[accent][0], alpha, bg)


def role(name, accent, dark):
    return {"onSurface": ON_SURFACE, "onSurfaceVariant": ON_SURFACE_VARIANT, "outline": OUTLINE,
            "primary": (PRIMARY[accent][0], PRIMARY[accent][1])}[name][dark]


def pairs():
    """(Gruppe, Beschreibung, Vordergrund, Hintergrund, Grenze, ausgenommen)."""
    for d, mode in ((0, "hell"), (1, "dunkel")):
        surfaces = DARK_SURFACES if d else LIGHT_SURFACES
        for sname, bg in surfaces.items():
            where = f"{mode} auf {sname} #{bg:06X}"
            yield "Haupttext", f"onSurface {where}", ON_SURFACE[d], bg, 4.5, False
            yield "Nebentext", f"onSurfaceVariant {where}", ON_SURFACE_VARIANT[d], bg, 4.5, False
            # Platzhalter: Android Material-TextField, iOS `Text.placeholder` / `SearchFieldPlaceholder`
            yield "Platzhalter", f"onSurfaceVariant {where}", ON_SURFACE_VARIANT[d], bg, 4.5, False
            yield "Symbol outline", f"outline {where}", OUTLINE[d], bg, 3.0, False
            if d and sname in ERROR_SYMBOL_ONLY_DARK:
                yield "Fehler (Symbol)", f"error {where}", ERROR[d], bg, 3.0, False
            else:
                yield "Fehler", f"error {where}", ERROR[d], bg, 4.5, False
            yield "Warnung", f"warningText {where}", WARNING_TEXT[d], bg, 4.5, False
            yield "Warnung", f"warning (Symbol) {where}", WARNING_ICON[d], bg, 3.0, False
            yield "Deaktiviert", f"onSurface·{DISABLED_ALPHA} {where}", over(ON_SURFACE[d], DISABLED_ALPHA, bg), \
                bg, 4.5, True
            for acc in ACCENTS:
                yield "Akzent", f"{acc} primary {where}", PRIMARY[acc][d], bg, 4.5, False
                yield "Akzent hoher Kontrast", f"{acc} primary HK {where}", PRIMARY[acc][2 + d], bg, 7.0, False
            if sname not in TINT_BASES:
                continue
            for alpha in ERROR_FILLS:
                fill = over(ERROR[d], alpha, bg)
                yield "Fehlerfläche iOS", f"error auf error·{alpha} {where}", ERROR[d], fill, 4.5, False
            for acc in ACCENTS:
                for alpha, texts, icons in TINT_USES:
                    t = tint(acc, d, alpha, bg)
                    for name, floor in [(n, 4.5) for n in texts] + [(n, 3.0) for n in icons]:
                        kind = "Schrift" if floor > 3 else "Symbol"
                        yield f"Tönung {alpha} {kind}", f"{acc} {name} auf Tönung {where}", \
                            role(name, acc, d), t, floor, False
        yield "Fehlerfläche", f"onErrorContainer {mode}", ON_ERROR_CONTAINER[d], ERROR_CONTAINER[d], 4.5, False
        for acc in ACCENTS:
            p, on = PRIMARY[acc][d], (0xFFFFFF if not d else CONTAINERS_LIGHT[acc][1])
            yield "Auf Akzent", f"{acc} onPrimary auf primary {mode}", on, p, 4.5, False
    for acc in ACCENTS:
        pc, opc, sc, osc = CONTAINERS_LIGHT[acc]
        yield "Container hell", f"{acc} onPrimaryContainer auf primaryContainer", opc, pc, 4.5, False
        yield "Container hell", f"{acc} onSecondaryContainer auf secondaryContainer", osc, sc, 4.5, False
        yield "Container hell", f"{acc} primary auf primaryContainer", PRIMARY[acc][0], pc, 4.5, False


ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
THEME_KT = "android/app/src/main/java/com/cryptochecker/app/ui/theme/"
SCHEME_NAMES = {"Orange": "orange", "Rot": "red", "Blau": "blue", "Grün": "green", "Marrs Green": "marrsGreen"}


def source_mismatches():
    """Werte oben gegen AppColorSchemes.kt, ErrorColors.kt und Theme.swift; fehlende Dateien zählen als Abweichung."""
    def read(rel):
        try:
            with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
                return f.read()
        except OSError:
            return None

    out = []
    schemes, errors, swift = read(THEME_KT + "AppColorSchemes.kt"), read(THEME_KT + "ErrorColors.kt"), \
        read("ios/Shared/Util/Theme.swift")
    if None in (schemes, errors, swift):
        return ["Quelldatei fehlt (AppColorSchemes.kt, ErrorColors.kt oder Theme.swift)"]
    for acc, name in SCHEME_NAMES.items():
        for d, suffix in ((0, "Light"), (1, "Dark")):
            start = schemes.find(f"private val {name}{suffix} = ")
            block = schemes[start:schemes.find("\n)", start)] if start >= 0 else ""
            for key, value in (("primary", PRIMARY[acc][d]), ("onSurface", ON_SURFACE[d]),
                               ("onSurfaceVariant", ON_SURFACE_VARIANT[d]), ("outline", OUTLINE[d])):
                if f"    {key} = Color(0xFF{value:06X})" not in block:
                    out.append(f"AppColorSchemes.kt {name}{suffix}: {key} nicht #{value:06X}")
    if f"ErrorLight = Color(0xFF{ERROR[0]:06X})" not in errors or \
            f"ErrorDark = Color(0xFF{ERROR[1]:06X})" not in errors:
        out.append("ErrorColors.kt: Fehlerfarbe weicht ab")
    if f"static let error = Pair(light: 0x{ERROR[0]:06X}, dark: 0x{ERROR[1]:06X})" not in swift:
        out.append("Theme.swift: ColorTokens.error weicht ab")
    for key, pair in (("onSurfaceVariant", ON_SURFACE_VARIANT), ("outline", OUTLINE), ("onSurface", ON_SURFACE)):
        if f"static let {key} = Pair(light: 0x{pair[0]:06X}, dark: 0x{pair[1]:06X})" not in swift:
            out.append(f"Theme.swift: ColorTokens.{key} weicht ab")
    return out


def check(show_all=False):
    failures, exempt = [], []
    for line in source_mismatches():
        failures.append("Quelltext: " + line)
    for group, label, fg, bg, floor, is_exempt in pairs():
        r = contrast(fg, bg)
        line = f"{group:<28}{label:<62}#{fg:06X} / #{bg:06X}  {r:5.2f} (≥ {floor})"
        if show_all:
            print(line)
        if r < floor:
            (exempt if is_exempt else failures).append(line)
    if exempt:
        print("Ausgenommen (WCAG 1.4.3, deaktiviert), nur gemeldet:")
        for line in exempt:
            print("  " + line)
    if failures:
        print("\nUnter der Grenze:")
        for line in failures:
            print("  " + line)
        return 1
    print("\nAlle Themenfarben erfüllt.")
    return 0


if __name__ == "__main__":
    sys.exit(check(show_all="--all" in sys.argv))
