package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.ActivityRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.FuturesDataSource
import com.cryptochecker.marketdata.model.FuturesContractType
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
    /** Werte eines Paars; null-Felder = diesmal keine Daten. */
    data class Values(val fundingPercent: Double?, val oiUnits: Double?, val fetchedAt: Long)

    /** Schlüssel mit Börse und Paar: Wird ein Paar bearbeitet (gleiche Id), gilt der alte Wert nicht. */
    private val cache = ConcurrentHashMap<String, Values>()

    private fun key(watch: WatchEntity) =
        "${watch.id}|${watch.marketKey}|${watch.baseAsset}|${watch.quoteAsset}|${watch.pairId}"

    /** Paar kann diese Alarme haben (Perpetual an Binance/Bybit/OKX). */
    fun supports(watch: WatchEntity): Boolean =
        DerivativesAlarm.supports(watch.marketKey, watch.contractType == FuturesContractType.PERPETUAL)

    /**
     * Aktuelle Werte (zwischengespeichert). [price] = letzter Kurs des Paars, um den USD-Wert des
     * Open Interest in Coins umzurechnen.
     */
    suspend fun values(watch: WatchEntity, price: Double, now: Long): Values? {
        if (!supports(watch)) return null
        cache[key(watch)]?.let { cached ->
            if (now - cached.fetchedAt in 0 until DerivativesAlarm.FETCH_INTERVAL_MILLIS) return cached
        }
        val info = try {
            withTimeoutOrNull(TIMEOUT_MILLIS) { futuresDataSource.fetch(watch) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Alarm: Funding/Open Interest für %s nicht verfügbar", watch.displayName)
            null
        }
        val units = info?.openInterestUsd?.takeIf { it.isFinite() && it > 0.0 && price > 0.0 }?.let { it / price }
        val values = Values(
            fundingPercent = info?.fundingRatePercent?.takeIf { it.isFinite() },
            oiUnits = units,
            fetchedAt = now,
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
