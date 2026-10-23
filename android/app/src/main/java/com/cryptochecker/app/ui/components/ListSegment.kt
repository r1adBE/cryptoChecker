package com.cryptochecker.app.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Coin-Listen als eine Einheit (Merkliste, Start-Auswahl, Heute auffällig, Portfolio): die Zeilen
 * liegen ohne Fuge aneinander und wirken wie eine einzige Karte — aussen gerundet (oben an der
 * ersten, unten an der letzten Zeile; eine einzelne Zeile ringsum), innen eckig, dazwischen eine
 * feine Trennlinie über die ganze Breite ([segmentLines]). Wie `ListSegment` (iOS).
 */
object ListSegment {
    /** Fuge zwischen zwei Zeilen: keine, die Trennlinie zeichnet die Zeile selbst. */
    val Gap = 0.dp

    /** Abstand zu anderen Elementen über bzw. unter der Liste (zusammen mit [Gap] 8 dp). */
    val Spacing = 8.dp - Gap

    /** Logo in allen Coin-Listen gleich gross (wie «+» und Lupe: 34). */
    val Logo = 34.dp

    /** Stärke von Trennlinie und Rand. */
    internal val Line = 1.dp

    private val Outer = 16.dp

    /** Form der Zeile [index] von [count] Zeilen. */
    fun shape(index: Int, count: Int): Shape =
        SegmentShape(first = index <= 0, last = index >= count - 1, radius = Outer.value)

    /** Farbe der Trennlinie zwischen den Zeilen: volle Token-Farbe, nie halbtransparent. */
    @Composable
    @ReadOnlyComposable
    fun dividerColor(): Color = MaterialTheme.colorScheme.outlineVariant

    /** Farbe des feinen Rands aussen um die Liste (wie früher der Rand jeder Karte); gleich wie die Trennlinie. */
    @Composable
    @ReadOnlyComposable
    fun outlineColor(): Color = MaterialTheme.colorScheme.outlineVariant
}

/**
 * Form einer Zeile in der Liste: gerundet nur, wo sie den Rand der Liste bildet. Datenklasse,
 * damit gleiche Plätze gleich sind (keine unnötige Neuzeichnung); [segmentLines] liest daraus,
 * ob eine Trennlinie bzw. welcher Rand dazugehört.
 */
internal data class SegmentShape(val first: Boolean, val last: Boolean, val radius: Float) : Shape {
    private val rounded = RoundedCornerShape(
        topStart = if (first) radius.dp else 0.dp,
        topEnd = if (first) radius.dp else 0.dp,
        bottomStart = if (last) radius.dp else 0.dp,
        bottomEnd = if (last) radius.dp else 0.dp,
    )

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        rounded.createOutline(size, layoutDirection, density)
}

/**
 * Linien einer Listenzeile mit der Form [shape] aus [ListSegment.shape]: unten eine feine
 * Trennlinie über die ganze Breite (nicht an der letzten Zeile) und — mit [outline] — der feine
 * Rand nur aussen (Seiten, oben an der ersten, unten an der letzten Zeile), damit die Liste wie
 * eine Karte mit Rand aussieht und nie doppelte Linien entstehen. Gehört zur Zeile selbst, wandert
 * also beim Wischen und Ziehen mit. Andere Formen: nur der Rand ringsum.
 */
fun Modifier.segmentLines(shape: Shape, divider: Color, outline: Color? = null): Modifier = drawWithContent {
    drawContent()
    val segment = shape as? SegmentShape
    val first = segment?.first ?: true
    val last = segment?.last ?: true
    val line = ListSegment.Line.toPx()
    if (outline != null) {
        // Andere Formen: Radius wie MaterialTheme.shapes.medium (16 dp)
        val radius = (segment?.radius ?: 16f).dp.toPx() - line / 2
        val top = CornerRadius(if (first) radius else 0f)
        val bottom = CornerRadius(if (last) radius else 0f)
        // Innen liegende Kanten über die Zeile hinaus verlegen: abgeschnitten bleiben nur die Seiten
        val rect = RoundRect(
            left = line / 2,
            top = if (first) line / 2 else -line * 2,
            right = size.width - line / 2,
            bottom = if (last) size.height - line / 2 else size.height + line * 2,
            topLeftCornerRadius = top,
            topRightCornerRadius = top,
            bottomRightCornerRadius = bottom,
            bottomLeftCornerRadius = bottom,
        )
        val path = Path().apply { addRoundRect(rect) }
        clipRect { drawPath(path, outline, style = Stroke(width = line)) }
    }
    if (!last) {
        // Zwischen den Seitenrändern, damit sich die Linien nicht überlagern
        val inset = if (outline != null) line else 0f
        drawRect(
            color = divider,
            topLeft = Offset(inset, size.height - line),
            size = Size(size.width - inset * 2, line),
        )
    }
}
