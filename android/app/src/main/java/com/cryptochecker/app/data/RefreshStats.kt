package com.cryptochecker.app.data

import androidx.core.content.edit
import android.content.Context
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.domain.refresh.MarketRefresh
import com.cryptochecker.app.domain.refresh.RefreshFailure
import com.cryptochecker.app.domain.refresh.RefreshReport
import com.cryptochecker.app.domain.watch.ChangeStamp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kennzahlen der letzten Aktualisierung. Wird an zwei Stellen gebraucht:
 * im Widget (synchron beim Zeichnen) und in der Watchlist (als Flow).
 * Deshalb SharedPreferences plus StateFlow statt DataStore.
 */
@Singleton
class RefreshStats @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs get() = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _lastDurationMillis = MutableStateFlow(prefs.getLong(KEY_LAST_DURATION, 0))
    private val _lastRefreshAt = MutableStateFlow(prefs.getLong(KEY_LAST_REFRESH_AT, 0))
    private val _lastReport = MutableStateFlow(decode(prefs.getString(KEY_LAST_REPORT, null)))
    private val _changeStamp = MutableStateFlow(ChangeStamp.decode(prefs.getString(KEY_CHANGE_STAMP, null)))

    /** Dauer des letzten vollständigen Durchlaufs. */
    val lastDurationMillis: StateFlow<Long> = _lastDurationMillis.asStateFlow()

    /** Zeitpunkt des letzten vollständigen Durchlaufs. */
    val lastRefreshAt: StateFlow<Long> = _lastRefreshAt.asStateFlow()

    /** Bericht des letzten Durchlaufs (Börsen, Ablauf); null = noch keiner. */
    val lastReport: StateFlow<RefreshReport?> = _lastReport.asStateFlow()

    /**
     * Mit welcher %-Basis (und welchem Tagesbeginn) die gespeicherten Veränderungen zuletzt
     * gerechnet wurden; null = noch nie (gilt als rollend). Siehe `ChangeBasisMath.isCurrent`.
     */
    val changeStamp: StateFlow<ChangeStamp?> = _changeStamp.asStateFlow()

    /** Nach einem vollen Durchlauf: Basis und Tagesbeginn der neuen Werte. */
    fun setChangeStamp(stamp: ChangeStamp) {
        if (_changeStamp.value == stamp) return
        prefs.edit { putString(KEY_CHANGE_STAMP, stamp.encode()) }
        _changeStamp.value = stamp
    }

    fun setLastRefresh(
        durationMillis: Long,
        at: Long = System.currentTimeMillis(),
    ) {
        prefs.edit {
            putLong(KEY_LAST_DURATION, durationMillis)
            putLong(KEY_LAST_REFRESH_AT, at)
        }

        if (durationMillis > 0) _lastDurationMillis.value = durationMillis
        _lastRefreshAt.value = at
    }

    /** Ersetzt den Bericht (auch bei einem abgebrochenen Durchlauf). */
    fun setLastReport(report: RefreshReport) {
        prefs.edit {
            putString(KEY_LAST_REPORT, encode(report))
            // Bis Runde 21 als Klartext gespeichert
            remove(KEY_LEGACY_REPORT)
        }
        _lastReport.value = report
    }

    /** Wartezeit zwischen Knopfdruck und Start nachtragen (WorkManager). */
    fun setWaitMillis(millis: Long) {
        _lastReport.value?.let { setLastReport(it.copy(waitMillis = millis)) }
    }

    private val _appStartMillis = MutableStateFlow(prefs.getLong(KEY_APP_START, 0L).takeIf { it > 0 })

    /** App-Start bis zum ersten Bild der Merkliste (zuletzt gemessen); null = noch nie. Nur lokal. */
    val appStartMillis: StateFlow<Long?> = _appStartMillis.asStateFlow()

    fun setAppStartMillis(millis: Long) {
        prefs.edit { putLong(KEY_APP_START, millis) }
        _appStartMillis.value = millis
    }

    /** Pausen je Börse (ExchangeBackoff), Schlüssel = Börsen-Kennung. Gespeichert, damit sie
     *  auch über einen Neustart des Prozesses (WorkManager) gelten. */
    @Synchronized
    fun backoffStates(): Map<String, ExchangeBackoff.State> =
        ExchangeBackoff.decode(prefs.getString(KEY_BACKOFF, null))

    @Synchronized
    fun setBackoffStates(states: Map<String, ExchangeBackoff.State>) {
        prefs.edit {
            if (states.isEmpty()) remove(KEY_BACKOFF) else putString(KEY_BACKOFF, ExchangeBackoff.encode(states))
        }
    }

    /** Für Aufrufer ohne Coroutine, etwa das Zeichnen des Widgets. */
    fun lastDuration(): Long = _lastDurationMillis.value

    fun lastRefresh(): Long = _lastRefreshAt.value

    private companion object {
        const val FILE_NAME = "refresh_stats"
        const val KEY_LAST_DURATION = "last_refresh_duration"
        const val KEY_LAST_REFRESH_AT = "last_refresh_at"
        const val KEY_LAST_REPORT = "last_refresh_report_v2"
        const val KEY_LEGACY_REPORT = "last_refresh_report"
        const val KEY_CHANGE_STAMP = "change_basis_stamp"
        const val KEY_APP_START = "app_start_millis"
        const val KEY_BACKOFF = "exchange_backoff"

        fun encode(r: RefreshReport): String = JSONObject()
            .put("at", r.at)
            .put("total", r.totalMillis)
            .put("pairs", r.pairs)
            .put("network", r.networkMillis)
            .put("markets", JSONArray().apply {
                r.markets.forEach { m ->
                    put(
                        JSONObject()
                            .put("name", m.name)
                            .put("millis", m.millis)
                            .put("pairs", m.pairs)
                            .put("updated", m.updated)
                            .put("notTraded", m.notTraded)
                            .put("failed", m.failed)
                            .put("bulkTried", m.bulkTried)
                            .put("bulkMillis", m.bulkMillis)
                            .put("bulkPrices", m.bulkPrices)
                            .put("singles", m.singles)
                            .apply { m.reason?.let { put("reason", it.name) } }
                            .apply { m.pausedUntil?.let { put("pausedUntil", it) } }
                            .apply { m.pauseReason?.let { put("pauseReason", it.name) } }
                    )
                }
            })
            .apply {
                r.dbMillis?.let { put("db", it) }
                r.effectsMillis?.let { put("effects", it) }
                put("alarms", r.alarms)
                put("notifications", r.notifications)
                r.widgetMillis?.let { put("widgets", it) }
                r.waitMillis?.let { put("wait", it) }
                r.aborted?.let { put("aborted", it) }
            }
            .toString()

        /** Unlesbar oder fehlend → null («noch kein Bericht»). */
        fun decode(json: String?): RefreshReport? {
            if (json.isNullOrEmpty()) return null
            return runCatching {
                val o = JSONObject(json)
                val list = o.optJSONArray("markets") ?: JSONArray()
                val markets = (0 until list.length()).map { i ->
                    val m = list.getJSONObject(i)
                    MarketRefresh(
                        name = m.optString("name"),
                        millis = m.optLong("millis"),
                        pairs = m.optInt("pairs"),
                        updated = m.optInt("updated"),
                        notTraded = m.optInt("notTraded"),
                        failed = m.optInt("failed"),
                        bulkTried = m.optBoolean("bulkTried"),
                        bulkMillis = m.optLong("bulkMillis"),
                        bulkPrices = m.optInt("bulkPrices"),
                        singles = m.optInt("singles"),
                        reason = m.optString("reason").let { r -> RefreshFailure.entries.firstOrNull { it.name == r } },
                        pausedUntil = m.optLongOrNull("pausedUntil"),
                        pauseReason = m.optString("pauseReason").let { r -> RefreshFailure.entries.firstOrNull { it.name == r } },
                    )
                }
                RefreshReport(
                    at = o.optLong("at"),
                    totalMillis = o.optLong("total"),
                    pairs = o.optInt("pairs"),
                    networkMillis = o.optLong("network"),
                    markets = markets,
                    dbMillis = o.optLongOrNull("db"),
                    effectsMillis = o.optLongOrNull("effects"),
                    alarms = o.optInt("alarms"),
                    notifications = o.optInt("notifications"),
                    widgetMillis = o.optLongOrNull("widgets"),
                    waitMillis = o.optLongOrNull("wait"),
                    aborted = if (o.has("aborted")) o.optString("aborted") else null,
                )
            }.getOrNull()
        }

        fun JSONObject.optLongOrNull(key: String): Long? = if (has(key)) optLong(key) else null
    }
}
