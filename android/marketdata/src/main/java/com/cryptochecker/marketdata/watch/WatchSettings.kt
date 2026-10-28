package com.cryptochecker.marketdata.watch

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wahl der Uhr: «Wie Handy» ([Mode.PHONE], Liste [phone] = Favoriten des Handys) oder «Eigene Liste»
 * ([Mode.OWN], Liste [own]); [revision] = Änderungsnummer von Wahl und eigener Liste, jede Änderung zählt eins über
 * den bekannten Stand hinaus ([nextRevision]); die höhere Nummer gewinnt (Gleichstand: Handy), unabhängig von der
 * Uhrzeit der Geräte.
 * Übertragen wird nur das (Android: Wear-Daten-Schicht, iPhone: WatchConnectivity), als JSON — gleich auf beiden
 * Plattformen.
 */
data class WatchSettings(
    val mode: Mode = Mode.OWN,
    val own: List<String> = WatchCoins.DEFAULT_OWN,
    val phone: List<String> = emptyList(),
    val revision: Long = 0L,
) {
    enum class Mode(val key: String) { PHONE("phone"), OWN("own") }

    /** Coins, die die Uhr zeigt. */
    val shown: List<String> get() = if (mode == Mode.PHONE) phone else own

    /** Für eine eigene Änderung von Wahl oder eigener Liste: eine Nummer über dem bisherigen Stand. */
    fun nextRevision(): WatchSettings = copy(revision = revision + 1)

    fun toJson(): String = JSONObject()
        .put("v", 2)
        .put("mode", mode.key)
        .put("own", JSONArray(own))
        .put("phone", JSONArray(phone))
        .put("r", revision)
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
            return WatchSettings(mode, WatchCoins.cleanOwn(list("own")), WatchCoins.fromFavorites(list("phone")), o.optLong("r"))
        }

        /**
         * Zusammenführen: Wahl und eigene Liste von der Seite mit der höheren [revision], bei Gleichstand vom Handy (beide
         * Geräte landen so beim selben Stand); die Handy-Liste immer vom Handy ([remoteIsPhone] = true, wenn [remote] vom
         * Handy kommt).
         */
        fun merge(local: WatchSettings, remote: WatchSettings, remoteIsPhone: Boolean): WatchSettings {
            val remoteWins = remote.revision > local.revision || (remote.revision == local.revision && remoteIsPhone)
            val winner = if (remoteWins) remote else local
            val phone = if (remoteIsPhone) remote.phone else local.phone
            return winner.copy(phone = phone)
        }
    }
}
