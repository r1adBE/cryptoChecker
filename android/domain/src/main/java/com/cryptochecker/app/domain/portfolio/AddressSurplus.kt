// «Nur Mehrbestand, gleiche Wallet»: beobachtete Adressen zählen nur, was sie MEHR halten als die eigenen Transaktionen
// IHRER Wallet — Spiegel: `AddressSurplus.swift`.
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/**
 * EINE Regel für Adressen, die zum Portfolio zählen ([WatchedAddress.countInPortfolio]): je Wallet und Coin zählt das
 * Guthaben nur, soweit es den eigenen Bestand IN DIESER WALLET übersteigt — gezählt = max(0, Adress-Teil − eigener
 * Bestand der Wallet) ([counted]). Bestände in anderen Wallets (Börse, andere Adresse) ziehen nie etwas ab.
 *  - Wallet = [AddressCostLots.walletName] (Bezeichnung, sonst gekürzte Adresse), verglichen mit [PortfolioWallets.key].
 *  - Adress-Teil der Wallet = Guthaben ihrer zählenden Adressen zusammen ohne das schon Gebuchte (Eröffnungsposten und
 *    erfasste Bewegungen der Wallet bis zum Abruf: [AddressCostLots.accountedByWallet], im Verlauf [AddressBooked.held]).
 *  - Eigener Bestand der Wallet = erfasste Bewegungen dieser Wallet ([AddressCostLots.recordedMove]) ohne die, die das
 *    Guthaben schon enthält (bis zum Abruf, [WalletMoves.fetched]); nie unter 0 ([outside]) — nie doppelt abgezogen. Was
 *    davon das Guthaben der verrechnenden Adresse übersteigt ([WalletMoves.owner]), zieht von den übrigen Adressen der
 *    Wallet ab ([ledger]; im Verlauf negative Stufen in [steps]).
 *  - Mehrere zählende Adressen derselben Wallet: erst summiert, dann der eigene Bestand der Wallet EINMAL abgezogen;
 *    verschiedene Wallets rechnen unabhängig.
 * Ohne Posten zeigt eine Wallet so max(eigene Einträge der Wallet, Guthaben). Beispiele: Börse 1 BTC + Cold-Wallet-Adresse
 * 1 BTC (keine eigenen Einträge in ihrer Wallet) → 2; eigene 1.00 in der Adress-Wallet erfasst + Adresse 1.06 → 1.06;
 * Umbuchung Börse → Adress-Wallet 1 erfasst + Adresse 1 → 1; Airdrop nur auf der Adresse → ganz. Die automatische Regel
 * «Nur anzeigen» (alle Coins innerhalb 5 %, [WatchedAddressRules]) bleibt davor.
 * Karte, Zeilen, Detail, Alarme, Widgets und Uhr ([parts] über [AddressBalance.trades]) sowie Zeiträume, Wertverlauf,
 * Stichtag und CH-Vermögenswert ([steps] über [AddressBalanceHistory.trades], Stufen am Stichtag) rechnen damit.
 */
object AddressSurplus {

    /** Gezählter Adress-Teil: max(0, [address] − [ledger]); ein negativer eigener Bestand zählt als 0. */
    fun counted(address: BigDecimal, ledger: BigDecimal): BigDecimal =
        (address - ledger.max(BigDecimal.ZERO)).max(BigDecimal.ZERO)

    /** Eigener Bestand der Wallet [net] ohne die schon im Guthaben enthaltenen Bewegungen [recorded] (Abgänge ziehen nichts ab), ≥ 0. */
    fun outside(net: BigDecimal, recorded: BigDecimal): BigDecimal =
        (net - recorded.max(BigDecimal.ZERO)).max(BigDecimal.ZERO)

    /**
     * Erfasste Bewegungen EINER Adress-Wallet je Coin ([AddressCostLots.recordedMove]): [all] = alle eigenen Transaktionen
     * der Wallet, [fetched] = davon die bis zum Abruf der verrechnenden Adresse [owner] ([AddressCostLots.movesOwners],
     * [AddressCostLots.fetched]) — sie stecken schon im Guthaben.
     */
    data class WalletMoves(
        val owner: WatchedAddress,
        val all: Map<String, List<AddressMove>>,
        val fetched: Map<String, List<AddressMove>>,
    )

    /** Je Wallet ([PortfolioWallets.key]) einer zählenden Adresse ihre erfassten Bewegungen ([WalletMoves]). */
    fun walletMoves(addresses: List<WatchedAddress>, real: List<PortfolioTrade>): Map<String, WalletMoves> {
        val own = real.filter { !AddressBalance.isAddressTrade(it.id) }
        if (own.isEmpty()) return emptyMap()
        val owners = AddressCostLots.movesOwners(addresses)
        val coins = PortfolioLedger.coins(own)
        val out = LinkedHashMap<String, WalletMoves>()
        for ((a, _) in AddressCostLots.counted(addresses)) {
            val wallet = AddressCostLots.walletName(a)
            val key = PortfolioWallets.key(wallet)
            if (owners[key] != a.id) continue
            val all = LinkedHashMap<String, MutableList<AddressMove>>()
            val inBalance = LinkedHashMap<String, MutableList<AddressMove>>()
            for (coin in coins) for (t in own) {
                val m = AddressCostLots.recordedMove(coin, wallet, t)
                if (m.signum() == 0) continue
                all.getOrPut(coin) { ArrayList() } += AddressMove(t.time, m)
                // Bis zum Abruf wie [AddressCostLots.fetched]
                if (a.lastUpdated <= 0 || t.time <= a.lastUpdated) inBalance.getOrPut(coin) { ArrayList() } += AddressMove(t.time, m)
            }
            out[key] = WalletMoves(a, all, inBalance)
        }
        return out
    }

    private fun sum(moves: List<AddressMove>?, until: Long = Long.MAX_VALUE): BigDecimal =
        moves.orEmpty().fold(BigDecimal.ZERO) { s, m -> if (m.time <= until) s + m.amount else s }

    /**
     * Eigener Bestand der Wallet [moves] für [coin] auf der Karte: [outside] über alle ihre Bewegungen, dazu die schon im
     * Guthaben erwarteten Bewegungen, die das Guthaben der verrechnenden Adresse übersteigen (sie kürzte ihr Adress-Teil
     * nur bis 0, [AddressCostLots.cover]); ohne = 0.
     */
    fun ledger(moves: WalletMoves?, coin: String): BigDecimal {
        if (moves == null) return BigDecimal.ZERO
        val recorded = sum(moves.fetched[coin])
        val observed = AddressCostLots.balances(moves.owner)[coin]?.max(BigDecimal.ZERO) ?: BigDecimal.ZERO
        return outside(sum(moves.all[coin]), recorded) + (recorded - observed).max(BigDecimal.ZERO)
    }

    /**
     * Adress-Teil je Wallet ([PortfolioWallets.key]) und Coin: Guthaben ihrer zählenden Adressen ([AddressCostLots.counted])
     * ohne das schon Gebuchte ([AddressCostLots.accountedByWallet]).
     */
    fun walletParts(addresses: List<WatchedAddress>, real: List<PortfolioTrade>): Map<String, Map<String, BigDecimal>> {
        val out = LinkedHashMap<String, LinkedHashMap<String, BigDecimal>>()
        for ((a, balances) in AddressCostLots.counted(addresses)) {
            val w = out.getOrPut(PortfolioWallets.key(AddressCostLots.walletName(a))) { LinkedHashMap() }
            for ((coin, v) in balances) w[coin] = (w[coin] ?: BigDecimal.ZERO) + v
        }
        for ((key, coins) in AddressCostLots.accountedByWallet(addresses, real)) {
            val w = out.getOrPut(key) { LinkedHashMap() }
            for ((coin, v) in coins) w[coin] = (w[coin] ?: BigDecimal.ZERO) - v
        }
        return out
    }

    /**
     * Gezählter Adress-Teil je Coin für die Karte (Reihenfolge von [AddressBalance.holdings], nur > 0): je Wallet
     * [counted] des Adress-Teils ([walletParts]) gegen den eigenen Bestand dieser Wallet ([ledger]), dann summiert.
     */
    fun parts(addresses: List<WatchedAddress>, real: List<PortfolioTrade>): List<Pair<String, BigDecimal>> {
        val held = AddressBalance.holdings(addresses)
        if (held.isEmpty()) return emptyList()
        val wallets = walletParts(addresses, real)
        val moves = walletMoves(addresses, real)
        return held.mapNotNull { (coin, text) ->
            if (PortfolioDecimal.parse(text) == null) return@mapNotNull null
            val part = wallets.entries.fold(BigDecimal.ZERO) { s, (key, coins) ->
                val p = coins[coin] ?: return@fold s
                s + counted(p, ledger(moves[key], coin))
            }
            if (part.signum() > 0) coin to part else null
        }
    }

    /**
     * Stufen des eigenen Bestands einer Wallet ([outside]) aus [net] und [recorded]; Zeiten auf [last] (letzte Beobachtung
     * des Coins in dieser Wallet) begrenzt — das zuletzt geladene Guthaben steht gegen alle Buchungen der Wallet wie auf der
     * Karte. [absorbed] = die verrechnende Adresse hat eine Reihe dieses Coins (dort gebucht, ein Übersteigen zeigt ihre
     * negative Stufe, [steps]); sonst zählt [recorded] (> 0) ganz dazu — wie [ledger] ohne Guthaben.
     */
    fun ledgerSteps(net: List<AddressMove>, recorded: List<AddressMove>, last: Long, absorbed: Boolean = true): List<AddressObservation> {
        val n = net.map { it.copy(time = minOf(it.time, last)) }
        val r = recorded.map { it.copy(time = minOf(it.time, last)) }
        return (n + r).map { it.time }.distinct().sorted().map { t ->
            val inBalance = sum(r, t)
            AddressObservation(t, outside(sum(n, t), inBalance) + if (absorbed) BigDecimal.ZERO else inBalance.max(BigDecimal.ZERO))
        }
    }

    /**
     * Verlauf eines Coins in EINER Wallet: Stufen der Adress-Teile [parts] (je Adresse der Wallet, ohne Untergrenze:
     * [AddressBooked.held] mit `floor = false`) zu jeder ihrer Zeiten — die positiven summiert, davon [counted] gegen den
     * eigenen Bestand der Wallet [ledger] zu dieser Zeit ([ledgerSteps]) plus die negativen (Buchungen über dem Guthaben
     * ihrer Adresse); aufsteigend.
     */
    fun steps(parts: List<List<AddressObservation>>, ledger: List<AddressObservation>): List<AddressObservation> {
        val sorted = parts.map { p -> p.sortedBy { it.time } }
        return sorted.flatten().map { it.time }.distinct().sorted().map { t ->
            val at = sorted.map { list -> list.lastOrNull { it.time <= t }?.balance ?: BigDecimal.ZERO }
            val held = at.fold(BigDecimal.ZERO) { s, v -> s + v.max(BigDecimal.ZERO) }
            val over = at.fold(BigDecimal.ZERO) { s, v -> s + v.negate().max(BigDecimal.ZERO) }
            AddressObservation(t, counted(held, AddressBalanceHistory.balanceAt(ledger, t) + over))
        }
    }
}
