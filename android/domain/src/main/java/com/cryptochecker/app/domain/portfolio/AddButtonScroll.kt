package com.cryptochecker.app.domain.portfolio

/**
 * Schwebender «+»-Knopf des Portfolios beim Scrollen: Nach unten gescrollt (man liest die Liste) blendet er aus, damit
 * er keine Beträge verdeckt; nach oben gescrollt kommt er wieder. Ganz oben und ganz unten ist er immer da — unten
 * gibt der Freiraum unter der letzten Zeile ihm Platz. Reine Logik, gleich wie iOS `AddButtonScroll`; gemeinsame
 * Fälle in `testdata/parity/add_button_scroll.json`.
 */
object AddButtonScroll {

    /** So weit (dp bzw. pt) muss es am Stück in eine Richtung gehen, bevor der Knopf wechselt — kein Flackern. */
    const val TRAVEL = 12.0

    /** [travel]: bisheriger Weg in die laufende Richtung (positiv = nach unten gescrollt). */
    data class State(val visible: Boolean = true, val travel: Double = 0.0)

    /**
     * Zustand nach einem Scroll-Schritt. [delta] in dp bzw. pt, positiv = Inhalt nach unten gescrollt (spätere Zeilen
     * kommen). [atTop]/[atEnd]: Liste steht am Anfang bzw. am Ende (auch eine Liste, die gar nicht scrollt).
     */
    fun next(state: State, delta: Double, atTop: Boolean, atEnd: Boolean): State {
        if (atTop || atEnd) return State(visible = true)
        if (delta == 0.0) return state
        // Richtungswechsel: der Weg beginnt neu
        val sameDirection = state.travel == 0.0 || (state.travel > 0) == (delta > 0)
        val travel = if (sameDirection) state.travel + delta else delta
        return when {
            travel >= TRAVEL -> State(visible = false)
            travel <= -TRAVEL -> State(visible = true)
            else -> state.copy(travel = travel)
        }
    }
}
