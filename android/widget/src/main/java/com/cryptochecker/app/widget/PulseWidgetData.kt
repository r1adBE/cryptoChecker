package com.cryptochecker.app.widget

import com.cryptochecker.app.data.cycle.CycleCacheCodecs
import com.cryptochecker.app.data.cycle.CycleCacheStore
import com.cryptochecker.app.data.remote.PulseDataSource
import com.cryptochecker.app.domain.market.CachedValue
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.CycleCachePolicy
import com.cryptochecker.app.domain.market.CycleSource
import com.cryptochecker.app.domain.market.PulseInput
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Daten des Widgets «Was gerade auffällt» (wie iOS `PulseWidgetData`): der Zwischenspeicher des Markt-Tabs
 * ([CycleCacheStore], Eintrag «pulse») und bei Bedarf ein frischer Abruf über [PulseDataSource]. Gezeichnet
 * wird in [PulseWidgetRenderer].
 */
@Singleton
class PulseWidgetData @Inject constructor(
    private val pulseDataSource: PulseDataSource,
    private val cycleCacheStore: CycleCacheStore,
) {
    /** Höchstens ein Abruf der Pulse-Daten gleichzeitig (mehrere Auslöser kurz nacheinander). */
    private val pulseMutex = Mutex()

    fun isPulseFresh(value: CachedValue<PulseInput>?, maxAgeMillis: Long = TTL): Boolean =
        value != null && CycleCachePolicy.isFresh(value.savedAt, System.currentTimeMillis(), maxAgeMillis)

    /** Gespeicherte Pulse-Eingaben, nur wenn sie sich auswerten lassen (wie im Markt-Tab). */
    suspend fun readPulse(): CachedValue<PulseInput>? =
        cycleCacheStore.read(CycleSource.PULSE.key, CycleCacheCodecs.pulseInput)
            ?.takeIf { CryptoPulse.evaluate(it.value) != null }

    /**
     * Frisch laden (harte Zeitgrenze des Bereichs) und speichern; null bei Fehler. Lief
     * inzwischen schon ein Abruf (anderer Auslöser), gilt dessen frisches Ergebnis.
     */
    suspend fun loadPulse(maxAgeMillis: Long = TTL): CachedValue<PulseInput>? = pulseMutex.withLock {
        readPulse()?.takeIf { isPulseFresh(it, maxAgeMillis) }?.let { return@withLock it }
        val fetched = try {
            withTimeoutOrNull(CycleSource.PULSE.timeoutMillis) { pulseDataSource.fetchInput() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Timber.d(e, "Pulse für Widget: Zeitüberschreitung")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Pulse für Widget nicht verfügbar")
            null
        }
        val fresh = fetched?.takeIf { CryptoPulse.evaluate(it) != null } ?: return@withLock null
        val at = fresh.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        cycleCacheStore.write(CycleSource.PULSE.key, fresh, at, CycleCacheCodecs.pulseInput)
        CachedValue(fresh, at)
    }

    companion object {
        /** Gültigkeit wie im Markt-Tab ([CycleSource.PULSE], 5 Min.). */
        val TTL: Long = CycleSource.PULSE.ttlMillis

        /** Tipp auf «Aktualisieren»: jüngere Daten gelten als frisch (schont die freie Quelle bei mehreren Tipps). */
        const val TAP_MIN_AGE_MILLIS = 30_000L
    }
}
