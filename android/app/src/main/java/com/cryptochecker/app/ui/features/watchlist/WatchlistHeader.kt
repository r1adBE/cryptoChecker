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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.watch.AlarmPulse
import com.cryptochecker.app.ui.components.AppMenuHead

/**
 * Kopfzeile der Merkliste: links die Gruppen-Chips («Alle» und Gruppen, scrollbar, mit «+» für
 * eine neue Gruppe), rechts die Glocke (nur mit aktiven Alarmen) und fest das Überlaufmenü. Die LazyRow der Chips schneidet
 * in Laufrichtung ab, sie läuft also nicht unter die Knöpfe. Im Sortiermodus statt der Knöpfe
 * «Fertig». «Paar hinzufügen» steht rechts neben der Lupe ([WatchlistStatusRow]).
 */
@Composable
internal fun WatchlistHeader(
    groups: List<String>,
    selectedGroup: String?,
    hasFavorites: Boolean,
    onSelectGroup: (String?) -> Unit,
    onEditGroup: (String) -> Unit,
    onAddGroup: () -> Unit,
    sortMode: Boolean,
    onSortDone: () -> Unit,
    activeAlarms: Int,
    /** Grösse der Glocke (Puls bei einem neuen Alarm); nur beim Zeichnen gelesen. */
    bellScale: () -> Float,
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
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.weight(1f)) {
            GroupChips(
                groups = groups,
                selected = selectedGroup,
                hasFavorites = hasFavorites,
                onSelect = onSelectGroup,
                onEdit = onEditGroup,
                onAdd = onAddGroup
            )
        }
        // Tippflächen 48 dp; um 12 dp nach aussen versetzt, damit die Symbole
        // bündig mit dem Kartenrand stehen (Fläche ragt in den Seitenrand)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.offset(x = 12.dp)
        ) {
            if (sortMode) {
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
                // Glocke nur mit aktiven Alarmen und ohne Zahl (eine Zahl läse sich leicht als
                // «so oft ausgelöst»); «Alle Alarme» steht immer auch im Menü
                if (activeAlarms > 0) {
                    AlarmBell(scale = bellScale, onClick = onOpenAllAlarms)
                }
                WatchlistMenu(
                    onOpenAbout = onOpenAbout,
                    onOpenAllAlarms = onOpenAllAlarms,
                    refreshing = refreshing,
                    onRefresh = onRefresh,
                    canSort = canSort,
                    onSort = onSort,
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

/** Glocke: öffnet alle Alarme; pulsiert bei einem ausgelösten Alarm ([scale]). */
@Composable
private fun AlarmBell(scale: () -> Float, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painterResource(R.drawable.ic_notifications),
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
    onRefresh: () -> Unit,
    canSort: Boolean,
    onSort: () -> Unit,
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
                onRefresh = onRefresh
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.alarms_overview_title)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_notifications), null) },
                onClick = { menuOpen = false; onOpenAllAlarms() }
            )
            if (canSort) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_sort)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_sort), null) },
                    onClick = { menuOpen = false; onSort() }
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
