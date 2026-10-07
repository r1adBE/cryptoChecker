package com.cryptochecker.marketdata.model

interface Ticker {
	var bid: Double
	var ask: Double
	var vol: Double
    var volQuote: Double
	var high: Double
	var low: Double
	var last: Double
	var timestamp: Long

	/**
	 * Gleitende 24-h-Veränderung in Prozent (1.5 = +1,5 %), wie sie die Börse
	 * im Ticker liefert; null, wenn die Börse keinen gleitenden 24-h-Wert hat
	 * (dann rechnet die App mit Stundenkerzen). Tageswerte seit Mitternacht
	 * (UTC, KST, …) gehören nicht hierher. Siehe DEVELOPMENT.md, «24 h change».
	 */
	var change24hPercent: Double?

	companion object {
		const val NO_DATA = -1
	}
}