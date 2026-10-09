@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.background
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
import com.cryptochecker.app.domain.watch.WatchFilter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Gruppen-Auswahl oben: «Alle · Favoriten · Gruppe 1 · Gruppe 2 … · +».
 * Tippen filtert, lange drücken öffnet «Gruppe bearbeiten», «+» legt eine an.
 * Nur was es gibt: «Favoriten» erst mit mindestens einem Favoriten, «+» erst mit einer Gruppe (die
 * erste legt man über das Aktionsblatt eines Paars an). Gibt es weder noch, bleibt die Zeile leer —
 * ein einzelnes «Alle» wäre nur ein weiterer Knopf ([showsGroupChips]).
 */
@Composable
internal fun GroupChips(
    groups: List<String>,
    selected: String?,
    hasFavorites: Boolean,
    onSelect: (String?) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!showsGroupChips(groups, selected, hasFavorites)) return@LazyRow
        item(key = "all") {
            GroupChip(
                text = stringResource(R.string.group_all),
                selected = selected == null,
                onClick = { onSelect(null) }
            )
        }
        // Auch ohne Favoriten sichtbar, solange die Ansicht gewählt ist (sonst gäbe es keinen Weg zurück)
        if (hasFavorites || WatchFilter.isFavorites(selected)) {
            item(key = "favorites") {
                GroupChip(
                    text = stringResource(R.string.group_favorites),
                    selected = WatchFilter.isFavorites(selected),
                    onClick = { onSelect(WatchFilter.FAVORITES) }
                )
            }
        }
        items(groups, key = { "group:$it" }) { group ->
            GroupChip(
                text = group,
                selected = selected == group,
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** Gruppen-Chips zeigen? Nur wenn es neben «Alle» etwas zu wählen gibt (oder eine Auswahl aktiv ist). */
internal fun showsGroupChips(groups: List<String>, selected: String?, hasFavorites: Boolean): Boolean =
    groups.isNotEmpty() || hasFavorites || selected != null

/**
 * Chip im Stil des Material-FilterChips, aber mit langem Drücken
 * (FilterChip kennt das nicht).
 */
@Composable
private fun GroupChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    /** Vorgelesen statt [text] (z. B. «Favoriten» für «FAV»). */
    description: String? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            // Tippfläche 48 dp hoch, sichtbar bleibt der 32-dp-Chip
            .minimumInteractiveComponentSize()
            .height(32.dp)
            .clip(shape)
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
        )
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
