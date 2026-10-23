@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.ui.components.GroupNameDialog
import com.cryptochecker.app.ui.components.canonicalGroupName
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.headline

/**
 * «Gruppe bearbeiten»: Name ändern und per Häkchen festlegen, welche Paare
 * dazugehören. Paare aus einer anderen Gruppe zeigen diese klein an; ein
 * Häkchen verschiebt sie hierher. Erst «Fertig» speichert alles auf einmal;
 * Wegwischen verwirft die Änderungen.
 *
 * @param groupName bisheriger Name bzw. bei einer neuen Gruppe der eingegebene
 * @param isNew neue Gruppe: noch ohne Paare, «Löschen» entfällt
 * @param watches alle Paare in der Reihenfolge der Merkliste (Favoriten zuerst)
 * @param groups alle bestehenden Gruppen (für die Schreibweise beim Umbenennen)
 */
@Composable
internal fun GroupEditSheet(
    groupName: String,
    isNew: Boolean,
    watches: List<WatchEntity>,
    groups: List<String>,
    /** `cancelled`: «Abbrechen» in der Rückfrage F-08 (Blatt bleibt, wieder bereit); zu geht es über den Aufrufer. */
    onDone: (newName: String, memberIds: Set<Long>, cancelled: () -> Unit) -> Unit,
    onDelete: (cancelled: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable(groupName) { mutableStateOf(groupName) }
    // Startzustand: die Paare, die schon in dieser Gruppe sind
    var checked by remember(groupName) {
        mutableStateOf(watches.filter { it.groupName == groupName }.map { it.id }.toSet())
    }
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    // Speichern bzw. Löschen läuft (Rückfrage F-08 offen): weitere Tipps zählen nicht (IOS6-01, wie iOS `saving`)
    var saving by remember { mutableStateOf(false) }

    if (renaming) {
        GroupNameDialog(
            title = stringResource(R.string.group_rename),
            initial = name,
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                // Gleicher Name wie eine andere Gruppe: deren Schreibweise, die Gruppen werden zusammengeführt
                name = canonicalGroupName(it, groups.filter { g -> g != groupName })
                renaming = false
            },
            onDismiss = { renaming = false }
        )
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text(stringResource(R.string.group_delete)) },
            text = { Text(stringResource(R.string.group_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    if (!saving) { saving = true; onDelete { saving = false } }
                }) {
                    Text(stringResource(R.string.group_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    val trimmedQuery = query.trim()
    val searchNames = rememberSearchNames(watches)
    val shown = remember(watches, trimmedQuery, searchNames) {
        if (trimmedQuery.isEmpty()) watches else watches.filter { it.matchesSearch(trimmedQuery, searchNames[it.id]) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Kopf: «Gruppe bearbeiten», Name gross, Stift zum Umbenennen
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(if (isNew) R.string.group_add else R.string.group_edit_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.headline,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { renaming = true }) {
                Icon(
                    painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.group_rename),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        GroupSearchField(
            query = query,
            onQueryChange = { query = it },
            // Feld 48 dp hoch (Tippfläche), Pille 44 dp: je 2 dp weniger Abstand, damit nichts wächst
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 2.dp)
        )

        // Alle Paare mit Häkchen — lazy, auch bei vielen Paaren flüssig
        LazyColumn(
            // Füllt die Höhe: beim Suchen schrumpft die Liste, das Blatt bleibt stehen
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.watchlist_search_empty, trimmedQuery),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                    )
                }
            }
            items(shown, key = { it.id }) { watch ->
                val isChecked = watch.id in checked
                val toggle = { checked = if (isChecked) checked - watch.id else checked + watch.id }
                // Steht in einer anderen Gruppe (und wird nicht gerade hierher verschoben)
                val otherGroup = watch.groupName?.takeIf { it != groupName && !isChecked }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = toggle)
                        .padding(end = 12.dp)
                ) {
                    Checkbox(checked = isChecked, onCheckedChange = { toggle() })
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (watch.favorite) {
                                Icon(
                                    painterResource(R.drawable.ic_star),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 4.dp).size(14.dp)
                                )
                            }
                            Text(
                                text = watch.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = watch.marketName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (otherGroup != null) {
                        Text(
                            text = stringResource(R.string.group_in_other, otherGroup),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // Fuss: Löschen (nur bestehende Gruppe) links, «Fertig» rechts
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (!isNew) {
                TextButton(onClick = { askDelete = true }) {
                    Icon(
                        painterResource(R.drawable.ic_delete),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        stringResource(R.string.group_delete),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = Spacing.xs)
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = { if (!saving) { saving = true; onDone(name, checked) { saving = false } } }) {
                Text(stringResource(R.string.group_done))
            }
        }
    }
}

/** Schlichtes, rundes Suchfeld für die Paarliste (ohne Autofokus). Intern statt privat für den Semantik-Test. */
@Composable
internal fun GroupSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    // Tippflächen 48 dp, sichtbare Pille wie bisher 44 dp (2 dp Rand oben und unten)
    Box(contentAlignment = Alignment.CenterEnd, modifier = modifier.fillMaxWidth().height(48.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    RoundedCornerShape(50)
                )
                .padding(start = 12.dp)
        ) {
            Icon(
                painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            ) {
                val textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)
                val hint = stringResource(R.string.group_search_hint)
                if (query.isEmpty()) {
                    Text(
                        text = hint,
                        style = textStyle,
                        // Volle onSurfaceVariant (≥ 4.5:1); mit 70 % lag der Platzhalter unter AA
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Screenreader liest den Hinweis am Feld selbst, nicht doppelt
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    // Bezeichnung für Screenreader, auch wenn schon Text drinsteht
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint }
                )
            }
            // Platz für den Löschknopf darüber
            Spacer(modifier = Modifier.width(if (query.isNotEmpty()) 44.dp else 12.dp))
        }
        if (query.isNotEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { onQueryChange("") }
            ) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.action_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
