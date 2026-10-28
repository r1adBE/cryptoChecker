// Beobachtete Adressen am Stichtag in der Sicht der Portfolio-Karte (Stichtag-CSV, CH-Vermögenswert) — Spiegel:
// `AddressCutoff.swift`.
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/**
 * Adressen am Stichtag ([AddressCutoff.at]), gerechnet wie Karte und Wertverlauf ([AddressBooked]):
 *  - [lots]: wirksame Eröffnungsposten der Karte ([AddressCostLots.trades]) mit Zeit ≤ Stichtag als Käufe (Wallet =
 *    Adresse, negative Id [AddressCostLots.tradeId]) — ab ihrem Kaufdatum, auch vor der ersten Beobachtung;
 *  - [part]: Adress-Teil ohne Einstand je Coin = Stufe der Reihe ohne das schon Gebuchte ([AddressBooked.held]) zur letzten
 *    Beobachtung ≤ Stichtag (nur > 0), und die am Stichtag nicht beobachteten Coins ([AddressBalancesAt.unobserved]);
 *  - [lotsAfterObservation]: ein Posten liegt zwischen der benutzten Beobachtung und dem Stichtag — das Guthaben jener
 *    Beobachtung kennt ihn noch nicht; ist er wegen eines tieferen heutigen Guthabens gekürzt, kann er fehlen
 *    (Hinweiszeile in der Datei, Vermerk an der Summe der Karte).
 */
data class AddressCutoffView(
    val lots: List<PortfolioTrade> = emptyList(),
    val part: AddressBalancesAt = AddressBalancesAt(emptyMap(), emptySet()),
    val lotsAfterObservation: Boolean = false,
)

/**
 * Stichtag-CSV und CH-Vermögenswert rechnen je Coin mit DERSELBEN Regel wie Karte und Wertverlauf ([AddressBooked]):
 * eigene Buchungen bis zum Stichtag + Posten ab ihrem Kaufdatum (Abdeckung gegen das heutige Guthaben wie auf der Karte,
 * [AddressCostLots.coverage]) + Adress-Teil ohne Einstand = Reihe ohne das schon Gebuchte am Stichtag. Vor der ersten
 * Beobachtung zählt die Adresse nicht (Coin «nicht beobachtet»), ihre Posten schon. So zeigen Karte, Verlauf, Stichtag
 * und CH-Vermögen für denselben Tag dieselbe Menge.
 */
object AddressCutoff {

    /** Adressen [addresses] am Stichtag [cutoffMillis] mit den echten Transaktionen [real] (Adress-Einträge fallen weg). */
    fun at(addresses: List<WatchedAddress>, real: List<PortfolioTrade>, cutoffMillis: Long): AddressCutoffView {
        val own = real.filter { !AddressBalance.isAddressTrade(it.id) }
        val lots = AddressCostLots.trades(addresses, own).filter { it.time <= cutoffMillis }
        val histories = AddressBooked.histories(addresses, own)
        val part = LinkedHashMap<String, BigDecimal>()
        for ((coin, steps) in AddressBalanceHistory.stepsByCoin(histories)) {
            val key = PortfolioCalculator.normalizeCoin(coin)
            part[key] = (part[key] ?: BigDecimal.ZERO) + AddressBalanceHistory.balanceAt(steps, cutoffMillis)
        }
        val unobserved = AddressBalanceHistory.balancesAt(histories, cutoffMillis).unobserved
        return AddressCutoffView(lots, AddressBalancesAt(part.filterValues { it.signum() > 0 }, unobserved), lateLots(addresses, cutoffMillis))
    }

    /** Ein Posten mit Zeit ≤ Stichtag nach der letzten Beobachtung seines Coins ≤ Stichtag (siehe [AddressCutoffView]). */
    private fun lateLots(addresses: List<WatchedAddress>, cutoffMillis: Long): Boolean {
        for ((a, _) in AddressCostLots.counted(addresses)) {
            val seen = HashMap<String, Long>()
            for (h in AddressBalanceHistory.of(listOf(a))) {
                val at = AddressBalanceHistory.clean(h.observations).lastOrNull { it.time <= cutoffMillis } ?: continue
                val coin = PortfolioCalculator.normalizeCoin(h.coin)
                seen[coin] = maxOf(seen[coin] ?: Long.MIN_VALUE, at.time)
            }
            if (AddressCostLots.clean(a.costLots).any { l -> l.time <= cutoffMillis && seen[l.coin]?.let { l.time > it } == true }) return true
        }
        return false
    }

    /**
     * Bestand je Coin am Stichtag wie die Karte: echte Transaktionen ≤ [cutoffMillis] und die Posten von [view] nach
     * [method] ([PortfolioHoldingsBook], FIFO je Wallet kappt je Wallet; Posten in ihrer Wallet = der Adresse wie in
     * [PortfolioFifo] auf der Karte, damit eine Umbuchung weg oder ein Verkauf dort sie verbraucht). Ohne Adress-Teil
     * ([CutoffExport.addressRows]); Staub = 0; alphabetisch.
     */
    fun holdingsAt(
        real: List<PortfolioTrade>,
        view: AddressCutoffView?,
        cutoffMillis: Long,
        method: CostMethod = CostMethod.DEFAULT,
    ): List<CutoffHolding> {
        val trades = real.filter { !AddressBalance.isAddressTrade(it.id) && it.time <= cutoffMillis } +
            view?.lots.orEmpty().filter { it.time <= cutoffMillis }
        val out = ArrayList<CutoffHolding>()
        for ((coin, events) in PortfolioLedger.eventsByCoin(trades)) {
            val book = PortfolioHoldingsBook(method)
            events.forEach { book.apply(it) }
            if (book.total.signum() > 0 && !PortfolioDecimal.isDust(book.total)) out += CutoffHolding(coin, book.total)
        }
        return out.sortedBy { it.coin }
    }
}
