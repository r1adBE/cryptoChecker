package com.cryptochecker.app.domain.watch

import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType

/**
 * Gruppe für ein neues Paar, wenn beim Hinzufügen keine gewählt ist: Futures auf Aktien,
 * Rohstoffe, Devisen und Pre-IPO kommen nach «TradFi», Laufzeit-Futures (Quartal u. a.) nach
 * «QTLY» — so stehen sie nicht zwischen den Coins. Eine selbst gewählte Gruppe geht immer vor;
 * bestehende Einträge bleiben, wie sie sind. Namen in allen Sprachen gleich (wie ein Kürzel).
 * Wie `AutoGroup` (iOS).
 */
object AutoGroup {
    const val TRADFI = "TradFi"
    const val DATED = "QTLY"

    /** Laufzeit-Kontrakte (mit Verfall); Perpetuals und Spot nicht. */
    private val DATED_CONTRACTS = setOf(
        FuturesContractType.WEEKLY,
        FuturesContractType.BIWEEKLY,
        FuturesContractType.MONTHLY,
        FuturesContractType.BIMONTHLY,
        FuturesContractType.QUARTERLY,
        FuturesContractType.BIQUARTERLY,
    )

    /** null: keine eigene Gruppe (Krypto-Spot und -Perpetuals). */
    fun forPair(pair: CurrencyPairInfo): String? = when {
        pair.tradFi -> TRADFI
        pair.contractType in DATED_CONTRACTS -> DATED
        else -> null
    }

    /** Gewählte Gruppe, sonst die passende von [forPair]. */
    fun resolve(chosen: String?, pair: CurrencyPairInfo): String? =
        chosen?.trim()?.takeIf { it.isNotEmpty() } ?: forPair(pair)
}
