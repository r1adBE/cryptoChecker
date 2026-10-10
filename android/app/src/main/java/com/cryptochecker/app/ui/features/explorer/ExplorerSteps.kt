package com.cryptochecker.app.ui.features.explorer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.FavoriteKind
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.app.domain.model.MarketTickerResult
import com.cryptochecker.app.ui.components.ComboBox
import com.cryptochecker.app.ui.components.Ticker
import com.cryptochecker.app.ui.features.watchlist.friendlyError
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.util.FormatUtilsBase

/** Schritt 1: Börse wählen, Paare neu laden; darunter Laden, Fehler oder Zahl der Paare. */
@Composable
internal fun MarketStepCard(
    markets: List<MarketInfo>,
    currentMarket: MarketInfo?,
    isDex: Boolean,
    hasPairs: Boolean,
    syncing: Boolean,
    canUpdatePairs: Boolean,
    updateError: String?,
    pairsInfo: MarketPairsInfo?,
    favorites: FavoritesUi,
    onMarketChanged: (MarketInfo?) -> Unit,
    onSync: () -> Unit,
) {
    val context = LocalContext.current
    // Anzeigename („Crypto.com“, „Bybit Futures“) statt interner Kennung.
    val marketNames = markets.map { market -> market.name }
    val currentMarketIndex = markets.indexOf(currentMarket)
    StepCard {
        StepHeader(
            number = 1,
            title = stringResource(R.string.market_screen_market),
            active = true,
            done = currentMarket != null && (hasPairs || isDex)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            ComboBox(
                modifier = Modifier.weight(1f),
                itemList = marketNames,
                selectedIndex = currentMarketIndex,
                label = stringResource(id = R.string.market_screen_market),
                searchable = true,
                emptyText = stringResource(R.string.explorer_select_placeholder),
                favorites = favorites.markets,
                onToggleFavorite = { favorites.onToggle(FavoriteKind.MARKET, it) },
                onValueChange = { marketIndex -> onMarketChanged(markets[marketIndex]) }
            )
            if (!isDex) {
                Spacer(modifier = Modifier.size(8.dp))
                // Paare neu laden — erst aktiv, wenn eine Börse gewählt ist.
                FilledTonalIconButton(
                    onClick = onSync,
                    enabled = canUpdatePairs && !syncing,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        painterResource(R.drawable.ic_refresh),
                        contentDescription = stringResource(R.string.market_screen_sync)
                    )
                }
            }
        }

        when {
            currentMarket == null -> StepHint(stringResource(R.string.explorer_select_market_hint))
            isDex -> Unit
            syncing -> Column(modifier = Modifier.padding(top = STEP_GAP)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
                StepHint(stringResource(R.string.explorer_loading_pairs))
            }
            updateError != null -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.check_error_generic_prefix, friendlyError(updateError)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onSync) { Text(stringResource(R.string.action_retry)) }
            }
            hasPairs -> pairsInfo?.let { info ->
                StepHint(
                    pluralStringResource(
                        R.plurals.explorer_pairs_status,
                        info.size,
                        info.size,
                        if (info.lastSyncDate > 0)
                            FormatUtilsBase.formatSameDayTimeOrDate(context, info.lastSyncDate)
                        else stringResource(R.string.checker_add_dynamic_currency_pairs_dialog_last_sync_never)
                    )
                )
            }
            !canUpdatePairs -> StepHint(stringResource(R.string.checker_add_check_currency_empty_warning_title))
            else -> Unit
        }
    }
}

/**
 * Schritt 2: Basis, Quote und (bei mehreren Laufzeiten) Kontrakt untereinander, darunter der
 * Kurs, die Ziel-Gruppe und «Zur Merkliste».
 */
@Composable
internal fun PairStepCard(
    hasPairs: Boolean,
    pairSelected: Boolean,
    syncing: Boolean,
    canUpdatePairs: Boolean,
    baseAssets: List<String>,
    quoteAssets: List<String>,
    contractTypes: List<FuturesContractType>,
    currentBaseAsset: String?,
    currentQuoteAsset: String?,
    currentContractType: FuturesContractType?,
    ticker: MarketTickerResult?,
    favorites: FavoritesUi,
    groupTarget: GroupTargetUi,
    onBaseAssetChanged: (String?) -> Unit,
    onQuoteAssetChanged: (String?) -> Unit,
    onContractTypeChanged: (FuturesContractType?) -> Unit,
    onRetryTicker: () -> Unit,
    onSync: () -> Unit,
    onAdd: () -> Unit,
    /** Kopf «2 Paar wählen» zeigen; nein unter den Registern (dort steht der Titel schon). */
    showHeader: Boolean = true,
) {
    StepCard {
        if (showHeader) {
            StepHeader(
                number = 2,
                title = stringResource(R.string.explorer_step_pair),
                active = hasPairs,
                done = pairSelected
            )
        }

        if (hasPairs) {
            // Untereinander statt gequetscht nebeneinander
            ComboBox(
                modifier = Modifier.fillMaxWidth(),
                itemList = baseAssets,
                selectedIndex = baseAssets.indexOf(currentBaseAsset),
                label = stringResource(id = R.string.market_screen_base),
                searchable = true,
                favorites = favorites.coins,
                onToggleFavorite = { favorites.onToggle(FavoriteKind.COIN, it) },
                onValueChange = { index -> onBaseAssetChanged(baseAssets[index]) }
            )
            ComboBox(
                modifier = Modifier.fillMaxWidth().padding(top = STEP_GAP),
                itemList = quoteAssets,
                selectedIndex = quoteAssets.indexOf(currentQuoteAsset),
                label = stringResource(id = R.string.market_screen_quote),
                searchable = true,
                favorites = favorites.quotes,
                onToggleFavorite = { favorites.onToggle(FavoriteKind.QUOTE, it) },
                onValueChange = { index -> onQuoteAssetChanged(quoteAssets[index]) }
            )

            // Kontrakt nur bei Futures; auswählbar nur bei mehreren Laufzeiten.
            val hasContractTypes = contractTypes.isNotEmpty() &&
                !(contractTypes.size == 1 && contractTypes[0] == FuturesContractType.NONE)
            if (hasContractTypes) {
                ComboBox(
                    modifier = Modifier.fillMaxWidth().padding(top = STEP_GAP),
                    itemList = contractTypes.map { getContractTypeName(it) },
                    selectedIndex = contractTypes.indexOf(currentContractType),
                    label = stringResource(id = R.string.market_screen_contract_type),
                    enabled = contractTypes.size > 1,
                    onValueChange = { index -> onContractTypeChanged(contractTypes[index]) }
                )
            }

            StepHint(stringResource(R.string.hint_favorites_list))
        } else if (!syncing) {
            // Antippbar ist nur der Knopf — der Text sagt nicht mehr «hier tippen»
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.explorer_no_pairs_yet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onSync, enabled = canUpdatePairs) {
                    Text(stringResource(R.string.market_screen_sync))
                }
            }
        }

        // Kurs lädt automatisch, sobald ein Paar gewählt ist — über dem Knopf
        if (pairSelected) {
            Column(modifier = Modifier.padding(top = STEP_GAP)) {
                PairTicker(ticker, onRetry = onRetryTicker)
            }
        }

        // Ziel-Gruppe für das neue Paar
        if (hasPairs) GroupTargetSelector(groupTarget, modifier = Modifier.padding(top = STEP_GAP))
        Button(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            enabled = pairSelected && !syncing,
            onClick = onAdd
        ) {
            Icon(
                painterResource(R.drawable.ic_add),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                stringResource(id = R.string.explorer_add_to_watchlist),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** Kurs des gewählten Paars: lädt, Fehler mit «Erneut», sonst die Kennzahlen. */
@Composable
private fun PairTicker(result: MarketTickerResult?, onRetry: () -> Unit) {
    when {
        result == null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.explorer_price_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.sm)
            )
        }
        result.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.check_error_generic_prefix, friendlyError(result.error.orEmpty())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }
        }
        else -> with(result.ticker) {
            Ticker(
                timestamp = timestamp,
                last = last,
                high = high,
                low = low,
                ask = ask,
                bid = bid,
                volBase = vol,
                volQuote = volQuote,
                currencyBase = result.pairInfo.currencyBase,
                currencyQuote = result.pairInfo.currencyCounter,
            )
        }
    }
}

/** Abstand zwischen den Feldern eines Schritts. */
private val STEP_GAP = 12.dp
