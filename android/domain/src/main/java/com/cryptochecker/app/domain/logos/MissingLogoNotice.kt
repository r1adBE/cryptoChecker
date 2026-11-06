package com.cryptochecker.app.domain.logos

/**
 * Hinweis «2 Coins ohne Logo» an der Stelle von «Logos werden geladen …» ([LogoLoadingHint]): Coins der Merkliste, die
 * ein Logo haben sollten, aber weder ein mitgeliefertes noch ein geladenes haben (Initialen).
 *
 * Welche Coins zählen ([keyOf]): die Basis-Coins aller Paare der Merkliste (unabhängig von Gruppe und Filter), nach
 * derselben Regel wie die Plakette — DEX-Pools nie ([CoinLogos.allowedFor]: dort immer Initialen), Kürzel ohne
 * Dateinamen nie ([CoinLogos.fileName]: immer Initialen); TradFi-Paare im eigenen Namensraum ([CoinLogos.tradFiKey]),
 * Krypto wie beim Laden ([CoinLogos.normalize]: «1000PEPE» und «PEPE» sind ein Coin). Der Schlüssel ist zugleich der
 * Schlüssel des Namens ([CoinLogos.nameKey]).
 *
 * Sichtbar ([state]) nur mit Schalter «Coin-Logos › In der App», nie während Logos laden (erstes Laden noch nicht
 * erledigt oder Hinweis «Logos werden geladen» steht) und nur, wenn mindestens ein Coin ohne Logo NICHT im
 * geschlossenen Satz ([dismissed], ✕) liegt. Die Zahl nennt alle Coins ohne Logo. Kommen Logos, sinkt sie von selbst.
 * Gleich in iOS `MissingLogoNotice.swift`, gemeinsame Testfälle `testdata/parity/missing_logos.json`.
 */
object MissingLogoNotice {

    /** Ergebnis: Zeile zeigen und mit welcher Zahl (0, wenn unsichtbar). */
    data class State(val visible: Boolean, val count: Int) {
        companion object {
            val HIDDEN = State(visible = false, count = 0)
        }
    }

    /** Schlüssel eines Coins der Merkliste, der ein Logo haben sollte; null = zeigt immer Initialen (gewollt). */
    fun keyOf(exchangeKey: String, base: String, tradFi: Boolean): String? {
        if (!CoinLogos.allowedFor(exchangeKey)) return null
        val key = if (tradFi) CoinLogos.tradFiKey(base) else CoinLogos.normalize(base)
        return key.takeIf { CoinLogos.fileName(it) != null }
    }

    /** Ein Paar der Merkliste: Börse, Basis-Coin, TradFi. */
    data class WatchedCoin(val exchangeKey: String, val base: String, val tradFi: Boolean)

    /** Schlüssel der Coins aus [pairs] ([keyOf]), ohne doppelte, in der Reihenfolge der Merkliste. */
    fun candidates(pairs: List<WatchedCoin>): List<String> =
        pairs.mapNotNullTo(LinkedHashSet()) { keyOf(it.exchangeKey, it.base, it.tradFi) }.toList()

    /**
     * Zeile zeigen? [missing]: Coins ohne Logo ([candidates] ohne mitgeliefertes oder geladenes Logo); [dismissed]: beim
     * letzten ✕ gemerkte Coins; [loading]: Logos laden noch; [logosEnabled]: Schalter «Coin-Logos › In der App».
     */
    fun state(missing: Collection<String>, dismissed: Set<String>, loading: Boolean, logosEnabled: Boolean): State {
        if (!logosEnabled || loading || missing.isEmpty()) return State.HIDDEN
        val distinct = missing.toSet()
        if (distinct.all { it in dismissed }) return State.HIDDEN
        return State(visible = true, count = distinct.size)
    }

    /** Symbol zum Anzeigen (ohne [CoinLogos.TRADFI_PREFIX]). */
    fun displaySymbol(key: String): String = CoinLogos.displaySymbol(key)

    /** Gemerkter Satz als Text (eine Zeile je Schlüssel, sortiert). */
    fun encode(keys: Set<String>): String = keys.sorted().joinToString("\n")

    /** Gemerkter Satz aus [text]; leer bei null oder leer. */
    fun decode(text: String?): Set<String> =
        text?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
}
