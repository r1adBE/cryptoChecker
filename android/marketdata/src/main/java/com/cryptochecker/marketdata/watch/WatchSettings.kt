package com.cryptochecker.marketdata.watch

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wahl der Uhr: «Wie Handy» ([Mode.PHONE], Liste [phone] = Favoriten des Handys) oder «Eigene Liste»
 * ([Mode.OWN], Liste [own]); [revision] = Änderungsnummer von Wahl und eigener Liste, jede Änderung zählt eins über
 * beide bekannten Stände hinaus ([change]); die höhere Nummer gewinnt (Gleichstand: Handy), unabhängig von der
 * Uhrzeit der Geräte.
 * Zu [phone] die Paare der Favoriten ([pairs], [WatchPair]: Börse und Kontrakt wie auf dem Handy, JSON «pp»); die Uhr
 * zeigt und fragt dann genau dieses Paar ([phoneKeys]). Ohne Paar (ältere Handy-App) bleibt es beim Kürzel.
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
    val pairs: List<WatchPair> = emptyList(),
) {
    enum class Mode(val key: String) { PHONE("phone"), OWN("own") }

    /** «Kurse vom Handy, sonst selbst» (Standard) oder «Immer selbst abrufen». */
    enum class Source(val key: String) { PHONE("phone"), SELF("self") }

    /** Schlüssel der Liste «Wie Handy»: je Coin das Paar des Handys ([WatchPair.key]), ohne Paar das Kürzel. */
    val phoneKeys: List<String> get() = phone.map { coin -> pairs.firstOrNull { it.base == coin }?.key ?: coin }

    /** Schlüssel, die die Uhr zeigt (Kürzel der eigenen Liste bzw. [phoneKeys]); Anzeige über [WatchPair.label]. */
    val shown: List<String> get() = if (mode == Mode.PHONE) phoneKeys else own

    /** Schlüssel beider Listen: Kurse dazu behält die Uhr, das Handy schickt sie. */
    val kept: List<String> get() = own + phoneKeys

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
        .apply {
            if (pairs.isNotEmpty()) {
                val list = JSONArray()
                pairs.forEach { p -> list.put(JSONObject().put("b", p.base).put("q", p.quote).put("x", p.exchange).put("c", p.contract)) }
                put("pp", list)
            }
        }
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
            val phone = WatchCoins.fromFavorites(list("phone"))
            return WatchSettings(
                mode, WatchCoins.cleanOwn(list("own")), phone, o.optLong("r"), source, interval, pairs(o, phone),
            )
        }

        /** Paare «pp» zu [phone]: je Coin das erste gültige Paar mit dieser Basis, in der Reihenfolge von [phone]. */
        private fun pairs(o: JSONObject, phone: List<String>): List<WatchPair> {
            val a = o.optJSONArray("pp") ?: return emptyList()
            val all = (0 until a.length()).mapNotNull { i ->
                val p = a.optJSONObject(i) ?: return@mapNotNull null
                WatchPair.of(p.optString("b"), p.optString("q"), p.optString("x"), p.optString("c"))
            }
            return phone.mapNotNull { coin -> all.firstOrNull { it.base == coin } }
        }

        /**
         * Zusammenführen: Wahl und eigene Liste von der Seite mit der höheren [revision], bei Gleichstand vom Handy (beide
         * Geräte landen so beim selben Stand); Handy-Liste samt [pairs], [source] und [interval] immer vom Handy
         * ([remoteIsPhone] = true, wenn [remote] vom Handy kommt).
         */
        fun merge(local: WatchSettings, remote: WatchSettings, remoteIsPhone: Boolean): WatchSettings {
            val remoteWins = remote.revision > local.revision || (remote.revision == local.revision && remoteIsPhone)
            val winner = if (remoteWins) remote else local
            val phoneSide = if (remoteIsPhone) remote else local
            return winner.copy(
                phone = phoneSide.phone, source = phoneSide.source, interval = phoneSide.interval, pairs = phoneSide.pairs,
            )
        }

        /**
         * Kennt die Uhr ([watch], ihr gemeldeter Stand) einen anderen Handy-Teil als das Handy ([phone]): Favoriten samt
         * Paaren, Kursquelle oder Intervall? Dann hat sie den Stand des Handys nicht (Uhr-App später dazugekommen,
         * Ablegen gescheitert) — das Handy schickt ihn noch einmal.
         */
        fun phoneSideDiffers(phone: WatchSettings, watch: WatchSettings): Boolean =
            phone.phone != watch.phone || phone.pairs != watch.pairs || phone.source != watch.source ||
                phone.interval != watch.interval

        /**
         * Handy ohne Stand (App dort zurückgesetzt oder entfernt): wie ein Handy mit Standardwerten ([merge] mit
         * `WatchSettings()`) — Handy-Liste und Paare leer, Kursquelle und Intervall Standard; Wahl und eigene Liste der
         * Uhr bleiben, sobald sie einmal geändert wurden (Nummer > 0). Wear OS: Eintrag des Handys gelöscht; Apple Watch:
         * iPhone-App nicht mehr installiert.
         */
        fun forgetPhone(local: WatchSettings): WatchSettings = merge(local, WatchSettings(), remoteIsPhone = true)

        /**
         * Eigene Änderung von Wahl oder eigener Liste auf dieser Seite ([isPhone]): zuerst den neuesten bekannten Stand
         * der anderen Seite ([remote], null = keiner) übernehmen ([merge]), dann [edit] anwenden, mit einer Nummer über
         * beiden Ständen (max(lokal, andere Seite) + 1). So gewinnt die Änderung auf beiden Geräten, auch wenn die andere
         * Seite schon weiter gezählt hatte (Uhr 5, Handy 3 ändert → 6). [edit] ändert die Nummer nicht.
         */
        fun change(
            local: WatchSettings,
            remote: WatchSettings?,
            isPhone: Boolean,
            edit: (WatchSettings) -> WatchSettings,
        ): WatchSettings {
            val base = remote?.let { merge(local, it, remoteIsPhone = !isPhone) } ?: local
            val top = maxOf(local.revision, remote?.revision ?: 0L, base.revision)
            return edit(base).copy(revision = top + 1)
        }
    }
}
