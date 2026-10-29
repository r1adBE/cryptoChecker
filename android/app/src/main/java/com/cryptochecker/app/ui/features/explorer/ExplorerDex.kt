package com.cryptochecker.app.ui.features.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.DexNames
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.CompactAmount
import com.cryptochecker.marketdata.model.exchange.DexPool
import com.cryptochecker.marketdata.util.DisplayFormat

internal const val DEX_EXCHANGE_KEY = "DexScreener"

/** Zustand und Aktionen der DEX-Suche. */
class DexUi(
    val results: List<DexPool> = emptyList(),
    val searching: Boolean = false,
    val message: DexMessage? = null,
    val onSearch: (String) -> Unit = {},
    val onAdd: (DexPool) -> Unit = {},
    val onMessageShown: () -> Unit = {},
    /** Vorbelegung aus der Coin-Suche; eine neue ([DexPrefill.serial]) ersetzt den Text im Feld. */
    val prefill: DexPrefill? = null,
)

/** Text für das DEX-Suchfeld; [serial] zählt hoch, damit derselbe Text nochmals vorbelegt werden kann. */
data class DexPrefill(val text: String, val serial: Int)

/**
 * Suche nach Token auf dezentralen Börsen. Ein Pool = ein Paar auf einer
 * bestimmten DEX und Chain; der Kurs kommt in USD.
 */
@Composable
internal fun DexSearchSection(dex: DexUi, groupTarget: GroupTargetUi) {
    var query by rememberSaveable(dex.prefill?.serial) { mutableStateOf(dex.prefill?.text.orEmpty()) }

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
                                // Vertragsadresse des Tokens: unterscheidet gleichnamige Token derselben Chain
                                pool.shortBaseAddress.takeIf { it.isNotEmpty() }?.let { BidiText.isolate(it) },
                                pool.priceUsd?.let { "$" + formatDexPrice(it) },
                                pool.liquidityUsd?.let { stringResource(R.string.dex_liquidity, "$" + CompactAmount.number(it)) }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Dünner Pool (unter DEX_MIN_ALARM_LIQUIDITY_USD oder unbekannt): kein Alarm möglich
                        if (!pool.alarmCapable) {
                            Text(
                                text = stringResource(R.string.dex_low_liquidity_no_alarm),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
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
    // Ganz kleine Preise mit höchstens vier gültigen Stellen wie die Kursanzeige («@###»,
    // DisplayFormat) — eine feste Stellenzahl ergäbe für 3e-11 «0»; wie iOS (PriceFormat.formatDouble)
    else -> DisplayFormat.formatDoubleWithFourMax(value)
}

/** Treffer «DexScreener» der Coin-Suche: [name] der Börse, [match] der getippte DEX-Name und der übrige Suchtext. */
class DexSearchHit(val name: String, val match: DexNames.Match)

/** DexScreener-Treffer zum Suchtext, sofern DexScreener angeboten wird («Token & TradFi»); null = keiner. */
internal fun dexSearchHit(query: String, selection: ExplorerSelection): DexSearchHit? {
    val dex = selection.exchanges.firstOrNull { it.key == DEX_EXCHANGE_KEY } ?: return null
    return DexNames.match(query)?.let { DexSearchHit(dex.name, it) }
}

/**
 * «DexScreener – Pools von Uniswap über DexScreener» in der Coin-Suche («uniswap pepe»): Tippen öffnet die DEX-Suche,
 * vorbelegt mit dem übrigen Suchtext. Eine Zeile für Bedienungshilfen (Name, Hinweis, «Suchen»).
 */
@Composable
internal fun DexSearchHitRow(hit: DexSearchHit, onOpen: (String) -> Unit) {
    val hint = hit.match.dexName?.let { stringResource(R.string.dex_search_hit, it) }
        ?: stringResource(R.string.dex_search_hit_generic)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClickLabel = stringResource(R.string.dex_search)) { onOpen(hit.match.token) }
            .padding(start = Spacing.md, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm)
    ) {
        // Platz wie die Coin-Plakette (36 dp), Symbol 24 dp; rein dekorativ
        Box(Modifier.padding(end = Spacing.md).size(36.dp), contentAlignment = Alignment.Center) {
            Icon(
                painterResource(R.drawable.ic_swap_horiz),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = hit.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
