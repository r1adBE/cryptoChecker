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
import com.cryptochecker.app.domain.market.BidAskSpread
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.displayCompact
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.PercentText
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.CurrencyText
import com.cryptochecker.marketdata.util.FormatUtilsBase

/**
 * Letzter Kurs gross, darunter Hoch/Tief, Bid/Ask (mit einem erklärenden Satz) und Volumen kleiner in
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

    base: String,
    currencyQuote: String,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        // Gegenwährung klein und grau wie der Börsenname der Merkliste
        PriceWithQuote(
            price = FormatUtilsBase.formatDouble(last),
            quote = CurrencyText.symbol(currencyQuote),
            priceStyle = MaterialTheme.typography.displayCompact.tabularNumbers()
        )
        Text(
            text = stringResource(R.string.ticker_timestamp) + " " +
                FormatUtilsBase.formatSameDayTimeOrDate(context, timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        val quoteSymbol = CurrencyText.symbol(currencyQuote)
        val baseSymbol = CurrencyText.symbol(base)
        if (high > Ticker.MISSING) {
            StatRow(
                R.string.ticker_high, StatValue(FormatUtilsBase.formatDouble(high), quoteSymbol),
                R.string.ticker_low, StatValue(FormatUtilsBase.formatDouble(low), quoteSymbol)
            )
        }
        if (ask > Ticker.MISSING) {
            StatRow(
                R.string.ticker_bid, StatValue(FormatUtilsBase.formatDouble(bid), quoteSymbol),
                R.string.ticker_ask, StatValue(FormatUtilsBase.formatDouble(ask), quoteSymbol)
            )
            // Ein Satz, was Geld und Brief heissen (wie iOS)
            Text(
                text = stringResource(R.string.explain_bid_ask),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.sm)
            )
            // Grosser Abstand Kauf/Verkauf (über 2 % der Mitte): dünner Markt, Hinweis nur hier im Blatt
            BidAskSpread.widePercent(bid, ask)?.let { percent ->
                Text(
                    text = stringResource(R.string.ticker_wide_spread, PercentText.unsigned(percent, 1)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = Spacing.sm)
                )
            }
        }
        if (volBase > Ticker.MISSING || volQuote > Ticker.MISSING) {
            StatRow(
                R.string.ticker_vol_base,
                if (volBase > Ticker.MISSING) StatValue(FormatUtilsBase.formatDouble(volBase), baseSymbol) else StatValue.NONE,
                R.string.ticker_vol_quote,
                if (volQuote > Ticker.MISSING) StatValue(FormatUtilsBase.formatDouble(volQuote), quoteSymbol) else StatValue.NONE
            )
        }
    }
}

/** Wert einer Kennzahl: Zahl und Währung getrennt, damit die Währung klein und grau steht. */
private data class StatValue(val amount: String, val currency: String?) {
    companion object {
        val NONE = StatValue("—", null)
    }
}

@Composable
private fun StatRow(@StringRes leftTitle: Int, leftValue: StatValue, @StringRes rightTitle: Int, rightValue: StatValue) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Stat(leftTitle, leftValue, Modifier.weight(1f))
        Stat(rightTitle, rightValue, Modifier.weight(1f))
    }
}

@Composable
private fun Stat(@StringRes title: Int, value: StatValue, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Gegenwährung klein und grau wie der Börsenname der Merkliste
        PriceWithQuote(
            price = value.amount,
            quote = value.currency,
            priceStyle = MaterialTheme.typography.bodyMedium.tabularNumbers(),
            fontWeight = FontWeight.Medium
        )
    }
}
