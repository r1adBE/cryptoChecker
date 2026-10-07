package com.cryptochecker.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.cryptochecker.app.R


val appTypography = Typography(
//    defaultFontFamily = redHatDisplay,
//    h1 = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Black),
)

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
