package com.cryptochecker.app.data

import com.cryptochecker.marketdata.model.Ticker

/** Veränderbarer Kurs, den die Börsen beim Lesen füllen; alle Zahlen anfangs «keine Angabe». */
internal class TickerImpl : Ticker {
    override var last: Double = EMPTY
    override var bid: Double = EMPTY
    override var ask: Double = EMPTY
    override var high: Double = EMPTY
    override var low: Double = EMPTY
    override var vol: Double = EMPTY
    override var volQuote: Double = EMPTY
    override var timestamp: Long = Ticker.MISSING.toLong()
    override var exchangeTime: Long? = null
    override var change24hPercent: Double? = null

    private companion object {
        val EMPTY: Double = Ticker.MISSING
    }
}
