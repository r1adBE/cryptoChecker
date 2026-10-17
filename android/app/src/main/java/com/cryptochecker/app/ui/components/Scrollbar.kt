package com.cryptochecker.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Dauer des Einblendens beim Rollen (ms). */
private const val FADE_IN_MS = 150

/** Dauer des Ausblendens nach dem Rollen (ms). */
private const val FADE_OUT_MS = 1_000

/** Deckkraft des Balkens (Farbe des Haupttexts). */
private const val BAR_ALPHA = 0.7f

/**
 * Schmaler Rollbalken rechts an einer Liste mit [LazyListState]: sichtbar beim Rollen, danach langsam
 * ausgeblendet. Lage und Länge rechnet [ScrollThumb.span] (jede Zeile gleich viel Anteil).
 */
fun Modifier.simpleVerticalScrollbar(
    state: LazyListState,
    width: Dp = 8.dp,
): Modifier = composed {
    val rolling = state.isScrollInProgress
    val opacity by animateFloatAsState(
        targetValue = if (rolling) 1f else 0f,
        animationSpec = tween(durationMillis = if (rolling) FADE_IN_MS else FADE_OUT_MS),
        label = "scrollbar",
    )
    val barColor = MaterialTheme.colorScheme.onSurface.copy(alpha = BAR_ALPHA)

    drawWithContent {
        drawContent()
        // Ausgeblendet und still: nichts zeichnen
        if (opacity <= 0f && !state.isScrollInProgress) return@drawWithContent

        val info = state.layoutInfo
        val shown = info.visibleItemsInfo
        val span = ScrollThumb.span(shown.firstOrNull()?.index, shown.size, info.totalItemsCount, size.height)
            ?: return@drawWithContent
        val barPx = width.toPx()
        drawRect(
            color = barColor,
            topLeft = Offset(x = size.width - barPx, y = span.top),
            size = Size(width = barPx, height = span.height),
            alpha = opacity,
        )
    }
}
