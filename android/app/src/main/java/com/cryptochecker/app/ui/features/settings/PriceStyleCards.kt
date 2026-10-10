package com.cryptochecker.app.ui.features.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.PriceColorScheme
import com.cryptochecker.app.ui.theme.LocalDarkTheme
import com.cryptochecker.app.ui.theme.LocalHighContrast

/**
 * Karte eines Kursfarben-Stils («Frisch», «Traditionell», «Farbsehschwäche») — wie iOS
 * `PriceStyleCard`: zwei Farbfelder (steigend, fallend), der Name und rechts eine kleine
 * Kerzen-Vorschau. Felder und Vorschau zeigen genau die Farben, die gelten würden (hoher
 * Kontrast und «Farben tauschen» eingerechnet). Alle Kerzen gefüllt, wie in den echten Charts.
 *
 * Gewählt: Rand in der Akzentfarbe (wie der gewählte Gruppen-Chip), Name kräftiger, Häkchen.
 * Ganze Karte tippbar; für den Screenreader ein Radioknopf mit dem Namen.
 */
@Composable
internal fun PriceStyleCard(
    scheme: PriceColorScheme,
    selected: Boolean,
    inverted: Boolean,
    onClick: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    val highContrast = LocalHighContrast.current
    val up = Color(scheme.up(dark, highContrast, inverted))
    val down = Color(scheme.down(dark, highContrast, inverted))
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(16.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(2.dp, if (selected) accent else Color.Transparent, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorSwatch(up)
                ColorSwatch(down)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(priceColorSchemeLabel(scheme)),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (selected) {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.padding(start = 6.dp).size(18.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        CandlePreview(up = up, down = down, modifier = Modifier.width(112.dp).height(48.dp))
    }
}

/** Kleines abgerundetes Farbfeld. */
@Composable
private fun ColorSwatch(color: Color) {
    Box(
        Modifier
            .size(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
    )
}

/**
 * Feste Kerzenreihe ([PriceStylePreview]): leichter Aufwärtstrend mit ein paar fallenden
 * Kerzen, ältere Kerzen blasser. Docht oben und unten getrennt gezeichnet, damit er sich
 * bei Transparenz nicht mit dem Körper überlagert.
 */
@Composable
private fun CandlePreview(up: Color, down: Color, modifier: Modifier) {
    Canvas(modifier) {
        val n = PriceStylePreview.COUNT
        val slot = size.width / n
        val bodyWidth = slot * 0.62f
        val wickWidth = 1.dp.toPx()
        val span = PriceStylePreview.high - PriceStylePreview.low
        fun y(v: Int): Float = size.height - (v - PriceStylePreview.low) / span * size.height
        for (i in 0 until n) {
            val open = PriceStylePreview.CLOSES[i]
            val close = PriceStylePreview.CLOSES[i + 1]
            val wick = PriceStylePreview.WICKS[i]
            val color = (if (close >= open) up else down).copy(alpha = PriceStylePreview.alpha(i))
            val cx = slot * i + slot / 2
            val bodyTop = y(maxOf(open, close))
            val bodyBottom = y(minOf(open, close))
            drawLine(color, Offset(cx, y(maxOf(open, close) + wick)), Offset(cx, bodyTop), strokeWidth = wickWidth)
            drawLine(color, Offset(cx, bodyBottom), Offset(cx, y(minOf(open, close) - wick)), strokeWidth = wickWidth)
            drawRect(
                color,
                topLeft = Offset(cx - bodyWidth / 2, bodyTop),
                size = Size(bodyWidth, maxOf(bodyBottom - bodyTop, 1f))
            )
        }
    }
}

/** Kerzen der Vorschau — dieselben Werte wie iOS `PriceStylePreview`. */
internal object PriceStylePreview {
    /** 31 Schlusskurse → 30 Kerzen (Eröffnung = Schluss der vorigen). */
    val CLOSES = intArrayOf(
        20, 23, 25, 22, 24, 27, 30, 28, 25, 27, 30, 32, 34, 31, 35, 37,
        39, 36, 32, 36, 39, 44, 42, 47, 50, 52, 50, 53, 57, 59, 63,
    )

    /** Dochtlänge je Kerze (oben und unten). */
    val WICKS = intArrayOf(2, 1, 3, 1, 3, 1, 3, 1, 2, 3, 2, 2, 2, 3, 2, 2, 2, 1, 1, 1, 1, 3, 2, 3, 2, 2, 2, 2, 3, 1)

    val COUNT = WICKS.size

    val low: Float = (0 until COUNT).minOf { minOf(CLOSES[it], CLOSES[it + 1]) - WICKS[it] }.toFloat()
    val high: Float = (0 until COUNT).maxOf { maxOf(CLOSES[it], CLOSES[it + 1]) + WICKS[it] }.toFloat()

    /** Deckkraft: die ältesten Kerzen 25 %, ab 70 % der Reihe voll. */
    fun alpha(index: Int): Float = 0.25f + 0.75f * minOf(1f, index / (COUNT * 0.7f))
}
