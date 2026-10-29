package com.cryptochecker.app.domain.watch

/**
 * Ruhige Fusszeile am Ende der Merkliste: «6 Paare» bzw. «6 von 42 Paaren», sobald eine Ansicht (Gruppe, Filter-Chip,
 * ⚡, «nur veraltete/deaktivierte», Suche) Paare ausblendet; nahe der Obergrenze ([WatchlistLimits.MAX_PAIRS])
 * zusätzlich «· noch 5 frei» bzw. «· Merkliste voll». Reine Regel — wie iOS `WatchlistFooter`
 * (gleiche Testfälle: `testdata/parity/watchlist_footer.json`).
 *
 * @property shown gezeigte Zeilen
 * @property total Paare der ganzen Merkliste
 * @property filtered «x von y» statt «x Paare» (die Ansicht blendet Paare aus)
 * @property remaining freie Plätze, nur nahe der Grenze ([isNearLimit]); 0 = voll, null = kein Hinweis
 */
data class WatchlistFooter(val shown: Int, val total: Int, val filtered: Boolean, val remaining: Int?) {

    companion object {
        /** «Nahe» heisst höchstens so viele freie Plätze: 10 % der Grenze bzw. 10, was kleiner ist. */
        const val NEAR_LIMIT_SLOTS = 10
        const val NEAR_LIMIT_PERCENT = 10

        /**
         * Fusszeile zu [shown] gezeigten von [total] Paaren; null = keine (leere Merkliste mit Start-Auswahl, leere Ansicht
         * mit Hinweis, Mehrfachauswahl — dort steht unten die Leiste).
         */
        fun of(shown: Int, total: Int, selecting: Boolean, max: Int = WatchlistLimits.MAX_PAIRS): WatchlistFooter? {
            if (selecting || shown <= 0 || total <= 0) return null
            val free = (max - total).coerceAtLeast(0)
            return WatchlistFooter(
                shown = shown,
                total = total,
                filtered = shown < total,
                remaining = free.takeIf { isNearLimit(it, max) },
            )
        }

        /** Höchstens [NEAR_LIMIT_PERCENT] % von [max] bzw. [NEAR_LIMIT_SLOTS] Plätze frei, was kleiner ist? */
        fun isNearLimit(free: Int, max: Int): Boolean =
            free <= minOf(max * NEAR_LIMIT_PERCENT / 100, NEAR_LIMIT_SLOTS)
    }
}
