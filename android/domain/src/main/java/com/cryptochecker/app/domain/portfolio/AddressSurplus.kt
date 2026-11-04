// «Nur Mehrbestand»: beobachtete Adressen zählen nur, was sie MEHR halten als die eigenen Transaktionen — Spiegel:
// `AddressSurplus.swift`.
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/**
 * EINE Regel für Adressen, die zum Portfolio zählen ([WatchedAddress.countInPortfolio]): je Coin zählt das Guthaben
 * nur, soweit es den eigenen Bestand übersteigt — gezählt = max(0, Adress-Teil − eigener Bestand) ([counted]).
 *  - Adress-Teil = Guthaben ALLER zählenden Adressen des Coins zusammen ohne das schon Gebuchte (Eröffnungsposten und
 *    erfasste Bewegungen der Adress-Wallet: [AddressCostLots.accounted], im Verlauf [AddressBooked.held]).
 *  - Eigener Bestand = Bestand der eigenen Transaktionen ([PortfolioHoldingsBook], Standardmethode) ohne die erfassten
 *    Zugänge der Adress-Wallets, die das Guthaben schon enthält; nie unter 0 ([outside]).
 *  - Mehrere zählende Adressen desselben Coins: erst summiert, dann der eigene Bestand EINMAL abgezogen.
 * Beispiele: eigene Transaktionen 1.00 BTC, Adresse 1.00 → 0; Adresse 1.06 → 0.06; Airdrop nur auf der Adresse → ganz;
 * Adresse weniger als die eigenen Transaktionen → 0. Coins nur in den eigenen Transaktionen unverändert. Die automatische
 * Regel «Nur anzeigen» (alle Coins innerhalb 5 %, [WatchedAddressRules]) bleibt davor.
 * Karte, Zeilen, Alarme, Widgets und Uhr ([parts] über [AddressBalance.trades]) sowie Zeiträume, Wertverlauf, Stichtag
 * und CH-Vermögenswert ([steps] über [AddressBalanceHistory.trades], Stufen am Stichtag) rechnen damit.
 */
object AddressSurplus {

    /** Gezählter Adress-Teil: max(0, [address] − [ledger]); ein negativer eigener Bestand zählt als 0. */
    fun counted(address: BigDecimal, ledger: BigDecimal): BigDecimal =
        (address - ledger.max(BigDecimal.ZERO)).max(BigDecimal.ZERO)

    /** Eigener Bestand [net] ohne die erfassten Zugänge der Adress-Wallets [recorded] (Abgänge ziehen nichts ab), ≥ 0. */
    fun outside(net: BigDecimal, recorded: BigDecimal): BigDecimal =
        (net - recorded.max(BigDecimal.ZERO)).max(BigDecimal.ZERO)

    /** Bestandsänderungen der eigenen Transaktionen je Coin ([PortfolioHoldingsBook], Standardmethode), zeitlich. */
    fun netMoves(real: List<PortfolioTrade>): Map<String, List<AddressMove>> {
        val own = real.filter { !AddressBalance.isAddressTrade(it.id) }
        val out = LinkedHashMap<String, List<AddressMove>>()
        for ((coin, events) in PortfolioLedger.eventsByCoin(own)) {
            val book = PortfolioHoldingsBook()
            val moves = ArrayList<AddressMove>()
            for (e in events) {
                val before = book.total
                book.apply(e)
                val delta = book.total - before
                if (delta.signum() != 0) moves += AddressMove(e.trade.time, delta)
            }
            if (moves.isNotEmpty()) out[coin] = moves
        }
        return out
    }

    /**
     * Erfasste Bewegungen der Adress-Wallets je Coin ([AddressCostLots.recordedMove]): nur die verrechnende Adresse je
     * Wallet ([AddressCostLots.movesOwners]), nur bis zum Abruf ihres Guthabens — wie [AddressBooked.histories].
     */
    fun recordedMoves(addresses: List<WatchedAddress>, real: List<PortfolioTrade>): Map<String, List<AddressMove>> {
        val own = real.filter { !AddressBalance.isAddressTrade(it.id) }
        if (own.isEmpty()) return emptyMap()
        val owners = AddressCostLots.movesOwners(addresses)
        val coins = PortfolioLedger.coins(own)
        val out = LinkedHashMap<String, MutableList<AddressMove>>()
        for ((a, _) in AddressCostLots.counted(addresses)) {
            val wallet = AddressCostLots.walletName(a)
            if (owners[PortfolioWallets.key(wallet)] != a.id) continue
            val fetched = AddressCostLots.fetched(a, own)
            for (coin in coins) for (t in fetched) {
                val m = AddressCostLots.recordedMove(coin, wallet, t)
                if (m.signum() != 0) out.getOrPut(coin) { ArrayList() } += AddressMove(t.time, m)
            }
        }
        return out
    }

    private fun sum(moves: List<AddressMove>?, until: Long = Long.MAX_VALUE): BigDecimal =
        moves.orEmpty().fold(BigDecimal.ZERO) { s, m -> if (m.time <= until) s + m.amount else s }

    /** Eigener Bestand je Coin für die Karte ([outside] über alle eigenen Transaktionen). */
    fun ledger(addresses: List<WatchedAddress>, real: List<PortfolioTrade>): Map<String, BigDecimal> {
        val net = netMoves(real)
        val recorded = recordedMoves(addresses, real)
        return (net.keys + recorded.keys).associateWith { outside(sum(net[it]), sum(recorded[it])) }
    }

    /**
     * Gezählter Adress-Teil je Coin für die Karte (Reihenfolge von [AddressBalance.holdings], nur > 0): Guthaben ohne das
     * schon Gebuchte ([AddressCostLots.accounted]), davon [counted] gegen [ledger].
     */
    fun parts(addresses: List<WatchedAddress>, real: List<PortfolioTrade>): List<Pair<String, BigDecimal>> {
        val held = AddressBalance.holdings(addresses)
        if (held.isEmpty()) return emptyList()
        val accounted = AddressCostLots.accounted(addresses, real)
        val ledger = ledger(addresses, real)
        return held.mapNotNull { (coin, text) ->
            val total = PortfolioDecimal.parse(text) ?: return@mapNotNull null
            val part = counted(total - (accounted[coin] ?: BigDecimal.ZERO), ledger[coin] ?: BigDecimal.ZERO)
            if (part.signum() > 0) coin to part else null
        }
    }

    /**
     * Stufen des eigenen Bestands ([outside]) aus [net] und [recorded]; Zeiten auf [last] (letzte Beobachtung des Coins)
     * begrenzt — das zuletzt geladene Guthaben steht gegen alle eigenen Transaktionen wie auf der Karte.
     */
    fun ledgerSteps(net: List<AddressMove>, recorded: List<AddressMove>, last: Long): List<AddressObservation> {
        val n = net.map { it.copy(time = minOf(it.time, last)) }
        val r = recorded.map { it.copy(time = minOf(it.time, last)) }
        return (n + r).map { it.time }.distinct().sorted().map { t -> AddressObservation(t, outside(sum(n, t), sum(r, t))) }
    }

    /**
     * Verlauf eines Coins: Stufen der Adress-Teile [parts] (je Adresse) zu jeder ihrer Zeiten summiert, davon [counted]
     * gegen den eigenen Bestand [ledger] zu dieser Zeit ([ledgerSteps]); aufsteigend.
     */
    fun steps(parts: List<List<AddressObservation>>, ledger: List<AddressObservation>): List<AddressObservation> {
        val clean = parts.map { p -> p.filter { it.balance.signum() >= 0 }.sortedBy { it.time } }
        return clean.flatten().map { it.time }.distinct().sorted().map { t ->
            val held = clean.fold(BigDecimal.ZERO) { s, list -> s + (list.lastOrNull { it.time <= t }?.balance ?: BigDecimal.ZERO) }
            AddressObservation(t, counted(held, AddressBalanceHistory.balanceAt(ledger, t)))
        }
    }
}
