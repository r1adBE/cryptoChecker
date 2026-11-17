package com.cryptochecker.wear

import com.cryptochecker.marketdata.watch.WatchSettings

/**
 * Was die Kurs-Komplikation zeigt, ohne Android ([PriceComplicationService]): nie «keine Daten». Ohne gewählte Coins ein
 * Hinweis mit Tipp-Ziel ([First.Empty]: «Wie Handy» ohne Favoriten bzw. «Coins wählen»), sonst der erste Coin; ohne Kurs
 * bzw. mit stillgelegter Börse «Paar / —» mit Grund ([shown] null). Wie watchOS.
 */
internal object ComplicationPlan {
    sealed interface First {
        /** Keine Coins gewählt; [phoneMode] = «Wie Handy» (dann «Keine Favoriten auf dem Handy»). */
        data class Empty(val phoneMode: Boolean) : First

        /** Erster gewählter Coin bzw. erstes Paar. */
        data class Coin(val key: String) : First
    }

    fun first(shown: List<String>, mode: WatchSettings.Mode): First =
        shown.firstOrNull()?.let { First.Coin(it) } ?: First.Empty(phoneMode = mode == WatchSettings.Mode.PHONE)

    /** Gezeigter Kurs; null = «Paar / —» (noch nichts geladen bzw. Börse stillgelegt — kein alter Kurs). */
    fun shown(cached: WatchCached?, retired: Boolean): WatchCached? = cached?.takeIf { !retired }
}
