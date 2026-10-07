package com.cryptochecker.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Drei graue, sanft pulsierende Zeilen statt eines Kreisels. */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, rows: Int = 3) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )
    val block = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(
        // Puls erst beim Zeichnen gelesen: kein Neuaufbau der Liste je Bild
        modifier = modifier.readableWidth().padding(16.dp).graphicsLayer { alpha = pulse },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(rows) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(16.dp)
            ) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(block))
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Box(Modifier.width(110.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(block))
                    Box(Modifier.padding(top = 8.dp).width(70.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(block))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Box(Modifier.width(80.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(block))
                    Box(Modifier.padding(top = 8.dp).width(48.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(block))
                }
            }
        }
    }
}

/**
 * Hülle für form-gleiche Platzhalter (z. B. Karten des Markt-Tabs): pulsiert wie
 * [SkeletonList]; bei reduzierter Bewegung stehend. Der Puls wirkt nur auf die
 * Ebene ([graphicsLayer]) und setzt den Inhalt nicht jedes Bild neu zusammen.
 */
@Composable
fun SkeletonPulse(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    // Wert ändert sich zur Laufzeit nicht (einmal gelesen) — der bedingte Aufruf ist stabil
    val pulse = if (rememberReduceMotion()) null else rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )
    Column(modifier = modifier.graphicsLayer { alpha = pulse?.value ?: 0.7f }, content = content)
}

/**
 * Grauer Balken anstelle einer Textzeile in [style]: genau so hoch wie die echte
 * Zeile (folgt auch der Schriftgrösse), die Breite bestimmt [modifier]. Für
 * Screenreader unsichtbar.
 */
@Composable
fun SkeletonLine(style: TextStyle, modifier: Modifier = Modifier) {
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    Text(
        text = " ",
        style = style,
        maxLines = 1,
        modifier = modifier
            .clearAndSetSemantics { }
            .drawBehind {
                // Etwas schmaler als die Zeile, mittig — wie Text ohne Ober-/Unterlängen
                val h = size.height * 0.72f
                drawRoundRect(
                    color = block,
                    topLeft = Offset(0f, (size.height - h) / 2f),
                    size = Size(size.width, h),
                    cornerRadius = CornerRadius(h / 2f)
                )
            }
    )
}

/**
 * Graue Pille in der Höhe einer Etikette mit Text in [style] und Innenabstand
 * [horizontal]/[vertical] (z. B. Zonen-Etikett, Hinweis-Pillen); die Breite bestimmt
 * [modifier]. Für Screenreader unsichtbar.
 */
@Composable
fun SkeletonPill(
    style: TextStyle,
    modifier: Modifier = Modifier,
    horizontal: Dp = 10.dp,
    vertical: Dp = 6.dp,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = horizontal, vertical = vertical)
    ) {
        Text(" ", style = style, maxLines = 1, modifier = Modifier.clearAndSetSemantics { })
    }
}

/** Grauer Block fester Höhe (Skala, Balken, Chart-Fläche); Breite und Höhe bestimmt [modifier]. */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, corner: Dp = 50.dp) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clearAndSetSemantics { }
    )
}

/**
 * Platzhalter für mehrzeiligen Text: [text] (z. B. der echte feste Text oder ein Beispiel
 * mit typischen Zahlen) wird unsichtbar gesetzt und je Zeile ein grauer Balken gezeichnet —
 * so ist der Platzhalter genau so hoch wie der spätere Text (auch bei Umbruch und
 * Schriftgrösse). Für Screenreader unsichtbar.
 */
@Composable
fun SkeletonText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = style,
        color = Color.Transparent,
        onTextLayout = { layout = it },
        modifier = modifier
            .clearAndSetSemantics { }
            .drawBehind {
                // Erst beim Zeichnen gelesen: neue Zeilen zeichnen nur neu
                val result = layout ?: return@drawBehind
                for (line in 0 until result.lineCount) {
                    val top = result.getLineTop(line)
                    val bottom = result.getLineBottom(line)
                    val left = result.getLineLeft(line)
                    val right = result.getLineRight(line)
                    val h = (bottom - top) * 0.72f
                    drawRoundRect(
                        color = block,
                        topLeft = Offset(minOf(left, right), top + (bottom - top - h) / 2f),
                        size = Size(kotlin.math.abs(right - left), h),
                        cornerRadius = CornerRadius(h / 2f)
                    )
                }
            }
    )
}
