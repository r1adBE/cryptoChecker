// Ein Eintrag der Merkliste (Teil von `WatchlistList`): Zeile mit Wischen, Ziehen im Sortiermodus, Auswahl und
// Screenreader-Aktionen. Eigene Datei, weil die Liste sonst zu lang würde.
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cryptochecker.app.domain.WatchMove
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.starter.AddMoment
import com.cryptochecker.app.domain.watch.SparklineTiming
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.HapticPlayer
import com.cryptochecker.app.ui.components.ListSegment
import com.cryptochecker.app.ui.components.rememberHaptics
import kotlinx.coroutines.delay

/**
 * Ein Paar in der Liste: Ziehen zum Sortieren (lange drücken oder am Griff), Wischen (links
 * löschen, rechts Favorit; RTL gespiegelt; nicht beim Sortieren), Mini-Chart und Live-Kurs.
 */
@Composable
internal fun LazyItemScope.WatchlistItem(
    watch: WatchEntity,
    reorder: ReorderState,
    searching: Boolean,
    sortMode: Boolean,
    /** Form je nach Platz in der Liste ([ListSegment.shape]). */
    shape: Shape = MaterialTheme.shapes.medium,
    /** Mehrfachauswahl an: Tipp hakt an/ab, kein Wischen. */
    selecting: Boolean = false,
    selected: Boolean = false,
    onToggleSelected: () -> Unit = {},
    /** Mini-Charts laden (Einstellung an, genug Breite). */
    sparklines: Boolean,
    cachedSparkline: (WatchEntity) -> List<Double>?,
    loadSparkline: suspend (WatchEntity) -> List<Double>?,
    /** Börse des Mini-Charts, falls nicht die eigene ([WatchlistViewModel.foreignSparklineSource]). */
    sparklineSource: (WatchEntity) -> String? = { null },
    /** Börse der Kerzen hinter der %-Änderung, falls nicht die eigene ([WatchlistViewModel.foreignCandleSource]). */
    changeSource: (WatchEntity) -> String? = { null },
    moment: AddMoment?,
    reduceMotion: Boolean,
    livePrices: State<Map<Long, LiveQuote>>,
    rollingBasis: Boolean,
    alarmCount: Int,
    now: Long,
    staleAfter: Long,
    /** Paar am Live-Strom: Frische am letzten Takt ([LiveOverlay.atHeartbeat]). */
    onLiveSocket: Boolean = false,
    convertTarget: String?,
    /** Letzte Alarm-Auslösung des Paars (Epoch-ms, 0 = nie), für «Alarm ausgelöst» im Screenreader. */
    alarmFiredAt: Long = 0L,
    /** Art des Paars (Symbol vor der Glocke). */
    kind: com.cryptochecker.app.domain.watch.AssetKind = com.cryptochecker.app.domain.watch.AssetKind.TOKEN,
    convertRates: Map<String, Double>,
    /** Abrufzeit alter Devisenkurse (älter als 3 h) je Quote-Währung, siehe [WatchlistLive.convertFxTimes]. */
    convertFxTimes: Map<String, Long> = emptyMap(),
    /** USDT = 1 angenommen bzw. unsichere Umrechnung je Quote-Währung, siehe [WatchlistLive.convertUsdtNotes]. */
    convertUsdtNotes: Map<String, com.cryptochecker.app.domain.convert.FiatAlarmGuard.UsdtState> = emptyMap(),
    hasActivity: Boolean,
    onOpenWhy: () -> Unit,
    onOpenActions: () -> Unit,
    onMove: (WatchMove) -> Unit,
    /** Löschen mit «Rückgängig» (Wischen, Screenreader-Aktion). */
    onDelete: () -> Unit,
    /** Favorit an/aus mit Banner (Wischen, Screenreader-Aktion). */
    onFavoriteWithBanner: () -> Unit,
    /** Lange drücken bzw. Screenreader-Aktion «Auswählen»: Mehrfachauswahl mit dieser Zeile starten. */
    onStartSelection: () -> Unit = {},
    /** Kurzer Hinweis im Banner (Herkunft der Änderung, Tipp auf «*»). */
    onSourceHint: (String) -> Unit = {},
) {
    val haptics = rememberHaptics()
    val dragging = reorder.draggingId == watch.id
    val lift by animateFloatAsState(if (dragging) 1.03f else 1f, label = "lift")
    val elevation by animateDpAsState(if (dragging) 12.dp else 0.dp, label = "elevation")

    // Sortieren nur im Sortiermodus (⋯ › Sortieren): dann lange drücken und ziehen oder am Griff.
    // Ausserhalb startet langes Drücken nichts mehr — ein zögerliches Wischen landete sonst im
    // Sortiermodus. Während der Suche kein Sortieren — die Reihenfolge wäre mehrdeutig.
    val dragModifier = sortDragModifier(watch.id, searching, sortMode, haptics, reorder)

    // 24-Stunden-Verlauf: nur für sichtbare Zeilen (LazyColumn), danach alle 15 Min. neu.
    // Fehler → kein Mini-Chart, keine Meldung. Nicht mehr gehandelt bzw. deaktiviert: keiner (keine Abfrage).
    // Börse stillgelegt (ExchangeRetirement, Marker oder Ende erreicht): ebenso keiner
    val rowSparklines = sparklines && !watch.isNotTraded && !watch.disabled &&
        com.cryptochecker.app.domain.watch.RetiredWatch.exchangeOf(watch, now) == null
    // Zwischengespeicherter Verlauf als Startwert: nur einmal je Schlüssel nachschlagen, nicht bei jeder Rekomposition
    // (produceState nimmt den Startwert ohnehin nur beim ersten Mal)
    val sparkline = rememberRowSparkline(watch, rowSparklines, cachedSparkline, loadSparkline)

    // Mini-Chart einer anderen Börse: im Zeilensatz nennen (sichtbar steht es im Aktionsblatt)
    val chartSource = remember(sparkline, watch.exchangeKey, watch.lastPrice != null) {
        if (sparkline != null) sparklineSource(watch) else null
    }

    // %-Änderung aus fremden Kerzen: kleiner Marker «*» und Satz im Screenreader. Der Anbieter wird bei jeder
    // Aktualisierung vor dem Speichern gemerkt — neu lesen, sobald ein neuer Stand des Paars kommt
    val changeProvider = remember(watch) { changeSource(watch) }

    // Teil des Erst-Moments? Dann Platz in der Staffelung, sonst null
    val celebrateIndex = moment?.indexOf(watch.exchangeKey, watch.baseAsset, watch.quoteAsset)

    SwipeActionsRow(
        enabled = !sortMode && !selecting,
        favorite = watch.favorite,
        onDelete = onDelete,
        onToggleFavorite = onFavoriteWithBanner,
        reduceMotion = reduceMotion,
        shape = shape,
        modifier = (
            if (dragging) {
                Modifier
                    .zIndex(1f)
                    .graphicsLayer {
                        translationY = reorder.offset
                        scaleX = lift
                        scaleY = lift
                    }
            } else if (reduceMotion) {
                Modifier
            } else {
                Modifier.animateItem()
            }
            ).then(dragModifier).testTag(UiTestTags.WATCH_ROW),
    ) {
        WithLiveQuote(watch, livePrices, rollingBasis = rollingBasis, onLiveSocket = onLiveSocket, now = now) { shown ->
            WatchlistItemRow(
                shown = shown,
                watchId = watch.id,
                now = now,
                staleAfter = staleAfter,
                convertTarget = convertTarget,
                convertRates = convertRates,
                convertFxTimes = convertFxTimes,
                convertUsdtNotes = convertUsdtNotes,
                alarmCount = alarmCount,
                alarmFiredAt = alarmFiredAt,
                kind = kind,
                sparkline = sparkline.takeIf { rowSparklines && !sortMode },
                chartSource = chartSource,
                changeProvider = changeProvider,
                moment = moment,
                celebrateIndex = celebrateIndex,
                reduceMotion = reduceMotion,
                hasActivity = hasActivity,
                elevation = elevation,
                highlighted = dragging || selected,
                shape = shape,
                searching = searching,
                sortMode = sortMode,
                selecting = selecting,
                selected = selected,
                haptics = haptics,
                reorder = reorder,
                onToggleSelected = onToggleSelected,
                onOpenWhy = onOpenWhy,
                onOpenActions = onOpenActions,
                onMove = onMove,
                onDelete = onDelete,
                onFavoriteWithBanner = onFavoriteWithBanner,
                onStartSelection = onStartSelection,
                onSourceHint = onSourceHint,
            )
        }
    }
}

/** Ziehen zum Sortieren nach langem Drücken (nur im Sortiermodus, nicht während der Suche); sonst kein Modifier. */
private fun sortDragModifier(
    watchId: Long,
    searching: Boolean,
    sortMode: Boolean,
    haptics: HapticPlayer,
    reorder: ReorderState,
): Modifier = if (searching || !sortMode) {
    Modifier
} else {
    Modifier.pointerInput(watchId) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                haptics.play(HapticEvent.LONG_PRESS)
                reorder.start(watchId)
            },
            onDrag = { change, amount ->
                change.consume()
                reorder.drag(amount.y)
            },
            onDragEnd = {
                haptics.play(HapticEvent.LIGHT_IMPACT)
                reorder.end()
            },
            onDragCancel = { reorder.end() }
        )
    }
}

/**
 * 24-Stunden-Verlauf einer Zeile, danach alle 15 Min. neu ([SparklineTiming.TTL_MILLIS]). Liefert einen Wert,
 * liest also wie zuvor im Aufrufer.
 */
@Composable
private fun rememberRowSparkline(
    watch: WatchEntity,
    rowSparklines: Boolean,
    cachedSparkline: (WatchEntity) -> List<Double>?,
    loadSparkline: suspend (WatchEntity) -> List<Double>?,
): List<Double>? {
    val initialSparkline = remember(watch.baseAsset, watch.quoteAsset, watch.lastPrice != null, rowSparklines) {
        if (rowSparklines) cachedSparkline(watch) else null
    }
    val sparkline by produceState(
        initialValue = initialSparkline,
        watch.baseAsset,
        watch.quoteAsset,
        // Erst mit Kurs prüfbar (gleicher Token, SheetChart.sparklineFor)
        watch.lastPrice != null,
        rowSparklines,
    ) {
        if (!rowSparklines) {
            value = null
            return@produceState
        }
        var first = true
        while (true) {
            val closes = loadSparkline(watch)
            // Später fehlgeschlagene Abrufe lassen den letzten Verlauf stehen
            if (closes != null || first) value = closes
            first = false
            delay(SparklineTiming.TTL_MILLIS)
        }
    }
    return sparkline
}
