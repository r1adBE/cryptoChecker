package com.cryptochecker.app.domain.market

/**
 * Reihenfolge, in der die Teile des Markt-Tabs von oben nach unten erscheinen — je Register
 * ([MarketSections.of]) eine eigene Kette ([MarketReveal]); die Überschrift «Jetzt» gehört zu
 * [PULSE]. Wie `CycleRevealSlot` (iOS).
 *
 * Jetzt (Pulse, «Heute auffällig» — Karten) → Einordnung (Fear & Greed, Marktphase, Dominanz
 * mit Altcoin-Saison, Zyklus/Halving) → Daten (Krypto-Markt, Gas, Wirtschaftsdaten, Coin) —
 * Einordnung und Daten als Zeilen ohne Karte. Die drei stehen als Register nebeneinander
 * ([MarketSections.of]); [HEADER_CONTEXT] und [HEADER_DATA] zeigen nichts und bleiben
 * nur, damit die Reihenfolge gleich bleibt.
 */
enum class MarketRevealSlot {
    PULSE,

    /**
     * «Heute auffällig» (marktweit ungewöhnliche Bewegungen); in der Kette «Jetzt» nach dem Pulse, auf dem
     * Bildschirm aber unter Fear & Greed in «Mehr Marktdaten» ([MarketReveal.NOW_ORDER]).
     */
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
 * Ruhiges, schrittweises Erscheinen der Karten im Markt-Tab (reines Kotlin, testbar), je Register
 * ([MarketSection]: Jetzt, Einordnung, Daten) eine eigene Kette — eine langsame Quelle in
 * «Einordnung» hält «Daten» nicht auf. Innerhalb eines Registers erscheint Karte n erst, wenn ihre
 * Daten bereit sind (geladen, gescheitert oder aus dem Zwischenspeicher) UND Karte n−1 schon steht —
 * frühestens [STAGGER_MILLIS] nach ihr. Braucht eine Karte länger als [WAIT_MILLIS] nach der vorigen
 * (die erste eines Registers: nach dem Start), erscheint sie trotzdem (als Platzhalter).
 * Was schon beim Start bereit ist (führende Karten je Register), erscheint sofort und ohne Animation.
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
     * Reihenfolge auf «Jetzt» von oben: Pulse, Fear & Greed (mit «Gewinner und Verlierer 24 h»), dann
     * «Heute auffällig» unter «Mehr Marktdaten» — dieses wartet auf die sichtbaren Karten, nie umgekehrt.
     */
    val NOW_ORDER: List<MarketRevealSlot> =
        listOf(MarketRevealSlot.PULSE, MarketRevealSlot.FEAR_GREED, MarketRevealSlot.UNUSUAL)

    /**
     * @param revealed diese Teile sind jetzt sichtbar (je Register die ersten)
     * @param nextAt spätestens dann neu rechnen (oder früher, sobald Daten kommen); null = alle sichtbar
     */
    data class Plan(val revealed: Set<MarketRevealSlot>, val nextAt: Long?)

    /** Sichtbarer Stand für die Ansicht: [shown] Teile, davon [instant] ohne Animation. */
    data class State(
        val shown: Set<MarketRevealSlot> = emptySet(),
        val instant: Set<MarketRevealSlot> = emptySet(),
    ) {
        fun isShown(slot: MarketRevealSlot): Boolean = slot in shown

        /**
         * Auf «Jetzt» ([NOW_ORDER]): ein Teil erst, wenn auch alles darüber auf «Jetzt» steht — so bleibt
         * die Seite von oben nach unten. Teile, die nicht auf «Jetzt» stehen: wie [isShown].
         */
        fun isShownOnNow(slot: MarketRevealSlot): Boolean {
            val above = NOW_ORDER.indexOf(slot).coerceAtLeast(0)
            return isShown(slot) && NOW_ORDER.take(above).all { isShown(it) }
        }

        /**
         * Ladezeile unter «Jetzt»: bis der unterste sichtbare Teil steht — bei zugeklapptem «Mehr Marktdaten»
         * ([moreExpanded]) Fear & Greed, aufgeklappt «Heute auffällig». Ein verborgener Teil hält nichts auf.
         */
        fun nowLoading(moreExpanded: Boolean): Boolean =
            !isShownOnNow(if (moreExpanded) MarketRevealSlot.UNUSUAL else MarketRevealSlot.FEAR_GREED)

        /**
         * Wirtschaftsdaten-Hinweis auf «Jetzt» steht fest: «Mehr Marktdaten» offen und alles darüber
         * (bis «Heute auffällig») erschienen — erst dann zum Hinweis scrollen, sonst rutscht er danach weg.
         */
        fun macroHintReady(moreExpanded: Boolean): Boolean =
            moreExpanded && isShownOnNow(MarketRevealSlot.UNUSUAL)

        /** Alle Teile sichtbar. */
        val complete: Boolean get() = shown.size == COUNT
    }

    /** Führende Teile je Register, die beim Start [start] schon bereit waren — sofort, ohne Animation. */
    fun instantSlots(readyAt: List<Long?>, start: Long): Set<MarketRevealSlot> =
        MarketSection.entries.flatMapTo(mutableSetOf()) { section ->
            slots(section).takeWhile { slot -> readyAt[slot.ordinal].let { it != null && it <= start } }
        }

    /**
     * Welche Teile zur Zeit [now] sichtbar sind; je Register eine Kette.
     *
     * @param readyAt je Teil (Reihenfolge [MarketRevealSlot]) der Zeitpunkt, zu dem seine Daten
     *   zum ersten Mal bereit waren; null = noch nicht
     * @param start Beginn der Abfolge (gleiche Uhr wie [readyAt] und [now])
     */
    fun plan(readyAt: List<Long?>, start: Long, now: Long): Plan {
        val revealed = mutableSetOf<MarketRevealSlot>()
        var nextAt: Long? = null
        for (section in MarketSection.entries) {
            val (shown, next) = chain(slots(section), readyAt, start, now)
            revealed += shown
            if (next != null) nextAt = nextAt?.let { minOf(it, next) } ?: next
        }
        return Plan(revealed, nextAt)
    }

    /** Teile eines Registers von oben nach unten. */
    private fun slots(section: MarketSection): List<MarketRevealSlot> =
        MarketRevealSlot.entries.filter { MarketSections.of(it) == section }

    /** Eine Kette: sichtbare Teile (von oben) und wann neu rechnen (null = alle sichtbar). */
    private fun chain(
        slots: List<MarketRevealSlot>,
        readyAt: List<Long?>,
        start: Long,
        now: Long,
    ): Pair<List<MarketRevealSlot>, Long?> {
        val instant = slots.takeWhile { slot -> readyAt[slot.ordinal].let { it != null && it <= start } }.size
        var previous = start
        for ((i, slot) in slots.withIndex()) {
            val at: Long = if (i < instant) {
                start
            } else {
                val earliest = if (i == 0) start else previous + STAGGER_MILLIS
                val deadline = previous + WAIT_MILLIS
                val ready = readyAt[slot.ordinal]
                when {
                    ready != null && ready <= deadline -> maxOf(earliest, ready)
                    // Zu spät oder noch nicht da, Wartezeit aber vorbei: als Platzhalter zeigen
                    ready != null || now >= deadline -> deadline
                    // Noch offen: spätestens zur Frist wieder rechnen
                    else -> return slots.take(i) to deadline
                }
            }
            if (at > now) return slots.take(i) to at
            previous = at
        }
        return slots to null
    }
}
