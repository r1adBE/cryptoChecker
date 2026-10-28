// Rechnungen der Coin-Ansicht und der Wallet-Hinweise (Teil von `PortfolioViewModel`), abseits des
// Hauptthreads statt in der Komposition: Ledger/FIFO, Adress-Guthaben, Umbuchungs-Treffer. Ergebnis wie vorher.
package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.PortfolioPrices
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressCostLots
import com.cryptochecker.app.domain.portfolio.CoinLedger
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioLedger
import com.cryptochecker.app.domain.portfolio.PortfolioPeriodChange
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioWalletShortfall
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.math.BigDecimal

/** Alles, was die Coin-Ansicht zeigt ([PortfolioDetailScreen]); [all] = alle echten Transaktionen. */
internal data class CoinDetailUi(
    val all: List<PortfolioTxEntity>,
    /** Transaktionen dieses Coins, auch Tausche in ihn («Tausch von BTC»). */
    val own: List<PortfolioTxEntity>,
    val ledger: CoinLedger,
    /** Guthaben beobachteter Adressen (> 0); null = keins. */
    val addressAmount: BigDecimal?,
    /** Eigene Einzahlung/Umbuchung = Guthaben/Zugang einer zählenden Adresse (± 1 %). */
    val addressMatches: List<PortfolioTrade>,
    /** Erste zählende Adresse mit Guthaben in diesem Coin (Einstand für Adressguthaben); null = keine. */
    val lotAddress: WatchedAddress?,
)

/** Rechnung zu [coinDetailFlow] — rein, für Tests und den Flow. */
internal fun coinDetail(
    symbol: String,
    all: List<PortfolioTxEntity>,
    calc: List<PortfolioTxEntity>,
    addresses: List<WatchedAddress>,
    currentPrice: Double?,
    method: CostMethod,
    feePrice: ((String, Long) -> Double?)?,
): CoinDetailUi {
    val own = all.filter { tx -> tx.coins().any { PortfolioCalculator.normalizeCoin(it) == symbol } }
    // Gebühr-Zeilen zum Tageskurs wie auf der Karte ([marketPriceAt], hier [CoinFeePrices])
    val ledger = PortfolioCalculator.ledger(symbol, calc.map { it.toTrade() }, currentPrice, method, feePrice)
    val addressAmount = AddressBalance.amountFor(symbol, addresses, all.map { it.toTrade() })?.takeIf { it.signum() > 0 }
    val matches = WatchedAddressRules.transferMatchesFor(symbol, addresses, own.map { it.toTrade() })
    val lotAddress = AddressCostLots.counted(addresses).firstOrNull { (_, balances) -> (balances[symbol]?.signum() ?: 0) > 0 }?.first
    return CoinDetailUi(all, own, ledger, addressAmount, matches, lotAddress)
}

/**
 * Coin-Ansicht für [symbol] (normalisiert); leer, bis die Datenbank geantwortet hat. Erst mit beiden
 * Listen (alle und zählende Transaktionen — vorher kurz Bestand 0), und der Vergleich der Ergebnisse läuft vor [flowOn]
 * abseits des Hauptthreads. Kurs-Ticks anderer Coins rechnen wirklich nicht neu — der
 * Tageskurs für Gebühr-/Tausch-Zeilen hängt nur an den Kursen der Coins, die die eigenen Zeilen brauchen
 * ([coinDetailFeeCoins], [CoinFeePrices]); die Kursfunktion entsteht erst in der Rechnung.
 */
internal fun PortfolioViewModel.coinDetailFlow(symbol: String): Flow<CoinDetailUi> =
    coinDetailFlowOf(symbol, transactions, calcTransactions, watchedAddresses, prices, _closes, costMethod)

/** [coinDetailFlow] aus den einzelnen Flows; [detail] = Rechnung (Tests zählen damit die Aufrufe). */
internal fun coinDetailFlowOf(
    symbol: String,
    transactions: Flow<List<PortfolioTxEntity>?>,
    calcTransactions: Flow<List<PortfolioTxEntity>?>,
    watchedAddresses: Flow<List<WatchedAddress>>,
    prices: Flow<PortfolioPrices>,
    closes: Flow<HistoryCloses?>,
    costMethod: Flow<CostMethod>,
    detail: (String, List<PortfolioTxEntity>, List<PortfolioTxEntity>, List<WatchedAddress>, Double?, CostMethod,
             ((String, Long) -> Double?)?) -> CoinDetailUi = ::coinDetail,
): Flow<CoinDetailUi> {
    val feeCoins = calcTransactions.map { coinDetailFeeCoins(symbol, it.orEmpty()) }.distinctUntilChanged()
    val feePrices = combine(feeCoins, prices, closes) { coins, p, loaded -> CoinFeePrices.of(coins, p, loaded) }
        .distinctUntilChanged()
    return combine(transactions, calcTransactions, watchedAddresses, prices, costMethod) { all, calc, addresses, p, method ->
        Inputs(all, calc, addresses, p.prices[symbol], method)
    }.distinctUntilChanged().combine(feePrices) { input, fee ->
        if (input.all == null || input.calc == null) null
        else detail(symbol, input.all, input.calc, input.addresses, input.price, input.method, fee.priceAt())
    }.filterNotNull().distinctUntilChanged().flowOn(Dispatchers.Default)
}

/**
 * Coins (normalisiert), deren Tageskurs die Zeilen von [symbol] brauchen ([PortfolioLedger.valueAtDayClose]): Zeilen, die
 * [symbol] betreffen (Coin, erhaltener Coin oder Gebühr-Coin) und einen Tagesschluss brauchen — deren Coin und beim
 * Tausch der erhaltene Coin.
 */
internal fun coinDetailFeeCoins(symbol: String, calc: List<PortfolioTxEntity>): Set<String> {
    val out = sortedSetOf<String>()
    for (t in calc.map { it.toTrade() }) {
        val coin = PortfolioCalculator.normalizeCoin(t.coin)
        if (symbol != coin && symbol != t.swapToCoin && symbol != t.thirdFeeCoin) continue
        if (!PortfolioLedger.needsDayClose(t)) continue
        out += coin
        t.swapToCoin?.let { out += it }
    }
    return out
}

/**
 * Kurse nur der Coins aus [coinDetailFeeCoins]: aktueller Kurs und Tagesschlüsse (wie [marketPriceAt]). Vergleichbar,
 * damit Ticks anderer Coins nichts auslösen; [priceAt] gibt für andere Coins null (deren Zeilen zählen hier nicht).
 */
internal data class CoinFeePrices(val live: Map<String, Double?>, val closes: Map<String, Map<Long, Double>>) {
    fun priceAt(): (String, Long) -> Double? = { coin, time ->
        val c = PortfolioCalculator.normalizeCoin(coin)
        if (c in live) PortfolioPeriodChange.marketPriceAt(c, live[c], closes, time) else null
    }

    companion object {
        fun of(coins: Set<String>, p: PortfolioPrices, loaded: HistoryCloses?): CoinFeePrices {
            if (coins.isEmpty()) return CoinFeePrices(emptyMap(), emptyMap())
            val all = loaded?.routed(p)?.closes.orEmpty()
            val closes = coins.mapNotNull { c -> all[c]?.let { c to it } }.toMap()
            return CoinFeePrices(coins.associateWith { p.prices[it] }, closes)
        }
    }
}

/** FIFO je Wallet: Abgänge über den Bestand ihrer Wallet (Hinweis unter der Gesamtkarte, [PortfolioScreen]). */
internal fun PortfolioViewModel.walletShortfallsFlow(): Flow<List<PortfolioWalletShortfall>> =
    combine(calcTransactions, costMethod) { calc, method ->
        PortfolioCalculator.walletShortfalls(calc.orEmpty().map { it.toTrade() }, method)
    }.distinctUntilChanged().flowOn(Dispatchers.Default) // Vergleich vor flowOn (nicht auf Main)

private data class Inputs(
    val all: List<PortfolioTxEntity>?,
    val calc: List<PortfolioTxEntity>?,
    val addresses: List<WatchedAddress>,
    val price: Double?,
    val method: CostMethod,
)
