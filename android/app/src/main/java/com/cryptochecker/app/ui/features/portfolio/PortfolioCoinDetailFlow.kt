// Rechnungen der Coin-Ansicht und der Wallet-Hinweise (Teil von `PortfolioViewModel`), seit Build 103 abseits des
// Hauptthreads statt in der Komposition: Ledger/FIFO, Adress-Guthaben, Umbuchungs-Treffer. Ergebnis wie vorher.
package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressCostLots
import com.cryptochecker.app.domain.portfolio.CoinLedger
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
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
    method: com.cryptochecker.app.domain.portfolio.CostMethod,
    feePrice: ((String, Long) -> Double?)?,
): CoinDetailUi {
    val own = all.filter { tx -> tx.coins().any { PortfolioCalculator.normalizeCoin(it) == symbol } }
    // Gebühr-Zeilen zum Tageskurs wie auf der Karte ([PortfolioViewModel.feePrice])
    val ledger = PortfolioCalculator.ledger(symbol, calc.map { it.toTrade() }, currentPrice, method, feePrice)
    val addressAmount = AddressBalance.amountFor(symbol, addresses, all.map { it.toTrade() })?.takeIf { it.signum() > 0 }
    val matches = WatchedAddressRules.transferMatchesFor(symbol, addresses, own.map { it.toTrade() })
    val lotAddress = AddressCostLots.counted(addresses).firstOrNull { (_, balances) -> (balances[symbol]?.signum() ?: 0) > 0 }?.first
    return CoinDetailUi(all, own, ledger, addressAmount, matches, lotAddress)
}

/** Coin-Ansicht für [symbol] (normalisiert); leer, bis die Datenbank geantwortet hat. */
internal fun PortfolioViewModel.coinDetailFlow(symbol: String): Flow<CoinDetailUi> =
    combine(transactions, calcTransactions, watchedAddresses, prices, costMethod) { all, calc, addresses, p, method ->
        Inputs(all, calc, addresses, p.prices[symbol], method)
    }.combine(feePrice) { input, fee ->
        if (input.all == null) null
        else coinDetail(symbol, input.all, input.calc.orEmpty(), input.addresses, input.price, input.method, fee)
    }.filterNotNull().flowOn(Dispatchers.Default).distinctUntilChanged()

/** FIFO je Wallet: Abgänge über den Bestand ihrer Wallet (Hinweis unter der Gesamtkarte, [PortfolioScreen]). */
internal fun PortfolioViewModel.walletShortfallsFlow(): Flow<List<PortfolioWalletShortfall>> =
    combine(calcTransactions, costMethod) { calc, method ->
        PortfolioCalculator.walletShortfalls(calc.orEmpty().map { it.toTrade() }, method)
    }.flowOn(Dispatchers.Default).distinctUntilChanged()

private data class Inputs(
    val all: List<PortfolioTxEntity>?,
    val calc: List<PortfolioTxEntity>?,
    val addresses: List<WatchedAddress>,
    val price: Double?,
    val method: com.cryptochecker.app.domain.portfolio.CostMethod,
)
