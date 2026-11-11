// Aktionen der Paarseite: ein Hauptknopf («Alarm setzen» bzw. in der Vorschau «Zur Merkliste hinzufügen»), kleine
// Knöpfe darunter (Favorit, «Was bewegt sich mit?», Börse wechseln, Kursbenachrichtigung) und das Menü ⋮ mit dem
// Seltenen (Gruppe, Notiz, Portfolio, Aktualisieren, Widget, Vorlesen, Deaktivieren, Löschen). Wie `PairActions.swift`.
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.theme.DisabledColors
import com.cryptochecker.app.ui.theme.Spacing

/**
 * Hauptknopf der Seite, volle Breite: «Alarm setzen» öffnet den Alarm-Editor ([enabled] false bei Referenzkurs,
 * zu dünnem DEX-Pool oder erreichter Höchstzahl — der Grund steht im Abschnitt «Alarme»).
 */
@Composable
internal fun PairPrimaryButton(
    icon: Int,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = DisabledColors.button(),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = Spacing.sm)
        )
    }
}

/** «Alarm setzen» des gespeicherten Paars (Kennung für Oberflächentests und Bildschirmfotos). */
@Composable
internal fun PairAlarmButton(enabled: Boolean, onClick: () -> Unit) {
    PairPrimaryButton(
        icon = R.drawable.ic_notifications_active,
        text = stringResource(R.string.add_alarm_action),
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag(UiTestTags.WATCH_ACTION_ALARM),
    )
}

/**
 * Kleine Knöpfe unter dem Hauptknopf; brechen bei grosser Schrift in weitere Zeilen um. Favorit und
 * Kursbenachrichtigung sind Umschalter (Screenreader: «ausgewählt»); «Was bewegt sich mit?» fehlt bei nicht mehr
 * gehandelten bzw. deaktivierten Paaren ([showWhy]), mit Signalen gefüllte Glühbirne ([hasActivity]).
 */
@Composable
internal fun PairSecondaryActions(
    watch: WatchEntity,
    showWhy: Boolean,
    hasActivity: Boolean,
    onToggleFavorite: () -> Unit,
    onWhy: () -> Unit,
    onChangeExchange: () -> Unit,
    onNotificationChange: (Boolean) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.fillMaxWidth()
    ) {
        FilterChip(
            selected = watch.favorite,
            onClick = onToggleFavorite,
            label = { Text(stringResource(R.string.watch_action_favorite)) },
            leadingIcon = {
                Icon(painterResource(if (watch.favorite) R.drawable.ic_star else R.drawable.ic_star_outline), null, Modifier.size(18.dp))
            },
        )
        if (showWhy) {
            AssistChip(
                onClick = onWhy,
                label = { Text(stringResource(R.string.watch_action_why)) },
                leadingIcon = {
                    // Gefüllt, solange beim Paar gerade etwas passiert (⚡), sonst als Umriss (wie iOS)
                    Icon(
                        painterResource(if (hasActivity) R.drawable.ic_lightbulb_filled else R.drawable.ic_lightbulb),
                        null,
                        Modifier.size(18.dp)
                    )
                },
            )
        }
        AssistChip(
            onClick = onChangeExchange,
            label = { Text(stringResource(R.string.watch_action_change_exchange)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_swap_horiz), null, Modifier.size(18.dp)) },
        )
        // Dieselbe schlichte Glocke wie in der Zeile der Merkliste; aus = durchgestrichen
        FilterChip(
            selected = watch.notificationEnabled,
            onClick = { onNotificationChange(!watch.notificationEnabled) },
            label = { Text(stringResource(R.string.watchlist_notification)) },
            leadingIcon = {
                Icon(
                    painterResource(if (watch.notificationEnabled) R.drawable.ic_notifications else R.drawable.ic_notifications_off),
                    null,
                    Modifier.size(18.dp)
                )
            },
        )
    }
}

/** Was das Menü ⋮ der Paarseite auslöst; null = Eintrag fehlt. */
internal class PairMenuActions(
    val onEditGroup: () -> Unit,
    val onEditNote: () -> Unit,
    val onAddToPortfolio: (() -> Unit)?,
    val onRefresh: () -> Unit,
    val onAddWidget: () -> Unit,
    val onTtsChange: (Boolean) -> Unit,
    val onToggleDisabled: () -> Unit,
    val onDelete: () -> Unit,
)

/**
 * Menü ⋮ oben rechts: alles Seltene aus dem früheren Abschnitt «Mehr» — Gruppe, Notiz, Portfolio, Aktualisieren
 * (nicht bei [off]), Widget, Vorlesen (Häkchen), Deaktivieren/Aktivieren und zuletzt Löschen in der Fehlerfarbe.
 */
@Composable
internal fun PairOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    watch: WatchEntity,
    off: Boolean,
    actions: PairMenuActions,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        fun pick(action: () -> Unit): () -> Unit = { onDismiss(); action() }
        MenuEntry(
            R.drawable.ic_list,
            watch.groupName?.let { stringResource(R.string.group_value, it) } ?: stringResource(R.string.group_title),
            pick(actions.onEditGroup),
        )
        MenuEntry(
            R.drawable.ic_note,
            stringResource(if (watch.note.isNullOrBlank()) R.string.note_add else R.string.note_edit),
            pick(actions.onEditNote),
        )
        actions.onAddToPortfolio?.let { MenuEntry(R.drawable.ic_portfolio, stringResource(R.string.portfolio_add_from_watch), pick(it)) }
        if (!off) MenuEntry(R.drawable.ic_refresh, stringResource(R.string.action_refresh), pick(actions.onRefresh))
        // Einzel-Widget mit diesem Paar; kann der Startbildschirm das nicht, eine kurze Anleitung
        MenuEntry(R.drawable.ic_widgets, stringResource(R.string.watch_action_add_widget), pick(actions.onAddWidget))
        DropdownMenuItem(
            text = { Text(stringResource(R.string.watchlist_tts)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_volume_up), null) },
            // Häkchen trägt den Zustand («aktiviert»/«nicht aktiviert») auch für den Screenreader
            trailingIcon = { Checkbox(checked = watch.ttsEnabled, onCheckedChange = null) },
            onClick = pick { actions.onTtsChange(!watch.ttsEnabled) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // Deaktivieren (keine Abfragen, keine Meldungen, Alarme ruhen) bzw. wieder aktivieren
        DropdownMenuItem(
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(stringResource(if (watch.disabled) R.string.watch_enable else R.string.watch_disable))
                    Text(
                        stringResource(if (watch.disabled) R.string.watch_enable_hint else R.string.watch_disable_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            leadingIcon = {
                Icon(painterResource(if (watch.disabled) R.drawable.ic_play_circle else R.drawable.ic_pause_circle), null)
            },
            onClick = pick(actions.onToggleDisabled),
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null, tint = MaterialTheme.colorScheme.error) },
            onClick = pick(actions.onDelete),
        )
    }
}

@Composable
private fun MenuEntry(icon: Int, text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.primary) },
        onClick = onClick,
    )
}
