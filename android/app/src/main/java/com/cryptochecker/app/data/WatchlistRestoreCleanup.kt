package com.cryptochecker.app.data

/**
 * Bereinigt Merkliste und Paar-Alarme einer Sicherung, bevor etwas geschrieben wird
 * ([BackupManager.restore]). So kann das Schreiben nicht an doppelten Einträgen oder verwaisten
 * Alarmen scheitern (Eindeutigkeit der Paare, Fremdschlüssel der Alarme) und halb fertig stehen bleiben.
 *
 * - Gleiche Paar-Id mehrfach: der letzte Eintrag gilt, an der Stelle des ersten (wie iOS).
 * - Gleiches Paar (Börse, Basis, Quote, Kontrakt) unter verschiedenen Ids: Android erlaubt es nur
 *   einmal (eindeutiger Index). Das erste bleibt; Alarme der weggelassenen Ids hängen danach am
 *   behaltenen Paar statt verloren zu gehen.
 * - Alarme ohne (verbleibendes) Paar fallen weg; gleiche Alarm-Id mehrfach: der letzte gilt (wie iOS).
 * - Id 0 (oder kleiner) heisst «neu vergeben» (Room): solche Paare und Alarme werden nie
 *   zusammengelegt; ein Alarm kann nicht an einem Paar ohne feste Id hängen und fällt weg.
 *
 * Generisch gehalten, damit die Regeln ohne Room/Android prüfbar sind.
 */
internal object WatchlistRestoreCleanup {

    fun <W, A> clean(
        watches: List<W>,
        alarms: List<A>,
        watchId: (W) -> Long,
        pairKey: (W) -> Any,
        alarmId: (A) -> Long,
        alarmWatchId: (A) -> Long,
        withWatchId: (A, Long) -> A,
    ): Pair<List<W>, List<A>> {
        // 1. Gleiche Id: letzter Eintrag gewinnt, Reihenfolge nach erstem Auftreten
        val byId = LinkedHashMap<Any, W>()
        watches.forEachIndexed { i, w -> byId[watchId(w).takeIf { it > 0 } ?: NewId(i)] = w }

        // 2. Gleiches Paar: erstes behalten, Ids der übrigen auf das behaltene umlenken
        val keptByPair = LinkedHashMap<Any, W>()
        val redirect = HashMap<Long, Long>()
        byId.values.forEach { w ->
            val kept = keptByPair[pairKey(w)]
            if (kept == null) keptByPair[pairKey(w)] = w
            else if (watchId(w) > 0) redirect[watchId(w)] = watchId(kept)
        }
        val keptWatches = keptByPair.values.toList()
        val keptIds = keptWatches.map(watchId).filter { it > 0 }.toSet()

        // 3. Alarme umlenken, verwaiste weglassen, gleiche Id: letzter gewinnt
        val alarmsById = LinkedHashMap<Any, A>()
        alarms.forEachIndexed { i, a ->
            val original = alarmWatchId(a)
            val target = redirect[original] ?: original
            if (target !in keptIds) return@forEachIndexed
            alarmsById[alarmId(a).takeIf { it > 0 } ?: NewId(i)] = if (target == original) a else withWatchId(a, target)
        }
        return keptWatches to alarmsById.values.toList()
    }

    /** Schlüssel für Einträge ohne feste Id (nie zusammengelegt). */
    private data class NewId(val index: Int)
}
