// Teile des Markt-Tabs: gestaffeltes Einblenden der Karten, Status-Pille der Daten und deren Zeitpunkt.
// Eigene Datei, weil `MarketPhaseScreen` sonst zu lang würde.
package com.cryptochecker.app.ui.features.info

import com.cryptochecker.app.ui.components.DataStatusPill
import com.cryptochecker.app.ui.components.DataStatusTone
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableLongStateOf
import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.MarketReveal
import com.cryptochecker.app.domain.market.MarketRevealSlot
import com.cryptochecker.app.ui.theme.Spacing

/**
 * Ein Teil des Tabs ([slot]): erst da, wenn [MarketReveal] ihn freigibt (auf «Jetzt», [onNow],
 * erst unter den Teilen darüber) — vorher weder gezeichnet noch im Bedienungshilfen-Baum.
 * Erscheint mit Einblenden und 8 dp von unten (220 ms), ausser er stand schon ([instant]) oder
 * Bewegung ist reduziert.
 * Die Animation wirkt nur auf die Ebene (graphicsLayer), nicht auf das Layout.
 */
@Composable
internal fun Reveal(
    slot: MarketRevealSlot,
    reveal: MarketReveal.State,
    instant: Set<MarketRevealSlot>,
    motion: Boolean,
    onNow: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (if (onNow) reveal.isShownOnNow(slot) else reveal.isShown(slot)) {
        RevealItem(animate = motion && slot !in instant, content = content)
    }
}

@Composable
private fun RevealItem(animate: Boolean, content: @Composable () -> Unit) {
    // Nur beim ersten Erscheinen massgebend; danach bleibt die Karte einfach stehen
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(progress) {
        if (progress.value < 1f) {
            progress.animateTo(1f, tween(MarketReveal.ENTER_MILLIS, easing = LinearOutSlowInEasing))
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                // Wert erst beim Zeichnen gelesen: kein Neuaufbau je Bild
                val p = progress.value
                alpha = p
                translationY = (1f - p) * 8.dp.toPx()
            }
    ) {
        content()
    }
}

/** Kleine Ladezeile fester Höhe unter der letzten sichtbaren Karte. */
@Composable
internal fun RevealLoadingRow() {
    val description = stringResource(R.string.loading_hint)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(40.dp)
            .clearAndSetSemantics { contentDescription = description }
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}

/**
 * Zustand oben im Markt-Tab wie in der Merkliste (statt einer Überschrift «Jetzt»): Punkt und Satz
 * in einer Pille — grün «Alles aktuell · vor 2 Min.» (Stand von «Was gerade auffällt»), während
 * still neu geladen wird neutral «Stand 14:05 · wird aktualisiert…» (ältester gezeigter Stand).
 * Ohne Netz (wie die Merkliste, [com.cryptochecker.app.util.ConnectivityMonitor]) neutral «Offline ·
 * Stand 14:05» — die Karten zeigen weiter den letzten Stand.
 * Immer gleich hoch, auch ohne Stand — darunter springt nichts. Wie iOS `statusPill`.
 */
@Composable
internal fun MarketStatusPill(refreshingSince: Long?, updatedAt: Long?, online: Boolean, animate: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val refreshing = refreshingSince != null && online
    val asOf = updatedAt?.takeIf { it > 0 } ?: refreshingSince
    val text = when {
        !online && asOf != null -> stringResource(R.string.offline_status_since, dataTime(asOf))
        !online -> stringResource(R.string.offline_status)
        refreshingSince != null -> stringResource(R.string.cycle_data_as_of, dataTime(refreshingSince))
        updatedAt != null && updatedAt > 0 ->
            stringResource(R.string.watchlist_all_fresh, com.cryptochecker.app.ui.features.watchlist.ago(updatedAt, now))
        else -> null
    }
    val tone = if (!online || refreshing || text == null) DataStatusTone.NEUTRAL else DataStatusTone.FRESH
    val visibility by animateFloatAsState(
        targetValue = if (text != null) 1f else 0f,
        animationSpec = tween(if (animate) 200 else 0),
        label = "market_status"
    )
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(bottom = Spacing.xs)
            .graphicsLayer { alpha = visibility }
    ) {
        if (text != null) {
            // Gleiche Pille wie im Portfolio-Tab (Aussehen wie die Status-Pille der Merkliste)
            DataStatusPill(
                text = text,
                tone = tone,
                reduceMotion = !animate,
                modifier = if (refreshing || !online) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
            )
        }
    }
}

/** Uhrzeit, bei älteren Daten (nicht von heute) mit Datum. */
@Composable
private fun dataTime(millis: Long): String {
    val context = LocalContext.current
    val flags = if (DateUtils.isToday(millis)) DateUtils.FORMAT_SHOW_TIME
    else DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    return DateUtils.formatDateTime(context, millis, flags)
}
