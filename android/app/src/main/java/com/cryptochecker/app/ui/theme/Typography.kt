package com.cryptochecker.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.cryptochecker.app.R

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
 */
val appTypography = Typography()

/** Display: grosse Werte (Portfolio-Summe, Kurs im Blatt, Fear & Greed, Dominanz) — 36 sp. */
val Typography.display: TextStyle get() = displaySmall

/** Display, kompakt: Werte in Karten und Kopfzeilen (Marktphase-Index, Laufband, Positionswert) — 28 sp. */
val Typography.displayCompact: TextStyle get() = headlineMedium

/** Headline: Titel von Bildschirmen und Blättern — 24 sp. */
val Typography.headline: TextStyle get() = headlineSmall

/** Title: Titel von Karten und Abschnitten — 16 sp. */
val Typography.title: TextStyle get() = titleMedium

/** Body: Fliesstext — 14 sp. */
val Typography.body: TextStyle get() = bodyMedium

/** Label: Nebentexte, Metadaten, Pillen — 12 sp. */
val Typography.label: TextStyle get() = labelMedium

/**
 * Schrift nur für Beträge (Kurse, Summen, Prozent-Pillen): Rubik, leicht gerundet,
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

/** Betrag: [AmountFontFamily] mit gleich breiten Ziffern, damit Zahlen beim Aktualisieren nicht springen. */
fun TextStyle.amountNumbers(): TextStyle = copy(fontFamily = AmountFontFamily, fontFeatureSettings = "tnum")

/** Numeric: Ziffern gleich breit (OpenType `tnum`), damit Kurse beim Aktualisieren nicht springen. */
fun TextStyle.tabularNumbers(): TextStyle = copy(fontFeatureSettings = "tnum")
