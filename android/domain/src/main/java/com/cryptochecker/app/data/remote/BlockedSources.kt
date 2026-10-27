package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.exceptions.HttpMarketError
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/**
 * Quellen, die mit 451/403 geantwortet haben (Geo-Sperre, z. B. Binance in den USA).
 * Sie werden für [BLOCK_MILLIS] übersprungen. Prozessweit geteilt von Kerzen und
 * Futures-Kennzahlen; Schlüssel ist der Hostname.
 *
 * Dazu die gemeinsame Pause nach 429/418 («zu viele Anfragen», «IP gesperrt»): Antwortet ein
 * Binance-Host so, pausieren ALLE Binance-Hosts ([ExchangeBackoff.BINANCE_HOSTS]) — weitere
 * Anfragen an irgendeinen davon verlängerten die Sperre. Dauer siehe [ExchangeBackoff.groupPause]
 * («Retry-After» gilt, sonst gestaffelt ab 30 s). Die Kursabfrage gleicht sich in beide
 * Richtungen ab ([pauseGroup], [groupPausedUntil]).
 */
object BlockedSources {
    private const val BLOCK_MILLIS = 6 * 60 * 60_000L
    private val blockedUntil = ConcurrentHashMap<String, Long>()

    /** Gemeinsame Pausen je Gruppe ([ExchangeBackoff.sharedGroup]). */
    private val groupPauses = ConcurrentHashMap<String, ExchangeBackoff.State>()

    fun isBlocked(key: String): Boolean {
        if (groupPausedUntil(key) != null) return true
        val until = blockedUntil[key] ?: return false
        if (System.currentTimeMillis() < until) return true
        blockedUntil.remove(key, until)
        return false
    }

    /** Sperre (451/403) dieser Quelle aufheben, z. B. eigener Bitcoin-Server nach Speichern oder erfolgreichem Test. */
    fun unblock(key: String) {
        blockedUntil.remove(key)
    }

    /**
     * Wertet einen Fehler aus; true = Sperr- bzw. Pausen-Antwort, Quelle ist jetzt vermerkt.
     * 451/403: diese Quelle 6 h. 429/418 eines Hosts mit gemeinsamer Pause: die ganze Gruppe.
     */
    fun noteFailure(key: String, error: Throwable): Boolean {
        if (error !is HttpMarketError) return false
        if (error.httpCode == 451 || error.httpCode == 403) {
            blockedUntil[key] = System.currentTimeMillis() + BLOCK_MILLIS
            Timber.i("Quelle gesperrt (HTTP %d), überspringe sie 6 h: %s", error.httpCode, key)
            return true
        }
        if (ExchangeBackoff.isRateLimitCode(error.httpCode)) {
            val group = ExchangeBackoff.sharedGroup(key) ?: return false
            val now = System.currentTimeMillis()
            val state = groupPauses.compute(group) { _, old ->
                ExchangeBackoff.groupPause(old, now, error.httpCode, error.retryAfterSeconds)
            }
            Timber.i(
                "Zu viele Anfragen (HTTP %d) von %s: alle Endpunkte von %s pausiert bis %d",
                error.httpCode, key, group, state?.pausedUntil ?: now,
            )
            return true
        }
        return false
    }

    /**
     * Ende der gemeinsamen Pause, die [hostOrMarketKey] betrifft (Hostname oder Börsen-Schlüssel
     * der Kursabfrage); null = keine Pause.
     */
    fun groupPausedUntil(hostOrMarketKey: String): Long? {
        val group = ExchangeBackoff.sharedGroup(hostOrMarketKey) ?: return null
        val state = groupPauses[group] ?: return null
        return state.pausedUntil.takeIf { ExchangeBackoff.isPaused(state, System.currentTimeMillis()) }
    }

    /** Pause aus der Kursabfrage übernehmen (429/418 beim Ticker); verkürzt nie eine laufende. */
    fun pauseGroup(hostOrMarketKey: String, until: Long) {
        val group = ExchangeBackoff.sharedGroup(hostOrMarketKey) ?: return
        // Eine unmögliche alte Pause (ExchangeBackoff.isPaused: zu weit in der Zukunft) blockiert die neue nicht
        groupPauses.compute(group) { _, old -> ExchangeBackoff.adoptedPause(old, until, System.currentTimeMillis()) }
    }
}
