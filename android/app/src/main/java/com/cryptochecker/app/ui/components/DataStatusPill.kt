package com.cryptochecker.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.A11ySpeech

/** Ton der [DataStatusPill] — gleiche Farben wie die Status-Pille der Merkliste. */
enum class DataStatusTone {
    /** Grün: alles aktuell. */
    FRESH,

    /** Neutral (grau): offline oder lädt gerade — kein Fehler der App. */
    NEUTRAL,

    /** Rot: veraltet. */
    WARN,
}

/**
 * Farbe zu [tone]: aktuell onSurface (ruhig, nicht in der Steigen-Farbe — sonst sähen «frisch» und «gestiegen»
 * gleich aus), neutral onSurfaceVariant, veraltet error (wie die Merkliste).
 */
@Composable
fun dataStatusColor(tone: DataStatusTone): Color = when (tone) {
    DataStatusTone.FRESH -> MaterialTheme.colorScheme.onSurface
    DataStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    DataStatusTone.WARN -> MaterialTheme.colorScheme.error
}

/**
 * Zustand der gezeigten Daten als Pille wie in der Merkliste: Punkt in der Tonfarbe und der Satz,
 * z. B. «Alles aktuell · vor 2 Min.», «Offline · Stand 14:05», «Kurse veraltet · Stand 09:12».
 * Für Markt- und Portfolio-Tab (die Merkliste hat ihre eigene, antippbare Pille). Farbe wechselt
 * weich, ausser bei reduzierter Bewegung. Screenreader: der Satz mit « · » als Pause, oder [spoken].
 * Wie iOS `DataStatusPill`.
 */
@Composable
fun DataStatusPill(
    text: String,
    tone: DataStatusTone,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    spoken: String? = null,
) {
    val color by animateColorAsState(
        targetValue = dataStatusColor(tone),
        animationSpec = if (reduceMotion) snap() else tween(STATUS_FADE_MILLIS),
        label = "data_status_tone"
    )
    val description = spoken ?: A11ySpeech.spokenDots(text)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = Spacing.sm)
            .clearAndSetSemantics { contentDescription = description }
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.tabularNumbers(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** Weicher Wechsel der Farbe (wie die Status-Pille der Merkliste). */
private const val STATUS_FADE_MILLIS = 200
