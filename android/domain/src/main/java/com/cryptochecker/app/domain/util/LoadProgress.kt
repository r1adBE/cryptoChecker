package com.cryptochecker.app.domain.util

/**
 * Fortschritt eines Ladevorgangs aus mehreren gleichzeitigen Schritten (Merkliste: je Börse; Markt: je Karte;
 * Portfolio: Adressen, Kurse, Verlauf, Devisen …). Jeder fertige Schritt rückt den Balken vor, egal in welcher
 * Reihenfolge. Reine Logik, wie iOS `LoadProgress`.
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
        /** Erst nach dieser Zeit erscheint der Balken: Schnelles Laden bleibt ohne Balken (Inhaber: «länger als 2 s»). */
        const val SHOW_AFTER_MILLIS = 2_000L

        /** Kreisel beim Ziehen: nur so lange als «verstanden», danach übernimmt der Balken. */
        const val PULL_ACK_MILLIS = 1_000L

        /** Ein kleiner Anfang, damit der Balken sofort lebt (nie ganz leer). */
        const val START = 0.08f

        fun shown(done: Int, total: Int): Float {
            if (total <= 0) return 1f
            return START + (1f - START) * (done.coerceIn(0, total).toFloat() / total)
        }
    }
}

/**
 * Fortschritt aus einer Menge laufender Teile (Markt: Bereiche, die gerade laden): Jeder Teil, der während eines
 * Ladens dazukommt, zählt mit; fertig ist, wer nicht mehr läuft. Läuft nichts mehr, beginnt die Zählung neu.
 * Wie iOS `LoadTally`.
 */
class LoadTally<K> {
    private val seen = HashSet<K>()

    /** Anteil für den Balken; null = nichts lädt. */
    fun next(running: Set<K>): Float? {
        if (running.isEmpty()) {
            seen.clear()
            return null
        }
        seen += running
        return LoadProgress.shown(seen.size - running.size, seen.size)
    }
}
