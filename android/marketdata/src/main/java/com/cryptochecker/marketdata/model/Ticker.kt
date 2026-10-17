package com.cryptochecker.marketdata.model

/**
 * Kursdaten eines Paars. Fehlende Zahlen stehen auf [MISSING].
 */
interface Ticker {
    /** Letzter Kurs (bei [Market.priceIsMid] der Mittelkurs). */
    var last: Double
    var bid: Double
    var ask: Double
    var high: Double
    var low: Double

    /** Volumen in der Basiswährung bzw. in der Gegenwährung. */
    var vol: Double
    var volQuote: Double

    /** Nach [Market.readTicker]: Moment des Abrufs in ms. */
    var timestamp: Long

    /**
     * Zeit laut Börse in ms; null, wenn sie keine mitschickt (Kraken, Gate.io, Hyperliquid …). Bleibt der Kurs
     * lange gleich, zeigt die Merkliste damit «Kurs unverändert seit …» (`PriceFreshness`).
     */
    var exchangeTime: Long?

    /**
     * Gleitende 24-h-Veränderung in Prozent (1.5 = +1,5 %), so wie die Börse sie im Ticker meldet. null ohne
     * gleitenden Wert — die App rechnet dann mit Stundenkerzen. Werte seit Mitternacht (UTC, KST …) gehören nicht
     * hierher; Einzelheiten in DEVELOPMENT.md, «24 h change».
     */
    var change24hPercent: Double?

    companion object {
        /** Platzhalter für «nicht geliefert» in den Zahlenfeldern (alle Kurswerte sind sonst positiv). Wie iOS `Ticker.missing`. */
        const val MISSING = -1.0
    }
}
