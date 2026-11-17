// Menü «⋮» der Merkliste (Teil des Kopfs, WatchlistHeader).
package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.refresh.RefreshDebounce
import com.cryptochecker.app.domain.watch.AlarmPulse
import com.cryptochecker.app.domain.watch.QuickView
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.AppMenuHead
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.resources.R
import kotlinx.coroutines.delay

/**
 * Überlaufmenü: oben App-Logo und Name (öffnet «Über die App»), dann Aktualisieren, Alle Alarme,
 * Sortieren, Bericht und — getrennt — nicht gehandelte Paare entfernen und Merkliste leeren.
 */
@Composable
internal fun WatchlistMenu(
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
    var legendOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // Eben erst aktualisiert: «Aktualisieren (5)», «(4)» … und dann von selbst wieder aktiv, auch bei
    // offenem Menü. Nur solange es offen ist, einmal je Sekunde (genau auf der Sekundengrenze).
    // Läuft eine, zeigt der Eintrag den Kreisel statt einer Zahl. Keine Ansage je Sekunde: Der
    // Screenreader liest die neue Beschriftung, wenn er den Eintrag erreicht.
    var cooldown by remember(menuOpen, refreshing) {
        mutableIntStateOf(
            if (!menuOpen || refreshing) {
                0
            } else {
                RefreshDebounce.remainingSeconds(lastFullRefreshAt(), System.currentTimeMillis())
            }
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
        // Antippbarer Knoten 48 × 48 dp (Standard 40 dp in 48 dp Platz); sichtbar bleibt das 24-dp-Symbol
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(MinTouchTarget)) {
            Icon(
                painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.action_more)
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            // Gleicher Anfang wie Markt und Portfolio: App (→ «Über die App»), Aktualisieren
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
                onClick = {
                    menuOpen = false
                    onOpenAllAlarms()
                }
            )
            if (canSort) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_sort)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_sort), null) },
                    onClick = {
                        menuOpen = false
                        onSort()
                    }
                )
            }
            // Mehrfachauswahl: Favorit, Gruppe oder Löschen für mehrere Paare auf einmal
            if (canSelect) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.watchlist_select)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_check), null) },
                    onClick = {
                        menuOpen = false
                        onStartSelection()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.watchlist_refresh_report)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_info), null) },
                onClick = {
                    menuOpen = false
                    onShowReport()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.watchlist_symbols_legend)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_notifications), null) },
                onClick = {
                    menuOpen = false
                    legendOpen = true
                }
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
                        onClick = {
                            menuOpen = false
                            onRemoveNotTraded()
                        }
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
                            painterResource(R.drawable.ic_delete),
                            null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onClearAll()
                    }
                )
            }
        }
        if (legendOpen) WatchlistSymbolLegendSheet(onDismiss = { legendOpen = false })
    }
}
