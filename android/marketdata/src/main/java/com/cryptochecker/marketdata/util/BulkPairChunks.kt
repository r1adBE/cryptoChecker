package com.cryptochecker.marketdata.util

/**
 * Teilt die beobachteten Paar-Kennungen für eine gefilterte Massenabfrage
 * («…?pair=A,B,C») so auf, dass jede URL kürzer als [MAX_URL_LENGTH] Zeichen
 * bleibt. Reine Logik ohne Netz; Spiegel in Swift: `enum BulkPairChunks`.
 */
object BulkPairChunks {
    /** Viele Server und Proxys lehnen längere URLs ab. */
    const val MAX_URL_LENGTH = 2000

    /** Nur Zeichen, die in einer Query unverändert stehen dürfen (Kraken «XXBTZUSD», Bitfinex «tDOGE:USD»). */
    private val SAFE_ID = Regex("[A-Za-z0-9._:~-]+")

    /**
     * Sortierte, doppelfreie Gruppen von Kennungen, je Gruppe eine Anfrage.
     * null: nicht filterbar (keine Kennungen oder eine mit Zeichen, die
     * kodiert werden müssten) — dann gilt die ungefilterte Abfrage.
     */
    fun chunks(prefix: String, pairIds: Collection<String>, maxUrlLength: Int = MAX_URL_LENGTH): List<List<String>>? {
        val ids = pairIds.filter { it.isNotEmpty() }.distinct().sorted()
        if (ids.isEmpty() || ids.any { !SAFE_ID.matches(it) }) return null

        val result = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var length = prefix.length
        for (id in ids) {
            val added = if (current.isEmpty()) id.length else id.length + 1
            if (current.isNotEmpty() && length + added >= maxUrlLength) {
                result.add(current)
                current = ArrayList()
                length = prefix.length
            }
            length += if (current.isEmpty()) id.length else id.length + 1
            current.add(id)
        }
        if (current.isNotEmpty()) result.add(current)
        return result
    }

    /** URL einer Gruppe: Präfix plus kommagetrennte Kennungen. */
    fun url(prefix: String, chunk: List<String>): String = prefix + chunk.joinToString(",")
}
