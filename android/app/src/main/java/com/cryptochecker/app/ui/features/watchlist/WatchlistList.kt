@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cryptochecker.app.R
import com.cryptochecker.app.data.SparklineRepository
import com.cryptochecker.app.data.WatchMove
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.starter.AddMoment
import com.cryptochecker.app.domain.watch.WatchPulse
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.ui.theme.Spacing
import kotlinx.coroutines.delay

@Composable
private fun HintText(text: String, highlight: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

/** Kurzer Hinweis zu den Gesten mit Schliessen-Knopf. */
@Composable
private fun GestureHint(onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(start = Spacing.md, top = 4.dp, bottom = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.watch_gesture_hint_short),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.action_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Die scrollende Liste unter dem festen Kopf: Puls, Aktivitätskarte, Hinweise, leere
 * Zustände und die Paare ([row] je Paar). Kein schwebender Plus-Knopf: Hinzufügen läuft über
 * «+» in der Kopfzeile. Mit Sprungknopf ([roomForJump]) unten mehr Platz, damit er die letzte
 * Zeile nicht verdeckt.
 */
@Composable
internal fun WatchlistList(
    listState: LazyListState,
    inset: Dp,
    roomForJump: Boolean,
    pulse: WatchPulse?,
    /** Paare mit Signalen für die Karte «⚡ Hier passiert gerade etwas»; leer = keine Karte. */
    hot: List<WatchEntity>,
    hotLimit: Int?,
    onOpenWhy: (WatchEntity) -> Unit,
    onAdjustActivity: () -> Unit,
    sortMode: Boolean,
    showGestureHint: Boolean,
    onDismissGestureHint: () -> Unit,
    /** Suche ohne Treffer. */
    noSearchMatch: Boolean,
    query: String,
    /** Leere Ansicht (Gruppe ohne Paare): ruhiger Hinweis statt einer leeren Fläche. */
    groupEmpty: Boolean,
    shown: List<WatchEntity>,
    row: @Composable LazyItemScope.(WatchEntity) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("watchlist"),
        contentPadding = PaddingValues(
            start = 16.dp + inset,
            top = 8.dp,
            end = 16.dp + inset,
            bottom = if (roomForJump) 24.dp + JumpButtonSize + JumpButtonMargin else 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Puls als erste Zeile unter der festen Kopfzeile (scrollt mit), Abstände per spacedBy
        if (pulse != null) {
            item(key = "pulse") {
                WatchPulseLine(pulse = pulse, modifier = Modifier.animateItem())
            }
        }
        if (hot.isNotEmpty()) {
            item(key = "activity") {
                ActivityCard(
                    hot = hot,
                    limit = hotLimit,
                    onOpen = onOpenWhy,
                    onAdjust = onAdjustActivity,
                )
            }
        }
        if (sortMode) {
            item(key = "sort_hint") {
                HintText(stringResource(R.string.watchlist_sort_hint), highlight = true)
            }
        } else if (showGestureHint) {
            // Einmaliger Gesten-Hinweis, bleibt bis er weggeklickt wird.
            item(key = "gesture_hint") {
                GestureHint(onDismiss = onDismissGestureHint)
            }
        }
        if (noSearchMatch) {
            item(key = "search_empty") {
                ListNote(stringResource(R.string.watchlist_search_empty, query))
            }
        }
        if (groupEmpty) {
            item(key = "group_empty") {
                ListNote(stringResource(R.string.watchlist_group_empty_hint))
            }
        }
        items(shown, key = { it.id }) { watch -> row(watch) }
    }
}

/** Ruhiger Hinweis mitten in der Liste (keine Treffer, leere Gruppe). */
@Composable
private fun ListNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)
    )
}

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
    /** Lange drücken: Sortiermodus an. */
    onStartSort: () -> Unit,
    /** Mini-Charts laden (Einstellung an, genug Breite). */
    sparklines: Boolean,
    cachedSparkline: (String) -> List<Double>?,
    loadSparkline: suspend (String) -> List<Double>?,
    moment: AddMoment?,
    reduceMotion: Boolean,
    livePrices: State<Map<Long, LiveQuote>>,
    rollingBasis: Boolean,
    alarmCount: Int,
    now: Long,
    staleAfter: Long,
    outdatedAfter: Long,
    convertTarget: String?,
    convertRates: Map<String, Double>,
    hasActivity: Boolean,
    onOpenWhy: () -> Unit,
    onOpenActions: () -> Unit,
    onMove: (WatchMove) -> Unit,
    /** Löschen mit «Rückgängig» (Wischen, Screenreader-Aktion). */
    onDelete: () -> Unit,
    /** Favorit an/aus mit Banner (Wischen, Screenreader-Aktion). */
    onFavoriteWithBanner: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val dragging = reorder.draggingId == watch.id
    val lift by animateFloatAsState(if (dragging) 1.03f else 1f, label = "lift")
    val elevation by animateDpAsState(if (dragging) 12.dp else 0.dp, label = "elevation")

    // Lange drücken = Sortiermodus an und Karte direkt ziehen.
    // Während der Suche kein Sortieren — die Reihenfolge wäre mehrdeutig.
    val dragModifier = if (searching) Modifier else Modifier.pointerInput(watch.id) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onStartSort()
                reorder.start(watch.id)
            },
            onDrag = { change, amount ->
                change.consume()
                reorder.drag(amount.y)
            },
            onDragEnd = {
                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                reorder.end()
            },
            onDragCancel = { reorder.end() }
        )
    }

    // 24-Stunden-Verlauf: nur für sichtbare Zeilen (LazyColumn), danach alle 15 Min. neu.
    // Fehler → kein Mini-Chart, keine Meldung. Nicht mehr gehandelt: keiner.
    val rowSparklines = sparklines && !watch.isNotTraded
    val sparkline by produceState(
        initialValue = if (rowSparklines) cachedSparkline(watch.baseAsset) else null,
        watch.baseAsset,
        rowSparklines,
    ) {
        if (!rowSparklines) {
            value = null
            return@produceState
        }
        var first = true
        while (true) {
            val closes = loadSparkline(watch.baseAsset)
            // Später fehlgeschlagene Abrufe lassen den letzten Verlauf stehen
            if (closes != null || first) value = closes
            first = false
            delay(SparklineRepository.TTL_MILLIS)
        }
    }

    // Teil des Erst-Moments? Dann Platz in der Staffelung, sonst null
    val celebrateIndex = moment?.indexOf(watch.marketKey, watch.baseAsset, watch.quoteAsset)

    SwipeActionsRow(
        enabled = !sortMode,
        favorite = watch.favorite,
        onDelete = onDelete,
        onToggleFavorite = onFavoriteWithBanner,
        reduceMotion = reduceMotion,
        modifier = (
            if (dragging) Modifier
                .zIndex(1f)
                .graphicsLayer {
                    translationY = reorder.offset
                    scaleX = lift
                    scaleY = lift
                }
            else Modifier.animateItem()
        ).then(dragModifier).testTag("watch_row"),
    ) {
        WithLiveQuote(watch, livePrices, rollingBasis = rollingBasis) { shown ->
            WatchRow(
                watch = shown,
                alarmCount = alarmCount,
                now = now,
                staleAfter = staleAfter,
                outdatedAfter = outdatedAfter,
                converted = convertedPrice(shown, convertTarget, convertRates),
                // Im Sortiermodus ausgeblendet (Platz für Griff und Menü)
                sparkline = sparkline.takeIf { rowSparklines && !sortMode },
                celebrateKey = moment?.id?.takeIf { celebrateIndex != null },
                celebrateIndex = celebrateIndex,
                reduceMotion = reduceMotion,
                hasActivity = hasActivity,
                onActivityClick = { if (!sortMode) onOpenWhy() },
                elevation = elevation,
                highlighted = dragging,
                sortMode = sortMode,
                onClick = { if (!sortMode) onOpenActions() },
                onMove = onMove,
                // Screenreader: «Löschen» und «Favorit» wie Wischen
                onDeleteAction = onDelete,
                onFavoriteAction = onFavoriteWithBanner,
                // Screenreader: «Nach oben/unten» wie Ziehen (nicht während der Suche)
                canReorder = !searching,
                // Am Griff ohne Warten ziehen
                handleModifier = Modifier.pointerInput(watch.id) {
                    detectDragGestures(
                        onDragStart = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            reorder.start(watch.id)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            reorder.drag(amount.y)
                        },
                        onDragEnd = {
                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            reorder.end()
                        },
                        onDragCancel = { reorder.end() }
                    )
                }
            )
        }
    }
}
