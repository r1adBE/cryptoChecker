@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.ui.features.watchlist.matchesSearch
import com.cryptochecker.app.ui.features.watchlist.rememberSearchNames

/**
 * Seit Build 103: Paare für die Auswahl — Treffer der Suche ([matchesSearch], wie die Merklisten-Suche), Favoriten
 * zuerst, sonst in der Reihenfolge der Merkliste (`alarm_pair_picker.json`). Wie iOS `AlarmPairPicker.choices`.
 */
internal fun alarmPairChoices(watches: List<WatchEntity>, query: String, names: Map<Long, String>): List<WatchEntity> {
    val hits = if (query.isBlank()) watches else watches.filter { it.matchesSearch(query, names[it.id]) }
    return hits.filter { it.favorite } + hits.filterNot { it.favorite }
}

/**
 * «Neuer Alarm» ohne Paar: erst das Paar wählen, dann dessen Alarm-Seite (öffnet ohne Alarm gleich den Editor).
 * Eine Auswahl für die leere Alarm-Übersicht und die Glocke der Merkliste ohne Alarm
 * ([com.cryptochecker.app.domain.watch.AlarmBellRoute.PICK_PAIR]). Seit Build 103 ein Blatt mit Suchfeld, Favoriten
 * oben und Lazy-Liste (bis 2000 Paare); Zeilen als Knopf für Screenreader. iOS: `AlarmPairPicker`.
 */
@Composable
internal fun AlarmPairPicker(
    watches: List<WatchEntity>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val names = rememberSearchNames(watches)
    val shown = remember(watches, query, names) { alarmPairChoices(watches, query, names) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                stringResource(R.string.alarms_add),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            // Bezeichnung des Felds (label) liest der Screenreader auch, wenn schon Text drinsteht
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.watchlist_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (shown.isEmpty()) {
                Text(
                    stringResource(R.string.watchlist_search_empty, query.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(shown, key = { it.id }) { watch ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onPick(watch.id) }
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(watch.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            watch.marketName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
