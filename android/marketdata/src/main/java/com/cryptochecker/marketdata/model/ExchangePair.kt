package com.cryptochecker.marketdata.model

/**
 * Handelspaar einer Börse: Basis, Gegenwährung, Kennung der Börse und Vertragsart.
 *
 * [tradFi] markiert Kontrakte auf Dinge ausserhalb von Krypto — Aktien, Rohstoffe wie Gold, Devisen oder Firmen
 * vor dem Börsengang (Binance «TradFi-Perpetuals»). Ob die Auswahl sie zeigt, regelt «Token & TradFi» unter
 * Einstellungen › Merkliste; abgefragt werden sie wie jedes Perpetual (Kurs, Live, Funding, Open Interest).
 *
 * Sortiert wird nach Basis, dann Gegenwährung (beide ohne Gross-/Kleinschreibung), dann Vertragsart.
 */
open class ExchangePair(
    val base: String,
    val quote: String,
    val pairId: String?,
    val contractType: FuturesContractType = FuturesContractType.NONE,
    val tradFi: Boolean = false,
) : Comparable<ExchangePair> {

    override fun compareTo(other: ExchangePair): Int =
        base.compareTo(other.base, ignoreCase = true).takeIf { it != 0 }
            ?: quote.compareTo(other.quote, ignoreCase = true).takeIf { it != 0 }
            ?: contractType.compareTo(other.contractType)

    /** Die Kennung der Börse, ohne sie «Basis:Gegen» mit angehängter Kurzform der Vertragsart (z. B. «:Perp»). */
    override fun toString(): String {
        pairId?.let { return it }
        val suffix = FuturesContractType.getShortName(contractType)?.let { ":$it" }.orEmpty()
        return "$base:$quote$suffix"
    }
}
