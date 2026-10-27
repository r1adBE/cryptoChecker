// Beobachtete Adressen am Stichtag in der Sicht der Portfolio-Karte (Stichtag-CSV, CH-Vermögenswert) — Spiegel:
// `AddressCutoff.swift`.
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/**
 * Adressen am Stichtag ([AddressCutoff.at]), gerechnet wie die Karte ([AddressBalance.trades]):
 *  - [lots]: wirksame Eröffnungsposten als Käufe (Wallet = Adresse, negative Id [AddressCostLots.tradeId]);
 *  - [part]: Adress-Teil ohne Einstand je Coin (Guthaben am Stichtag ohne den Teil, den Posten und erfasste Bewegungen
 *    schon abbilden; nur > 0) und die am Stichtag nicht beobachteten Coins ([AddressBalancesAt.unobserved]);
 *  - [lotsAfterObservation]: ein Posten liegt zwischen der benutzten Beobachtung und dem Stichtag — das Guthaben jener
 *    Beobachtung kennt ihn noch nicht, er kann fehlen (Hinweiszeile in der Datei, Vermerk an der Summe der Karte).
 */
data class AddressCutoffView(
    val lots: List<PortfolioTrade> = emptyList(),
    val part: AddressBalancesAt = AddressBalancesAt(emptyMap(), emptySet()),
    val lotsAfterObservation: Boolean = false,
)

/**
 * Stichtag-CSV und CH-Vermögenswert rechnen je Coin wie die Karte: eigene Buchungen bis zum Stichtag + Posten + Adress-Teil
 * ohne Einstand — statt Buchungen + volles Adress-Guthaben (eine erfasste Umbuchung zur Adresse zählte doppelt, eine von
 * ihr weg fehlte). Je zählender Adresse ([AddressCostLots.counted]) und Coin:
 *  - Guthaben = letzte Beobachtung ≤ Stichtag (auch 0); vorher nicht beobachtet: Coin «nicht beobachtet», seine Posten
 *    warten (wie «Guthaben noch nicht geladen» auf der Karte).
 *  - Erfasste Bewegungen der Adress-Wallet ([AddressCostLots.recorded], nur die verrechnende Adresse je Wallet,
 *    [AddressCostLots.movesOwners]) bis zur Zeit DIESER Beobachtung — nur sie stecken schon in ihrem Guthaben; spätere
 *    zählen allein als Buchung.
 *  - Posten mit Zeit ≤ Stichtag gegen den Bestand vor diesen Bewegungen ([AddressCostLots.cover]), Rest ohne Einstand.
 */
object AddressCutoff {

    /** Guthaben einer Adresse am Stichtag je Coin (auch 0), Zeit der benutzten Beobachtung, nicht beobachtete Coins. */
    private class Observed(val balances: Map<String, BigDecimal>, val times: Map<String, Long>, val unobserved: Set<String>)

    /** Wie [AddressBalanceHistory.balancesAt] für EINE Adresse, aber mit Guthaben 0 und der Zeit der Beobachtung. */
    private fun observedAt(a: WatchedAddress, cutoffMillis: Long): Observed {
        val balances = LinkedHashMap<String, BigDecimal>()
        val times = HashMap<String, Long>()
        val unobserved = LinkedHashSet<String>()
        for (h in AddressBalanceHistory.of(listOf(a))) {
            val coin = PortfolioCalculator.normalizeCoin(h.coin)
            val obs = AddressBalanceHistory.clean(h.observations)
            if (obs.isEmpty()) continue
            val at = obs.lastOrNull { it.time <= cutoffMillis }
            if (at == null) {
                unobserved += coin
                continue
            }
            balances[coin] = (balances[coin] ?: BigDecimal.ZERO) + at.balance
            // Zwei Reihen desselben Coins: die jüngere Beobachtung bestimmt, welche Bewegungen schon enthalten sind
            times[coin] = maxOf(times[coin] ?: Long.MIN_VALUE, at.time)
        }
        return Observed(balances, times, unobserved)
    }

    /** Adressen [addresses] am Stichtag [cutoffMillis] mit den echten Transaktionen [real] (Adress-Einträge fallen weg). */
    fun at(addresses: List<WatchedAddress>, real: List<PortfolioTrade>, cutoffMillis: Long): AddressCutoffView {
        val own = real.filter { !AddressBalance.isAddressTrade(it.id) && it.time <= cutoffMillis }
        val owners = AddressCostLots.movesOwners(addresses)
        val lots = ArrayList<PortfolioTrade>()
        val part = LinkedHashMap<String, BigDecimal>()
        val unobserved = LinkedHashSet<String>()
        var late = false
        for ((a, _) in AddressCostLots.counted(addresses)) {
            val seen = observedAt(a, cutoffMillis)
            unobserved += seen.unobserved
            val all = AddressCostLots.clean(a.costLots)
            val due = all.filter { it.time <= cutoffMillis }
            val wallet = AddressCostLots.walletName(a)
            val moves = owners[PortfolioWallets.key(wallet)] == a.id
            for ((coin, balance) in seen.balances) {
                val time = seen.times.getValue(coin)
                val coinLots = due.filter { it.coin == coin }
                val recorded = if (moves) AddressCostLots.recorded(coin, wallet, own.filter { it.time <= time }) else BigDecimal.ZERO
                val c = AddressCostLots.cover(coin, coinLots, balance, recorded)
                for (l in c.lots) {
                    lots += PortfolioTrade(
                        id = AddressCostLots.tradeId(a.id, all.indexOfFirst { it.id == l.id }), coin = coin,
                        type = PortfolioTxType.BUY, amount = l.amount, priceUsdt = l.price, time = l.time, exchange = wallet,
                    )
                }
                // Rest ohne Einstand = Guthaben − abgebildeter Teil ([AddressCostLots.Coverage.accounted])
                part[coin] = (part[coin] ?: BigDecimal.ZERO) + c.unlotted
                if (coinLots.any { it.time > time }) late = true
            }
        }
        return AddressCutoffView(lots, AddressBalancesAt(part.filterValues { it.signum() > 0 }, unobserved), late)
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
