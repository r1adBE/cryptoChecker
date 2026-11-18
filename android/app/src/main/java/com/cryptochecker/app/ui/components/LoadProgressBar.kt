package com.cryptochecker.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cryptochecker.resources.R

/**
 * Dünner Ladebalken oben über dem Inhalt ([progress] 0–1, null = keiner): rückt mit jedem fertigen Teil weich vor
 * und blendet aus, sobald alles da ist. Ersetzt beim Öffnen den Kreis über den Beträgen. Wie iOS `LoadProgressBar`.
 */
@Composable
fun LoadProgressBar(progress: Float?, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    // Fertig (null): weich auf voll, während er ausblendet
    val shown by animateFloatAsState(
        targetValue = progress ?: 1f,
        animationSpec = if (reduceMotion) snap() else tween(LOAD_STEP_MILLIS),
        label = "load_progress"
    )
    val label = stringResource(R.string.loading_hint)
    AnimatedVisibility(
        visible = progress != null,
        enter = fadeIn(if (reduceMotion) snap() else tween(LOAD_FADE_MILLIS)),
        exit = fadeOut(if (reduceMotion) snap() else tween(LOAD_FADE_MILLIS)),
        modifier = modifier
    ) {
        LinearProgressIndicator(
            progress = { shown },
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .semantics { contentDescription = label }
        )
    }
}

private const val LOAD_STEP_MILLIS = 350
private const val LOAD_FADE_MILLIS = 200
