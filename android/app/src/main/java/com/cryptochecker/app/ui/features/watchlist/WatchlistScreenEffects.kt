// Effekte der Merkliste: der Erst-Moment nach dem ersten Paar bzw. der Start-Auswahl (Haptik, Banner «… wird jetzt
// überwacht», ggf. «Alarm setzen»), «oben bleiben», wenn der Puls oben erscheint, die 30-s-Uhr und die Wege in die
// Merkliste (Meldung, Widget, Zurück-Taste).
// Eigene Datei, weil es eigenständige Abläufe sind; `WatchlistScreen` bleibt die eine Bildschirm-Funktion.
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.WatchlistStorageLock
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.starter.AddMoment
import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableLongStateOf
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics

/**
 * Erst-Moment (Start-Merkliste, allererstes Paar): einmal abspielen, sobald die neuen Zeilen da
 * sind — ein leichtes Haptik-Signal und das Banner «… wird jetzt überwacht»; haben die neuen
 * Paare noch keinen Alarm, mit «Alarm setzen» (öffnet die Alarme des ersten neuen Paars — wie
 * im Aktionsblatt). Nur dieser Zustandswechsel wird animiert, nichts Dauerhaftes.
 * [onHintClosed] kommt einmal, sobald dieses Banner wieder weg ist (mit `true`, wenn «Alarm setzen» getippt
 * wurde) — erst dann fragt die Merkliste nach der Mitteilungs-Erlaubnis, damit die Erklärung das Banner nicht verdeckt.
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
    onHintClosed: (alarmTapped: Boolean) -> Unit = {},
): AddMoment? {
    // Hier statt in `WatchlistScreen`: Dieser Effekt hat das Banner schon und läuft auf jedem Stand der Merkliste
    WatchlistStorageNotice(banner)
    // Schreibweg abgelehnt (Merkliste gesperrt, WatchlistStorageLock): kurzer Hinweis mit Warn-Haptik
    com.cryptochecker.app.ui.components.WatchlistLockedHint { banner.show(it) }
    val pendingMoment by viewModel.addMoment.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
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
        // Ein Erfolgs-Signal je Aktion, nicht je Zeile
        haptics.play(HapticEvent.SUCCESS)
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
            banner.showAction(text, alarmActionLabel, ALARM_HINT_MILLIS, onClosed = onHintClosed) { onOpenAlarms(first.id) }
        } else {
            banner.show(text, onClosed = onHintClosed)
        }
        viewModel.consumeAddMoment(m)
    }
    return readyMoment ?: shownMoment
}

/** So lange bleibt «… wird jetzt überwacht» mit «Alarm setzen» stehen (ohne Screenreader). */
private const val ALARM_HINT_MILLIS = 6_000L

/**
 * Merkliste in diesem Lauf nur im Arbeitsspeicher ([WatchlistStorageLock.notice]): Der Keystore gab den Schlüssel der
 * verschlüsselten Datei gerade nicht heraus ([DatabaseEncryptionNotice.storedKept] — der gespeicherte Stand kommt nach
 * einem Neustart zurück, Änderungen dieses Laufs nicht), oder es gibt gar keinen Schlüssel (keine Datei: nie
 * unverschlüsselt angelegt; alte unverschlüsselte Datei: seit Build 91 nur gelesen — nichts wird gespeichert, gleicher
 * Text wie iOS). Sagen statt still. Als Dauerhinweis
 * ([WatchlistBanner.showResting]): Er steht, solange der Zustand gilt, und kommt nach jedem anderen Banner wieder.
 */
@Composable
private fun WatchlistStorageNotice(banner: WatchlistBanner) {
    val kept by DatabaseEncryptionNotice.storedKept.collectAsStateWithLifecycle()
    val inMemory by DatabaseEncryptionNotice.inMemory.collectAsStateWithLifecycle()
    val notice = WatchlistStorageLock.notice(inMemory, kept)
    val resources = LocalResources.current
    val closeLabel = stringResource(R.string.action_close)
    LaunchedEffect(notice) {
        when (notice) {
            WatchlistStorageLock.Notice.KEPT ->
                banner.showResting(resources.getString(R.string.watchlist_storage_kept_in_memory), closeLabel)
            WatchlistStorageLock.Notice.NOT_SAVED -> {
                // Alte unverschlüsselte Datei nur gelesen (seit Build 93 auch nach gescheiterter Überführung MIT Schlüssel):
                // neutral «noch nicht verschlüsselt» statt «kein Schlüssel»
                val text = if (DatabaseEncryptionNotice.isVolatileCopy(AppDatabase.DB_NAME)) {
                    R.string.watchlist_storage_copy_in_memory
                } else R.string.watchlist_storage_in_memory
                banner.showResting(resources.getString(text), closeLabel)
            }
            null -> banner.clearResting()
        }
    }
}

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

/** Uhr für «vor 2 Min.» der Merkliste: alle 30 Sekunden neu. */
@Composable
internal fun rememberWatchlistClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * Wege in die Merkliste und hinaus: zurück aus «Alarme» (Aktionsblatt wieder öffnen), Blatt bzw. Aktionsblatt aus
 * einer Meldung oder Widget-Zeile, Zurück-Taste für Suche, Sortiermodus und Mehrfachauswahl.
 */
@Composable
internal fun WatchlistNavigationEffects(
    ui: WatchlistScreenState,
    viewModel: WatchlistViewModel,
    watches: List<WatchEntity>,
    groupVisible: List<WatchEntity>,
    selectedGroup: String?,
    loaded: Boolean,
    openWhyWatchId: Long?,
    onOpenWhyHandled: () -> Unit,
    focusWatchId: Long?,
    onFocusHandled: () -> Unit,
) {
    // Zurück aus «Alarme» (eigene Seite): Die Merkliste kommt wieder ins Bild — war das
    // Aktionsblatt der Ausgangspunkt, öffnet es sich wieder
    LaunchedEffect(Unit) { if (ui.whyFor == null) ui.reopenActions() }
    // Aus der Alarm-Meldung («Was bewegt sich mit?»): Blatt öffnen, sobald die Merkliste da ist (nicht gehandelte Paare zeigen keins)
    LaunchedEffect(openWhyWatchId) {
        val id = openWhyWatchId ?: return@LaunchedEffect
        ui.whyFor = id
        onOpenWhyHandled()
    }
    // Aus Mitteilung oder Widget-Zeile: Aktionsblatt des Paars, sobald die Merkliste geladen ist (wie iOS focus(on:))
    LaunchedEffect(focusWatchId, loaded) {
        val id = focusWatchId ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        val watch = watches.firstOrNull { it.id == id }
        if (watch != null) {
            // Paar ausserhalb der gewählten Gruppe: wieder alle zeigen
            if (selectedGroup != null && groupVisible.none { it.id == id }) viewModel.selectGroup(null)
            ui.whyFor = null
            ui.returnToActions = null
            ui.actionsFor = id
        }
        onFocusHandled()
    }
    // Zurück-Taste schliesst zuerst die Suche
    BackHandler(enabled = ui.searching) { ui.closeSearch() }
    // … bzw. beendet den Sortiermodus (wie «Fertig»)
    BackHandler(enabled = ui.sortMode && !ui.searching) { ui.sortMode = false }
    // … bzw. die Mehrfachauswahl
    BackHandler(enabled = ui.selecting) { ui.endSelection() }
}
