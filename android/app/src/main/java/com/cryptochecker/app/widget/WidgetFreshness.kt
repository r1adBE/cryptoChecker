package com.cryptochecker.app.widget

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.refresh.OutdatedRule
import com.cryptochecker.app.domain.watch.LiveStatus
import com.cryptochecker.app.domain.watch.PriceFreshness

/**
 * Frische eines Kurses in den Widgets — gleiche Regeln wie die Merkliste (K-1, K-4):
 *  - «veraltet»: Widgets sind nie am Live-Strom, also immer die Abfrage-Schwelle
 *    ([LiveStatus.pollingStaleAfterMillis]: 3 × Hintergrund-Intervall, mind. 15 Min.).
 *  - «Kurs unverändert seit 14:05» ([PriceFreshness.unchangedSince]) bei Börsen ohne eigene Zeit,
 *    aber nur, wenn das Paar nicht ohnehin «veraltet» ist (wie die Zeile der App).
 *  - [priceTime]: Zeit, die das Widget neben dem Kurs zeigt — Beginn des unveränderten Kurses,
 *    sonst Zeit laut Börse, sonst letzter Abruf. So wirkt ein stehender Kurs nicht frisch.
 * Reine Logik ohne Android (WidgetFreshnessTest), Swift-Spiegel WidgetFreshness.swift.
 */
object WidgetFreshness {

    /** Grenze «veraltet» für Widgets (nie live). */
    fun afterMillis(backgroundIntervalMinutes: Int): Long =
        LiveStatus.pollingStaleAfterMillis(backgroundIntervalMinutes)

    /**
     * Zeitpunkt für «Kurs unverändert seit …» oder null: nicht bei deaktivierten, fehlerhaften oder
     * veralteten Paaren (dort steht anderes) und nie mit Zeit der Börse.
     */
    fun unchangedSince(watch: WatchEntity, now: Long, afterMillis: Long): Long? {
        if (OutdatedRule.isOutdated(watch.lastUpdate, now, afterMillis)) return null
        return PriceFreshness.unchangedSince(watch, now, afterMillis)
    }

    /** Zeit neben dem Kurs (kompakte Zeilen ohne Platz für den Hinweis): siehe oben. */
    fun priceTime(watch: WatchEntity, now: Long, afterMillis: Long): Long =
        unchangedSince(watch, now, afterMillis) ?: PriceFreshness.freshnessTime(watch)
}
