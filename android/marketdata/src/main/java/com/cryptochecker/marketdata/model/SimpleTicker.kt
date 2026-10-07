package com.cryptochecker.marketdata.model

import com.cryptochecker.marketdata.model.Ticker.Companion.NO_DATA

/**
 * Einfache Ticker-Implementierung für die Massenabfrage, bei der die
 * Börsenklasse selbst Instanzen anlegen muss.
 */
class SimpleTicker : Ticker {
    override var bid: Double = NO_DATA_DOUBLE
    override var ask: Double = NO_DATA_DOUBLE
    override var vol: Double = NO_DATA_DOUBLE
    override var volQuote: Double = NO_DATA_DOUBLE
    override var high: Double = NO_DATA_DOUBLE
    override var low: Double = NO_DATA_DOUBLE
    override var last: Double = NO_DATA_DOUBLE
    override var timestamp: Long = NO_DATA.toLong()
    override var change24hPercent: Double? = null

    private companion object {
        const val NO_DATA_DOUBLE: Double = NO_DATA.toDouble()
    }
}
