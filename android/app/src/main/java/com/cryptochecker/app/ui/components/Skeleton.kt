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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.Spacing

/**
 * Drei graue, sanft pulsierende Zeilen statt eines Kreisels — Puls über [SkeletonPulse], bei reduzierter Bewegung
 * («Animationen entfernen») stehend. Für Screenreader ein Knoten «Wird geladen…» mit unbestimmtem Fortschritt.
 * [reduceMotion] nur für Tests vorgeben.
 */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, rows: Int = 3, reduceMotion: Boolean = rememberReduceMotion()) {
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    val loading = stringResource(R.string.loading_hint)

    SkeletonPulse(
        modifier = modifier.readableWidth().padding(16.dp).semantics {
            contentDescription = loading
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        },
        reduceMotion = reduceMotion,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
                        Box(Modifier.width(110.dp).height(14.dp).clip(RoundedCornerShape(50)).background(block))
                        Box(Modifier.padding(top = 8.dp).width(70.dp).height(10.dp).clip(RoundedCornerShape(50)).background(block))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Box(Modifier.width(80.dp).height(14.dp).clip(RoundedCornerShape(50)).background(block))
                        Box(Modifier.padding(top = 8.dp).width(48.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(block))
                    }
                }
            }
        }
    }
}

/**
 * Hülle für form-gleiche Platzhalter (z. B. Karten des Markt-Tabs, [SkeletonList]): pulsiert sanft
 * (0,45 … 1 in 900 ms hin und zurück); bei reduzierter Bewegung stehend (Deckkraft [STILL_ALPHA]).
 * Der Puls wirkt nur auf die Ebene ([graphicsLayer]) und setzt den Inhalt nicht jedes Bild neu zusammen.
 * [reduceMotion] nur für Tests vorgeben.
 */
@Composable
fun SkeletonPulse(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    reduceMotion: Boolean = rememberReduceMotion(),
    content: @Composable ColumnScope.() -> Unit,
) {
    // Wechselt «Animationen entfernen» zur Laufzeit, baut Compose den Zweig neu auf (bedingter Aufruf als eigene Gruppe)
    val pulse = if (reduceMotion) null else rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )
    Column(
        modifier = modifier.graphicsLayer { alpha = pulse?.value ?: STILL_ALPHA },
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/** Deckkraft der Platzhalter bei reduzierter Bewegung (stehend, wie iOS `SkeletonPulse`). */
internal const val STILL_ALPHA = 0.7f

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
