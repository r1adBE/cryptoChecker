package com.cryptochecker.marketdata.model

/** Das Paar, dessen Kurs gerade abgefragt wird, mit Kürzeln in Kleinschrift für Adressen, die das verlangen. */
class PairQuery(
    base: String,
    quote: String,
    pairId: String?,
    contractType: FuturesContractType,
) : ExchangePair(base, quote, pairId, contractType) {

    val baseLower: String get() = base.lowercase()

    val quoteLower: String get() = quote.lowercase()
}
