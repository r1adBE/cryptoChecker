package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.ActivityRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.FuturesDataSource
import com.cryptochecker.app.data.remote.FuturesInfo
import com.cryptochecker.app.data.remote.FuturesSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Funding und Open Interest für die Alarme FUNDING_* / OI_* — je Paar höchstens alle
 * [DerivativesAlarm.FETCH_INTERVAL_MILLIS] abgefragt (auch Fehlschläge zählen, damit eine
 * gesperrte Quelle nicht jede Aktualisierung bremst). Jede neue Open-Interest-Messung kommt in
 * den Verlauf ([ActivityRepository.appendOiHistory]), gegen den die OI-Alarme vergleichen.
 */
@Singleton
class DerivativesAlarmData @Inject constructor(
    private val futuresDataSource: FuturesDataSource,
    private val activityRepository: ActivityRepository,
) {
    /**
     * Werte eines Paars; null-Felder = diesmal keine Daten. [fundingPercent] pro 8 Stunden
     * ([DerivativesAlarm.fundingPer8h]). [referenceSource] = die Werte stammen nicht
     * von der beobachteten Börse, sondern als Richtwert von dieser Quelle (z. B. «Binance Futures»).
     */
    data class Values(
        val fundingPercent: Double?,
        val oiUnits: Double?,
        val fetchedAt: Long,
        val referenceSource: String? = null,
    )

    /** Schlüssel mit Börse und Paar: Wird ein Paar bearbeitet (gleiche Id), gilt der alte Wert nicht. */
    private val cache = ConcurrentHashMap<String, Values>()

    private fun key(watch: WatchEntity) =
        "${watch.id}|${watch.marketKey}|${watch.baseAsset}|${watch.quoteAsset}|${watch.pairId}"

    /**
     * Vorab geholte Rohdaten ([prefetch], parallel zur Kursabfrage): Abrufzeit und Antwort (null = keine).
     * [values] nimmt sie, solange frisch — gerechnet wird dort mit dem neuen Kurs.
     */
    private val prefetched = ConcurrentHashMap<String, Pair<Long, FuturesInfo?>>()

    /**
     * Funding/Open Interest schon während der Kursabfrage holen (Review 13): Im Doze-Modus ist das
     * Netz-Fenster nach den Kursen oft vorbei. Nichts, wenn der Zwischenspeicher noch frisch ist.
     */
    suspend fun prefetch(watch: WatchEntity, now: Long) {
        if (!supports(watch)) return
        cache[key(watch)]?.let { if (now - it.fetchedAt in 0 until DerivativesAlarm.FETCH_INTERVAL_MILLIS) return }
        prefetched[key(watch)]?.let { if (now - it.first in 0 until DerivativesAlarm.FETCH_INTERVAL_MILLIS) return }
        prefetched[key(watch)] = now to fetchInfo(watch)
    }

    private suspend fun fetchInfo(watch: WatchEntity): FuturesInfo? = try {
        withTimeoutOrNull(TIMEOUT_MILLIS) { futuresDataSource.fetch(watch) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d(e, "Alarm: Funding/Open Interest für %s nicht verfügbar", watch.displayName)
        null
    }

    /** Paar kann diese Alarme haben (Perpetual an einer Börse mit eigenen Daten, [FuturesSources]). */
    fun supports(watch: WatchEntity): Boolean =
        DerivativesAlarm.supports(watch.marketKey, watch.contractType.isPerpetual)

    /**
     * Aktuelle Werte (zwischengespeichert). [price] = letzter Kurs des Paars, um den USD-Wert des
     * Open Interest in Coins umzurechnen.
     */
    suspend fun values(watch: WatchEntity, price: Double, now: Long): Values? {
        if (!supports(watch)) return null
        cache[key(watch)]?.let { cached ->
            if (now - cached.fetchedAt in 0 until DerivativesAlarm.FETCH_INTERVAL_MILLIS) return cached
        }
        // Vorab geholt (frisch): verwenden statt erneut abzufragen
        val early = prefetched.remove(key(watch))?.takeIf { now - it.first in 0 until DerivativesAlarm.FETCH_INTERVAL_MILLIS }
        val info = if (early != null) early.second else fetchInfo(watch)
        // Richtwert einer anderen Börse: Funding meldet weiter (Text nennt die Quelle); Open Interest
        // nicht — es gilt je Börse und passt nicht zum Verlauf der beobachteten Börse
        val foreign = info?.let { FuturesSources.note(true, watch.marketKey, it.source, watch.pairId) != null } ?: false
        val units = info?.openInterestUsd?.takeIf { !foreign && it.isFinite() && it > 0.0 && price > 0.0 }?.let { it / price }
        val values = Values(
            // Pro 8 Stunden wie die Schwelle, mit dem Intervall des Kontrakts (z. B. 4 Std. bei Binance)
            fundingPercent = DerivativesAlarm.fundingPer8h(info?.fundingRatePercent, info?.source, info?.fundingIntervalHours),
            oiUnits = units,
            fetchedAt = now,
            referenceSource = info?.source?.takeIf { foreign },
        )
        cache[key(watch)] = values
        if (units != null) activityRepository.appendOiHistory(watch.id, DerivativesAlarm.OiPoint(units, now), now)
        return values
    }

    /** Veränderung des Open Interest in % über [hours] Stunden (null ohne alte genug Messung). */
    fun oiChange(watch: WatchEntity, values: Values, hours: Int, now: Long): Double? =
        DerivativesAlarm.oiChangePercent(activityRepository.oiHistory(watch.id), values.oiUnits, hours, now)

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
