@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.itemsIndexed
import com.cryptochecker.app.ui.components.ListSegment
import androidx.compose.ui.graphics.Shape
import com.cryptochecker.app.ui.theme.AppColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.AutoOrder
import com.cryptochecker.app.domain.watch.WatchPulse
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.UiTestTags

@Composable
private fun HintText(text: String, highlight: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

/**
 * «Nach Coin ordnen» · «Nach Börse ordnen» im Sortiermodus: ordnet die gezeigten Paare einmal und speichert
 * sie als eigene Reihenfolge ([ReorderState.autoOrder]). Bricht bei langen Übersetzungen in die nächste Zeile um.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoOrderButtons(onAutoOrder: (AutoOrder) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        TextButton(onClick = { onAutoOrder(AutoOrder.COIN) }) {
            Text(stringResource(R.string.watchlist_sort_by_coin), style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = { onAutoOrder(AutoOrder.EXCHANGE) }) {
            Text(stringResource(R.string.watchlist_sort_by_exchange), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Kurzer Hinweis zu den Gesten mit Schliessen-Knopf. Zuerst eine Zeile, was ein Tipp bringt («Was bewegt sich
 * mit?», sonst kaum zu entdecken), darunter die Gesten.
 */
@Composable
private fun GestureHint(onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(AppColors.accentTint(0.08f))
            .padding(start = Spacing.md, top = 4.dp, bottom = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.watch_hint_tap_why),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.watch_gesture_hint_short),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
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
 * Karte «Ersten Alarm setzen» ([com.cryptochecker.app.domain.watch.FirstAlarmCard]): ganze Karte
 * antippbar (öffnet die Alarme des ersten Paars der gespeicherten Reihenfolge, ohne Alarm gleich den Editor), ✕
 * schliesst sie für immer. Der Titel nennt das Paar ([pair]), damit klar ist, wofür der Alarm gilt.
 */
@Composable
private fun FirstAlarmCardView(pair: String?, onSetAlarm: () -> Unit, onDismiss: () -> Unit) {
    val title = if (pair != null) stringResource(R.string.watchlist_first_alarm_title_pair, pair)
    else stringResource(R.string.watchlist_first_alarm_title)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(AppColors.accentTint(0.12f))
            .clickable(onClickLabel = title, role = Role.Button, onClick = onSetAlarm)
            .padding(start = Spacing.md, top = Spacing.sm, bottom = Spacing.sm)
    ) {
        Icon(
            painterResource(R.drawable.ic_notifications_active_outline),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f).padding(start = Spacing.sm)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.watchlist_first_alarm_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
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
 * Die scrollende Liste unter dem festen Kopf: Puls, Hinweise, leere Zustände und die Paare
 * ([row] je Paar). «Hier passiert gerade etwas» ist keine Karte mehr, sondern der ⚡-Chip bei
 * den Gruppen ([GroupChips]). Kein schwebender Plus-Knopf: Hinzufügen läuft über
 * «+» in der Kopfzeile. Mit Sprungknopf ([roomForJump]) unten mehr Platz, damit er die letzte
 * Zeile nicht verdeckt.
 */
@Composable
internal fun WatchlistList(
    listState: LazyListState,
    inset: Dp,
    roomForJump: Boolean,
    pulse: WatchPulse?,
    /** Sortieren nach Spalte («Name ⇅ · Kurs ⇅ · 24h ⇅»); null = keine Zeile (Sortiermodus, < 2 Paare). */
    sortBar: (@Composable () -> Unit)? = null,
    sortMode: Boolean,
    /** Sortiermodus: «Nach Coin/Börse ordnen» unter dem Hinweis; null = keine Knöpfe (< 2 Paare). */
    onAutoOrder: ((AutoOrder) -> Unit)? = null,
    showGestureHint: Boolean,
    onDismissGestureHint: () -> Unit,
    /** Karte «Ersten Alarm setzen»: Tipp öffnet den Alarm-Editor; null = keine Karte. */
    onSetFirstAlarm: (() -> Unit)? = null,
    onDismissFirstAlarm: () -> Unit = {},
    /** Paar, das die Karte «Ersten Alarm setzen» öffnet (im Titel genannt). */
    firstAlarmPair: String? = null,
    /** Suche ohne Treffer. */
    noSearchMatch: Boolean,
    query: String,
    /** Leere Ansicht (Gruppe ohne Paare): ruhiger Hinweis statt einer leeren Fläche. */
    groupEmpty: Boolean,
    /** «FAV» ohne Favoriten: Hinweis, wie man einen setzt (statt des Gruppen-Hinweises). */
    favoritesEmpty: Boolean = false,
    /** Eigene Gruppe gewählt: Knopf «Gruppe bearbeiten» unter dem Hinweis (null = keiner, z. B. «FAV», TradFi). */
    onEditGroup: (() -> Unit)? = null,
    shown: List<WatchEntity>,
    /** Zeile je Paar mit ihrer Form ([ListSegment.shape]: die Paare wirken wie eine Einheit). */
    row: @Composable LazyItemScope.(WatchEntity, Shape) -> Unit,
) {
    // Zeilenbreite einmal für alle Zeilen messen (Liste abzüglich des seitlichen Innenabstands)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rowWidth = maxWidth - (16.dp + inset) * 2
        CompositionLocalProvider(LocalWatchRowWidth provides rowWidth) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag(UiTestTags.WATCHLIST),
                contentPadding = PaddingValues(
                    start = 16.dp + inset,
                    top = 8.dp,
                    end = 16.dp + inset,
                    bottom = if (roomForJump) 24.dp + JumpButtonSize + JumpButtonMargin else 24.dp,
                ),
                // Paare ohne Fuge als eine Karte ([ListSegment]); alles darüber bringt den Abstand selbst mit
                verticalArrangement = Arrangement.spacedBy(ListSegment.Gap)
            ) {
                // Puls als erste Zeile unter der festen Kopfzeile (scrollt mit), Abstände per spacedBy
                if (pulse != null) {
                    item(key = "pulse") {
                        WatchPulseLine(pulse = pulse, modifier = Modifier.animateItem().padding(bottom = ListSegment.Spacing))
                    }
                }
                if (onSetFirstAlarm != null) {
                    item(key = "first_alarm") {
                        Box(Modifier.animateItem().padding(bottom = ListSegment.Spacing)) {
                            FirstAlarmCardView(pair = firstAlarmPair, onSetAlarm = onSetFirstAlarm, onDismiss = onDismissFirstAlarm)
                        }
                    }
                }
                if (sortBar != null) {
                    item(key = "sort_bar") { Box(Modifier.padding(bottom = ListSegment.Spacing)) { sortBar() } }
                }
                if (sortMode) {
                    item(key = "sort_hint") {
                        Column(Modifier.padding(bottom = ListSegment.Spacing)) {
                            HintText(stringResource(R.string.watchlist_sort_hint), highlight = true)
                            if (onAutoOrder != null) AutoOrderButtons(onAutoOrder)
                        }
                    }
                } else if (showGestureHint) {
                    // Einmaliger Gesten-Hinweis, bleibt bis er weggeklickt wird.
                    item(key = "gesture_hint") {
                        Box(Modifier.padding(bottom = ListSegment.Spacing)) { GestureHint(onDismiss = onDismissGestureHint) }
                    }
                }
                if (noSearchMatch) {
                    item(key = "search_empty") {
                        ListNote(stringResource(R.string.watchlist_search_empty, query))
                    }
                }
                if (groupEmpty) {
                    item(key = "group_empty") {
                        val edit = onEditGroup?.takeIf { !favoritesEmpty }
                        if (edit == null) {
                            ListNote(stringResource(if (favoritesEmpty) R.string.watchlist_favorites_empty_hint else R.string.watchlist_group_empty_hint))
                        } else {
                            // Langes Drücken ist nicht auffindbar: derselbe Weg als sichtbarer Knopf (wie iOS)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp)
                            ) {
                                ListNote(stringResource(R.string.watchlist_group_empty_hint), vertical = 0.dp)
                                TextButton(onClick = edit, modifier = Modifier.padding(top = Spacing.xs)) {
                                    Text(stringResource(R.string.group_edit_title))
                                }
                            }
                        }
                    }
                }
                itemsIndexed(shown, key = { _, it -> it.id }) { index, watch -> row(watch, ListSegment.shape(index, shown.size)) }
            }
        }
    }
}

/** Ruhiger Hinweis mitten in der Liste (keine Treffer, leere Gruppe). */
@Composable
private fun ListNote(text: String, vertical: Dp = 24.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = vertical)
    )
}
