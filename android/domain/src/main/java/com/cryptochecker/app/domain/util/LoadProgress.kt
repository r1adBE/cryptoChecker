package com.cryptochecker.app.domain.util

/**
 * Fortschritt eines Ladevorgangs aus mehreren gleichzeitigen Schritten (Portfolio: Adressen, Kurse, Verlauf,
 * Devisen …). Jeder fertige Schritt rückt den Balken vor, egal in welcher Reihenfolge. Reine Logik, wie iOS
 * `LoadProgress`.
 */
class LoadProgress(private val total: Int) {
    private var done = 0

    /** Ein Schritt fertig; der angezeigte Anteil danach. */
    fun step(): Float {
        done = (done + 1).coerceAtMost(total)
        return shown(done, total)
    }

    /** Angezeigter Anteil jetzt. */
    fun current(): Float = shown(done, total)

    companion object {
        /** Erst nach dieser Zeit erscheint der Balken: Schnelles Laden bleibt ohne Flackern. */
        const val SHOW_AFTER_MILLIS = 500L

        /** Ein kleiner Anfang, damit der Balken sofort lebt (nie ganz leer). */
        const val START = 0.08f

        fun shown(done: Int, total: Int): Float {
            if (total <= 0) return 1f
            return START + (1f - START) * (done.coerceIn(0, total).toFloat() / total)
        }
    }
}
