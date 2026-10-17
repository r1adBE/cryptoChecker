package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.spot.SpotData
import com.cryptochecker.app.domain.spot.SpotMarket
import com.cryptochecker.app.ui.components.ChangePill
import com.cryptochecker.app.ui.components.ChangePillSize
import com.cryptochecker.app.ui.features.info.CoinGeckoAttribution
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.PriceFormat

/** Quelle der Spot-Kennzahlen (Eigenname, nicht übersetzt). */
private const val SPOT_SOURCE = "CoinGecko"

/**
 * Register «Spot» der Karte «Marktdaten»: lädt noch, keine Spot-Daten (TradFi, DEX, unbekannter
 * Coin) oder die Kennzahlen. Wie iOS `WatchlistSpotSection`.
 */
@Composable
internal fun SpotSection(data: SpotData?, loaded: Boolean, showExplain: Boolean) {
    if (showExplain) {
        MarketDataExplanation(stringResource(R.string.spot_market_cap) + ": " + stringResource(R.string.explain_spot_market_cap))
        MarketDataExplanation(stringResource(R.string.spot_volume_24h) + ": " + stringResource(R.string.explain_spot_volume))
        MarketDataExplanation(stringResource(R.string.spot_fdv) + ": " + stringResource(R.string.explain_spot_fdv))
        MarketDataExplanation(stringResource(R.string.explain_spot_supply))
        MarketDataExplanation(stringResource(R.string.explain_spot_source))
    }
    when {
        data != null -> SpotValues(data)
        loaded -> MarketDataMessage(stringResource(R.string.spot_no_data))
        else -> MarketDataMessage(stringResource(R.string.loading_hint))
    }
}

@Composable
private fun SpotValues(data: SpotData) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            SpotMetric(stringResource(R.string.spot_market_cap), SpotMarket.usd(data.marketCap), Modifier.weight(1f)) {
                // 24h-Veränderung der Marktkapitalisierung als farbige Schrift wie alle Werte mit Vorzeichen
                ChangePill(change = data.marketCapChange24h, size = ChangePillSize.SMALL, modifier = Modifier.padding(top = 2.dp))
            }
            SpotMetric(stringResource(R.string.spot_volume_24h), SpotMarket.usd(data.volume24h), Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
            SpotMetric(
                stringResource(R.string.spot_vol_mcap),
                SpotMarket.percent(SpotMarket.volumeToMarketCap(data.volume24h, data.marketCap)),
                Modifier.weight(1f)
            )
            SpotMetric(stringResource(R.string.spot_fdv), SpotMarket.usd(data.fdv), Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
            SpotMetric(stringResource(R.string.spot_total_supply), SpotMarket.supply(data.totalSupply, data.symbol), Modifier.weight(1f))
            SpotMetric(stringResource(R.string.spot_max_supply), SpotMarket.supply(data.maxSupply, data.symbol), Modifier.weight(1f))
        }
        SpotMetric(
            stringResource(R.string.spot_circulating),
            SpotMarket.supply(data.circulatingSupply, data.symbol),
            Modifier.fillMaxWidth().padding(top = Spacing.sm)
        ) {
            SpotMarket.supplyRatio(data.circulatingSupply, data.maxSupply)?.let { ratio ->
                SupplyBar(ratio)
                Text(
                    stringResource(R.string.spot_supply_of_max, SpotMarket.percent(ratio * 100.0)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Text(
            data.lastUpdated?.let { stringResource(R.string.spot_source_updated, SPOT_SOURCE, PriceFormat.time(it)) }
                ?: stringResource(R.string.futures_source, SPOT_SOURCE),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm)
        )
        CoinGeckoAttribution()
    }
}

/** Bezeichnung klein und grau, Wert darunter; [extra] unter dem Wert (Änderung, Balken). */
@Composable
private fun SpotMetric(label: String, value: String, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    Column(modifier = modifier.padding(end = Spacing.sm)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall.amountNumbers())
        extra()
    }
}

/** Anteil Umlauf an der Höchstmenge als Balken in der Themenfarbe. */
@Composable
private fun SupplyBar(ratio: Double) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(ratio.toFloat().coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}
