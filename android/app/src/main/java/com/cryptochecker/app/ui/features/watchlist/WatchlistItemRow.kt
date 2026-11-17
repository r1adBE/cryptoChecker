@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.cryptochecker.app.domain.WatchMove
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.starter.AddMoment
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.HapticPlayer
import com.cryptochecker.resources.R

// Zeile eines Merklisten-Eintrags ([WatchlistItem]) mit dem Live-Kurs: Umrechnung, Mini-Chart, Erst-Moment und
// Bedienung. Eigene Datei, weil WatchlistListItem.kt sonst zu lang würde.

/** Zeile eines Eintrags mit dem Live-Kurs ([shown]): Umrechnung, Mini-Chart, Erst-Moment und Bedienung. */
@Composable
internal fun WatchlistItemRow(
    shown: WatchEntity,
    watchId: Long,
    now: Long,
    staleAfter: Long,
    convertTarget: String?,
    convertRates: Map<String, Double>,
    convertFxTimes: Map<String, Long>,
    convertUsdtNotes: Map<String, com.cryptochecker.app.domain.convert.FiatAlarmGuard.UsdtState>,
    alarmCount: Int,
    alarmFiredAt: Long,
    kind: com.cryptochecker.app.domain.watch.AssetKind,
    sparkline: List<Double>?,
    chartSource: String?,
    changeProvider: String?,
    moment: AddMoment?,
    celebrateIndex: Int?,
    reduceMotion: Boolean,
    hasActivity: Boolean,
    elevation: Dp,
    highlighted: Boolean,
    shape: Shape,
    searching: Boolean,
    sortMode: Boolean,
    selecting: Boolean,
    selected: Boolean,
    haptics: HapticPlayer,
    reorder: ReorderState,
    onToggleSelected: () -> Unit,
    onOpenWhy: () -> Unit,
    onOpenActions: () -> Unit,
    onMove: (WatchMove) -> Unit,
    onDelete: () -> Unit,
    onFavoriteWithBanner: () -> Unit,
    onStartSelection: () -> Unit,
    onSourceHint: (String) -> Unit,
) {
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
        // Börse stillgelegt: «—» mit Grund statt des letzten Kurses, auch vor dem ersten Abruf danach (RetiredWatch)
        watch = com.cryptochecker.app.domain.watch.RetiredWatch.shown(shown, now),
        alarmCount = alarmCount,
        alarmFiredAt = alarmFiredAt,
        kind = kind,
        now = now,
        staleAfter = staleAfter,
        converted = converted,
        convertedA11y = convertedA11y,
        // Im Sortiermodus ausgeblendet (Platz für Griff und Menü)
        sparkline = sparkline,
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
        highlighted = highlighted,
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
        onLongClick = if (sortMode || selecting) {
            null
        } else {
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
        handleModifier = Modifier.pointerInput(watchId) {
            detectDragGestures(
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
    )
}
