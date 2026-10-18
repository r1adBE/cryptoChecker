// JSON-Hilfen der Markt-Zwischenspeicher (Teil von `CycleCacheCodecs`): fehlende oder nicht endliche Zahlen werden
// weggelassen (JSON kennt kein NaN), Daten und Zyklus-Marken.
package com.cryptochecker.app.data

import com.cryptochecker.app.domain.market.CycleMarker
import org.json.JSONObject
import java.time.LocalDate

/** JSON-Hilfen für [CycleCacheCodecs]. */
internal object CycleCacheJson {

    // ---- Hilfen: fehlende oder nicht endliche Zahlen werden weggelassen (JSON kennt kein NaN)

    fun JSONObject.putNumber(key: String, value: Double?): JSONObject =
        if (value != null && value.isFinite()) put(key, value) else this

    fun JSONObject.putNumber(key: String, value: Int?): JSONObject =
        if (value != null) put(key, value) else this

    fun JSONObject.putDate(key: String, value: LocalDate?): JSONObject =
        if (value != null) put(key, value.toString()) else this

    fun JSONObject.doubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null

    fun JSONObject.requireDouble(key: String): Double =
        getDouble(key).also { require(it.isFinite()) { "$key nicht endlich" } }

    fun JSONObject.intOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    fun JSONObject.dateOrNull(key: String): LocalDate? =
        if (has(key) && !isNull(key)) LocalDate.parse(getString(key)) else null

    fun Map<String, Double>.toJson(): JSONObject {
        val o = JSONObject()
        forEach { (key, value) -> if (value.isFinite()) o.put(key, value) }
        return o
    }

    fun JSONObject.toDoubleMap(): Map<String, Double> =
        keys().asSequence().mapNotNull { key -> doubleOrNull(key)?.let { key to it } }.toMap()

    fun JSONObject.putMarker(key: String, marker: CycleMarker?): JSONObject {
        if (marker == null) return this
        return put(
            key,
            JSONObject()
                .put("day", marker.day)
                .put("date", marker.date.toString())
                .put("price", marker.priceUsd)
                .put("multiple", marker.multiple)
                .put("change", marker.change)
        )
    }

    fun JSONObject.markerOrNull(key: String): CycleMarker? {
        val m = optJSONObject(key) ?: return null
        return CycleMarker(
            day = m.getInt("day"),
            date = LocalDate.parse(m.getString("date")),
            priceUsd = m.requireDouble("price"),
            multiple = m.requireDouble("multiple"),
            change = m.requireDouble("change"),
        )
    }
}
