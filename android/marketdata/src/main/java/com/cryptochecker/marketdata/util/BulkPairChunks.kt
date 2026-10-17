package com.cryptochecker.marketdata.util

/**
 * Verteilt Paar-Kennungen für eine gefilterte Sammelabfrage («…?pair=A,B,C») auf mehrere Adressen, damit keine
 * [MAX_URL_LENGTH] Zeichen erreicht. Ohne Netz; iOS: `enum BulkPairChunks`.
 */
object BulkPairChunks {
    /** Grenze, ab der viele Server und Proxys eine Adresse abweisen. */
    const val MAX_URL_LENGTH = 2000

    // Zeichen, die unkodiert in einer Query stehen dürfen (Kraken «XXBTZUSD», Bitfinex «tDOGE:USD»)
    private val SAFE_ID = Regex("[A-Za-z0-9._:~-]+")

    /**
     * Gruppen sortierter Kennungen ohne Doppelte, eine Anfrage je Gruppe. null, wenn nicht gefiltert werden kann
     * (keine Kennung, oder eine bräuchte Kodierung) — dann gilt die ungefilterte Abfrage. Eine einzelne zu lange
     * Kennung bildet ihre eigene Gruppe.
     */
    fun chunks(prefix: String, pairIds: Collection<String>, maxUrlLength: Int = MAX_URL_LENGTH): List<List<String>>? {
        val ids = pairIds.filterTo(sortedSetOf()) { it.isNotEmpty() }
        if (ids.isEmpty() || !ids.all(SAFE_ID::matches)) return null

        val groups = ArrayList<List<String>>()
        var group = ArrayList<String>()
        var urlLength = prefix.length
        for (id in ids) {
            // Ab der zweiten Kennung kommt ein Komma dazu
            if (group.isNotEmpty() && urlLength + 1 + id.length >= maxUrlLength) {
                groups.add(group)
                group = ArrayList()
                urlLength = prefix.length
            }
            urlLength += if (group.isEmpty()) id.length else 1 + id.length
            group.add(id)
        }
        if (group.isNotEmpty()) groups.add(group)
        return groups
    }

    /** Adresse einer Gruppe: [prefix] und die Kennungen mit Komma dazwischen. */
    fun url(prefix: String, chunk: List<String>): String = chunk.joinToString(separator = ",", prefix = prefix)
}
