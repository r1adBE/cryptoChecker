@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.ui.theme.LocalHighContrast
import com.cryptochecker.app.ui.theme.PriceColors
import kotlinx.coroutines.flow.first

/** Mindestbreite des Bildschirms (dp, bei Schriftgrösse 100 %) für das Mini-Chart. */
internal const val SPARKLINE_MIN_SCREEN_DP = 360

/**
 * Mini-Chart: Linie mit sanfter Fläche darunter (Verlauf von der Kursfarbe zu
 * transparent), ohne Achsen und Punkte. Farbe nach Richtung über die
 * ganze Reihe (letzter ≥ erster Wert = steigend), gemäss Einstellung «Kursfarben».
 * Hoher Kontrast: Fläche kräftiger (18 % statt 12 %), die Linie ist immer voll.
 * Für den Screenreader beschreibt die Zeile den Verlauf ([A11yText.chart]).
 */
@Composable
internal fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, progress: () -> Float = { 1f }) {
    val up = values.last() >= values.first()
    val color = if (up) PriceColors.up else PriceColors.down
    val fillAlpha = if (LocalHighContrast.current) 0.18f else 0.12f
    // Pfade nur neu, wenn sich Verlauf, Farbe oder Grösse ändern (drawWithCache) — nicht bei
    // jedem Zeichnen und nicht bei jeder Neuzusammensetzung der Zeile (30-s-Uhr, Kurs-Takt).
    // Der Block selbst ist gemerkt; ein neuer Block würde den Zwischenspeicher verwerfen.
    val buildCache = remember<CacheDrawScope.() -> DrawResult>(values, color, fillAlpha, progress) {
        {
            val stroke = 1.75.dp.toPx()
            val inset = stroke
            val w = size.width - 2 * inset
            val h = size.height - 2 * inset
            val min = values.min()
            val max = values.max()
            val range = max - min
            val path = Path()
            // Fläche: dieselben Punkte, dann am unteren Rand zurück
            val area = Path()
            var top = size.height
            if (w > 0f && h > 0f) {
                values.forEachIndexed { i, v ->
                    val x = inset + w * i / values.lastIndex
                    // Flache Reihe: Linie in der Mitte
                    val y = if (range > 0.0) inset + h - ((v - min) / range * h).toFloat() else inset + h / 2
                    if (y < top) top = y
                    if (i == 0) {
                        path.moveTo(x, y)
                        area.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                        area.lineTo(x, y)
                    }
                }
                area.lineTo(inset + w, size.height)
                area.lineTo(inset, size.height)
                area.close()
            }
            val fill = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = fillAlpha), color.copy(alpha = 0f)),
                startY = top,
                endY = size.height,
            )
            val line = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind {
                if (w <= 0f || h <= 0f) return@onDrawBehind
                // Erst-Moment: von links nach rechts aufdecken (1 = ganz)
                val shown = progress().coerceIn(0f, 1f)
                if (shown <= 0f) return@onDrawBehind
                clipRect(right = size.width * shown) {
                    drawPath(path = area, brush = fill)
                    drawPath(path = path, color = color, style = line)
                }
            }
        }
    }
    Spacer(modifier = modifier.drawWithCache(buildCache))
}
