package com.cryptochecker.app.data

import androidx.core.content.edit
import android.content.Context
import com.cryptochecker.app.domain.activity.ActivityReport
import com.cryptochecker.app.domain.activity.ActivitySignal
import com.cryptochecker.app.domain.activity.OiSample
import com.cryptochecker.app.domain.activity.SignalKind
import com.cryptochecker.app.domain.activity.SignalSeverity
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ergebnisse der Auswertung «Ungewöhnliche Aktivität» je Paar (Watch-Id),
 * im Speicher als StateFlow und in SharedPreferences, damit die Liste nach
 * einem Neustart sofort weiss, wo etwas los ist. Dazu die letzte
 * Open-Interest-Messung und die Zeit der letzten Meldung je Paar.
 *
 * Bewusst SharedPreferences statt Room: kleine, kurzlebige Daten ohne Migration.
 */
@Singleton
class ActivityRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _reports = MutableStateFlow<Map<Long, ActivityReport>>(emptyMap())
    @Volatile private var loaded = false
    private val lock = Any()

    /** Berichte je Watch-Id; abgelaufene Signale filtert [ActivityReport.active]. */
    val reports: StateFlow<Map<Long, ActivityReport>>
        get() {
            ensureLoaded()
            return _reports.asStateFlow()
        }

    fun report(watchId: Long): ActivityReport? {
        ensureLoaded()
        return _reports.value[watchId]
    }

    /** Mehrere Berichte auf einmal übernehmen und einmal speichern. */
    fun putAll(updates: Map<Long, ActivityReport>) {
        if (updates.isEmpty()) return
        ensureLoaded()
        _reports.update { it + updates }
        persistReports()
    }

    /** Berichte gelöschter Paare wegräumen. */
    fun retain(watchIds: Set<Long>) {
        ensureLoaded()
        val before = _reports.value
        if (before.keys.all { it in watchIds }) return
        _reports.update { map -> map.filterKeys { it in watchIds } }
        persistReports()
        synchronized(lock) {
            fun JSONObject.keepOnly(): JSONObject = apply {
                keys().asSequence().toList().forEach { key ->
                    val id = key.toLongOrNull()
                    if (id == null || id !in watchIds) remove(key)
                }
            }
            prefs.edit {
                putString(KEY_OI, readMap(KEY_OI).keepOnly().toString())
                putString(KEY_OI_HISTORY, readMap(KEY_OI_HISTORY).keepOnly().toString())
                putString(KEY_NOTIFIED, readMap(KEY_NOTIFIED).keepOnly().toString())
            }
        }
    }

    /**
     * Paar bearbeitet (andere Börse/anderes Paar, gleiche Id): Signale, Open-Interest-Messung
     * und Meldezeit des alten Paars verwerfen.
     */
    fun forget(watchId: Long) {
        ensureLoaded()
        if (watchId in _reports.value) {
            _reports.update { it - watchId }
            persistReports()
        }
        synchronized(lock) {
            val key = watchId.toString()
            prefs.edit {
                putString(KEY_OI, readMap(KEY_OI).apply { remove(key) }.toString())
                putString(KEY_OI_HISTORY, readMap(KEY_OI_HISTORY).apply { remove(key) }.toString())
                putString(KEY_NOTIFIED, readMap(KEY_NOTIFIED).apply { remove(key) }.toString())
            }
        }
    }

    // ---------------- Open Interest ----------------

    fun oiSample(watchId: Long): OiSample? = synchronized(lock) {
        readMap(KEY_OI).optJSONObject(watchId.toString())?.let {
            OiSample(units = it.optDouble("u", 0.0), time = it.optLong("t", 0L))
        }
    }

    fun setOiSample(watchId: Long, sample: OiSample) = synchronized(lock) {
        val map = readMap(KEY_OI).put(watchId.toString(), JSONObject().put("u", if (sample.units.isFinite()) sample.units else 0.0).put("t", sample.time))
        prefs.edit { putString(KEY_OI, map.toString()) }
    }

    // ---------------- Open-Interest-Verlauf (Alarme OI_UP/OI_DOWN) ----------------

    /** Gespeicherter Verlauf je Paar, älteste zuerst; {"<watchId>": [[zeit, coins], …]}. */
    fun oiHistory(watchId: Long): List<DerivativesAlarm.OiPoint> = synchronized(lock) {
        decodeHistory(readMap(KEY_OI_HISTORY).optJSONArray(watchId.toString()))
    }

    /** Messung anhängen und Verlauf aufräumen ([DerivativesAlarm.appendOi], 26 h). */
    fun appendOiHistory(watchId: Long, point: DerivativesAlarm.OiPoint, now: Long) = synchronized(lock) {
        val map = readMap(KEY_OI_HISTORY)
        val updated = DerivativesAlarm.appendOi(decodeHistory(map.optJSONArray(watchId.toString())), point, now)
        val array = JSONArray()
        updated.forEach { array.put(JSONArray().put(it.time).put(it.units)) }
        map.put(watchId.toString(), array)
        prefs.edit { putString(KEY_OI_HISTORY, map.toString()) }
    }

    private fun decodeHistory(array: JSONArray?): List<DerivativesAlarm.OiPoint> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONArray(i) ?: return@mapNotNull null
            DerivativesAlarm.OiPoint(units = item.optDouble(1, 0.0), time = item.optLong(0, 0L))
        }
    }

    // ---------------- Meldungen ----------------

    fun lastNotifiedAt(watchId: Long): Long = synchronized(lock) {
        readMap(KEY_NOTIFIED).optLong(watchId.toString(), 0L)
    }

    fun setNotifiedAt(watchId: Long, time: Long) = synchronized(lock) {
        val map = readMap(KEY_NOTIFIED).put(watchId.toString(), time)
        prefs.edit { putString(KEY_NOTIFIED, map.toString()) }
    }

    // ---------------- Speichern ----------------

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            _reports.value = runCatching { decode(prefs.getString(KEY_REPORTS, null)) }
                .onFailure { Timber.w(it, "Aktivitätsdaten unlesbar, beginne leer") }
                .getOrDefault(emptyMap())
            loaded = true
        }
    }

    private fun persistReports() = synchronized(lock) {
        prefs.edit { putString(KEY_REPORTS, encode(_reports.value)) }
    }

    private fun readMap(key: String): JSONObject =
        runCatching { JSONObject(prefs.getString(key, null) ?: "{}") }.getOrDefault(JSONObject())

    private companion object {
        const val PREFS = "activity"
        const val KEY_REPORTS = "reports"
        const val KEY_OI = "open_interest"
        const val KEY_OI_HISTORY = "open_interest_history"
        const val KEY_NOTIFIED = "notified"

        /** {"<watchId>": {"t": computedAt, "s": [{"k","sv","v","f","a"}]}} */
        fun encode(map: Map<Long, ActivityReport>): String {
            val root = JSONObject()
            map.forEach { (id, report) ->
                val signals = JSONArray()
                report.signals.forEach { s ->
                    signals.put(
                        JSONObject()
                            .put("k", s.kind.name)
                            .put("sv", s.severity.name)
                            .put("v", if (s.value.isFinite()) s.value else 0.0)
                            .put("f", s.factor?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                            .put("a", s.seenAt)
                    )
                }
                root.put(id.toString(), JSONObject().put("t", report.computedAt).put("s", signals))
            }
            return root.toString()
        }

        fun decode(json: String?): Map<Long, ActivityReport> {
            if (json.isNullOrEmpty()) return emptyMap()
            val root = JSONObject(json)
            val out = HashMap<Long, ActivityReport>()
            root.keys().forEach { key ->
                val id = key.toLongOrNull() ?: return@forEach
                val o = root.getJSONObject(key)
                val array = o.optJSONArray("s") ?: JSONArray()
                val signals = (0 until array.length()).mapNotNull { i ->
                    val s = array.getJSONObject(i)
                    val kind = SignalKind.entries.firstOrNull { it.name == s.optString("k") } ?: return@mapNotNull null
                    ActivitySignal(
                        kind = kind,
                        severity = SignalSeverity.entries.firstOrNull { it.name == s.optString("sv") }
                            ?: SignalSeverity.NOTABLE,
                        value = s.optDouble("v", 0.0),
                        factor = if (s.isNull("f")) null else s.optDouble("f").takeUnless { it.isNaN() },
                        seenAt = s.optLong("a", 0L),
                    )
                }
                out[id] = ActivityReport(signals, o.optLong("t", 0L))
            }
            return out
        }
    }
}
