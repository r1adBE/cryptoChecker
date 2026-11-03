package com.cryptochecker.marketdata.watch

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wahl der Uhr: «Wie Handy» ([Mode.PHONE], Liste [phone] = Favoriten des Handys) oder «Eigene Liste»
 * ([Mode.OWN], Liste [own]); [revision] = Änderungsnummer von Wahl und eigener Liste, jede Änderung zählt eins über
 * den bekannten Stand hinaus ([nextRevision]); die höhere Nummer gewinnt (Gleichstand: Handy), unabhängig von der
 * Uhrzeit der Geräte.
 * Dazu zwei Werte, die nur das Handy bestimmt (wie [phone]): [source] (Kurse vom Handy oder immer selbst abrufen) und
 * [interval] (Intervall der Kurs-Aktualisierung des Handys, [WatchInterval.clamp]).
 * Übertragen wird das (Android: Wear-Daten-Schicht, iPhone: WatchConnectivity) als JSON — gleich auf beiden
 * Plattformen.
 */
data class WatchSettings(
    val mode: Mode = Mode.OWN,
    val own: List<String> = WatchCoins.DEFAULT_OWN,
    val phone: List<String> = emptyList(),
    val revision: Long = 0L,
    val source: Source = Source.PHONE,
    val interval: Int = WatchInterval.DEFAULT_MINUTES,
) {
    enum class Mode(val key: String) { PHONE("phone"), OWN("own") }

    /** «Kurse vom Handy, sonst selbst» (Standard) oder «Immer selbst abrufen». */
    enum class Source(val key: String) { PHONE("phone"), SELF("self") }

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
        .put("src", source.key)
        .put("i", interval)
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
            val source = Source.entries.firstOrNull { it.key == o.optString("src") } ?: Source.PHONE
            val interval = WatchInterval.clamp(o.optInt("i", WatchInterval.DEFAULT_MINUTES))
            return WatchSettings(
                mode, WatchCoins.cleanOwn(list("own")), WatchCoins.fromFavorites(list("phone")), o.optLong("r"), source, interval,
            )
        }

        /**
         * Zusammenführen: Wahl und eigene Liste von der Seite mit der höheren [revision], bei Gleichstand vom Handy (beide
         * Geräte landen so beim selben Stand); Handy-Liste, [source] und [interval] immer vom Handy ([remoteIsPhone] =
         * true, wenn [remote] vom Handy kommt).
         */
        fun merge(local: WatchSettings, remote: WatchSettings, remoteIsPhone: Boolean): WatchSettings {
            val remoteWins = remote.revision > local.revision || (remote.revision == local.revision && remoteIsPhone)
            val winner = if (remoteWins) remote else local
            val phoneSide = if (remoteIsPhone) remote else local
            return winner.copy(phone = phoneSide.phone, source = phoneSide.source, interval = phoneSide.interval)
        }
    }
}
