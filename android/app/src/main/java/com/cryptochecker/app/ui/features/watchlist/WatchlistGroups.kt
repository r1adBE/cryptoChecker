@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.watch.QuickView
import com.cryptochecker.app.domain.watch.WatchFilter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

/**
 * Gruppen-Auswahl oben: «Alle · ★ · ⚡ · Gruppe 1 · Gruppe 2 … · +».
 * Tippen filtert, lange drücken öffnet «Gruppe bearbeiten», «+» legt eine an.
 * Nur was es gibt: ★ (Favoriten) erst mit mindestens einem Favoriten, ⚡ (Paare, bei denen gerade
 * etwas passiert; [QuickView.ACTIVITY], ohne Zahl) nur mit solchen Paaren, «+» erst mit einer
 * Gruppe (die erste legt man über das Aktionsblatt eines Paars an). Gibt es nichts davon, bleibt
 * die Zeile leer — ein einzelnes «Alle» wäre nur ein weiterer Knopf ([showsGroupChips]).
 * Ist eine vorübergehende Ansicht ([quickView]) an, ist keine Gruppe markiert (ausser ⚡ selbst).
 */
@Composable
internal fun GroupChips(
    groups: List<String>,
    selected: String?,
    hasFavorites: Boolean,
    onSelect: (String?) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    hasActivity: Boolean = false,
    quickView: QuickView? = null,
    onToggleActivity: () -> Unit = {},
) {
    val activitySelected = quickView == QuickView.ACTIVITY
    val groupSelected: (String?) -> Boolean = { it == selected && quickView == null }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!showsGroupChips(groups, selected, hasFavorites, hasActivity || activitySelected)) return@LazyRow
        item(key = "all") {
            GroupChip(
                text = stringResource(R.string.group_all),
                selected = groupSelected(null),
                onClick = { onSelect(null) }
            )
        }
        // Nur der Stern (Screenreader: «Favoriten»); auch ohne Favoriten sichtbar, solange die
        // Ansicht gewählt ist (sonst gäbe es keinen Weg zurück)
        if (hasFavorites || WatchFilter.isFavorites(selected)) {
            item(key = "favorites") {
                GroupChip(
                    icon = R.drawable.ic_star,
                    description = stringResource(R.string.group_favorites),
                    selected = groupSelected(WatchFilter.FAVORITES),
                    onClick = { onSelect(WatchFilter.FAVORITES) }
                )
            }
        }
        // ⚡ wie in den Zeilen (neutral, gewählt in der Themenfarbe); Screenreader: «Hier passiert gerade etwas»
        if (hasActivity || activitySelected) {
            item(key = "activity") {
                GroupChip(
                    icon = R.drawable.ic_bolt,
                    description = stringResource(R.string.activity_card_title),
                    selected = activitySelected,
                    onClick = onToggleActivity
                )
            }
        }
        items(groups, key = { "group:$it" }) { group ->
            GroupChip(
                text = group,
                selected = groupSelected(group),
                onClick = { onSelect(group) },
                onLongClick = { onEdit(group) }
            )
        }
        if (groups.isNotEmpty()) item(key = "add") {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    // Tippfläche 48 dp, sichtbar bleibt das kleine Feld
                    .minimumInteractiveComponentSize()
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .clickable(onClick = onAdd)
            ) {
                Icon(
                    painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.group_add),
                    // Neutral wie die übrigen Chips
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** Gruppen-Chips zeigen? Nur wenn es neben «Alle» etwas zu wählen gibt (oder eine Auswahl aktiv ist). */
internal fun showsGroupChips(
    groups: List<String>,
    selected: String?,
    hasFavorites: Boolean,
    hasActivity: Boolean = false,
): Boolean = groups.isNotEmpty() || hasFavorites || hasActivity || selected != null

/**
 * Chip im Stil des Material-FilterChips, aber mit langem Drücken
 * (FilterChip kennt das nicht). Alle Chips neutral: Text und Symbol in onSurface, grauer Rand,
 * keine Füllung. Der gewählte bekommt Rand, Text und Symbol in der Themenfarbe (orange) —
 * ebenfalls ohne Füllung. Screenreader: Tab mit Zustand «ausgewählt» (wie iOS `.isSelected`).
 */
@Composable
private fun GroupChip(
    selected: Boolean,
    onClick: () -> Unit,
    text: String? = null,
    /** Nur ein Symbol statt [text] (★ Favoriten, ⚡ Aktivität); dann [description] angeben. */
    icon: Int? = null,
    onLongClick: (() -> Unit)? = null,
    /** Vorgelesen statt [text] bzw. für das Symbol. */
    description: String? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    val accent = MaterialTheme.colorScheme.primary
    val chipSelected = selected
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            // Tippfläche 48 dp hoch, sichtbar bleibt der 32-dp-Chip
            .minimumInteractiveComponentSize()
            .height(32.dp)
            .clip(shape)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
                shape
            )
            .combinedClickable(role = Role.Tab, onClick = onClick, onLongClick = onLongClick)
            .semantics { this.selected = chipSelected }
            .padding(horizontal = if (icon != null) 12.dp else 16.dp)
    ) {
        val contentColor = if (selected) accent else MaterialTheme.colorScheme.onSurface
        val a11y = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
        if (icon != null) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp).then(a11y)
            )
        } else {
            Text(
                text = text.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = a11y
            )
        }
    }
}

/**
 * Gruppe wählen: bestehende Gruppen, «Keine Gruppe» oder eine neue anlegen.
 * Eine Auswahl gilt sofort; nur die neue Gruppe braucht «Speichern».
 */
@Composable
internal fun GroupDialog(
    current: String?,
    groups: List<String>,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                GroupOption(stringResource(R.string.group_none), current == null && !creating) { onSelect(null) }
                groups.forEach { group ->
                    GroupOption(group, current == group && !creating) { onSelect(group) }
                }
                GroupOption(stringResource(R.string.group_new), creating) { creating = true }
                if (creating) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(MAX_GROUP_NAME) },
                        label = { Text(stringResource(R.string.group_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(enabled = trimmed.isNotEmpty(), onClick = { onSelect(trimmed) }) {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

private const val MAX_GROUP_NAME = 24

@Composable
private fun GroupOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
