package com.cryptochecker.app.ui.components

import android.os.SystemClock
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.util.LoadProgress
import com.cryptochecker.resources.R
import kotlinx.coroutines.delay

/**
 * Dünner Ladebalken oben über dem Inhalt ([progress] 0–1, null = es lädt nichts): erscheint sofort, sobald etwas lädt
 * (Inhaber: «sofort anzeigen»), rückt mit jedem fertigen Teil weich vor und blendet aus, sobald alles da ist.
 * Merkliste, Markt und Portfolio. Wie iOS `LoadProgressBar`.
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

/**
 * Kreisel des Ziehens: genau [LoadProgress.PULL_ACK_MILLIS] als «verstanden» — auch wenn das Laden früher fertig ist
 * (überall gleich: Widgets und Uhr mindestens so lange); dauert es länger, übernimmt danach der Balken
 * ([LoadProgressBar]). [active] = von Hand angestossenes Laden läuft. Wie iOS `PullAck`.
 */
@Composable
fun rememberPullAck(active: Boolean): Boolean {
    var shown by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        if (active) {
            startedAt = SystemClock.elapsedRealtime()
            shown = true
            delay(LoadProgress.PULL_ACK_MILLIS)
            shown = false
        } else if (shown) {
            // Schon fertig: die restliche Zeit noch zeigen
            delay((LoadProgress.PULL_ACK_MILLIS - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L))
            shown = false
        }
    }
    return shown
}
