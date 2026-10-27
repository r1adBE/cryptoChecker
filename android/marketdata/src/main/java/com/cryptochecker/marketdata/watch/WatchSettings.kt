package com.cryptochecker.marketdata.watch

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wahl der Uhr (Build 154): «Wie Handy» ([Mode.PHONE], Liste [phone] = Favoriten des Handys) oder «Eigene Liste»
 * ([Mode.OWN], Liste [own]); [time] = letzte Änderung (Epoch-ms), die neuere Seite gewinnt. Übertragen wird nur das
 * (Android: Wear-Daten-Schicht, iPhone: WatchConnectivity), als JSON — gleich auf beiden Plattformen.
 */
data class WatchSettings(
    val mode: Mode = Mode.OWN,
    val own: List<String> = WatchCoins.DEFAULT_OWN,
    val phone: List<String> = emptyList(),
    val time: Long = 0L,
) {
    enum class Mode(val key: String) { PHONE("phone"), OWN("own") }

    /** Coins, die die Uhr zeigt. */
    val shown: List<String> get() = if (mode == Mode.PHONE) phone else own

    fun toJson(): String = JSONObject()
        .put("v", 1)
        .put("mode", mode.key)
        .put("own", JSONArray(own))
        .put("phone", JSONArray(phone))
        .put("t", time)
        .toString()

    companion object {
        /** Liest [text]; Unlesbares oder Unbekanntes → null bzw. Standardwerte je Feld. */
        fun fromJson(text: String?): WatchSettings? {
            val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            fun list(name: String): List<String> {
                val a = o.optJSONArray(name) ?: return emptyList()
                return (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { s -> s.isNotEmpty() } }
            }
            val mode = Mode.entries.firstOrNull { it.key == o.optString("mode") } ?: Mode.OWN
            return WatchSettings(mode, WatchCoins.cleanOwn(list("own")), WatchCoins.fromFavorites(list("phone")), o.optLong("t"))
        }

        /**
         * Zusammenführen: Wahl und eigene Liste von der neueren Seite ([time]); die Handy-Liste immer vom Handy
         * ([remoteIsPhone] = true, wenn [remote] vom Handy kommt).
         */
        fun merge(local: WatchSettings, remote: WatchSettings, remoteIsPhone: Boolean): WatchSettings {
            val newer = if (remote.time > local.time) remote else local
            val phone = if (remoteIsPhone) remote.phone else local.phone
            return newer.copy(phone = phone)
        }
    }
}
