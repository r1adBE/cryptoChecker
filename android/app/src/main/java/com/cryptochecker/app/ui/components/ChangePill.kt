package com.cryptochecker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat

/** Tönung der Pillen-Fläche in der Kursfarbe. */
private const val PILL_TINT_ALPHA = 0.14f

/**
 * Die Kapsel aller Kurs-Pillen (Merkliste, Aktionsblatt, «Warum?», Portfolio, Puls-Zeile):
 * rund, Fläche in [color] mit 14 % Tönung, Innenabstand 8 × 2 dp. Einzige Stelle, die sie zeichnet.
 */
fun Modifier.changePill(color: Color): Modifier =
    clip(RoundedCornerShape(50))
        .background(color.copy(alpha = PILL_TINT_ALPHA))
        .padding(horizontal = 8.dp, vertical = 2.dp)

/**
 * Prozent-Änderung als Pille in der Kursfarbe (Grün/Rot bzw. Blau/Orange), immer mit Vorzeichen
 * und Pfeil — die Bedeutung hängt nie allein an der Farbe. Praktisch keine Änderung: graues
 * «0.00%». Ohne Wert: mit [dashWhenMissing] eine graue Pille «—», sonst nichts.
 * Screenreader: «gestiegen um 2.35%» statt «+2.35%» (umgebende Zeilen dürfen ersetzen).
 */
@Composable
fun ChangePill(change: Double?, modifier: Modifier = Modifier, dashWhenMissing: Boolean = false) {
    val value = change?.takeIf { it.isFinite() }
    if (value == null && !dashWhenMissing) return
    val formatted = value?.let { PriceFormat.changePercent(it) }
    // Pfeil folgt dem Vorzeichen, nie dem Farbtausch; bei 0.00% und «—» keiner
    val text = when {
        value == null -> "—"
        formatted != null -> "${PriceFormat.changeArrow(value)} $formatted"
        else -> PriceFormat.zeroPercent()
    }
    val color = if (value == null || formatted == null) MaterialTheme.colorScheme.onSurfaceVariant
    else PriceColors.forChange(value)
    val spoken = value?.let { A11yText.change(LocalContext.current, it) }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.amountNumbers(),
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        modifier = modifier
            .then(if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken } else Modifier)
            .changePill(color)
    )
}
