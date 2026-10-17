// Effekte der Merkliste: der Erst-Moment nach dem ersten Paar bzw. der Start-Auswahl (Haptik, Banner «… wird jetzt
// überwacht», ggf. «Alarm setzen») und «oben bleiben», wenn der Puls oben erscheint.
// Eigene Datei, weil es eigenständige Abläufe sind; `WatchlistScreen` bleibt die eine Bildschirm-Funktion.
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.starter.AddMoment
import kotlinx.coroutines.delay

/**
 * Erst-Moment (Start-Merkliste, allererstes Paar): einmal abspielen, sobald die neuen Zeilen da
 * sind — ein leichtes Haptik-Signal und das Banner «… wird jetzt überwacht»; haben die neuen
 * Paare noch keinen Alarm, mit «Alarm setzen» (öffnet die Alarme des ersten neuen Paars — wie
 * im Aktionsblatt). Nur dieser Zustandswechsel wird animiert, nichts Dauerhaftes.
 * @return der laufende Moment (für die Staffelung der Zeilen) oder null
 */
@Composable
internal fun rememberAddMoment(
    viewModel: WatchlistViewModel,
    watches: List<WatchEntity>,
    loaded: Boolean,
    alarmCounts: Map<Long, Int>,
    banner: WatchlistBanner,
    onOpenAlarms: (Long) -> Unit,
): AddMoment? {
    val pendingMoment by viewModel.addMoment.collectAsStateWithLifecycle()
    val view = LocalView.current
    // Texte über LocalResources (Lint: kein getString über LocalContext in Compose)
    val resources = LocalResources.current
    var shownMoment by remember { mutableStateOf<AddMoment?>(null) }
    val readyMoment = pendingMoment?.takeIf { m ->
        loaded && watches.any { w -> m.indexOf(w.marketKey, w.baseAsset, w.quoteAsset) != null }
    }
    LaunchedEffect(shownMoment) {
        val m = shownMoment ?: return@LaunchedEffect
        delay(AddMoment.totalMillis(m.pairs.size))
        if (shownMoment == m) shownMoment = null
    }
    val alarmActionLabel = stringResource(R.string.add_alarm_action)
    LaunchedEffect(readyMoment) {
        val m = readyMoment ?: return@LaunchedEffect
        shownMoment = m
        // Ein leichtes Signal je Aktion, nicht je Zeile
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY
        )
        // Mehrere Paare: eigene Mehrzahl-Fassung («… sind jetzt in deiner Merkliste»)
        val text = resources.getString(
            if (m.pairs.size > 1) R.string.pairs_added_watching else R.string.pair_added_watching,
            AddMoment.subject(m.pairs)
        )
        val added = watches.filter { w -> m.indexOf(w.marketKey, w.baseAsset, w.quoteAsset) != null }
        val first = m.pairs.firstOrNull()?.let { p ->
            added.firstOrNull { p.matches(it.marketKey, it.baseAsset, it.quoteAsset) }
        }
        // Der SnackbarHost meldet Text und Aktion dem Screenreader (Live-Region)
        if (first != null && added.none { (alarmCounts[it.id] ?: 0) > 0 }) {
            banner.showAction(text, alarmActionLabel, ALARM_HINT_MILLIS) { onOpenAlarms(first.id) }
        } else {
            banner.show(text)
        }
        viewModel.consumeAddMoment(m)
    }
    return readyMoment ?: shownMoment
}

/** So lange bleibt «… wird jetzt überwacht» mit «Alarm setzen» stehen (ohne Screenreader). */
private const val ALARM_HINT_MILLIS = 6_000L

/**
 * Taucht der Puls neu auf (Gruppe gewechselt, Suche zu, zweiter Kurs da), hielte LazyColumn
 * die bisherige erste Zeile per Schlüssel oben fest und der Puls läge unsichtbar darüber.
 * Stand die Liste ganz oben, bleibt sie oben. Gelesen wird nur beim Wechsel und ohne
 * Beobachtung — Scrollen löst keinen Neuaufbau aus.
 */
@Composable
internal fun KeepTopWhenPulseAppears(pulseShown: Boolean, listState: LazyListState) {
    val pulseWasShown = remember { booleanArrayOf(pulseShown) }
    if (pulseShown != pulseWasShown[0]) {
        pulseWasShown[0] = pulseShown
        if (pulseShown) {
            Snapshot.withoutReadObservation {
                if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                    listState.requestScrollToItem(0)
                }
            }
        }
    }
}
