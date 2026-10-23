// Ein Eintrag der Merkliste (Teil von `WatchlistList`): Zeile mit Wischen, Ziehen im Sortiermodus, Auswahl und
// Screenreader-Aktionen. Eigene Datei, weil die Liste sonst zu lang würde.
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.ui.components.ListSegment
import androidx.compose.ui.graphics.Shape
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import com.cryptochecker.app.R
import com.cryptochecker.app.data.SparklineRepository
import com.cryptochecker.app.data.WatchMove
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.starter.AddMoment
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.ui.UiTestTags
import kotlinx.coroutines.delay
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics

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
    outdatedAfter: Long,
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
    val dragModifier = if (searching || !sortMode) Modifier else Modifier.pointerInput(watch.id) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                haptics.play(HapticEvent.LONG_PRESS)
                reorder.start(watch.id)
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

    // 24-Stunden-Verlauf: nur für sichtbare Zeilen (LazyColumn), danach alle 15 Min. neu.
    // Fehler → kein Mini-Chart, keine Meldung. Nicht mehr gehandelt bzw. deaktiviert: keiner (keine Abfrage).
    val rowSparklines = sparklines && !watch.isNotTraded && !watch.disabled
    // Zwischengespeicherter Verlauf als Startwert: nur einmal je Schlüssel nachschlagen, nicht bei jeder Rekomposition
    // (produceState nimmt den Startwert ohnehin nur beim ersten Mal)
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
            delay(SparklineRepository.TTL_MILLIS)
        }
    }

    // Mini-Chart einer anderen Börse: im Zeilensatz nennen (sichtbar steht es seit L-14 im Aktionsblatt)
    val chartSource = remember(sparkline, watch.marketKey, watch.lastPrice != null) {
        if (sparkline != null) sparklineSource(watch) else null
    }

    // %-Änderung aus fremden Kerzen: kleiner Marker «*» und Satz im Screenreader. Der Anbieter wird bei jeder
    // Aktualisierung vor dem Speichern gemerkt — neu lesen, sobald ein neuer Stand des Paars kommt
    val changeProvider = remember(watch) { changeSource(watch) }

    // Teil des Erst-Moments? Dann Platz in der Staffelung, sonst null
    val celebrateIndex = moment?.indexOf(watch.marketKey, watch.baseAsset, watch.quoteAsset)

    SwipeActionsRow(
        enabled = !sortMode && !selecting,
        favorite = watch.favorite,
        onDelete = onDelete,
        onToggleFavorite = onFavoriteWithBanner,
        reduceMotion = reduceMotion,
        shape = shape,
        modifier = (
            if (dragging) Modifier
                .zIndex(1f)
                .graphicsLayer {
                    translationY = reorder.offset
                    scaleX = lift
                    scaleY = lift
                }
            else Modifier.animateItem()
        ).then(dragModifier).testTag(UiTestTags.WATCH_ROW),
    ) {
        WithLiveQuote(watch, livePrices, rollingBasis = rollingBasis) { shown ->
            // Devisenkurs älter als 3 h: dessen Uhrzeit dahinter («≈ 66’120 CHF · Kurs 08:15»)
            val dated = convertedPrice(shown, convertTarget, convertRates)?.let { text ->
                convertedFxTime(shown, convertFxTimes, now)?.let {
                    stringResource(R.string.converted_fx_stale, text, com.cryptochecker.app.util.PriceFormat.shortTime(it))
                } ?: text
            }
            // USDT/USD nicht gemessen bzw. nur unsicher über USDC: in der Zeile kurz «Umrechnung unsicher»
            // (passt neben das Paar), im Screenreader beim nicht gemessenen USDT der ganze Satz
            val usdtNote = convertedUsdtNote(shown, convertTarget, convertUsdtNotes)
            val converted = dated?.let { if (usdtNote != null) "$it · " + stringResource(R.string.converted_unsure) else it }
            val convertedA11y = dated?.takeIf {
                usdtNote == com.cryptochecker.app.domain.convert.FiatAlarmGuard.UsdtState.ASSUMED
            }?.let { "$it · " + stringResource(R.string.portfolio_usdt_assumed) }
            WatchRow(
                watch = shown,
                alarmCount = alarmCount,
                alarmFiredAt = alarmFiredAt,
                kind = kind,
                now = now,
                staleAfter = staleAfter,
                outdatedAfter = outdatedAfter,
                converted = converted,
                convertedA11y = convertedA11y,
                // Im Sortiermodus ausgeblendet (Platz für Griff und Menü)
                sparkline = sparkline.takeIf { rowSparklines && !sortMode },
                chartSource = chartSource,
                changeSource = changeProvider,
                celebrateKey = moment?.id?.takeIf { celebrateIndex != null },
                celebrateIndex = celebrateIndex,
                reduceMotion = reduceMotion,
                hasActivity = hasActivity,
                onActivityClick = {
                    when {
                        selecting -> onToggleSelected()
                        !sortMode -> onOpenWhy()
                    }
                },
                elevation = elevation,
                highlighted = dragging || selected,
                shape = shape,
                sortMode = sortMode,
                selecting = selecting,
                selected = selected,
                onClick = {
                    when {
                        selecting -> onToggleSelected()
                        !sortMode -> onOpenActions()
                    }
                },
                onMove = onMove,
                // Lange drücken: Mehrfachauswahl mit dieser Zeile (wie ⋯ › «Auswählen»), mit Haptik.
                // Im Sortier- und Auswahlmodus nicht (die Zeile behandelt das selbst).
                onLongClick = if (sortMode || selecting) null else {
                    {
                        haptics.play(HapticEvent.LONG_PRESS)
                        onStartSelection()
                    }
                },
                // Screenreader: «Löschen» und «Favorit» wie Wischen
                onDeleteAction = onDelete,
                onFavoriteAction = onFavoriteWithBanner,
                onSourceHint = onSourceHint,
                // Screenreader: «Nach oben/unten» wie Ziehen (nicht während der Suche)
                canReorder = !searching,
                // Am Griff ohne Warten ziehen
                handleModifier = Modifier.pointerInput(watch.id) {
                    detectDragGestures(
                        onDragStart = {
                            haptics.play(HapticEvent.LONG_PRESS)
                            reorder.start(watch.id)
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
            )
        }
    }
}
