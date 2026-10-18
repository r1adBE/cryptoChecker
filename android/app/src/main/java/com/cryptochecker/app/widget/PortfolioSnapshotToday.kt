package com.cryptochecker.app.widget

import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.CoinPosition
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioPeriodChange
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.watch.ChangeBasisMath

// «Heute» der Momentaufnahme ([PortfolioSnapshotUpdater.record]): reine Rechnung ohne Speicher und Netz,
// darum neben dem Updater in eigener Datei.

/**
 * «Heute» für Karte, Widget und Portfolio-Alarm aus EINER Rechnung: Kursänderung je Coin mit
 * Vergleichskurs ([PortfolioPeriodChange.todayFromCoinChanges]; Coins ohne fehlen statt flach
 * mitzuzählen), um Käufe/Verkäufe seit Beginn der %-Basis berichtigt ([PortfolioPeriodChange.todayAdjusted]).
 * Betrag in der Anzeigewährung ([rate]); unbekannt = null. Dazu dieselbe Rechnung je Coin
 * ([PortfolioPeriodChange.todayByCoin]) für die Zeilen: [PortfolioSnapshot.coinPnl] (USDT) und
 * [PortfolioPosition.pnlAmount]/[PortfolioPosition.pnlPercent] (Anzeigewährung).
 */
internal fun PortfolioSnapshot.withHonestToday(
    transactions: List<PortfolioTxEntity>,
    holdings: Map<String, Double>,
    current: Map<String, Double>,
    basis: com.cryptochecker.app.domain.watch.ChangeBasis,
    now: Long,
    rate: Double,
    addresses: List<AddressBalanceHistory>,
    method: CostMethod,
): PortfolioSnapshot {
    if (empty) return this
    val start = ChangeBasisMath.dayStart(basis, now) ?: (now - DAY_MILLIS)
    val raw = PortfolioPeriodChange.todayFromCoinChanges(holdings, current, coinChanges)
    val trades = transactions.map { t -> t.toTrade() }
    val adjusted = raw?.let {
        PortfolioPeriodChange.todayAdjusted(it, trades, current, coinChanges, start, addresses = addresses, method = method)
    }
    val byCoin = PortfolioPeriodChange.todayByCoin(holdings, trades, current, coinChanges, start, addresses, method)
        .mapValues { (_, c) -> PortfolioChange(c.amountUsd, c.percent?.takeIf { it.isFinite() }) }
    return copy(
        changeAmount = adjusted?.amountUsd?.takeIf { it.isFinite() }?.times(rate),
        changePercent = adjusted?.percent?.takeIf { it.isFinite() },
        todaySkipped = adjusted?.skipped.orEmpty(),
        coinPnl = byCoin,
        positions = positions.map { p ->
            val pnl = byCoin[PortfolioCalculator.normalizeCoin(p.symbol)]
            p.copy(pnlAmount = pnl?.amount?.times(rate), pnlPercent = pnl?.percent)
        },
    )
}

/**
 * Bestand für «Heute» ([PortfolioPeriodChange]): eigener Bestand ([CoinPosition.ledgerHoldingsExact]) plus zuletzt
 * beobachtetes Adress-Guthaben ([AddressBalanceHistory.latestByCoin]) — passend zur Reihe [addresses].
 */
internal fun todayHoldings(open: List<CoinPosition>, addresses: List<AddressBalanceHistory>): Map<String, Double> {
    val observed = AddressBalanceHistory.latestByCoin(addresses)
    return open.associate { p ->
        p.coin to (p.ledgerHoldingsExact + (observed[PortfolioCalculator.normalizeCoin(p.coin)] ?: java.math.BigDecimal.ZERO)).toDouble()
    }
}

private const val DAY_MILLIS = 86_400_000L
