// Ableitungen des Merkliste-Bildschirms: Glocke, Signale «Ungewöhnliche Aktivität», gezeigte Paare der Schnellansicht
// und Puls. Eigene Datei, weil `WatchlistScreen` sonst zu lang würde; gemerkt wird mit denselben Schlüsseln wie dort.
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.domain.watch.AlarmBellRoute
import com.cryptochecker.app.ui.features.alarms.AlarmPairPicker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.activity.ActivityAnalyzer
import com.cryptochecker.app.domain.watch.ActivityView
import com.cryptochecker.app.domain.watch.ChangeView
import com.cryptochecker.app.domain.watch.DisabledView
import com.cryptochecker.app.domain.watch.NotTraded
import com.cryptochecker.app.domain.watch.QuickView
import com.cryptochecker.app.domain.watch.WatchPulse
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.domain.watch.shownChange
import com.cryptochecker.app.domain.activity.ActivityReport
import com.cryptochecker.app.domain.activity.ActivitySensitivity
import com.cryptochecker.app.domain.activity.ActivitySignal

/**
 * Glocke im Kopf und in der Start-Auswahl: mit Alarmen die Übersicht, ohne einen einzigen gleich zum Anlegen — ein
 * Paar: dessen Alarm-Seite (öffnet den Editor), mehrere: erst wählen ([AlarmPairPicker]), leere Merkliste: Coin hinzufügen.
 */
@Composable
internal fun rememberBellClick(
    alarmTotal: Int?,
    watches: List<WatchEntity>,
    onOpenAllAlarms: () -> Unit,
    onOpenAlarms: (Long) -> Unit,
    onAddClick: () -> Unit,
): () -> Unit {
    var pickAlarmPair by remember { mutableStateOf(false) }
    val onBellClick: () -> Unit = {
        when (AlarmBellRoute.of(alarmTotal, watches.size)) {
            AlarmBellRoute.OVERVIEW -> onOpenAllAlarms()
            AlarmBellRoute.EDITOR -> onOpenAlarms(watches.first().id)
            AlarmBellRoute.PICK_PAIR -> pickAlarmPair = true
            AlarmBellRoute.ADD_COIN -> onAddClick()
        }
    }
    if (pickAlarmPair) {
        AlarmPairPicker(
            watches = watches,
            onPick = { id -> pickAlarmPair = false; onOpenAlarms(id) },
            onDismiss = { pickAlarmPair = false },
        )
    }
    return onBellClick
}

/** Signale «Ungewöhnliche Aktivität» der Merkliste, nach der gewählten Empfindlichkeit neu beurteilt. */
internal class ActivitySignals(
    /** Nicht mehr gehandelte Paare: kein ⚡, nicht in der Karte. */
    val notTradedIds: Set<Long>,
    /** Nur noch gültige Signale, je Paar stärkstes zuerst. */
    val activeSignals: Map<Long, List<ActivitySignal>>,
    /** Paare für den ⚡-Chip: starke zuerst, sonst Listenreihenfolge. */
    val hot: List<WatchEntity>,
)

/**
 * Signale nach [sensitivity] (gleiche Schwellen wie die Meldungen); nicht gehandelte und deaktivierte Paare zählen nicht
 * (auch bevor die Auswertung aufräumt).
 */
@Composable
internal fun rememberActivitySignals(
    watches: List<WatchEntity>,
    activity: Map<Long, ActivityReport>,
    now: Long,
    sensitivity: ActivitySensitivity,
): ActivitySignals {
    val notTradedIds = remember(watches) { NotTraded.ids(watches, { it.id }, { it.lastError }) }
    // Deaktivierte Paare ebenso: kein ⚡, nicht in der Karte (auch bevor die Auswertung aufräumt)
    val quietIds = remember(watches, notTradedIds) {
        notTradedIds + DisabledView.only(watches) { it.disabled }.map { it.id }
    }
    val activeSignals = remember(activity, now, sensitivity, quietIds) {
        NotTraded.withoutIds(activity, quietIds)
            .mapValues { ActivityAnalyzer.applySensitivity(it.value.active(now), sensitivity) }
            .filterValues { it.isNotEmpty() }
    }
    // ⚡-Chip: Paare mit Signalen (alle Gruppen), starke zuerst, sonst Listenreihenfolge; mit
    // Empfindlichkeit «Weniger» nur die Paare der stärksten Coins
    val hot = remember(watches, activeSignals, sensitivity) {
        ActivityView.pick(
            watches.filter { it.id in activeSignals }
                .sortedByDescending { activeSignals[it.id]?.firstOrNull()?.severity?.ordinal ?: 0 },
            { it.baseAsset },
            sensitivity.maxCardCoins,
        )
    }
    return ActivitySignals(notTradedIds, activeSignals, hot)
}

/**
 * Gezeigte Paare: die Gruppen-Ansicht oder vorübergehend ⚡, «nur veraltete» bzw. «nur deaktivierte» ([QuickView]);
 * die vorübergehende Ansicht endet von selbst, wenn nichts mehr hineinpasst.
 */
@Composable
internal fun rememberVisibleWatches(
    ui: WatchlistScreenState,
    groupVisible: List<WatchEntity>,
    hot: List<WatchEntity>,
    now: Long,
    staleAfter: Long,
    liveIds: Set<Long>,
    activityChip: Boolean,
): List<WatchEntity> {
    val visible = remember(groupVisible, hot, ui.quickView, now, staleAfter, liveIds) {
        when (ui.quickView) {
            QuickView.ACTIVITY -> hot
            QuickView.STALE -> groupVisible.filter { WatchlistStatus.isStale(it, now, staleAfter, liveIds) }
            QuickView.DISABLED -> DisabledView.only(groupVisible) { it.disabled }
            null -> groupVisible
        }
    }
    // Vorübergehende Ansicht endet von selbst, wenn nichts mehr hineinpasst
    LaunchedEffect(ui.quickView, activityChip, visible.isEmpty()) {
        when (ui.quickView) {
            QuickView.ACTIVITY -> if (!activityChip) ui.quickView = null
            QuickView.STALE, QuickView.DISABLED -> if (visible.isEmpty()) ui.quickView = null
            null -> Unit
        }
    }
    return visible
}

/**
 * Puls ganz oben: wie viele steigen/fallen, Ø-Veränderung — nur für die gezeigte Gruppe, nicht beim Suchen oder
 * Sortieren und im ersten Gebrauch erst mit [onboardingPulse]. Gemerkt, damit nicht jede Neuzusammensetzung (z. B. die
 * 30-s-Uhr) hunderte Paare neu durchgeht; null = kein Puls.
 */
@Composable
internal fun rememberWatchPulse(
    visible: List<WatchEntity>,
    searching: Boolean,
    sortMode: Boolean,
    changeView: ChangeView,
    onboardingPulse: Boolean,
): WatchPulse? {
    return remember(visible, searching, sortMode, changeView, onboardingPulse) {
        if (searching || sortMode || !onboardingPulse) null
        else {
            // Nicht gehandelte Paare zählen weder als steigend/fallend noch als «ohne 24h-Wert»,
            // deaktivierte gar nicht
            val counted = DisabledView.active(visible) { it.disabled }
            WatchPulse.of(
                changes = counted.map { it.shownChange(changeView) },
                hasPrice = counted.map { it.lastPrice != null && !isNotTraded(it.lastError) },
            )
        }
    }
}
