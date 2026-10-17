@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.watch.AlarmPulse
import com.cryptochecker.app.domain.watch.QuickView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.cryptochecker.app.ui.components.AppMenuHead

/**
 * Kopfzeile der Merkliste: links die Gruppen-Chips («Alle» und Gruppen, scrollbar, mit «+» für
 * eine neue Gruppe), rechts die Glocke (nur mit aktiven Alarmen) und fest das Überlaufmenü. Die LazyRow der Chips schneidet
 * in Laufrichtung ab, sie läuft also nicht unter die Knöpfe. Im Sortiermodus statt der Knöpfe
 * «Fertig». «Paar hinzufügen» steht rechts neben der Lupe ([WatchlistStatusRow]).
 * Gibt es noch keine Chips, steht dort ab einigen Paaren ein grauer Hinweis ([showsGroupsHint]).
 */
@Composable
internal fun WatchlistHeader(
    groups: List<String>,
    selectedGroup: String?,
    hasFavorites: Boolean,
    /** «Token»-Chip zeigen (TradFi- und andere Paare vorhanden). */
    hasTokens: Boolean = false,
    /** «Alpha»-Chip zeigen (Alpha- und andere Paare vorhanden). */
    hasAlpha: Boolean = false,
    onSelectGroup: (String?) -> Unit,
    onEditGroup: (String) -> Unit,
    onAddGroup: () -> Unit,
    sortMode: Boolean,
    onSortDone: () -> Unit,
    activeAlarms: Int,
    /** Grösse der Glocke (Puls bei einem neuen Alarm); nur beim Zeichnen gelesen. */
    bellScale: () -> Float,
    /** Glocke: Übersicht bzw. ohne Alarm gleich zum Anlegen ([com.cryptochecker.app.domain.watch.AlarmBellRoute]). */
    onBellClick: () -> Unit,
    /** Menü «Alle Alarme»: immer die Übersicht. */
    onOpenAllAlarms: () -> Unit,
    onOpenAbout: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    canSort: Boolean,
    onSort: () -> Unit,
    onShowReport: () -> Unit,
    canClear: Boolean,
    notTradedCount: Int,
    onRemoveNotTraded: () -> Unit,
    onClearAll: () -> Unit,
    /** ⚡-Chip zeigen (Paare, bei denen gerade etwas passiert). */
    hasActivity: Boolean = false,
    quickView: QuickView? = null,
    onToggleActivity: () -> Unit = {},
    /** «Aktualisieren» im Menü möglich? Beim Öffnen gefragt (läuft schon / eben erst fertig = grau). */
    canRefresh: () -> Boolean = { !refreshing },
    /** Mehrfachauswahl: statt der Chips «n ausgewählt», rechts «Alle auswählen» und «Fertig». */
    selecting: Boolean = false,
    selectedCount: Int = 0,
    allSelected: Boolean = false,
    onSelectAll: () -> Unit = {},
    onSelectionDone: () -> Unit = {},
    canSelect: Boolean = false,
    onStartSelection: () -> Unit = {},
    /** Suche offen: dann kein Hinweis in der leeren Chip-Zeile. */
    searching: Boolean = false,
    /** Paare in der Merkliste (alle Gruppen): der Hinweis erst ab [GROUPS_HINT_MIN_PAIRS]. */
    pairCount: Int = GROUPS_HINT_MIN_PAIRS,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.weight(1f)) {
            if (selecting) {
                Text(
                    pluralStringResource(R.plurals.watchlist_selected_count, selectedCount, selectedCount),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            } else if (showsGroupsHint(groups, selectedGroup, hasFavorites, hasActivity, quickView, searching, sortMode, hasAlpha, pairCount)) {
                // Noch keine Chips: grauer Hinweis statt leerer Zeile (ohne Aktion)
                Text(
                    stringResource(R.string.watchlist_groups_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                GroupChips(
                    groups = groups,
                    selected = selectedGroup,
                    hasFavorites = hasFavorites,
                    hasTokens = hasTokens,
                    hasAlpha = hasAlpha,
                    onSelect = onSelectGroup,
                    onEdit = onEditGroup,
                    onAdd = onAddGroup,
                    hasActivity = hasActivity,
                    quickView = quickView,
                    onToggleActivity = onToggleActivity,
                )
            }
        }
        // Tippflächen 48 dp; ragen 12 dp nach aussen, damit die Symbole bündig mit dem
        // Kartenrand stehen (Fläche ragt in den Seitenrand). Anders als `offset` belegen die
        // Knöpfe so auch 12 dp weniger Platz — die Chips bekommen ihn (wie iOS `padding(-10)`)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.overhangEnd(HEADER_ACTIONS_OVERHANG)
        ) {
            if (selecting) {
                if (!allSelected) {
                    TextButton(onClick = onSelectAll) {
                        Text(stringResource(R.string.watchlist_select_all), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
                TextButton(onClick = onSelectionDone) {
                    Text(
                        stringResource(R.string.action_sort_done),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            } else if (sortMode) {
                // «Fertig» als Wort: der einzige Ausweg aus dem Sortiermodus, gut sichtbar
                TextButton(onClick = onSortDone) {
                    Text(
                        stringResource(R.string.action_sort_done),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            } else {
                // Glocke immer (ohne einen einzigen Alarm: gleich zum Anlegen statt in die leere Übersicht,
                // [onBellClick]), ohne Zahl (eine Zahl läse sich leicht als «so oft ausgelöst»); immer die
                // Alarm-Glocke mit Wellen, mit aktiven Alarmen gefüllt
                // Build 46: Glocke mit vollem 48-dp-Platz — die Tippflächen von Glocke und Menü
                // überlappen nicht mehr (früher 34 dp Platz, 14 dp der Glocke lagen unter dem Menü)
                AlarmBell(
                    scale = bellScale,
                    active = activeAlarms > 0,
                    onClick = onBellClick,
                )
                WatchlistMenu(
                    onOpenAbout = onOpenAbout,
                    onOpenAllAlarms = onOpenAllAlarms,
                    refreshing = refreshing,
                    canRefresh = canRefresh,
                    onRefresh = onRefresh,
                    canSort = canSort,
                    onSort = onSort,
                    canSelect = canSelect,
                    onStartSelection = onStartSelection,
                    onShowReport = onShowReport,
                    canClear = canClear,
                    notTradedCount = notTradedCount,
                    onRemoveNotTraded = onRemoveNotTraded,
                    onClearAll = onClearAll,
                )
            }
        }
    }
}

/**
 * Hinweis «Hier erscheinen deine Gruppen» statt der Chips? Nur wenn keine Chips da sind
 * ([showsGroupChips]) und weder eine vorübergehende Ansicht, die Suche noch das Sortieren läuft.
 */
internal fun showsGroupsHint(
    groups: List<String>,
    selected: String?,
    hasFavorites: Boolean,
    hasActivity: Boolean,
    quickView: QuickView?,
    searching: Boolean,
    sortMode: Boolean,
    hasAlpha: Boolean = false,
    /** Paare in der Merkliste: der Hinweis erst ab [GROUPS_HINT_MIN_PAIRS] (G-14, erster Gebrauch ruhig). */
    pairCount: Int = GROUPS_HINT_MIN_PAIRS,
): Boolean = quickView == null && !searching && !sortMode && pairCount >= GROUPS_HINT_MIN_PAIRS &&
    !showsGroupChips(groups, selected, hasFavorites, hasActivity, hasAlpha)

/**
 * Ab so vielen Paaren lohnt sich Gruppieren: vorher bleibt die leere Chip-Zeile ohne Hinweis
 * (Gruppen oder Favoriten zeigen die Chips trotzdem). Wie `WatchlistGroupChips.hintMinPairs` (iOS).
 */
internal const val GROUPS_HINT_MIN_PAIRS = 5

/** So weit ragen die Knöpfe der Kopfzeile über den Rand hinaus (Symbole bündig mit dem Kartenrand). */
private val HEADER_ACTIONS_OVERHANG = 12.dp

/**
 * Ragt am Ende um [amount] über den eigenen Platz hinaus: gemessen wird um [amount] schmaler, der
 * Inhalt steht am Anfang und reicht in Laufrichtung darüber (bei Rechts-nach-links nach links).
 */
private fun Modifier.overhangEnd(amount: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val width = (placeable.width - amount.roundToPx()).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.placeRelative(0, 0) }
}

/**
 * Glocke: öffnet alle Alarme; pulsiert bei einem ausgelösten Alarm ([scale]). Immer die Alarm-Glocke mit
 * Wellen wie die Alarme in Zeile und Aktionen — die schlichte Glocke heisst dort «Kurs-Mitteilung an».
 * [active]: es gibt aktive Alarme (gefüllt), sonst nur der Umriss.
 */
@Composable
private fun AlarmBell(scale: () -> Float, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painterResource(if (active) R.drawable.ic_notifications_active else R.drawable.ic_notifications_active_outline),
            contentDescription = stringResource(R.string.alarms_overview_title),
            modifier = Modifier.graphicsLayer {
                scaleX = scale()
                scaleY = scale()
            }
        )
    }
}

/**
 * Überlaufmenü: oben App-Logo und Name (öffnet «Über die App»), dann Aktualisieren, Alle Alarme,
 * Sortieren, Bericht und — getrennt — nicht gehandelte Paare entfernen und Merkliste leeren.
 */
@Composable
private fun WatchlistMenu(
    onOpenAbout: () -> Unit,
    onOpenAllAlarms: () -> Unit,
    refreshing: Boolean,
    canRefresh: () -> Boolean,
    onRefresh: () -> Unit,
    canSort: Boolean,
    onSort: () -> Unit,
    canSelect: Boolean,
    onStartSelection: () -> Unit,
    onShowReport: () -> Unit,
    canClear: Boolean,
    notTradedCount: Int,
    onRemoveNotTraded: () -> Unit,
    onClearAll: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.action_more)
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            // Gleicher Anfang wie Markt und Portfolio: App (→ «Über»), Aktualisieren
            AppMenuHead(
                refreshing = refreshing,
                onClose = { menuOpen = false },
                onOpenAbout = onOpenAbout,
                onRefresh = onRefresh,
                // Eben erst aktualisiert: grau statt Meldung «Gerade aktualisiert»
                refreshEnabled = remember(menuOpen, refreshing) { canRefresh() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.alarms_overview_title)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_notifications_active), null) },
                onClick = { menuOpen = false; onOpenAllAlarms() }
            )
            if (canSort) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_sort)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_sort), null) },
                    onClick = { menuOpen = false; onSort() }
                )
            }
            // Mehrfachauswahl: Favorit, Gruppe oder Löschen für mehrere Paare auf einmal
            if (canSelect) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.watchlist_select)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_check), null) },
                    onClick = { menuOpen = false; onStartSelection() }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.watchlist_refresh_report)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_info), null) },
                onClick = { menuOpen = false; onShowReport() }
            )
            if (canClear) {
                HorizontalDivider()
                // Nur wenn es nicht gehandelte Paare gibt; direkt vor «Merkliste leeren»
                if (notTradedCount > 0) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.watchlist_remove_not_traded_menu, notTradedCount))
                        },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) },
                        onClick = { menuOpen = false; onRemoveNotTraded() }
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.watchlist_clear),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_delete), null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = { menuOpen = false; onClearAll() }
                )
            }
        }
    }
}

/**
 * Glocke im Kopf: Löst bei offener App ein Alarm aus ([latestTrigger] neu), pulsiert sie einmal
 * ([AlarmPulse]); mit «Bewegung reduzieren» nicht.
 */
@Composable
internal fun rememberBellPulse(latestTrigger: Long?, reduceMotion: Boolean): Animatable<Float, AnimationVector1D> {
    val scale = remember { Animatable(1f) }
    var seenTrigger by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(latestTrigger) {
        val current = latestTrigger ?: return@LaunchedEffect
        val previous = seenTrigger
        seenTrigger = current
        if (reduceMotion || !AlarmPulse.isNew(previous, current)) {
            scale.snapTo(1f)
            return@LaunchedEffect
        }
        scale.animateTo(AlarmPulse.SCALE, tween(AlarmPulse.MILLIS / 2))
        scale.animateTo(1f, tween(AlarmPulse.MILLIS / 2))
    }
    return scale
}
