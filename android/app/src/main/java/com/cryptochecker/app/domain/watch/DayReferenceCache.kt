package com.cryptochecker.app.domain.watch

/**
 * Regeln für den Zwischenspeicher der 24-h-Bezüge aus Kerzen (Ausweich-Weg, wenn der Ticker
 * keinen 24-h-Wert liefert; siehe [DayChange]). Der Speicher liegt im Speicher UND als kleine
 * JSON-Datei im Cache-Ordner, damit die Pillen gleich nach dem App-Start Werte haben.
 *
 *  - [FRESH_MILLIS]: so lange gilt ein Bezug als frisch — kein neuer Abruf.
 *  - [MAX_AGE_MILLIS]: so alt darf ein Bezug höchstens sein, um noch benutzt zu werden
 *    (Eröffnung vor 24 h verschiebt sich mit der Zeit; älter wäre zu ungenau).
 *  - Datei mit Formatversion [FORMAT_VERSION]; fremde Version oder kaputte Einträge = nichts gespeichert.
 */
object DayReferenceCache {

    const val FORMAT_VERSION = 1

    /** Gültigkeit eines 24-h-Bezugs (Kerzen der Pille). Mini-Chart-Kurven haben ihre eigene, kürzere. */
    const val FRESH_MILLIS = 60 * 60_000L

    /** Älter wird ein gemerkter Bezug nicht mehr benutzt (auch nicht von der Platte). */
    const val MAX_AGE_MILLIS = 3 * 60 * 60_000L

    /** Höchstens so viele Einträge in der Datei (die jüngsten). */
    const val MAX_ENTRIES = 3_000

    /** Ein gespeicherter Bezug; [key] wie «BTC|USDT». */
    data class Stored(val key: String, val time: Long, val open: Double, val lastClose: Double)

    /** Abrufzeit [time] liegt höchstens [MAX_AGE_MILLIS] zurück (und nicht in der Zukunft). */
    fun usable(time: Long, now: Long): Boolean = time > 0 && now - time in 0..MAX_AGE_MILLIS

    /** Noch frisch: kein neuer Abruf nötig. */
    fun fresh(time: Long, now: Long): Boolean = time > 0 && now - time in 0 until FRESH_MILLIS

    /**
     * Was beim Start aus der Datei übernommen wird: nur aktuelles Format, gültige Werte
     * ([DayReference.of]), Schlüssel «BASE|QUOTE», nicht zu alt; je Schlüssel der jüngste,
     * insgesamt höchstens [MAX_ENTRIES].
     */
    fun restore(version: Int?, entries: List<Stored>, now: Long): Map<String, Stored> {
        if (version != FORMAT_VERSION) return emptyMap()
        val result = HashMap<String, Stored>()
        entries.asSequence()
            .filter { validKey(it.key) && usable(it.time, now) && DayReference.of(it.open, it.lastClose) != null }
            .sortedByDescending { it.time }
            .forEach { if (result.size < MAX_ENTRIES && it.key !in result) result[it.key] = it }
        return result
    }

    /** Was in die Datei kommt: nur noch benutzbare, die jüngsten zuerst, höchstens [MAX_ENTRIES]. */
    fun toSave(entries: Collection<Stored>, now: Long): List<Stored> =
        entries.filter { usable(it.time, now) }
            .sortedByDescending { it.time }
            .take(MAX_ENTRIES)

    private fun validKey(key: String): Boolean {
        val parts = key.split('|')
        return parts.size == 2 && parts.all { it.isNotBlank() && it == it.trim().uppercase() }
    }
}
