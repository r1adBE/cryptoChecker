package com.cryptochecker.app.widget

import com.cryptochecker.app.data.CachedValue
import com.cryptochecker.app.data.CycleCacheCodecs
import com.cryptochecker.app.data.CycleCacheStore
import com.cryptochecker.app.data.remote.PulseDataSource
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.CycleCachePolicy
import com.cryptochecker.app.domain.market.CycleSource
import com.cryptochecker.app.domain.market.PulseInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

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

    fun isPulseFresh(value: CachedValue<PulseInput>?): Boolean =
        value != null && CycleCachePolicy.isFresh(value.savedAt, System.currentTimeMillis(), CycleSource.PULSE.ttlMillis)

    /** Gespeicherte Pulse-Eingaben, nur wenn sie sich auswerten lassen (wie im Markt-Tab). */
    suspend fun readPulse(): CachedValue<PulseInput>? =
        cycleCacheStore.read(CycleSource.PULSE.key, CycleCacheCodecs.pulseInput)
            ?.takeIf { CryptoPulse.evaluate(it.value) != null }

    /**
     * Frisch laden (harte Zeitgrenze des Bereichs) und speichern; null bei Fehler. Lief
     * inzwischen schon ein Abruf (anderer Auslöser), gilt dessen frisches Ergebnis.
     */
    suspend fun loadPulse(): CachedValue<PulseInput>? = pulseMutex.withLock {
        readPulse()?.takeIf { isPulseFresh(it) }?.let { return@withLock it }
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
}
