// «Erweitert / nach Börse» in Schritten (Börse → Paar → hinzufügen, wahlweise mehrere Paare auf einmal)
// und die Zeile «Börse fehlt?».
// Eigene Datei, weil der Schritt-Ablauf neben der Coin-Suche ein eigener Weg ist; `ExplorerScreen` ordnet
// nur an.
package com.cryptochecker.app.ui.features.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.features.about.EXCHANGE_REQUEST_URL

/**
 * «Erweitert / nach Börse» in Schritten, nacheinander freigeschaltet: 1. Börse (Paare laden von selbst) →
 * 2. Paar → hinzufügen. Mit «mehrere Paare auf einmal» zwei Register statt zweier Karten.
 */
@Composable
internal fun PreciseSelection(
    sections: ExplorerSectionsState,
    selection: ExplorerSelection,
    actions: PairActions,
    bulk: BulkActions,
    favorites: FavoritesUi,
    dex: DexUi,
    groupTarget: GroupTargetUi,
    bulkListOpen: Boolean,
    onToggleBulkList: () -> Unit,
    onAskBulkConfirm: () -> Unit,
) {
    val exchange by selection.exchange.collectAsStateWithLifecycle()
    val canReloadPairs by selection.canReloadPairs.collectAsStateWithLifecycle()
    val pairList by selection.pairList.collectAsStateWithLifecycle()
    val pairListSync by selection.pairListSync.collectAsStateWithLifecycle()
    val coin by selection.coin.collectAsStateWithLifecycle()
    val quote by selection.quote.collectAsStateWithLifecycle()
    val contract by selection.contract.collectAsStateWithLifecycle()
    val coins by selection.coins.collectAsStateWithLifecycle()
    val quotes by selection.quotes.collectAsStateWithLifecycle()
    val contracts by selection.contracts.collectAsStateWithLifecycle()
    val price by selection.price.collectAsStateWithLifecycle()
    val bulkPairs by selection.bulkPairs.collectAsStateWithLifecycle()
    val bulkEmpty by selection.bulkEmpty.collectAsStateWithLifecycle()
    val bulkAddResult by selection.bulkAddResult.collectAsStateWithLifecycle()
    val bulkQuotes by selection.bulkQuotes.collectAsStateWithLifecycle()
    val bulkQuote by selection.bulkQuote.collectAsStateWithLifecycle()
    val bulkStats by selection.bulkStats.collectAsStateWithLifecycle()
    val bulkNew by selection.bulkNew.collectAsStateWithLifecycle()

    val chosen = exchange
    val isDex = chosen?.key == DEX_EXCHANGE_KEY
    val hasPairs = coins.isNotEmpty()
    val syncing = pairListSync.inProgress
    val pairSelected = hasPairs && coin != null && quote != null

    ExchangeStepCard(
        exchanges = selection.exchanges,
        categories = selection.exchangeCategories,
        exchange = chosen,
        isDex = isDex,
        hasPairs = hasPairs,
        syncing = syncing,
        canReloadPairs = canReloadPairs,
        syncError = pairListSync.error,
        pairList = pairList,
        favorites = favorites,
        onExchangeChanged = actions.onExchangeChanged,
        onReload = actions.onReloadPairs,
    )

    if (chosen == null) return
    if (isDex) {
        StepCard { DexSearchSection(dex, groupTarget) }
        return
    }

    val withTabs = hasPairs && bulkQuotes.isNotEmpty()
    if (withTabs) {
        ExplorerModeTabs(bulk = sections.bulk, onSelect = { sections.bulk = it })
    }
    val showBulk = withTabs && sections.bulk

    if (!showBulk) {
        PairStepCard(
            hasPairs = hasPairs,
            pairSelected = pairSelected,
            syncing = syncing,
            canReloadPairs = canReloadPairs,
            coins = coins,
            quotes = quotes,
            contracts = contracts,
            coin = coin,
            quote = quote,
            contract = contract,
            price = price,
            favorites = favorites,
            groupTarget = groupTarget,
            onCoinChanged = actions.onCoinChanged,
            onQuoteChanged = actions.onQuoteChanged,
            onContractChanged = actions.onContractChanged,
            onRetryPrice = actions.onRetryPrice,
            onReload = actions.onReloadPairs,
            onAdd = actions.onAdd,
            showHeader = !withTabs,
        )
    } else {
        // ── Mehrere Paare auf einmal (Register) ──
        BulkPairsCard(
            quotes = bulkQuotes,
            quote = bulkQuote,
            pairs = bulkPairs,
            emptyMessage = bulkEmpty,
            syncing = syncing,
            adding = bulkAddResult?.running == true,
            addDone = bulkAddResult?.done ?: 0,
            addTotal = bulkAddResult?.total ?: 0,
            newCount = bulkNew,
            coin = coin,
            pairQuote = quote,
            contract = contract,
            favorites = favorites,
            groupTarget = groupTarget,
            onQuoteChanged = bulk.onQuoteChanged,
            onApply = bulk.onApply,
            onSelectPair = bulk.onSelectPair,
            onAddAll = onAskBulkConfirm,
            showBulkList = bulkListOpen,
            onToggleList = onToggleBulkList,
            asTab = true,
            bulkOpen = sections.bulk,
            onBulkOpenChange = { sections.bulk = it },
            stats = bulkStats,
            onNeedStats = bulk.onNeedStats,
        )
    }
}

/** Runde 13b: Börse fehlt? → GitHub-Vorlage «Exchange request». */
@Composable
internal fun ExchangeMissingRow() {
    val uriHandler = LocalUriHandler.current
    // Verweise nach aussen überall gleich: Text in der Themenfarbe mit ↗ (wie in den Einstellungen)
    val externalLabel = stringResource(R.string.a11y_external_link)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.explorer_exchange_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = { runCatching { uriHandler.openUri(EXCHANGE_REQUEST_URL) } },
            modifier = Modifier.semantics { stateDescription = externalLabel },
        ) {
            Text(stringResource(R.string.about_request_exchange))
            Icon(
                painterResource(R.drawable.ic_open_external),
                contentDescription = null,
                modifier = Modifier.padding(start = 4.dp).size(14.dp),
            )
        }
    }
}
