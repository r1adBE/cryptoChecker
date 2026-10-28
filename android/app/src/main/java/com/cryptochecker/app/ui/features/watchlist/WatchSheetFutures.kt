@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.FuturesInfo
import com.cryptochecker.app.data.remote.FuturesSources
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import com.cryptochecker.app.domain.spot.SpotData
import com.cryptochecker.app.ui.components.ChangePillSize
import com.cryptochecker.app.ui.components.PillTone
import com.cryptochecker.app.ui.components.RegisterTabs
import com.cryptochecker.app.ui.components.TonePill
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.CompactAmount
import com.cryptochecker.app.util.DecimalText
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlin.math.abs

/** Nachkommastellen der Funding Rate («+0.0100%») — wie iOS. */
private const val FUNDING_DECIMALS = 4

/** Register der Karte «Marktdaten»: Reihenfolge = Reihenfolge der Register. */
internal enum class MarketDataTab { SPOT, FUTURES }

/**
 * Zuletzt gewähltes Register für die Dauer der Sitzung (Prozess), getrennt für Futures- und
 * Spot-Paare; Standard: Futures bei Futures-Paaren, Spot bei Spot-Paaren. Wie iOS `MarketDataTabMemory`.
 */
private object MarketDataTabMemory {
    @Volatile
    var forFutures: MarketDataTab = MarketDataTab.FUTURES

    @Volatile
    var forSpot: MarketDataTab = MarketDataTab.SPOT

    fun get(futuresPair: Boolean): MarketDataTab = if (futuresPair) forFutures else forSpot

    fun set(futuresPair: Boolean, tab: MarketDataTab) {
        if (futuresPair) {
            forFutures = tab
        } else {
            forSpot = tab
        }
    }
}

/**
 * Karte «Marktdaten» mit den Registern «Spot | Futures» ([RegisterTabs]):
 *  - Spot: Marktkapitalisierung (+ 24h), Volumen, Vol./Marktkap., FDV und Mengen von CoinGecko
 *    ([SpotSection]); erst geladen, wenn das Register offen ist.
 *  - Futures: Funding Rate (+ nächste Zahlung) und Open Interest des Perpetuals — bei Spot-Paaren
 *    des USDT-Perpetuals des Coins; bei Perpetuals gleich beim Öffnen des Blatts geladen.
 * ⓘ blendet die Erklärungen zum offenen Register ein. Wie iOS `WatchlistMarketDataSection`.
 */
@Composable
internal fun MarketDataSection(
    watch: WatchEntity,
    loadFutures: suspend (WatchEntity) -> FuturesInfo?,
    loadSpot: suspend (WatchEntity) -> SpotData?,
) {
    val futuresPair = watch.contractType != FuturesContractType.NONE
    val pairKey = listOf(watch.id, watch.exchangeKey, watch.baseAsset, watch.quoteAsset, watch.contractType)
    var tab by remember(futuresPair) { mutableStateOf(MarketDataTabMemory.get(futuresPair)) }
    var showExplain by remember { mutableStateOf(false) }
    // Neu laden, wenn das Paar bearbeitet wurde
    var futures by remember(pairKey) { mutableStateOf<FuturesInfo?>(null) }
    var futuresLoaded by remember(pairKey) { mutableStateOf(false) }
    var spot by remember(pairKey) { mutableStateOf<SpotData?>(null) }
    var spotLoaded by remember(pairKey) { mutableStateOf(false) }
    // Perpetuals sofort, alles andere erst mit offenem Register (keine unnötige Abfrage)
    val needFutures = tab == MarketDataTab.FUTURES || watch.contractType.isPerpetual
    LaunchedEffect(pairKey, needFutures) {
        if (needFutures && !futuresLoaded) {
            futures = loadFutures(watch)
            futuresLoaded = true
        }
    }
    LaunchedEffect(pairKey, tab) {
        if (tab == MarketDataTab.SPOT && !spotLoaded) {
            spot = loadSpot(watch)
            spotLoaded = true
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.market_data_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            // (i) 20 dp, Tippfläche 48 dp (IconButton-Standard)
            IconButton(onClick = { showExplain = !showExplain }) {
                Icon(
                    painterResource(R.drawable.ic_info),
                    contentDescription = stringResource(R.string.explain_show),
                    tint = if (showExplain) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        RegisterTabs(
            labels = listOf(stringResource(R.string.explorer_contract_spot), stringResource(R.string.market_data_tab_futures)),
            selected = tab.ordinal,
            onSelect = { index ->
                tab = MarketDataTab.entries[index]
                MarketDataTabMemory.set(futuresPair, tab)
            },
            modifier = Modifier.padding(top = Spacing.xs)
        )
        when (tab) {
            MarketDataTab.SPOT -> SpotSection(data = spot, loaded = spotLoaded, showExplain = showExplain)
            MarketDataTab.FUTURES -> FuturesBody(watch = watch, info = futures, loaded = futuresLoaded, showExplain = showExplain)
        }
    }
}

/** Register «Futures»: lädt noch, keine Daten oder die Kennzahlen. */
@Composable
private fun FuturesBody(watch: WatchEntity, info: FuturesInfo?, loaded: Boolean, showExplain: Boolean) {
    if (showExplain) {
        MarketDataExplanation(
            stringResource(R.string.label_value, stringResource(R.string.futures_funding), stringResource(R.string.explain_funding))
        )
        MarketDataExplanation(
            stringResource(R.string.label_value, stringResource(R.string.futures_open_interest), stringResource(R.string.explain_open_interest))
        )
    }
    when {
        info != null -> FuturesSection(info, watch)
        loaded -> MarketDataMessage(stringResource(R.string.futures_no_data))
        else -> MarketDataMessage(stringResource(R.string.loading_hint))
    }
}

/** Kurzer grauer Satz im Register («Wird geladen…», «Keine Futures-Daten für diesen Coin»). */
@Composable
internal fun MarketDataMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = Spacing.sm)
    )
}

/** Erklärung unter der Kopfzeile (ⓘ). */
@Composable
internal fun MarketDataExplanation(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

/** Funding Rate, nächste Zahlung und Open Interest eines Perpetuals. */
@Composable
private fun FuturesSection(info: FuturesInfo, watch: WatchEntity) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.futures_funding),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val rate = info.fundingRatePercent?.takeIf { it.isFinite() }
                // Positiv: Longs zahlen an Shorts (Markt überhitzt eher), negativ umgekehrt —
                // als farbige Schrift wie alle Werte mit Vorzeichen; gerundet 0 bzw. ohne Wert grau
                // Abrechnung nicht alle 8 Stunden (Hyperliquid stündlich, manche Binance-/Bybit-Kontrakte alle
                // 1, 2 oder 4 Std.): Einheit «pro Std.» bzw. «pro 4 Std.» dahinter
                val interval = info.intervalHours
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    TonePill(
                        text = rate?.let { PriceFormat.signedPercent(it, FUNDING_DECIMALS) } ?: "—",
                        tone = PillTone.forValue(rate) { DecimalText.rounded(abs(it), FUNDING_DECIMALS) == 0.0 },
                        size = ChangePillSize.MEDIUM
                    )
                    if (interval != 8 && rate != null) {
                        Text(
                            when (interval) {
                                null -> stringResource(R.string.futures_funding_interval_unknown)
                                1 -> stringResource(R.string.futures_funding_per_hour)
                                else -> stringResource(R.string.futures_funding_per_hours, interval)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = Spacing.xs)
                        )
                    }
                }
                // Alarme rechnen pro 8 Std.: den umgerechneten Wert zeigen, damit ein abgetippter Wert passt
                val per8h = DerivativesAlarm.fundingPer8h(rate, info.source, info.fundingIntervalHours)
                if (interval != null && interval != 8 && per8h != null) {
                    Text(
                        stringResource(R.string.futures_funding_per8h_equiv, PriceFormat.signedPercent(per8h, FUNDING_DECIMALS)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                info.nextFundingTime?.let { next ->
                    val minutes = ((next - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                    Text(
                        stringResource(R.string.futures_next_funding, (minutes / 60).toInt(), (minutes % 60).toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.futures_open_interest),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = info.openInterestUsd?.let { "$" + CompactAmount.number(it) } ?: "—",
                    style = MaterialTheme.typography.titleSmall.amountNumbers()
                )
            }
        }
        Text(
            futuresSourceText(info, watch),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs)
        )
    }
}

/**
 * Herkunft unter den Kennzahlen: «Quelle: WOO X Futures», wenn die beobachtete Börse selbst liefert;
 * sonst als Richtwert gekennzeichnet — bei Perpetuals mit dem Hinweis, dass Funding und Open Interest
 * je Börse gelten ([FuturesSources.note]). Wie iOS `WatchlistFuturesSection.sourceText`.
 */
@Composable
private fun futuresSourceText(info: FuturesInfo, watch: WatchEntity): String {
    val perpetual = watch.contractType.isPerpetual
    return when {
        FuturesSources.note(perpetual, watch.exchangeKey, info.source, watch.pairId) == null ->
            stringResource(R.string.futures_source, info.source)
        perpetual -> stringResource(R.string.futures_reference_hint, info.source, watch.exchangeName)
        else -> stringResource(R.string.futures_reference_spot, info.source)
    }
}

/** Quote-Währungen, deren Kurs als USDT-Preis übernommen werden kann. */
internal val USD_LIKE_QUOTES = setOf("USDT", "USD", "USDC", "FDUSD")

