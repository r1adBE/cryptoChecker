// Ablauf der Buchungen je Coin (offene Posten, Abgänge) — Teil von PortfolioCalculator.
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/** [ledger] aus den schon gesammelten Schritten [events] des Coins (ohne Adress-Einträge). */
internal fun PortfolioCalculator.ledgerOf(
    symbol: String,
    events: List<PortfolioLedgerEvent>,
    addressAmount: BigDecimal?,
    currentPrice: Double?,
    method: CostMethod,
    withParts: Boolean = true,
): CoinLedger {
    val book = when (method) {
        CostMethod.FIFO -> PortfolioFifo.run(symbol, events)
        CostMethod.FIFO_WALLET -> PortfolioFifo.run(symbol, events, perWallet = true)
        CostMethod.AVERAGE -> average(symbol, events, withParts)
    }
    val zero = BigDecimal.ZERO
    val mc = PortfolioDecimal.MC
    val priced = book.priced
    val unpriced = book.unpriced
    val cost = book.cost

    val ledgerHoldings = (priced + unpriced).let { if (PortfolioDecimal.isDust(it)) zero else it }
    val addressHoldings = addressAmount?.takeIf { it.signum() > 0 } ?: zero
    val holdings = ledgerHoldings + addressHoldings
    // Stablecoin-Regel: USDT = 1, andere Stablecoins Marktkurs, sonst kein Wert ([PortfolioStables])
    val price = PortfolioStables.price(symbol, currentPrice)
    val open = holdings.signum() > 0
    val ledgerOpen = ledgerHoldings.signum() > 0
    val priceMissing = ledgerOpen && unpriced.signum() > 0
    val avgCost = if (priced.signum() > 0) cost.divide(priced, mc).toDouble() else null
    val costBasisExact = if (ledgerOpen && !priceMissing) cost else null
    val costBasis = costBasisExact?.toDouble()
    // Einstand des Teils mit Kaufpreis: «Seit Kauf» auch dann, wenn nur ein Teil ohne Kaufpreis ist
    val pricedHoldings = if (ledgerOpen) priced else zero
    val pricedCostExact = if (pricedHoldings.signum() > 0) cost else null
    // Wert und unrealisierter Gewinn exakt (Kurs über seine kürzeste Darstellung); Double nur für die Anzeige.
    // Wert = ganzer Bestand (mit Adressen), «Seit Kauf» nur auf den eigenen Teil mit Kaufpreis.
    val priceExact = price?.let { PortfolioDecimal.of(it) }
    val valueExact = priceExact?.let { holdings.multiply(it, PortfolioDecimal.MC) }
    val value = valueExact?.toDouble()
    // Ganz mit Kaufpreis: ganzer eigener Bestand (Staub inklusive), sonst nur der Teil mit Kaufpreis
    val pricedValueExact = priceExact?.let { (if (priceMissing) pricedHoldings else ledgerHoldings).multiply(it, PortfolioDecimal.MC) }
    val unrealizedExact = if (pricedCostExact != null && pricedValueExact != null) pricedValueExact - pricedCostExact else null
    val unrealized = unrealizedExact?.toDouble()
    val unrealizedPercent = if (unrealizedExact != null && pricedCostExact != null && pricedCostExact.signum() > 0) {
        unrealizedExact.divide(pricedCostExact, mc).toDouble() * 100.0
    } else {
        null
    }

    val position = CoinPosition(
        coin = symbol,
        holdings = holdings.toDouble(),
        avgCost = avgCost,
        costBasis = costBasis,
        currentPrice = price,
        value = value,
        unrealized = unrealized,
        unrealizedPercent = unrealizedPercent,
        realized = book.realized.toDouble(),
        priceMissing = priceMissing,
        oversold = book.oversold,
        tradeCount = events.count { !it.feeLeg },
        realizedUnknownAmount = if (PortfolioDecimal.isDust(book.unknownSold)) 0.0 else book.unknownSold.toDouble(),
        soldAmount = if (PortfolioDecimal.isDust(book.soldTotal)) 0.0 else book.soldTotal.toDouble(),
        holdingsExact = holdings,
        costBasisExact = costBasisExact,
        realizedExact = book.realized,
        pricedHoldingsExact = pricedHoldings,
        valueExact = valueExact,
        unrealizedExact = unrealizedExact,
        feeValueMissing = book.feeValueMissing,
        realizedFeeMissing = book.realizedFeeMissing,
        withdrawnAmount = if (PortfolioDecimal.isDust(book.withdrawn)) 0.0 else book.withdrawn.toDouble(),
        addressHoldingsExact = addressHoldings,
        addressValueExact = priceExact?.let { addressHoldings.multiply(it, PortfolioDecimal.MC) },
        method = method,
        lostFeeCostUsdt = book.lostFeeCosts.fold(zero) { acc, l -> acc + l.costUsdt },
        unassignedFeeUsdt = book.unassignedFees.fold(zero) { acc, f -> acc + f.usdt },
        // Aus denselben Veräusserungen wie der Steuer-Export: ein Teil ohne Einstand/Erlös → unvollständig
        disposalsIncomplete = book.disposals.any { it.costUsdt == null || it.proceedsUsdt == null || it.feeValueMissing },
        pricedCostExact = pricedCostExact,
        pricedCostParts = if (pricedCostExact != null) book.openParts else emptyList(),
    )
    val openLots = openLotsOf(symbol, method, book, ledgerOpen)
    return CoinLedger(
        position,
        book.lots,
        book.disposals,
        book.withdrawals,
        openLots,
        book.shortfalls,
        book.lostFeeCosts,
        book.unassignedFees,
    )
}

/** Offene Posten für die Anzeige: FIFO je Posten, Ø zusammengefasst (Teil mit / ohne Kaufpreis). */
private fun openLotsOf(symbol: String, method: CostMethod, book: PortfolioBookResult, ledgerOpen: Boolean): List<PortfolioOpenLot> =
    if (method.isFifo) {
        book.lots.map {
            PortfolioOpenLot(symbol, it.txId, it.acquiredTime, it.kind, it.amount, it.costUsdt, it.wallet, aggregated = false)
        }
    } else {
        listOfNotNull(
            PortfolioOpenLot(symbol, null, null, null, book.priced, book.cost, null, aggregated = true).takeIf {
                book.priced.signum() > 0 && ledgerOpen
            },
            PortfolioOpenLot(symbol, null, null, null, book.unpriced, null, null, aggregated = true).takeIf {
                book.unpriced.signum() > 0 && ledgerOpen
            },
        )
    }
