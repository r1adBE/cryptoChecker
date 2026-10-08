package com.cryptochecker.app.domain.convert

/**
 * «1 CHF = 1’234 Sats» unter dem Kurs eines Bitcoin-Paars (Aktionsblatt): wie viele Satoshi
 * (1 BTC = 100 Mio. Sats) eine Einheit der Umrechnungswährung kauft. Der Kurs des Paars wird mit
 * dem bestehenden Umrechnungsfaktor (Quote → Umrechnungswährung) umgerechnet. Swift-Spiegel: Sats.swift.
 */
object Sats {

    const val PER_BTC = 100_000_000.0

    /** Nur Paare mit Basis BTC (nicht WBTC o. Ä.). */
    fun isBitcoin(baseAsset: String?): Boolean = baseAsset?.trim()?.uppercase() == "BTC"

    /**
     * Sats je Einheit der Zielwährung: 1e8 / (Kurs in der Quote × Faktor Quote → Ziel);
     * null ohne gültigen Kurs oder Faktor.
     */
    fun perUnit(price: Double?, rate: Double?): Double? {
        val p = price?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val r = rate?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val inTarget = p * r
        if (!inTarget.isFinite() || inTarget <= 0.0) return null
        val sats = PER_BTC / inTarget
        return sats.takeIf { it.isFinite() && it > 0.0 }
    }

    /** Nachkommastellen der Anzeige: ab 100 Sats ganze Zahlen, ab 1 eine Stelle, sonst drei. */
    fun decimals(sats: Double): Int = when {
        sats >= 100.0 -> 0
        sats >= 1.0 -> 1
        else -> 3
    }
}
