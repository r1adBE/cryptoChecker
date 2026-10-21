// Dialog «Gruppe» der Merkliste (wie `GroupDialog` in `WatchlistGroups.swift`): bestehende Gruppe wählen oder neue
// anlegen. Eigene Datei, weil `WatchlistGroups` (Gruppen-Chips) sonst zu lang würde.
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.ui.theme.label
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.GROUP_NAME_MAX
import com.cryptochecker.app.util.TextLimit
import androidx.compose.ui.semantics.selected

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
                        onValueChange = { name = TextLimit.takeGraphemes(it, GROUP_NAME_MAX) },
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
