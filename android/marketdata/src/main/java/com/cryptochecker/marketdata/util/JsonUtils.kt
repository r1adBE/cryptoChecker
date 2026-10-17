package com.cryptochecker.marketdata.util

import com.cryptochecker.marketdata.model.Ticker
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Kleine Helfer zum Lesen der Börsenantworten. Gegenstück auf iOS: JsonUtils.swift.

/** Jeden Eintrag der Liste als Text; ein anderer Typ wirft wie `getString`. */
fun JSONArray.forEachString(action: (item: String) -> Unit) {
    repeat(length()) { action(getString(it)) }
}

/** Jeden Eintrag der Liste als Objekt; ein anderer Typ wirft. */
fun JSONArray.forEachJSONObject(action: (item: JSONObject) -> Unit) {
    repeat(length()) { action(getJSONObject(it)) }
}

/**
 * Jeden Eintrag der Liste, der ein Objekt ist; andere Einträge werden übergangen. Nur wo die Börse fremde Einträge
 * mitschickt, die kein Fehler sind — sonst [forEachJSONObject], damit ein geändertes Format auffällt.
 */
fun JSONArray.forEachOptJSONObject(action: (item: JSONObject) -> Unit) {
    repeat(length()) { index -> optJSONObject(index)?.let(action) }
}

/** Jeden Eintrag der Liste als Liste; ein anderer Typ wirft. */
fun JSONArray.forEachJSONArray(action: (item: JSONArray) -> Unit) {
    repeat(length()) { action(getJSONArray(it)) }
}

/** Jedes Feld des Objekts mit seinem Namen; alle Werte müssen Objekte sein. Leeres Objekt: nichts. */
fun JSONObject.forEachName(action: (name: String, item: JSONObject) -> Unit) {
    // names() ist bei einem leeren Objekt null
    val names = names() ?: return
    repeat(names.length()) { i ->
        val name = names.getString(i)
        action(name, getJSONObject(name))
    }
}

/** Zahl eines Felds oder [Ticker.MISSING], wenn es fehlt oder keine Zahl ist. */
fun JSONObject.optDoubleOrMissing(name: String): Double = optDouble(name, Ticker.MISSING)

/**
 * Text eines Felds, leer bei fehlendem Feld oder JSON null. (`optString` gäbe für null den Text «null» zurück,
 * als Fehlertext unbrauchbar und anders als iOS `JObject.optString`.)
 */
fun JSONObject.optText(name: String): String = when {
    isNull(name) -> ""
    else -> optString(name)
}

/** Text eines Pflichtfelds; wirft bei fehlendem Feld und bei JSON null (wie iOS `JObject.string`). */
@Throws(JSONException::class)
fun JSONObject.getText(name: String): String =
    if (isNull(name)) throw JSONException("No value for $name") else getString(name)

/** Nicht leere Texte einer Liste («["TradFi", "Pre-IPO"]»); ohne Liste: leer. */
fun JSONObject.optStrings(name: String): List<String> {
    val list = optJSONArray(name) ?: return emptyList()
    return buildList {
        repeat(list.length()) { i -> list.optString(i).takeIf(String::isNotEmpty)?.let(::add) }
    }
}
