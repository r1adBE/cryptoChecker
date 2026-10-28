package com.cryptochecker.app.overlay

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.LiveStatus
import com.cryptochecker.app.domain.watch.PriceFreshness
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.notification.PriceNotificationRule
import com.cryptochecker.app.settings.AppSettings

/**
 * Regeln des Preis-Overlays (nur Android, Wunsch 27.10.2026): dieselben Paare wie die dauerhafte Kurs-Mitteilung
 * (Kurs-Mitteilungen an, Paar mit Mitteilung, nicht deaktiviert, gehandelt, mit Kurs), höchstens [MAX_PAIRS] in der
 * Reihenfolge der Merkliste; nur mit «dauerhafte Kurs-Mitteilung» und erteilter Berechtigung. Veraltete Kurse wie in der
 * Merkliste grau mit Alter ([stale]).
 */
object PriceOverlayRules {
    const val MAX_PAIRS = 3

    /** Soll das Overlay stehen? (Schalter, dauerhafte Mitteilung, Kurs-Mitteilungen, Berechtigung) */
    fun active(settings: AppSettings, canDraw: Boolean): Boolean =
        settings.priceOverlay && settings.ongoingNotifications && settings.priceNotifications && canDraw

    /** Paare des Overlays (leer = Overlay ausblenden). */
    fun pairs(watches: List<WatchEntity>, settings: AppSettings, canDraw: Boolean): List<WatchEntity> {
        if (!active(settings, canDraw)) return emptyList()
        return watches.filter {
            PriceNotificationRule.showNow(
                masterOn = settings.priceNotifications,
                pairOn = it.notificationEnabled,
                disabled = it.disabled,
                notTraded = it.isNotTraded,
                price = it.lastPrice,
            )
        }.take(MAX_PAIRS)
    }

    /** Kurs veraltet seit [since] (Epoch-ms), aus Grund [reason]. */
    data class Stale(val reason: PriceFreshness.StaleReason, val since: Long)

    /**
     * Veraltet? Dieselbe Regel wie die Zeile der Merkliste ([PriceFreshness.staleReason], Grenze aus dem
     * Hintergrund-Intervall: `OutdatedRule`, über [LiveStatus.pollingStaleAfterMillis]). Seit: letzter Abruf
     * ([PriceFreshness.StaleReason.FETCH]) bzw. letzter Handel laut Börse ([PriceFreshness.StaleReason.TRADE]); null = frisch.
     */
    fun stale(watch: WatchEntity, now: Long, backgroundIntervalMinutes: Int): Stale? {
        val after = LiveStatus.pollingStaleAfterMillis(backgroundIntervalMinutes)
        return when (val reason = PriceFreshness.staleReason(watch, now, after)) {
            PriceFreshness.StaleReason.FETCH -> Stale(reason, watch.lastUpdate)
            PriceFreshness.StaleReason.TRADE -> Stale(reason, watch.exchangeTime ?: watch.lastUpdate)
            null -> null
        }
    }

    /** Waagrechte Lage nach dem Loslassen: an den näheren Rand (Mitte der Pille entscheidet). */
    fun snapX(x: Int, width: Int, screenWidth: Int): Int =
        if (x + width / 2 < screenWidth / 2) 0 else (screenWidth - width).coerceAtLeast(0)

    /** Senkrechte Lage innerhalb des Bildschirms. */
    fun clampY(y: Int, height: Int, screenHeight: Int): Int = y.coerceIn(0, (screenHeight - height).coerceAtLeast(0))

    /** Losgelassen über dem «×» unten in der Mitte ([zone] Pixel hoch und breit)? Dann schliessen. */
    fun inCloseZone(centerX: Int, centerY: Int, screenWidth: Int, screenHeight: Int, zone: Int): Boolean =
        centerY >= screenHeight - zone && kotlin.math.abs(centerX - screenWidth / 2) <= zone
}
