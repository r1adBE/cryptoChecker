package com.cryptochecker.app.ui.features.explorer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.FavoriteKind
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.app.domain.model.ExchangeTickerResult
import com.cryptochecker.app.ui.components.DropdownField
import com.cryptochecker.app.ui.components.Ticker
import com.cryptochecker.app.ui.features.watchlist.friendlyError
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.util.DisplayFormat

/** Abstand zwischen den Feldern innerhalb einer Karte. */
private val FieldGap = 12.dp

// =================================================================================================
// Kopf der Börse
// =================================================================================================

/**
 * Kopf der geöffneten Börse: Zeichen und Name (Überschrift), rechts «Paare neu laden»; darunter Ladebalken, Fehler oder
 * Zahl der Paare und bei den EZB-Kursen der Hinweis «Tageskurs …».
 */
@Composable
internal fun ExchangeHeaderCard(
    exchange: ExchangeInfo,
    isDex: Boolean,
    isForex: Boolean,
    hasPairs: Boolean,
    syncing: Boolean,
    canReloadPairs: Boolean,
    syncError: String?,
    pairList: ExchangePairsInfo?,
    onReload: () -> Unit,
) {
    StepCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExchangeMarkBadge(exchange.key, exchange.name, size = 40.dp, modifier = Modifier.padding(end = Spacing.md))
            Text(
                text = exchange.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (!isDex) {
                Spacer(modifier = Modifier.size(8.dp))
                ReloadPairsButton(enabled = canReloadPairs && !syncing, onClick = onReload)
            }
        }
        ExchangeStatus(
            isDex = isDex,
            hasPairs = hasPairs,
            syncing = syncing,
            canReloadPairs = canReloadPairs,
            syncError = syncError,
            pairList = pairList,
            onReload = onReload,
        )
        // EZB-Referenzkurse: täglich, nicht live, keine Alarme — gleich beim Öffnen sagen
        if (isForex) StepHint(stringResource(R.string.explorer_forex_hint))
    }
}

/** Runder Knopf «Paare neu laden» neben der Börse; aktiv erst mit gewählter Börse. */
@Composable
private fun ReloadPairsButton(enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(52.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_refresh),
            contentDescription = stringResource(R.string.explorer_reload_pairs),
        )
    }
}

/** Zeile unter der Börse: Ladebalken, Fehler mit «Erneut» oder Stand der Paarliste. */
@Composable
private fun ExchangeStatus(
    isDex: Boolean,
    hasPairs: Boolean,
    syncing: Boolean,
    canReloadPairs: Boolean,
    syncError: String?,
    pairList: ExchangePairsInfo?,
    onReload: () -> Unit,
) {
    when {
        isDex -> Unit
        syncing -> Column(modifier = Modifier.padding(top = FieldGap)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
            StepHint(stringResource(R.string.explorer_loading_pairs))
        }
        syncError != null -> ErrorWithRetry(
            text = stringResource(R.string.check_error_generic_prefix, friendlyError(syncError)),
            modifier = Modifier.padding(top = 8.dp),
            onRetry = onReload,
        )
        hasPairs -> if (pairList != null) PairCountHint(pairList)
        !canReloadPairs -> StepHint(stringResource(R.string.explorer_no_pair_list))
        else -> Unit
    }
}

/** «n Paare · Stand …» bzw. «nie abgeglichen». */
@Composable
private fun PairCountHint(info: ExchangePairsInfo) {
    val syncedAt = if (info.lastSyncDate > 0) {
        DisplayFormat.formatSameDayTimeOrDate(LocalContext.current, info.lastSyncDate)
    } else {
        stringResource(R.string.explorer_sync_never)
    }
    StepHint(pluralStringResource(R.plurals.explorer_pairs_status, info.size, info.size, syncedAt))
}

/** Fehlertext in Fehlerfarbe mit «Erneut» rechts daneben. */
@Composable
private fun ErrorWithRetry(text: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

// =================================================================================================
// Paar
// =================================================================================================

/**
 * Paar: Basis, Gegenwährung und (bei mehreren Laufzeiten) Kontrakt untereinander; darunter
 * Kurs, Ziel-Gruppe und «Zur Merkliste».
 */
@Composable
internal fun PairStepCard(
    hasPairs: Boolean,
    pairSelected: Boolean,
    syncing: Boolean,
    canReloadPairs: Boolean,
    coins: List<String>,
    quotes: List<String>,
    contracts: List<FuturesContractType>,
    coin: String?,
    quote: String?,
    contract: FuturesContractType?,
    price: ExchangeTickerResult?,
    favorites: FavoritesUi,
    groupTarget: GroupTargetUi,
    onCoinChanged: (String?) -> Unit,
    onQuoteChanged: (String?) -> Unit,
    onContractChanged: (FuturesContractType?) -> Unit,
    onRetryPrice: () -> Unit,
    onReload: () -> Unit,
    onAdd: () -> Unit,
) {
    StepCard {
        when {
            hasPairs -> {
                PairPickers(
                    coins = coins,
                    quotes = quotes,
                    contracts = contracts,
                    coin = coin,
                    quote = quote,
                    contract = contract,
                    favorites = favorites,
                    onCoinChanged = onCoinChanged,
                    onQuoteChanged = onQuoteChanged,
                    onContractChanged = onContractChanged,
                )
                StepHint(stringResource(R.string.hint_favorites_list))
            }
            !syncing -> NoPairsYetRow(canReloadPairs = canReloadPairs, onReload = onReload)
        }

        // Der Kurs lädt von selbst, sobald ein Paar gewählt ist — er steht über dem Knopf
        if (pairSelected) {
            Column(modifier = Modifier.padding(top = FieldGap)) {
                PairPrice(price, onRetry = onRetryPrice)
            }
        }

        // Gruppe, in die das neue Paar kommt
        if (hasPairs) GroupTargetSelector(groupTarget, modifier = Modifier.padding(top = FieldGap))
        AddToWatchlistButton(enabled = pairSelected && !syncing, onClick = onAdd)
    }
}

/** Coin und Gegenwährung untereinander (nicht gequetscht nebeneinander), Kontrakt nur bei Futures. */
@Composable
private fun PairPickers(
    coins: List<String>,
    quotes: List<String>,
    contracts: List<FuturesContractType>,
    coin: String?,
    quote: String?,
    contract: FuturesContractType?,
    favorites: FavoritesUi,
    onCoinChanged: (String?) -> Unit,
    onQuoteChanged: (String?) -> Unit,
    onContractChanged: (FuturesContractType?) -> Unit,
) {
    DropdownField(
        modifier = Modifier.fillMaxWidth(),
        itemList = coins,
        selectedIndex = coins.indexOf(coin),
        label = stringResource(id = R.string.explorer_field_coin),
        searchable = true,
        favorites = favorites.coins,
        onToggleFavorite = { favorites.onToggle(FavoriteKind.COIN, it) },
        onValueChange = { picked -> onCoinChanged(coins[picked]) },
    )
    DropdownField(
        modifier = Modifier.fillMaxWidth().padding(top = FieldGap),
        itemList = quotes,
        selectedIndex = quotes.indexOf(quote),
        label = stringResource(id = R.string.explorer_field_quote),
        searchable = true,
        favorites = favorites.quotes,
        onToggleFavorite = { favorites.onToggle(FavoriteKind.QUOTE, it) },
        onValueChange = { picked -> onQuoteChanged(quotes[picked]) },
    )

    // Nur Spot (einzig NONE) oder gar nichts: kein Kontraktfeld; wählbar erst ab zwei Laufzeiten
    val spotOnly = contracts.singleOrNull() == FuturesContractType.NONE
    if (contracts.isNotEmpty() && !spotOnly) {
        DropdownField(
            modifier = Modifier.fillMaxWidth().padding(top = FieldGap),
            itemList = contracts.map { contractName(it) },
            selectedIndex = contracts.indexOf(contract),
            label = stringResource(id = R.string.explorer_field_contract),
            enabled = contracts.size > 1,
            onValueChange = { picked -> onContractChanged(contracts[picked]) },
        )
    }
}

/** Noch keine Paare: Hinweis und «Abgleichen». Antippbar ist nur der Knopf, nicht der Text. */
@Composable
private fun NoPairsYetRow(canReloadPairs: Boolean, onReload: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.explorer_no_pairs_yet),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onReload, enabled = canReloadPairs) {
            Text(stringResource(R.string.explorer_reload_pairs))
        }
    }
}

@Composable
private fun AddToWatchlistButton(enabled: Boolean, onClick: () -> Unit) {
    Button(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        enabled = enabled,
        onClick = onClick,
    ) {
        Icon(
            painterResource(R.drawable.ic_add),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(id = R.string.explorer_add_to_watchlist),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Kurs des gewählten Paars: lädt noch, Fehler mit «Erneut» oder die Kennzahlen. */
@Composable
private fun PairPrice(result: ExchangeTickerResult?, onRetry: () -> Unit) {
    if (result == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.explorer_price_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
        return
    }
    val error = result.error
    if (error != null) {
        ErrorWithRetry(
            text = stringResource(R.string.check_error_generic_prefix, friendlyError(error)),
            onRetry = onRetry,
        )
        return
    }
    val t = result.ticker
    Ticker(
        // Zeit laut Börse (z. B. letzter Handel), sonst der Abruf — timestamp ist stets der Abruf
        timestamp = t.exchangeTime ?: t.timestamp,
        last = t.last,
        high = t.high,
        low = t.low,
        ask = t.ask,
        bid = t.bid,
        volBase = t.vol,
        volQuote = t.volQuote,
        base = result.pairInfo.base,
        currencyQuote = result.pairInfo.quote,
    )
}
