// Beobachtete Adress-Reihen in der Sicht der Karte: ohne den Teil, den die App schon bucht (gespiegelt in
// `AddressBooked.swift`).
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/**
 * Bewegung, die ein beobachtetes Guthaben schon enthält und die App selbst bucht: Zeit (ms, höchstens die letzte
 * Beobachtung der Reihe) und Menge (+ Zugang, − Abgang).
 */
data class AddressMove(val time: Long, val amount: BigDecimal)

/**
 * EINE Bestandsregel für beobachtete Adressen in Karte, «Heute», 7 T / 1 J, Wertverlauf, Widget und Stichtag:
 * eigene Buchungen + Eröffnungsposten (Käufe ab ihrer Zeit, [AddressCostLots.trades]) + Adress-Teil ohne Einstand.
 * Der Adress-Teil ist das beobachtete Guthaben ohne den Teil, den Posten und erfasste Bewegungen der Adress-Wallet schon
 * abbilden ([AddressCostLots.Coverage.accounted]) — sonst zählte eine erfasste Umbuchung zur Adresse oder ein Posten
 * doppelt (einmal als Buchung, einmal im Guthaben).
 *
 * Für Zeiträume und Verlauf je Adresse und Coin zu jeder Beobachtung (Zeit t): max(Guthaben(t) − gebuchte Bewegungen
 * bis t, 0) — [held]. Gebuchte Bewegungen ([AddressBalanceHistory.moves]) = wirksame Posten ([AddressCostLots.coverage])
 * und erfasste Bewegungen der Wallet ([AddressCostLots.recordedMove], nur die verrechnende Adresse,
 * [AddressCostLots.movesOwners], nur bis zum Abruf des Guthabens). Ihre Zeit wird auf die letzte Beobachtung begrenzt:
 * das zuletzt geladene Guthaben enthält sie wie auf der Karte. Vor der ersten Beobachtung zählt die Adresse nicht (nie
 * rückwirkend); die Buchungen selbst zählen ab ihrer Zeit. Die letzte Stufe ist so der Adress-Teil der Karte.
 */
object AddressBooked {

    /**
     * Reihen wie [AddressBalanceHistory.of], an der ersten Reihe je Adresse und Coin die gebuchten Bewegungen
     * ([AddressBalanceHistory.moves]); [real] = echte Transaktionen (Adress-Einträge zählen nicht).
     */
    fun histories(addresses: List<WatchedAddress>, real: List<PortfolioTrade>): List<AddressBalanceHistory> {
        val out = AddressBalanceHistory.of(addresses).toMutableList()
        val owners = if (real.isEmpty()) emptyMap() else AddressCostLots.movesOwners(addresses)
        for ((a, _) in AddressCostLots.counted(addresses)) {
            val wallet = AddressCostLots.walletName(a)
            val lots = AddressCostLots.coverage(a, real, addresses)
            // Nur bis zum Abruf des Guthabens: spätere Buchungen stecken noch nicht darin
            val recorded = if (owners[PortfolioWallets.key(wallet)] != a.id) emptyList()
            else real.filter { a.lastUpdated <= 0 || it.time <= a.lastUpdated }
            val mine = out.indices.filter { out[it].chain == a.chain && WatchedAddressRules.sameAddress(a.chain, out[it].address, a.address) }
            for (coin in mine.map { PortfolioCalculator.normalizeCoin(out[it].coin) }.distinct()) {
                val series = mine.filter { PortfolioCalculator.normalizeCoin(out[it].coin) == coin }
                val last = series.maxOf { i -> out[i].observations.maxOf { it.time } }
                val moves = lots.filter { it.coin == coin }.flatMap { it.lots }.map { AddressMove(minOf(it.time, last), it.amount) } +
                    recorded.mapNotNull { t ->
                        AddressCostLots.recordedMove(coin, wallet, t).takeIf { it.signum() != 0 }?.let { AddressMove(minOf(t.time, last), it) }
                    }
                if (moves.isNotEmpty()) out[series.first()] = out[series.first()].copy(moves = moves)
            }
        }
        return out
    }

    /** Gleiche Adresse und gleicher Coin (Reihen, die [held] zusammen rechnet). */
    fun sameGroup(x: AddressBalanceHistory, y: AddressBalanceHistory): Boolean =
        x.chain == y.chain && WatchedAddressRules.sameAddress(x.chain, x.address, y.address) &&
            PortfolioCalculator.normalizeCoin(x.coin) == PortfolioCalculator.normalizeCoin(y.coin)

    /**
     * Stufen der Reihen [group] (eine Adresse, ein Coin) ohne gebuchte Bewegungen: zu jeder Beobachtungszeit Summe der
     * zuletzt beobachteten Guthaben minus Bewegungen bis dahin, nie unter 0; aufsteigend.
     */
    fun held(group: List<AddressBalanceHistory>): List<AddressObservation> {
        val series = group.map { h -> h.observations.filter { it.balance.signum() >= 0 }.sortedBy { it.time } }
        val moves = group.flatMap { it.moves }
        return series.flatten().map { it.time }.distinct().sorted().map { t ->
            val observed = series.fold(BigDecimal.ZERO) { sum, list -> sum + (list.lastOrNull { it.time <= t }?.balance ?: BigDecimal.ZERO) }
            val booked = moves.filter { it.time <= t }.fold(BigDecimal.ZERO) { sum, m -> sum + m.amount }
            AddressObservation(t, (observed - booked).max(BigDecimal.ZERO))
        }
    }
}
