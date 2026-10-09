package com.cryptochecker.marketdata.model

/**
 * Ein handelbares Paar einer Börse.
 *
 * [tradFi]: Kontrakt auf etwas, das kein Krypto-Token ist — Aktie, Rohstoff (z. B. Gold),
 * Devisen oder Firma vor dem Börsengang (Binance «TradFi-Perpetuals»). Die App zeigt solche
 * Paare nur mit dem Schalter unter Einstellungen › Merkliste. Kursabfrage, Live-Kurse,
 * Funding und Open Interest laufen wie bei jedem Perpetual.
 */
open class CurrencyPairInfo(
    val currencyBase: String,
    val currencyCounter: String,
    val currencyPairId: String?,
    val contractType: FuturesContractType = FuturesContractType.NONE,
    val tradFi: Boolean = false,
) : Comparable<CurrencyPairInfo> {

    @Suppress("unused") // Used by Gson
    private constructor() : this("", "", null)

    @Throws(NullPointerException::class)
    override fun compareTo(other: CurrencyPairInfo): Int {
        var compBase = currencyBase.compareTo(other.currencyBase, ignoreCase = true)
        if (compBase != 0) return compBase

        compBase = currencyCounter.compareTo(
            other.currencyCounter,
            ignoreCase = true
        )
        if (compBase != 0) return compBase

        return contractType.compareTo(other.contractType)
    }

    override fun toString(): String {
        fun tryGetContactName(): String {
            val resultName = FuturesContractType.getShortName(contractType)
            return if(resultName == null) "" else ":$resultName"
        }

        return currencyPairId ?: ("$currencyBase:$currencyCounter" + tryGetContactName())
    }
}