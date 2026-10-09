package com.cryptochecker.app.ui.features.explorer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.marketdata.model.market.DexPool

internal const val DEX_MARKET_KEY = "DexScreener"

/** Zustand und Aktionen der DEX-Suche. */
class DexUi(
    val results: List<DexPool> = emptyList(),
    val searching: Boolean = false,
    val message: DexMessage? = null,
    val onSearch: (String) -> Unit = {},
    val onAdd: (DexPool) -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

/**
 * Suche nach Token auf dezentralen Börsen. Ein Pool = ein Paar auf einer
 * bestimmten DEX und Chain; der Kurs kommt in USD.
 */
@Composable
internal fun DexSearchSection(dex: DexUi, groupTarget: GroupTargetUi) {
    var query by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.dex_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.dex_search_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { dex.onSearch(query) }),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Button(
                onClick = { dex.onSearch(query) },
                enabled = query.isNotBlank() && !dex.searching
            ) {
                Text(stringResource(R.string.dex_search))
            }
        }

        if (dex.searching) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp).size(22.dp), strokeWidth = 2.dp)
        }

        // Treffer/Fehler kommen als Snackbar; inline nur «keine Treffer».
        if (dex.message == DexMessage.NoResults) {
            Text(
                text = stringResource(R.string.dex_no_results),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm)
            )
        }

        if (dex.results.isNotEmpty()) {
            GroupTargetSelector(groupTarget, modifier = Modifier.padding(top = 12.dp))
        }

        // Eigene, begrenzte Liste statt Zeilen im Seiten-Scroll
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
        items(dex.results, key = { it.pairId }) { pool ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${pool.baseSymbol}/${pool.quoteSymbol}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = listOfNotNull(
                            BidiText.isolate("${pool.dexId} · ${pool.chainId}"),
                            pool.priceUsd?.let { "$" + formatDexPrice(it) },
                            pool.liquidityUsd?.let { stringResource(R.string.dex_liquidity, "$" + formatCompact(it)) }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { dex.onAdd(pool) }) {
                    Text(stringResource(R.string.dex_add))
                }
            }
        }
        }
    }
}

/** Kleinstpreise (Memecoins) mit genug Stellen, sonst zwei Nachkommastellen. */
private fun formatDexPrice(value: Double): String = when {
    value >= 1 -> "%,.2f".format(value)
    value >= 0.0001 -> "%.6f".format(value)
    // Ohne Nullen am Ende — auch mit arabischen/persischen Ziffern (trimEnd('0') fände sie nicht)
    else -> LocaleNumbers.decimal(value, 10, minDecimals = 0)
}

private fun formatCompact(value: Double): String = when {
    value >= 1e9 -> "%.1fB".format(value / 1e9)
    value >= 1e6 -> "%.1fM".format(value / 1e6)
    value >= 1e3 -> "%.0fK".format(value / 1e3)
    else -> "%.0f".format(value)
}
