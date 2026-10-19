package com.cryptochecker.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.cryptochecker.app.R
import com.cryptochecker.app.util.LocaleNumbers

/*
 * Eine Schriftskala für die ganze App (wie `AppFont` in iOS). Grundlage sind die
 * Material-3-Stufen; die semantischen Namen unten sagen, wofür eine Stufe da ist:
 *
 * - Display  — grosse Werte: Portfolio-Summe, Kurs im Blatt, Marktwerte ([display], [displayCompact])
 * - Headline — Titel von Bildschirmen und Blättern ([headline])
 * - Title    — Titel von Karten und Abschnitten ([title]; `titleSmall`/`titleLarge` als Nebenstufen)
 * - Body     — Fliesstext ([body]; `bodyLarge`/`bodySmall` als Nebenstufen)
 * - Label    — Nebentexte, Metadaten, Pillen ([label]; `labelLarge`/`labelSmall` als Nebenstufen)
 * - Numeric  — [tabularNumbers] (gleich breite Ziffern) bzw. [amountNumbers] (Betragsschrift);
 *              alle Kurse, Prozente und Portfolio-Werte tragen eines von beiden.
 *
 * Display und Headline haben dieselben Grössen wie iOS (34 / 22), damit eine Stufe auf beiden
 * Plattformen gleich wirkt; Label bleibt bei den Plattform-Massen (12 sp / iOS footnote 13 pt).
 * `labelSmall` (12 sp statt Material 11 sp: 11 sp ist auf vielen Geräten kaum lesbar) nur für
 * Quellenangaben, rechtliche Fusszeilen und Chart-Beschriftungen — sonst [label];
 * `TypographyUsageTest` hält die Zahl der Stellen fest.
 */
val appTypography = Typography().let { base ->
    base.copy(labelSmall = base.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp))
}

/** Display: grosse Werte (Portfolio-Summe, Kurs im Blatt, Fear & Greed, Dominanz) — 34 sp wie iOS. */
val Typography.display: TextStyle get() = displaySmall.copy(fontSize = 34.sp, lineHeight = 42.sp)

/** Display, kompakt: Werte in Karten und Kopfzeilen (Marktphase-Index, Laufband, Positionswert) — 28 sp. */
val Typography.displayCompact: TextStyle get() = headlineMedium

/** Headline: Titel von Bildschirmen und Blättern — 22 sp wie iOS `title2`. */
val Typography.headline: TextStyle get() = titleLarge

/** Title: Titel von Karten und Abschnitten — 16 sp. */
val Typography.title: TextStyle get() = titleMedium

/** Body: Fliesstext — 14 sp. */
val Typography.body: TextStyle get() = bodyMedium

/** Label: Nebentexte, Metadaten, Pillen — 12 sp. */
val Typography.label: TextStyle get() = labelMedium

/**
 * Schrift nur für Beträge (Kurse, Summen, Prozent-Änderungen): Rubik, leicht gerundet,
 * mit gleich breiten Ziffern über das OpenType-Merkmal `tnum` (SIL Open Font License,
 * siehe `assets/licenses/Rubik-OFL.txt`). Feste Schnitte statt der variablen Schrift
 * (aus Rubik[wght] erzeugt, auf Latein, Kyrillisch, Hebräisch und Satzzeichen
 * beschränkt); fehlende Zeichen (z. B. ▲ ▼ oder andere Schriften) kommen aus der
 * Systemschrift. Fliesstext bleibt in der Systemschrift.
 */
val AmountFontFamily = FontFamily(
    Font(R.font.rubik_regular, FontWeight.Normal),
    Font(R.font.rubik_medium, FontWeight.Medium),
    Font(R.font.rubik_semibold, FontWeight.SemiBold),
)

/**
 * Betrag: [AmountFontFamily] mit gleich breiten Ziffern, damit Zahlen beim Aktualisieren nicht springen.
 * Schreibt die App-Sprache eigene Ziffern (Arabisch «٧٢», Persisch «۷۲»), bleibt die Systemschrift (nur
 * gleich breite Ziffern): Rubik hat diese Ziffern und Trennzeichen nicht, ein Betrag mischte sonst zwei
 * Schriften (Ziffern aus der Systemschrift, «USDT» und Vorzeichen aus Rubik).
 */
fun TextStyle.amountNumbers(): TextStyle =
    if (LocaleNumbers.hasLatinDigits()) copy(fontFamily = AmountFontFamily, fontFeatureSettings = "tnum")
    else tabularNumbers()

/** Numeric: Ziffern gleich breit (OpenType `tnum`), damit Kurse beim Aktualisieren nicht springen. */
fun TextStyle.tabularNumbers(): TextStyle = copy(fontFeatureSettings = "tnum")
