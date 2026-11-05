// Hinweis auf eine nicht erfasste Umbuchung zu einer beobachteten Adresse — Spiegel: `AddressWithdrawalMatch.swift`.
package com.cryptochecker.app.domain.portfolio

/**
 * Mit «Nur gleiche Wallet» ([AddressSurplus]) zählt eine Adresse neben Beständen anderer Wallets ganz. Wer die Abhebung
 * von der Börse auf die Adresse als Umbuchung in eine ANDERE Wallet erfasst (z. B. «Ledger» statt der Bezeichnung der
 * Adresse), hat dieselben Coins zweimal: in jener Wallet und auf der Adresse. Hinweis an Adresszeile und Coin-Karte,
 * Summen unverändert; einzige Aktion «Ziel ändern» ([retarget]) setzt das Ziel der genannten Umbuchung auf die
 * Adress-Wallet (danach kein Treffer mehr). Eine erfasste Auszahlung ([PortfolioTxType.WITHDRAWAL]) nimmt die
 * Coins aus dem Portfolio — sie zählen dann nur einmal (über die Adresse), also kein Hinweis.
 *
 * Treffer = eigene Umbuchung ([PortfolioTxType.TRANSFER]) im Coin der Adresse aus einer ANDEREN Wallet in eine ANDERE
 * Wallet als die Adress-Wallet ([AddressCostLots.walletName], [PortfolioWallets.key]), deren Menge oder Menge nach Coin-Gebühr dem
 * Guthaben oder einem beobachteten Zugang ([WatchedAddressRules.matchTargets]) auf ± 1 % entspricht (gleiche Toleranz
 * wie [WatchedAddressRules.transferMatches]) — ausser eine erfasste Umbuchung bzw. Einzahlung IN die Adress-Wallet
 * entspricht derselben Menge (dann ist sie erfasst). Nur zählende Adressen.
 */
object AddressWithdrawalMatch {

    /** Treffer der Adresse [a] in den eigenen Transaktionen [trades], zeitlich aufsteigend, je Transaktion einmal. */
    fun matches(a: WatchedAddress, trades: List<PortfolioTrade>): List<PortfolioTrade> {
        if (!a.countInPortfolio) return emptyList()
        val targets = WatchedAddressRules.matchTargets(a)
        if (targets.isEmpty()) return emptyList()
        val own = trades.filter { !AddressBalance.isAddressTrade(it.id) }
        val wallet = AddressCostLots.walletName(a)
        val key = PortfolioWallets.key(wallet)
        fun near(t: PortfolioTrade, goal: java.math.BigDecimal) =
            WatchedAddressRules.nearlyEqual(t.amount, goal) || WatchedAddressRules.nearlyEqual(t.received, goal)
        return own.filter { t ->
            if (t.type != PortfolioTxType.TRANSFER) return@filter false
            if (PortfolioWallets.key(t.transferFrom) == key || PortfolioWallets.key(t.transferTo) == key) return@filter false
            val coin = PortfolioCalculator.normalizeCoin(t.coin)
            val goals = targets[coin].orEmpty().filter { near(t, it) }
            // Gleiche Menge schon als Umbuchung/Einzahlung in die Adress-Wallet erfasst: kein Hinweis
            goals.any { goal ->
                own.none { r ->
                    PortfolioCalculator.normalizeCoin(r.coin) == coin && AddressCostLots.recordedMove(coin, wallet, r).signum() > 0 &&
                        near(r, goal)
                }
            }
        }.sortedBy { it.time }
    }

    /** Ziel-Wallet des ersten Treffers (zeitlich) für den Hinweis «… Umbuchung nach «Ledger» …»; ohne Treffer/Namen null. */
    fun targetWallet(matches: List<PortfolioTrade>): String? = named(matches)?.transferTo?.trim()

    /** Die Umbuchung, die der Hinweis nennt ([targetWallet]): erster Treffer (zeitlich) mit Ziel-Namen; sonst null. */
    fun named(matches: List<PortfolioTrade>): PortfolioTrade? =
        matches.firstOrNull { t -> !t.transferTo?.trim().isNullOrEmpty() }

    /** «Ziel ändern»: Umbuchung [trade] (die der Hinweis nennt) von [from] in die Adress-Wallet [to]. */
    data class Retarget(val trade: PortfolioTrade, val from: String, val to: String)

    /** «Ziel ändern» an der Adresszeile von [a]: genannte Umbuchung ([named]) → Adress-Wallet; ohne Hinweis null. */
    fun retarget(a: WatchedAddress, trades: List<PortfolioTrade>): Retarget? =
        named(matches(a, trades))?.let { t -> Retarget(t, t.transferTo.orEmpty().trim(), AddressCostLots.walletName(a)) }

    /**
     * «Ziel ändern» an der Coin-Karte: genannte Umbuchung aus [matchesFor] → Wallet der ersten Adresse (in Listenfolge),
     * zu der sie passt; ohne Hinweis null.
     */
    fun retargetFor(coin: String, addresses: List<WatchedAddress>, trades: List<PortfolioTrade>): Retarget? {
        val t = named(matchesFor(coin, addresses, trades)) ?: return null
        val a = addresses.firstOrNull { x -> matches(x, trades).any { it.id == t.id } } ?: return null
        return Retarget(t, t.transferTo.orEmpty().trim(), AddressCostLots.walletName(a))
    }

    /** Coin-Karte: Treffer ([matches]) aller zählenden Adressen im Coin [coin], je Transaktion einmal, zeitlich aufsteigend. */
    fun matchesFor(coin: String, addresses: List<WatchedAddress>, trades: List<PortfolioTrade>): List<PortfolioTrade> {
        val c = PortfolioCalculator.normalizeCoin(coin)
        return addresses.flatMap { matches(it, trades) }
            .filter { PortfolioCalculator.normalizeCoin(it.coin) == c }
            .distinctBy { it.id }
            .sortedBy { it.time }
    }
}
