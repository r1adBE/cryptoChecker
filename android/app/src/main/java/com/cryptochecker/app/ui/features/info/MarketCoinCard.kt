package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.ui.components.PriceWithQuote
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.CoinReport
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.ui.components.ComboBox
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.PriceFormat

// ───────────────────────── Coin-Analyse ─────────────────────────

/**
 * Coin-Kennzahlen als letzte Zeile unter «Daten»: rechts Coin und Kurs, darunter die ersten
 * zwei Kennzahlen (reine Messwerte, keine Zone). Tippen klappt Auswahl und Analyse auf — auch beim Laden und bei Fehler,
 * damit sich ein anderer Coin wählen lässt.
 */
@Composable
internal fun CoinRow(
    coins: List<String>,
    selected: String,
    favorites: Set<String>,
    onSelect: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    state: LoadState<CoinReport>,
    onRetry: () -> Unit,
    first: Boolean = false,
    /** Herkunft und Stand des gezeigten Coins (Kerzen-Anbieter) für die Nebenzeile. */
    stamp: DataStamp? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val report = (state as? LoadState.Loaded)?.value
    MarketRow(
        title = stringResource(R.string.coin_title),
        secondary = report?.let { indicatorSummary(it.indicators, "USDT") }?.takeIf { it.isNotEmpty() } ?: selected,
        value = report?.let { "${it.symbol} · ${PriceFormat.priceWithCurrency(it.price, "USDT")}" },
        first = first,
        loading = state is LoadState.Loading,
        failure = if (state is LoadState.Failed) stringResource(R.string.coin_no_data) else null,
        onRetry = onRetry,
        stamp = stamp,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        info = MarketInfoTopic.COIN,
    ) {
        ComboBox(
            modifier = Modifier.fillMaxWidth(),
            itemList = coins,
            selectedIndex = coins.indexOf(selected),
            label = stringResource(R.string.coin_choose),
            searchable = true,
            emptyText = selected,
            favorites = favorites,
            onToggleFavorite = onToggleFavorite,
            onValueChange = { index -> onSelect(coins[index]) }
        )
        CardSwap(state, { loadKey(it) }) { shown ->
            when (shown) {
                LoadState.Loading -> CoinReportSkeleton()
                // Meldung und «Erneut» stehen schon in der Zeile
                LoadState.Failed -> Unit
                is LoadState.Loaded -> CoinReportContent(shown.value)
            }
        }
    }
}

@Composable
private fun CoinReportContent(report: CoinReport) {
    Box(contentAlignment = Alignment.CenterEnd, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        // Gegenwährung klein und grau wie der Börsenname der Merkliste
        PriceWithQuote(
            price = PriceFormat.price(report.price),
            quote = "USDT",
            priceStyle = MaterialTheme.typography.titleMedium.tabularNumbers(),
            fontWeight = null
        )
    }

    IndicatorList(report.indicators, currency = "USDT")

    if (report.historyDays in 1 until 1400) {
        Text(
            pluralStringResource(R.plurals.coin_short_history, report.historyDays, report.historyDays),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    Text(
        stringResource(R.string.coin_price_only),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/** Platzhalter in der Form von [CoinReportContent]: Kurs, je Kennzahl eine Zeile; der feste Hinweis steht echt da. */
@Composable
private fun CoinReportSkeleton() {
    val loading = stringResource(R.string.loading_hint)
    SkeletonPulse(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = loading }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
            Spacer(modifier = Modifier.weight(1f))
            SkeletonLine(MaterialTheme.typography.titleMedium.tabularNumbers(), Modifier.width(100.dp))
        }
        IndicatorListSkeleton(COIN_INDICATOR_COUNT)
    }
    Text(
        stringResource(R.string.coin_price_only),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/** So viele Kennzahlen liefert [com.cryptochecker.app.domain.market.CoinIndicatorModel]. */
private const val COIN_INDICATOR_COUNT = 8
