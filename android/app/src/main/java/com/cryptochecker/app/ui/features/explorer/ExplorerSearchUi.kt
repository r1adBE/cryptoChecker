package com.cryptochecker.app.ui.features.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.marketdata.model.FuturesContractType

/** Zustand und Aktionen der Suche über alle Börsen. */
class SearchUi(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val progress: SearchProgress? = null,
    /** Schon in der Merkliste (Schlüssel siehe [watchKey]). */
    val watched: Set<String> = emptySet(),
    val onQueryChange: (String) -> Unit = {},
    val onAdd: (SearchHit) -> Unit = {},
) {
    fun isWatched(hit: SearchHit): Boolean = watchKey(
        hit.market.key, hit.pair.currencyBase, hit.pair.currencyCounter, hit.pair.contractType.name
    ) in watched
}

/** Gleiche Felder wie die Dublettenprüfung beim Hinzufügen (WatchRepository.addWatch). */
fun watchKey(marketKey: String, base: String, quote: String, contractType: String): String =
    "$marketKey|$base|$quote|$contractType"

/**
 * Suchfeld oben: «BTC», «ETH USDT». Treffer über alle Börsen; Antippen legt
 * das Paar direkt in die Merkliste.
 */
@Composable
internal fun SearchField(search: SearchUi, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = search.query,
        onValueChange = search.onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.explorer_search_hint)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
            if (search.query.isNotEmpty()) {
                IconButton(onClick = { search.onQueryChange("") }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
            imeAction = ImeAction.Search
        ),
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp)
    )
}

/** Treffer als eigene Liste, die den Bildschirm füllt. */
@Composable
internal fun SearchResults(
    search: SearchUi,
    groupTarget: GroupTargetUi,
    modifier: Modifier = Modifier,
    /** Kein Treffer: Suche leeren und «Genau auswählen» aufklappen (Börse und Paar selbst wählen). */
    onChooseManually: (() -> Unit)? = null,
) {
    // Höchstens 50 Treffer — als normale Spalte, damit alles mit der Seite scrollt.
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        search.progress?.let { progress ->
            run {
                Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
                    LinearProgressIndicator(
                        progress = { if (progress.total == 0) 0f else progress.done / progress.total.toFloat() },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                    )
                    Text(
                        text = stringResource(R.string.explorer_search_loading, progress.done, progress.total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                }
            }
        }
        if (search.hits.isEmpty() && search.progress == null) {
            run {
                Text(
                    text = stringResource(R.string.explorer_search_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = if (onChooseManually != null) 0.dp else 16.dp)
                )
                // Der manuelle Weg bleibt erreichbar, ohne erst das Suchfeld zu leeren
                if (onChooseManually != null) {
                    androidx.compose.material3.TextButton(onClick = onChooseManually) {
                        Text(stringResource(R.string.explorer_search_choose_manually))
                    }
                }
            }
        }
        // Ziel-Gruppe gilt für jeden angetippten Treffer
        if (search.hits.isNotEmpty()) {
            GroupTargetSelector(groupTarget, modifier = Modifier.padding(bottom = 2.dp))
        }
        search.hits.forEach { hit ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { search.onAdd(hit) }
                    .padding(start = 16.dp, end = 4.dp, top = Spacing.sm, bottom = Spacing.sm)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${hit.pair.currencyBase}/${hit.pair.currencyCounter}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = listOfNotNull(
                            hit.market.name,
                            hit.pair.contractType.takeIf { it != FuturesContractType.NONE }?.toString()
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Schon in der Merkliste: Häkchen statt «+» (Screenreader: «Schon in der Merkliste»)
                val watched = search.isWatched(hit)
                IconButton(onClick = { search.onAdd(hit) }) {
                    Icon(
                        painterResource(if (watched) R.drawable.ic_check else R.drawable.ic_add),
                        contentDescription = stringResource(
                            if (watched) R.string.a11y_in_watchlist else R.string.explorer_add_to_watchlist
                        ),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
