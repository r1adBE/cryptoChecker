package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.Spacing

/**
 * «LIVE» in der Status-Pille, solange Kurse per WebSocket kommen: kleiner pulsierender Punkt
 * und Schriftzug in der Farbe der Pille. «Bewegung reduzieren»: Punkt steht still.
 */
@Composable
internal fun LiveBadge(color: Color, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.watchlist_live_a11y)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(start = Spacing.sm)
            .clearAndSetSemantics { contentDescription = description }
    ) {
        val dotAlpha = if (reduceMotion) {
            1f
        } else {
            val transition = rememberInfiniteTransition(label = "live_pulse")
            transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
                label = "live_dot",
            ).value
        }
        Box(
            Modifier
                .size(6.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = stringResource(R.string.watchlist_live_badge),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

private const val PULSE_MILLIS = 900
