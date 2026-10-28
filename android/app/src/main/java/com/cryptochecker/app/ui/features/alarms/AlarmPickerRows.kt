// Zeilen der Paarwahl für einen neuen Alarm (Review PERF2-02): ohne Kurs vorberechnet und schon
// gefaltet, damit Kurs-Schreibungen und Tastendrücke nicht jedes Mal alle Paare auf dem Main-Thread neu falten.
package com.cryptochecker.app.ui.features.alarms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.ui.components.LocalCoinSearchNameSource
import com.cryptochecker.app.ui.features.explorer.NameMatch
import com.cryptochecker.app.ui.features.watchlist.searchName
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Zeile der Paarwahl ohne Kurs: gleich, solange Id, Namen, Börse, Notiz und Favorit gleich bleiben. */
@Immutable
internal data class AlarmPickerRow(
    val id: Long,
    val displayName: String,
    val exchangeName: String,
    val favorite: Boolean,
    /** Suchfelder wie `matchesSearch` (ohne Coin-Namen), schon gefaltet ([NameMatch.fold]). */
    val folded: List<String>,
    /** Für den Coin-Namen der Suche ([searchName]). */
    val exchangeKey: String,
    val baseAsset: String,
    val quoteAsset: String,
    val contractType: String,
)

/** Zeilen in der Reihenfolge der Merkliste. */
internal fun alarmPickerRows(watches: List<WatchEntity>): List<AlarmPickerRow> = watches.map { w ->
    AlarmPickerRow(
        id = w.id,
        displayName = w.displayName,
        exchangeName = w.exchangeName,
        favorite = w.favorite,
        folded = listOfNotNull(
            w.baseAsset,
            w.quoteAsset,
            w.displayPair,
            w.displayName,
            FuturesContractType.getShortName(w.contractType),
            w.exchangeName,
            w.note,
        ).map { NameMatch.fold(it) },
        exchangeKey = w.exchangeKey,
        baseAsset = w.baseAsset,
        quoteAsset = w.quoteAsset,
        contractType = w.contractType.name,
    )
}

/**
 * Treffer der Suche (wie die Merklisten-Suche: jedes Wort muss ein Feld oder den Coin-Namen treffen), Favoriten zuerst,
 * sonst in der Reihenfolge der Merkliste (`alarm_pair_picker.json`). [foldedNames]: gefaltete Coin-Namen je Paar-Id.
 * Wie iOS `AlarmPairPicker.choices`.
 */
internal fun alarmPairChoices(rows: List<AlarmPickerRow>, query: String, foldedNames: Map<Long, String>): List<AlarmPickerRow> {
    val tokens = NameMatch.fold(query).split(' ').filter { it.isNotBlank() }
    val hits = if (tokens.isEmpty()) {
        rows
    } else {
        rows.filter { row ->
            val name = foldedNames[row.id]
            tokens.all { token -> row.folded.any { it.contains(token) } || name?.contains(token) == true }
        }
    }
    return hits.filter { it.favorite } + hits.filterNot { it.favorite }
}

/** Gefaltete Coin-Namen der Zeilen ([searchName]); neu nur, wenn sich die Zeilen oder die gespeicherten Namen ändern. */
@Composable
internal fun rememberFoldedPickerNames(rows: List<AlarmPickerRow>?): Map<Long, String> {
    val source = LocalCoinSearchNameSource.current ?: return emptyMap()
    LaunchedEffect(source) { source.loadSavedNames() }
    val names = source.names.collectAsState().value
    val tradFiPairs = source.tradFiPairs.collectAsState().value
    return remember(rows, names, tradFiPairs) {
        if (rows == null || names.isEmpty()) {
            emptyMap()
        } else {
            rows.mapNotNull { r ->
                searchName(r.exchangeKey, r.baseAsset, r.quoteAsset, r.contractType, names, tradFiPairs)
                    ?.let { r.id to NameMatch.fold(it) }
            }.toMap()
        }
    }
}

/**
 * Zeilen aus einer Liste, die sich bei jeder Kurs-Schreibung ändert (Merkliste): gerechnet im Hintergrund, nur
 * weitergegeben, wenn sich die Zeilen wirklich ändern. null = noch nicht gerechnet.
 */
@Composable
internal fun rememberAlarmPickerRows(watches: List<WatchEntity>): List<AlarmPickerRow>? {
    val latest by rememberUpdatedState(watches)
    return produceState<List<AlarmPickerRow>?>(initialValue = null) {
        snapshotFlow { latest }
            .map { alarmPickerRows(it) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .collect { value = it }
    }.value
}
