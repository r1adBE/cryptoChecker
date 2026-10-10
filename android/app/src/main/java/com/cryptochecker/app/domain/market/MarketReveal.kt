package com.cryptochecker.app.domain.market

/**
 * Reihenfolge, in der die Teile des Markt-Tabs von oben nach unten erscheinen
 * (die Überschrift «Jetzt» gehört zu [PULSE]). Wie `CycleRevealSlot` (iOS).
 *
 * Jetzt (Pulse, «Heute auffällig» — Karten) → Einordnung (Fear & Greed, Marktphase, Dominanz
 * mit Altcoin-Saison, Zyklus/Halving) → Daten (Krypto-Markt, Gas, Wirtschaftsdaten, Coin) —
 * Einordnung und Daten als Zeilen ohne Karte. Die drei stehen als Register nebeneinander
 * ([MarketSections.of]); [HEADER_CONTEXT] und [HEADER_DATA] zeigen nichts mehr (früher die
 * Überschriften zum Aufklappen) und bleiben nur, damit die Reihenfolge gleich bleibt.
 */
enum class MarketRevealSlot {
    PULSE,

    /** «Heute auffällig» (marktweit ungewöhnliche Bewegungen), direkt unter dem Pulse. */
    UNUSUAL,
    HEADER_CONTEXT,

    /** Fear & Greed — erste Zeile unter «Einordnung». */
    FEAR_GREED,
    PHASE,
    DOMINANCE,
    HALVING,
    HEADER_DATA,

    /** «Krypto-Markt» (Marktkapitalisierung, Volumen) — erste Karte unter «Daten». */
    MARKET_TOTALS,
    GAS,

    /** Coin-Analyse; davor der Wirtschaftsdaten-Hinweis, wenn kein Termin in ±2 h liegt. */
    COIN,
}

/**
 * Ruhiges, schrittweises Erscheinen der Karten im Markt-Tab (reines Kotlin, testbar):
 * Karte n erscheint erst, wenn ihre Daten bereit sind (geladen, gescheitert oder aus dem
 * Zwischenspeicher) UND Karte n−1 schon steht — frühestens [STAGGER_MILLIS] nach ihr.
 * Braucht eine Karte länger als [WAIT_MILLIS] nach der vorigen, erscheint sie trotzdem
 * (als Platzhalter), damit eine langsame Quelle den Rest nicht aufhält.
 * Was schon beim Start bereit ist (führende Karten), erscheint sofort und ohne Animation.
 * Wie `CycleReveal` (iOS).
 */
object MarketReveal {
    /** Abstand zwischen zwei aufeinanderfolgenden Karten. */
    const val STAGGER_MILLIS = 60L

    /** Längstens so lange nach der vorigen Karte auf Daten warten. */
    const val WAIT_MILLIS = 1_500L

    /** Einblenden einer Karte (dabei 8 dp von unten nach oben). */
    const val ENTER_MILLIS = 220

    /** Platzhalter → Inhalt in einer schon sichtbaren Karte (Überblenden, Höhe der Karte). */
    const val SWAP_MILLIS = 200

    /** Höchstens so lange auf das Lesen des Zwischenspeichers warten, bevor es losgeht. */
    const val CACHE_WAIT_MILLIS = 400L

    /** Anzahl Teile ([MarketRevealSlot]). */
    val COUNT: Int = MarketRevealSlot.entries.size

    /**
     * @param revealed so viele Teile (von oben) sind jetzt sichtbar
     * @param nextAt spätestens dann neu rechnen (oder früher, sobald Daten kommen); null = alle sichtbar
     */
    data class Plan(val revealed: Int, val nextAt: Long?)

    /** Sichtbarer Stand für die Ansicht: [count] Teile, davon die ersten [instant] ohne Animation. */
    data class State(val count: Int = 0, val instant: Int = 0)

    /** Führende Teile, die beim Start [start] schon bereit waren — erscheinen sofort, ohne Animation. */
    fun instantCount(readyAt: List<Long?>, start: Long): Int {
        val first = readyAt.indexOfFirst { it == null || it > start }
        return if (first < 0) readyAt.size else first
    }

    /**
     * Welche Teile zur Zeit [now] sichtbar sind.
     *
     * @param readyAt je Teil (Reihenfolge [MarketRevealSlot]) der Zeitpunkt, zu dem seine Daten
     *   zum ersten Mal bereit waren; null = noch nicht
     * @param start Beginn der Abfolge (gleiche Uhr wie [readyAt] und [now])
     */
    fun plan(readyAt: List<Long?>, start: Long, now: Long): Plan {
        val instant = instantCount(readyAt, start)
        var previous = start
        for (i in readyAt.indices) {
            val at: Long = if (i < instant) {
                start
            } else {
                val earliest = if (i == 0) start else previous + STAGGER_MILLIS
                val deadline = previous + WAIT_MILLIS
                val ready = readyAt[i]
                when {
                    ready != null && ready <= deadline -> maxOf(earliest, ready)
                    // Zu spät oder noch nicht da, Wartezeit aber vorbei: als Platzhalter zeigen
                    ready != null || now >= deadline -> deadline
                    // Noch offen: spätestens zur Frist wieder rechnen
                    else -> return Plan(i, deadline)
                }
            }
            if (at > now) return Plan(i, at)
            previous = at
        }
        return Plan(readyAt.size, null)
    }
}
