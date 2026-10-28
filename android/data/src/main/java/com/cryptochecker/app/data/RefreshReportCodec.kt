package com.cryptochecker.app.data

import com.cryptochecker.app.domain.refresh.ExchangeRefresh
import com.cryptochecker.app.domain.refresh.RefreshFailure
import com.cryptochecker.app.domain.refresh.RefreshReport
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bericht «Letzte Aktualisierung» als JSON für [RefreshStats] (SharedPreferences). Nicht gemessene
 * Werte (null) werden nicht geschrieben und fehlen beim Lesen.
 */
object RefreshReportCodec {

    fun encode(r: RefreshReport): String = JSONObject()
        .put("at", r.at)
        .put("total", r.totalMillis)
        .put("pairs", r.pairs)
        .put("network", r.networkMillis)
        .put("exchanges", JSONArray().apply { r.exchanges.forEach { put(encodeExchange(it)) } })
        .apply {
            r.dbMillis?.let { put("db", it) }
            r.effectsMillis?.let { put("effects", it) }
            put("alarms", r.alarms)
            put("notifications", r.notifications)
            r.widgetMillis?.let { put("widgets", it) }
            r.waitMillis?.let { put("wait", it) }
            r.attempt?.let { put("attempt", it) }
            r.aborted?.let { put("aborted", it) }
        }
        .toString()

    private fun encodeExchange(m: ExchangeRefresh): JSONObject = JSONObject()
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
        .apply { m.detail?.let { put("detail", it) } }

    /** Unlesbar oder fehlend → null («noch kein Bericht»). */
    fun decode(json: String?): RefreshReport? {
        if (json.isNullOrEmpty()) return null
        return runCatching {
            val o = JSONObject(json)
            val list = o.optJSONArray("exchanges") ?: JSONArray()
            RefreshReport(
                at = o.optLong("at"),
                totalMillis = o.optLong("total"),
                pairs = o.optInt("pairs"),
                networkMillis = o.optLong("network"),
                exchanges = (0 until list.length()).map { decodeExchange(list.getJSONObject(it)) },
                dbMillis = o.optLongOrNull("db"),
                effectsMillis = o.optLongOrNull("effects"),
                alarms = o.optInt("alarms"),
                notifications = o.optInt("notifications"),
                widgetMillis = o.optLongOrNull("widgets"),
                waitMillis = o.optLongOrNull("wait"),
                attempt = if (o.has("attempt")) o.optInt("attempt") else null,
                aborted = if (o.has("aborted")) o.optString("aborted") else null,
            )
        }.getOrNull()
    }

    private fun decodeExchange(m: JSONObject) = ExchangeRefresh(
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
        reason = failure(m.optString("reason")),
        pausedUntil = m.optLongOrNull("pausedUntil"),
        pauseReason = failure(m.optString("pauseReason")),
        detail = m.optString("detail").takeIf { it.isNotEmpty() },
    )

    private fun failure(name: String): RefreshFailure? = RefreshFailure.entries.firstOrNull { it.name == name }

    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key)) optLong(key) else null
}
