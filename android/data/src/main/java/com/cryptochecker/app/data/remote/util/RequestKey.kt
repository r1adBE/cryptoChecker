package com.cryptochecker.app.data.remote.util

/**
 * Vergleichsschlüssel einer Adresse: dieselbe Adresse ohne den Abfrageparameter `timestamp=<Ziffern>`. BingX stempelt
 * jede Adresse schon beim Bilden mit der aktuellen Zeit (ms) — ohne diesen Schlüssel wären zwei sonst gleiche
 * Anfragen nie gleich: gleichzeitige gleiche Abrufe liefen doppelt ([InFlightRequests]), und nach einer gescheiterten
 * Sammelabfrage folgte dieselbe Anfrage noch einmal als «ungefiltert» (MarketRemoteDataSource.loadBulkCall).
 * Gesendet wird immer die echte Adresse mit Stempel. Gespiegelt in `RequestKey.swift`, Fälle in `request_key.json`.
 */
object RequestKey {

    fun of(url: String): String {
        val query = url.indexOf('?')
        if (query < 0) return url
        val params = url.substring(query + 1).split('&')
        val kept = params.filterNot(::isStamp)
        if (kept.size == params.size) return url
        return if (kept.isEmpty()) url.substring(0, query) else url.substring(0, query + 1) + kept.joinToString("&")
    }

    /** `timestamp=` gefolgt von mindestens einer Ziffer und sonst nichts. */
    private fun isStamp(param: String): Boolean {
        if (!param.startsWith(STAMP)) return false
        val value = param.substring(STAMP.length)
        return value.isNotEmpty() && value.all { it in '0'..'9' }
    }

    private const val STAMP = "timestamp="
}
