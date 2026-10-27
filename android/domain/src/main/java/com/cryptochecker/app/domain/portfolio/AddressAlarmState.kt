package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/**
 * Was die beobachteten Adressen für die Portfolio-Alarme bedeuten (reines Kotlin, gleich wie `AddressAlarmState.swift`).
 *
 * - [pending]: zählende Adressen, deren Guthaben noch nie geladen wurde und die noch eine Chance haben ([isPending]):
 *   sie fehlen im Gesamtwert — Portfolio-Alarme prüfen dann nicht.
 * - [failing]: zählende Adressen, die nie geladen haben und seit über [PENDING_MILLIS] scheitern (Name: Bezeichnung oder
 *   gekürzte Adresse). Sie sperren die Alarme nicht ohne Ende: die Alarme rechnen ohne sie, der
 *   Hinweis in der Alarm-Übersicht nennt sie.
 * - [staleTokenAddresses] / [staleTokenAmounts]: zählende Token-Guthaben, deren Wert der letzte Abruf nicht bestätigt hat
 *   ([WatchedAddress.staleTokens], z. B. bekannter TON-Jetton fehlt auf einer vollen Seite) — sie gelten für die Alarme wie
 *   ein alter Kurs ([com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic.staleMaterial]).
 *
 * Gleiche Regeln wie [AddressBalance.holdings]: einzelne Bitcoin-Adressen in einem beobachteten xpub zählen über den xpub,
 * gleiche Adressen einmal.
 */
data class AddressAlarmState(
    val pending: Int = 0,
    val failing: List<String> = emptyList(),
    val staleTokenAddresses: List<String> = emptyList(),
    /** Menge je Coin der nicht bestätigten Token-Guthaben (Coin wie [TokenHolding.coin]). */
    val staleTokenAmounts: Map<String, BigDecimal> = emptyMap(),
) {
    /**
     * Wert der nicht bestätigten Token-Guthaben in USDT zu den Kursen [prices] (Coin → USDT-Kurs); Coins ohne Kurs zählen
     * nicht (sie fehlen schon als «ohne Kurs», [PortfolioSnapshot.missingPrices]).
     */
    fun staleTokenValue(prices: Map<String, Double>): Double {
        if (staleTokenAmounts.isEmpty()) return 0.0
        val byCoin = prices.entries.associate { PortfolioCalculator.normalizeCoin(it.key) to it.value }
        var sum = 0.0
        for ((coin, amount) in staleTokenAmounts) {
            val price = byCoin[PortfolioCalculator.normalizeCoin(coin)]?.takeIf { it.isFinite() && it > 0.0 } ?: continue
            sum += amount.toDouble() * price
        }
        return sum
    }

    companion object {
        /**
         * So lange nach dem Hinzufügen (oder Einspielen auf diesem Gerät, [WatchedAddress.pendingSince]) gilt eine nie
         * geladene Adresse mit Fehler noch als «lädt noch».
         */
        const val PENDING_MILLIS = 24 * 3_600_000L

        /**
         * Noch nie geladen ([WatchedAddress.lastBalance] fehlt oder ungültig) und entweder noch kein Fehler oder vor weniger
         * als [PENDING_MILLIS] hinzugefügt (Uhr zurückgestellt: zählt als frisch). Danach gilt sie als fehlgeschlagen.
         * Massgebend ist der spätere von [WatchedAddress.addedAt] und [WatchedAddress.pendingSince]: eingespielte Adressen
         * behalten ihr altes `addedAt` (auch 0) und bekämen sonst keine Chance zu laden.
         */
        fun isPending(a: WatchedAddress, now: Long): Boolean =
            !loaded(a) && (a.lastError == null || now - maxOf(a.addedAt, a.pendingSince) < PENDING_MILLIS)

        private fun loaded(a: WatchedAddress): Boolean = PortfolioDecimal.parse(a.lastBalance)?.let { it.signum() >= 0 } == true

        private fun name(a: WatchedAddress): String = a.label ?: WatchedAddressRules.shortAddress(a.address)

        private fun same(x: WatchedAddress, y: WatchedAddress): Boolean =
            x.chain == y.chain && WatchedAddressRules.sameAddress(x.chain, x.address, y.address)

        fun of(addresses: List<WatchedAddress>, now: Long): AddressAlarmState {
            val counted = addresses.filter { it.countInPortfolio }
            val covered = WatchedAddressRules.coveredByXpub(counted)
            val loaded = counted.filter { loaded(it) }
            var pending = 0
            val failing = ArrayList<String>()
            val staleNames = ArrayList<String>()
            val staleAmounts = LinkedHashMap<String, BigDecimal>()
            counted.forEachIndexed { i, a ->
                // Gleiche Adresse doppelt: nur die erste zählt (wie [AddressBalance.holdings])
                if (counted.subList(0, i).any { same(it, a) }) return@forEachIndexed
                if (a.staleTokens.isNotEmpty()) {
                    val kept = a.tokenHoldings().filter { it.key in a.staleTokens }
                    if (kept.isNotEmpty()) staleNames += name(a)
                    for (h in kept) {
                        val value = PortfolioDecimal.parse(h.amount) ?: continue
                        staleAmounts[h.coin] = (staleAmounts[h.coin] ?: BigDecimal.ZERO) + value
                    }
                }
                if (a.chain == AddressChain.BTC && !a.isXpub && a.address in covered) return@forEachIndexed
                if (loaded.any { same(it, a) }) return@forEachIndexed
                if (isPending(a, now)) pending++ else failing += name(a)
            }
            return AddressAlarmState(pending, failing, staleNames, staleAmounts)
        }
    }
}
