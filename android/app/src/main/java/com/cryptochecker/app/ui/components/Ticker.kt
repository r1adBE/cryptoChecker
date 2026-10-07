package com.cryptochecker.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.FormatUtilsBase

/**
 * Letzter Kurs gross, darunter Hoch/Tief, Bid/Ask und Volumen kleiner in
 * zwei Spalten. Ziffern gleich breit.
 */
@Composable
fun Ticker(
    timestamp: Long,
    last: Double,

    high: Double,
    low: Double,

    ask: Double,
    bid: Double,

    volBase: Double,
    volQuote: Double,

    currencyBase: String,
    currencyQuote: String,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = FormatUtilsBase.formatPriceWithCurrency(last, currencyQuote),
            style = MaterialTheme.typography.headlineMedium.tabularNumbers(),
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.ticker_timestamp) + " " +
                FormatUtilsBase.formatSameDayTimeOrDate(context, timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (high > Ticker.NO_DATA) {
            StatRow(
                R.string.ticker_high, FormatUtilsBase.formatPriceWithCurrency(high, currencyQuote),
                R.string.ticker_low, FormatUtilsBase.formatPriceWithCurrency(low, currencyQuote)
            )
        }
        if (ask > Ticker.NO_DATA) {
            StatRow(
                R.string.ticker_bid, FormatUtilsBase.formatPriceWithCurrency(bid, currencyQuote),
                R.string.ticker_ask, FormatUtilsBase.formatPriceWithCurrency(ask, currencyQuote)
            )
        }
        if (volBase > Ticker.NO_DATA || volQuote > Ticker.NO_DATA) {
            StatRow(
                R.string.ticker_vol_base,
                if (volBase > Ticker.NO_DATA) FormatUtilsBase.formatPriceWithCurrency(volBase, currencyBase) else "—",
                R.string.ticker_vol_quote,
                if (volQuote > Ticker.NO_DATA) FormatUtilsBase.formatPriceWithCurrency(volQuote, currencyQuote) else "—"
            )
        }
    }
}

@Composable
private fun StatRow(@StringRes leftTitle: Int, leftValue: String, @StringRes rightTitle: Int, rightValue: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Stat(leftTitle, leftValue, Modifier.weight(1f))
        Stat(rightTitle, rightValue, Modifier.weight(1f))
    }
}

@Composable
private fun Stat(@StringRes title: Int, value: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}
