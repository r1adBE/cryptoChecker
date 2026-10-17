package com.cryptochecker.app.domain.watch

/**
 * Sprungknopf der Merkliste («Zum Anfang» / «Zum Ende»): reine Regeln, auf Android
 * und iOS gleich. Der Knopf erscheint nur bei langen Listen, zeigt in der oberen
 * Hälfte nach unten (springt ans Ende), sonst nach oben (springt an den Anfang).
 */
object WatchJump {

    /** Erst ab mehr als so vielen Paaren in der sichtbaren Liste. */
    const val MIN_PAIRS = 30

    /** Weiter als so viele Zeilen: sofort springen statt lange zu animieren. */
    const val INSTANT_DISTANCE = 40

    /** Nach dem Scrollen so lange stehen lassen, dann ausblenden. */
    const val HIDE_DELAY_MILLIS = 2_000L

    /** Knopf möglich? Nicht im Sortiermodus; [pairs] = sichtbare (ggf. gefilterte) Paare. */
    fun eligible(pairs: Int, sorting: Boolean): Boolean = !sorting && pairs > MIN_PAIRS

    /**
     * Liegt die Mitte des sichtbaren Bereichs in der oberen Hälfte der Liste?
     * Dann zeigt der Pfeil nach unten. Indizes sind Listenpositionen (0-basiert).
     */
    fun pointsDown(firstVisible: Int, lastVisible: Int, total: Int): Boolean {
        if (total <= 0) return true
        return firstVisible + lastVisible < total - 1
    }

    /** Sanft scrollen nur über kurze Strecken und ohne «Bewegung reduzieren». */
    fun animate(distance: Int, reduceMotion: Boolean): Boolean =
        !reduceMotion && kotlin.math.abs(distance) <= INSTANT_DISTANCE
}
