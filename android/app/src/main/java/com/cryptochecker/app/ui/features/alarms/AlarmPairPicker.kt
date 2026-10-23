@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R

/**
 * «Neuer Alarm» ohne Paar: erst das Paar wählen, dann dessen Alarm-Seite (öffnet ohne Alarm gleich den Editor).
 * Eine Auswahl für die leere Alarm-Übersicht und die Glocke der Merkliste ohne Alarm
 * ([com.cryptochecker.app.domain.watch.AlarmBellRoute.PICK_PAIR]). Ein Blatt mit Suchfeld, Favoriten
 * oben und Lazy-Liste (bis 2000 Paare); Zeilen als Knopf für Screenreader. iOS: `AlarmPairPicker`.
 * Zeilen ohne Kurs vorberechnet ([rows], [AlarmPickerRow]; null = noch nicht gerechnet), Titel als
 * Überschrift, «keine Treffer» als Live-Region, Favoriten mit Stern.
 */
@Composable
internal fun AlarmPairPicker(
    rows: List<AlarmPickerRow>?,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val names = rememberFoldedPickerNames(rows)
    val shown = remember(rows, query, names) { rows?.let { alarmPairChoices(it, query, names) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                stringResource(R.string.alarms_add),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() },
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
            // Keine Treffer: der Screenreader sagt es beim Tippen an (Live-Region)
            if (shown != null && shown.isEmpty()) {
                Text(
                    stringResource(R.string.watchlist_search_empty, query.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(shown.orEmpty(), key = { it.id }) { row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onPick(row.id) }
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                row.marketName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Favorit: Stern wie in der Merkliste, vorgelesen als «Favorit»
                        if (row.favorite) {
                            Icon(
                                painterResource(R.drawable.ic_star),
                                contentDescription = stringResource(R.string.a11y_favorite),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp).size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
