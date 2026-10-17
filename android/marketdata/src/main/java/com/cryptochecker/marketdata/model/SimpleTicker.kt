package com.cryptochecker.marketdata.model

/** Ticker ohne Speicherbezug, den die Börsenklassen selbst anlegen (Sammelabfrage, Tests). Alles startet leer. */
class SimpleTicker : Ticker {
    override var last = Ticker.MISSING
    override var bid = Ticker.MISSING
    override var ask = Ticker.MISSING
    override var high = Ticker.MISSING
    override var low = Ticker.MISSING
    override var vol = Ticker.MISSING
    override var volQuote = Ticker.MISSING
    override var timestamp = Ticker.MISSING.toLong()
    override var exchangeTime: Long? = null
    override var change24hPercent: Double? = null
}
