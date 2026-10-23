package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.ChipShape
import com.cryptochecker.app.ui.theme.label

// Teile der Gruppen-Chips ([GroupChips]): Ausblend-Rand der Zeile und Namenshinweis unter einem Symbol-Chip.

/** Breite des weichen Ausblend-Rands am Ende der Chip-Zeile. */
private val CHIP_FADE_WIDTH = 24.dp

/**
 * Weicher Ausblend-Rand am Ende (rechts, bei RTL links), solange [scrollsOn] gilt — Hinweis, dass die Zeile weiter
 * scrollt. Nur Zeichnung: Tippflächen und Screenreader bleiben unverändert. Wie iOS `fadeEnd`.
 */
internal fun Modifier.fadeEnd(scrollsOn: () -> Boolean): Modifier = this
    // Eigene Ebene, damit DstIn nur die Chips ausblendet und nicht den Hintergrund darunter
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (!scrollsOn()) return@drawWithContent
        val fade = CHIP_FADE_WIDTH.toPx().coerceAtMost(size.width)
        val rtl = layoutDirection == LayoutDirection.Rtl
        val brush = if (rtl) {
            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade)
        } else {
            Brush.horizontalGradient(
                listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width,
            )
        }
        drawRect(brush = brush, blendMode = BlendMode.DstIn)
    }

/** Ein Tipp auf einen Symbol-Chip; absichtlich ohne `equals` (jeder Tipp zählt neu). */
internal class ChipHint(val key: String)

/** So lange bleibt der Namenshinweis unter einem Symbol-Chip stehen. */
internal const val CHIP_HINT_MILLIS = 1500L

/** Ein- und Ausblenden des Hinweises (mit «Bewegung reduzieren» sofort). */
private const val CHIP_HINT_FADE_MILLIS = 150

/**
 * Kleiner Namenshinweis unter einem Symbol-Chip (dunkle Blase, labelSmall). Als Popup, damit er
 * die Zeile nicht höher macht und die scrollende Chip-Zeile ihn nicht abschneidet. Für
 * Screenreader unsichtbar (sie lesen den Namen schon am Chip) und nicht fokussierbar.
 */
@Composable
internal fun ChipHintPopup(visible: Boolean, text: String) {
    val reduceMotion = rememberReduceMotion()
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (reduceMotion) snap<Float>() else tween<Float>(CHIP_HINT_FADE_MILLIS),
        label = "chipHint"
    )
    // Ausgeblendet und fertig animiert: kein Popup mehr
    if (!visible && alpha == 0f) return
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val position = remember(gap) { BelowAnchorPosition(gap) }
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false)) {
        Text(
            text = text,
            style = MaterialTheme.typography.label,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .graphicsLayer { this.alpha = alpha }
                .clearAndSetSemantics { }
                .background(MaterialTheme.colorScheme.inverseSurface, ChipShape)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** Popup unter dem Anker, waagrecht mittig; am Fensterrand nach innen gerückt. */
private class BelowAnchorPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxX)
        return IntOffset(x, anchorBounds.bottom + gap)
    }
}
