package com.cryptochecker.app.domain.refresh

/**
 * Nicht mehr gehandelte Paare aus der GEFILTERTEN Sammelabfrage heraushalten. Binance verwirft `symbols=[…]` mit
 * einem delisteten Symbol ganz (HTTP 400 «Invalid symbol») — dann lud jede Aktualisierung die ungefilterte Liste
 * (~2 MB). Welches Symbol, sagt Binance nicht: erkannt an der vollständigen Ersatzliste (angefragt, aber nicht
 * darin). Diese Paare und die schon als «nicht mehr gehandelt» gespeicherten ([NotTraded]) bleiben aus der Anfrage;
 * sie fehlen dann in der vollständigen Liste und laufen über die bestehende Logik. Alle [RECHECK_MILLIS] einmal
 * alle Paare (wieder gelistet?). Der Stand bleibt über Prozess-Starts erhalten ([BulkDelistedStore]): Worker und exakter
 * Wecker starten oft frisch, ohne gespeicherten Stand folgte sonst jedes Mal eine Neuprüfung. Reines Kotlin,
 * gespiegelt in `BulkDelisted.swift` (testdata/parity/bulk_delisted.json).
 */
object BulkDelisted {
    const val RECHECK_MILLIS = 6 * 60 * 60_000L

    /** [delisted]: zuletzt in der vollständigen Liste fehlende Paare; [recheckedAt]: letzter Lauf mit allen Paaren. */
    data class State(val delisted: Set<String>, val recheckedAt: Long)

    /** [all]: beobachtete Paare; [request]: davon anzufragen; [recheck]: Lauf mit allen Paaren. */
    data class Plan(val all: List<String>, val request: List<String>, val recheck: Boolean)

    fun plan(state: State?, pairIds: List<String>, notTraded: Set<String>, now: Long): Plan {
        // Ohne Stand (Erstinstallation, gelöschte Daten) nicht gleich alles laden: die Frist läuft ab jetzt. Ausnahme: alle
        // Paare schon «nicht mehr gehandelt» — ohne Abfrage entstünde nie ein Stand, die Neuprüfung käme nie.
        val recheck = if (state == null) {
            pairIds.isNotEmpty() && pairIds.all { it in notTraded }
        } else {
            now - state.recheckedAt !in 0 until RECHECK_MILLIS
        }
        if (recheck) return Plan(pairIds, pairIds, true)
        val skip = state?.delisted.orEmpty() + notTraded
        return Plan(pairIds, pairIds.filter { it !in skip }, false)
    }

    /**
     * Stand nach der Abfrage; nur eine vollständige Liste ([complete]) sagt etwas über fehlende Paare — und nur über
     * Paare, deren Teilabfrage geklappt hat ([incomplete]: z. B. Alpha gescheitert, Spot nicht).
     */
    fun learn(
        state: State?,
        plan: Plan,
        tickers: Set<String>,
        failed: Set<String>,
        complete: Boolean,
        now: Long,
        incomplete: Set<String> = emptySet(),
    ): State {
        val base = state ?: State(emptySet(), now)
        if (!complete) return base
        val all = plan.all.toSet()
        val kept = base.delisted.filter { it in all && it !in tickers && it !in plan.request }
        val missing = plan.request.filter { it !in tickers && it !in failed && it !in incomplete }
        return State((kept + missing).toSet(), if (plan.recheck) now else base.recheckedAt)
    }

    // ---------------- Speicherform (SharedPreferences / UserDefaults) ----------------

    private const val ENTRY_SEP = '\n'
    private const val FIELD_SEP = '\t'
    private const val PAIR_SEP = ' '

    /**
     * Stände je Börse als kurzer Text: je Börse eine Zeile «Börse⇥recheckedAt⇥Paar Paar …», sortiert. Paare oder
     * Börsen mit Trennzeichen (Leerraum) fallen weg — ein vergessenes Paar wird nur einmal mehr angefragt.
     */
    fun encode(states: Map<String, State>): String =
        states.entries.filter { it.key.isNotEmpty() && it.key.none(Char::isWhitespace) }.sortedBy { it.key }
            .joinToString(ENTRY_SEP.toString()) { (key, s) ->
                val pairs = s.delisted.filter { it.isNotEmpty() && it.none(Char::isWhitespace) }.sorted()
                "$key$FIELD_SEP${s.recheckedAt}$FIELD_SEP${pairs.joinToString(PAIR_SEP.toString())}"
            }

    /** Unlesbare Einträge fallen weg (die Börse gilt dann als ohne Stand). */
    fun decode(text: String?): Map<String, State> {
        if (text.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, State>()
        for (entry in text.split(ENTRY_SEP)) {
            val parts = entry.split(FIELD_SEP)
            if (parts.size != 3 || parts[0].isEmpty()) continue
            val at = parts[1].toLongOrNull() ?: continue
            val pairs = parts[2].split(PAIR_SEP).filterTo(HashSet()) { it.isNotEmpty() }
            result[parts[0]] = State(pairs, at)
        }
        return result
    }
}
