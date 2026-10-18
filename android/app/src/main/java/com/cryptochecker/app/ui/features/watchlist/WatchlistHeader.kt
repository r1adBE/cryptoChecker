@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.cryptochecker.app.ui.theme.Spacing
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
import com.cryptochecker.app.domain.refresh.RefreshDebounce
import com.cryptochecker.app.domain.watch.AlarmPulse
import com.cryptochecker.app.domain.watch.QuickView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.cryptochecker.app.ui.components.AppMenuHead
import kotlinx.coroutines.delay

/**
 * Kopfzeile der Merkliste: links die Gruppen-Chips («Alle» und Gruppen, scrollbar, mit «+» für
 * eine neue Gruppe), rechts die Glocke (nur mit aktiven Alarmen) und fest das Überlaufmenü. Die LazyRow der Chips schneidet
 * in Laufrichtung ab, sie läuft also nicht unter die Knöpfe. Im Sortiermodus statt der Knöpfe
 * «Fertig». «Paar hinzufügen» steht rechts neben der Lupe ([WatchlistStatusRow]).
 * Gibt es noch keine Chips, steht dort ab einigen Paaren ein grauer Hinweis ([showsGroupsHint]) — erst nach dem
 * ersten Gebrauch ([com.cryptochecker.app.domain.watch.WatchlistOnboarding]).
 */
@Composable
internal fun WatchlistHeader(
    state: WatchlistHeaderState,
    actions: WatchlistHeaderActions,
    /** Grösse der Glocke (Puls bei einem neuen Alarm); nur beim Zeichnen gelesen. */
    bellScale: () -> Float,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.weight(1f)) {
            if (state.selecting) {
                Text(
                    pluralStringResource(R.plurals.watchlist_selected_count, state.selectedCount, state.selectedCount),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            } else if (
                showsGroupsHint(
                    state.groups, state.selectedGroup, state.hasFavorites, state.hasActivity, state.quickView,
                    state.searching, state.sortMode, state.hasAlpha, state.pairCount, state.groupsHintAllowed,
                )
            ) {
                // Noch keine Chips: grauer Hinweis statt leerer Zeile, daneben «Neue Gruppe» (wie iOS) — sonst
                // fände man Gruppen nur über langes Drücken bzw. das Aktionsblatt
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.watchlist_groups_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    TextButton(onClick = actions.onAddGroup, contentPadding = PaddingValues(horizontal = Spacing.sm)) {
                        Text(stringResource(R.string.group_add), maxLines = 1)
                    }
                }
            } else {
                GroupChips(
                    groups = state.groups,
                    selected = state.selectedGroup,
                    hasFavorites = state.hasFavorites,
                    hasTokens = state.hasTokens,
                    hasAlpha = state.hasAlpha,
                    onSelect = actions.onSelectGroup,
                    onEdit = actions.onEditGroup,
                    onAdd = actions.onAddGroup,
                    hasActivity = state.hasActivity,
                    quickView = state.quickView,
                    onToggleActivity = actions.onToggleActivity,
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
            if (state.selecting) {
                if (!state.allSelected) {
                    TextButton(onClick = actions.onSelectAll) {
                        Text(stringResource(R.string.watchlist_select_all), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
                TextButton(onClick = actions.onSelectionDone) {
                    Text(
                        stringResource(R.string.action_sort_done),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            } else if (state.sortMode) {
                // «Fertig» als Wort: der einzige Ausweg aus dem Sortiermodus, gut sichtbar
                TextButton(onClick = actions.onSortDone) {
                    Text(
                        stringResource(R.string.action_sort_done),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            } else {
                // Glocke immer (ohne einen einzigen Alarm: gleich zum Anlegen statt in die leere Übersicht,
                // [WatchlistHeaderActions.onBellClick]), ohne Zahl (eine Zahl läse sich leicht als «so oft ausgelöst»); immer die
                // Alarm-Glocke mit Wellen, mit aktiven Alarmen gefüllt
                // Build 46: Glocke mit vollem 48-dp-Platz — die Tippflächen von Glocke und Menü
                // überlappen nicht mehr (früher 34 dp Platz, 14 dp der Glocke lagen unter dem Menü)
                AlarmBell(
                    scale = bellScale,
                    active = state.activeAlarms > 0,
                    alarmTotal = state.alarmTotal,
                    onClick = actions.onBellClick,
                )
                WatchlistMenu(
                    onOpenAbout = actions.onOpenAbout,
                    onOpenAllAlarms = actions.onOpenAllAlarms,
                    refreshing = state.refreshing,
                    lastFullRefreshAt = actions.lastFullRefreshAt,
                    onRefresh = actions.onRefresh,
                    canSort = state.canSort,
                    onSort = actions.onSort,
                    canSelect = state.canSelect,
                    onStartSelection = actions.onStartSelection,
                    onShowReport = actions.onShowReport,
                    canClear = state.canClear,
                    notTradedCount = state.notTradedCount,
                    onRemoveNotTraded = actions.onRemoveNotTraded,
                    onClearAll = actions.onClearAll,
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
    /** Erster Gebrauch vorbei ([com.cryptochecker.app.domain.watch.WatchlistOnboarding.Visible.groupsHint]). */
    onboarded: Boolean = true,
): Boolean = onboarded && quickView == null && !searching && !sortMode && pairCount >= GROUPS_HINT_MIN_PAIRS &&
    !showsGroupChips(groups, selected, hasFavorites, hasActivity, hasAlpha)


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
private fun AlarmBell(
    scale: () -> Float,
    active: Boolean,
    alarmTotal: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Beschriftung nach dem Ziel: ohne Alarm führt die Glocke zum Anlegen («Neuer Alarm»), sonst zur Übersicht
    // («Alarme (3)»); noch nicht geladen → «Alle Alarme» (gleich wie iOS)
    val label = when {
        alarmTotal == null -> stringResource(R.string.alarms_overview_title)
        alarmTotal == 0 -> stringResource(R.string.alarms_add)
        else -> pluralStringResource(R.plurals.watchlist_alarms_count, alarmTotal, alarmTotal)
    }
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painterResource(if (active) R.drawable.ic_notifications_active else R.drawable.ic_notifications_active_outline),
            contentDescription = label,
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
    lastFullRefreshAt: () -> Long,
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
    // Eben erst aktualisiert: «Aktualisieren (5)», «(4)» … und dann von selbst wieder aktiv, auch bei
    // offenem Menü. Nur solange es offen ist, einmal je Sekunde (genau auf der Sekundengrenze).
    // Läuft eine, zeigt der Eintrag den Kreisel statt einer Zahl. Keine Ansage je Sekunde: Der
    // Screenreader liest die neue Beschriftung, wenn er den Eintrag erreicht.
    var cooldown by remember(menuOpen, refreshing) {
        mutableIntStateOf(
            if (!menuOpen || refreshing) 0
            else RefreshDebounce.remainingSeconds(lastFullRefreshAt(), System.currentTimeMillis())
        )
    }
    LaunchedEffect(menuOpen, refreshing) {
        if (!menuOpen || refreshing) return@LaunchedEffect
        while (true) {
            val last = lastFullRefreshAt()
            val now = System.currentTimeMillis()
            cooldown = RefreshDebounce.remainingSeconds(last, now)
            if (cooldown == 0) break
            delay(RefreshDebounce.millisUntilNextChange(last, now))
        }
    }
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
                // Eben erst aktualisiert: grau mit Countdown statt Meldung «Gerade aktualisiert»
                refreshEnabled = cooldown == 0,
                refreshLabel = if (cooldown > 0) stringResource(R.string.watchlist_refresh_countdown, cooldown) else null,
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
