package com.cryptochecker.marketdata.util

import com.cryptochecker.marketdata.model.Ticker
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject


fun JSONArray.forEachString(function: (item: String) -> Unit) {
    for(i in 0 until this.length()) {
        function(this.getString(i))
    }
}

fun JSONArray.forEachJSONObject(function: (item: JSONObject) -> Unit) {
    for(i in 0 until this.length()) {
        function(this.getJSONObject(i))
    }
}

fun JSONArray.forEachJSONArray(function: (item: JSONArray) -> Unit) {
    for(i in 0 until this.length()) {
        function(this.getJSONArray(i))
    }
}

fun JSONObject.forEachName(function: (name: String, item: JSONObject) -> Unit) {
    // Leeres Objekt: names() ist null – dann gibt es nichts zu durchlaufen
    val namesJsonArray = this.names() ?: return
    for (i in 0 until namesJsonArray.length()) {
        val name = namesJsonArray.getString(i)
        val item = this.getJSONObject(name)
        function(name, item)
    }
}

fun JSONObject.optDoubleNoData(name: String): Double = this.optDouble(name, Ticker.NO_DATA.toDouble())

/**
 * Text eines Felds; fehlt es oder ist es JSON null: leer. `optString` liefert für null den Text
 * «null» – als Fehlertext sinnlos und anders als iOS (`JObject.optString`).
 */
fun JSONObject.optText(name: String): String = if (isNull(name)) "" else optString(name)

/** Wie `getString`, wirft aber auch bei JSON null (statt «null» zu liefern) – wie iOS `JObject.string`. */
@Throws(JSONException::class)
fun JSONObject.getText(name: String): String {
    if (isNull(name)) throw JSONException("No value for $name")
    return getString(name)
}

/** Texte eines Felds mit Liste («["TradFi", "Pre-IPO"]»); fehlt es oder ist es keine Liste: leer. */
fun JSONObject.optStrings(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return (0 until array.length()).mapNotNull { i -> array.optString(i).takeIf { it.isNotEmpty() } }
}
