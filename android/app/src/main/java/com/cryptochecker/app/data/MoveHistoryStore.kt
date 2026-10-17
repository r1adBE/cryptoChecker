package com.cryptochecker.app.data

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.domain.alarm.MoveWindow
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kursverlauf je Paar (Watch-Id) für den Bewegungs-Alarm «x % in y Stunden» (gleitendes Fenster,
 * [MoveWindow]). Nur für Paare mit so einem Alarm; höchstens alle 2 Minuten ein Punkt, ältere
 * ausgedünnt, 31 Stunden lang (Spiegel: `MoveHistoryStore` in Shared/Services/AlarmLogic.swift).
 *
 * Bewusst SharedPreferences statt Room: kleine, kurzlebige Daten ohne Migration (wie der
 * Open-Interest-Verlauf in [ActivityRepository]). Format: {"<watchId>": [[zeit, kurs], …]}.
 * Fehlt der Verlauf (gelöscht, neu installiert), prüft der Alarm gegen seinen gespeicherten Bezug.
 */
@Singleton
class MoveHistoryStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val lock = Any()

    /** Gespeicherter Verlauf des Paars, älteste zuerst. */
    fun history(watchId: Long): List<MoveWindow.PricePoint> = synchronized(lock) {
        decode(readMap().optJSONArray(watchId.toString()))
    }

    /**
     * Punkt anhängen und aufräumen ([MoveWindow.append]); schreibt nur, wenn sich etwas ändert.
     * Verläufe anderer Paare, deren letzter Punkt älter als die Aufbewahrung ist (gelöschte Paare,
     * Alarm entfernt), fallen dabei weg.
     */
    fun append(watchId: Long, point: MoveWindow.PricePoint, now: Long) = synchronized(lock) {
        val map = readMap()
        val key = watchId.toString()
        val before = decode(map.optJSONArray(key))
        val updated = MoveWindow.append(before, point, now)
        // Live-Kurse kommen sekündlich: meist kein neuer Punkt — dann nichts schreiben
        if (updated == before) return@synchronized
        val array = JSONArray()
        updated.forEach { array.put(JSONArray().put(it.time).put(it.price)) }
        map.put(key, array)
        map.keys().asSequence().toList().forEach { other ->
            if (other == key) return@forEach
            val last = decode(map.optJSONArray(other)).maxOfOrNull { it.time }
            if (last == null || now - last > MoveWindow.RETENTION_MILLIS) {
                map.remove(other)
            }
        }
        prefs.edit { putString(KEY, map.toString()) }
    }

    private fun readMap(): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY, null) ?: "{}") }.getOrDefault(JSONObject())

    private fun decode(array: JSONArray?): List<MoveWindow.PricePoint> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONArray(i) ?: return@mapNotNull null
            MoveWindow.PricePoint(price = item.optDouble(1, 0.0), time = item.optLong(0, 0L))
        }
    }

    private companion object {
        const val PREFS = "move_history"
        const val KEY = "history"
    }
}
