package com.cryptochecker.app.domain.starter

/**
 * Auswahl in der Start-Merkliste (reines Kotlin, testbar). Gespeichert werden die
 * **abgewählten** Symbole: So sind alle Coins anfangs ausgewählt, und Coins, die
 * eine frischere Liste neu bringt, sind es ebenfalls, ohne die Wahl zu verlieren.
 */
object StarterSelection {

    /** Ausgewählte Symbole in Anzeige-Reihenfolge. */
    fun selected(coins: List<StarterPairs.Coin>, deselected: Set<String>): List<String> =
        coins.map { it.symbol }.filter { it !in deselected }

    fun isSelected(symbol: String, deselected: Set<String>): Boolean = symbol !in deselected

    /** Ein Coin an/aus. */
    fun toggle(deselected: Set<String>, symbol: String): Set<String> =
        if (symbol in deselected) deselected - symbol else deselected + symbol

    /** Sind alle angezeigten Coins ausgewählt? (dann bietet der Umschalter «Keine auswählen») */
    fun allSelected(coins: List<StarterPairs.Coin>, deselected: Set<String>): Boolean =
        coins.none { it.symbol in deselected }

    /** «Alle auswählen» bzw. «Keine auswählen». */
    fun toggleAll(coins: List<StarterPairs.Coin>, deselected: Set<String>): Set<String> =
        if (allSelected(coins, deselected)) coins.map { it.symbol }.toSet() else emptySet()
}

/** Kurs eines Start-Coins in der Start-Quote (USDT bzw. USD) und Veränderung über 24 Stunden in %. */
data class StarterPrice(val price: Double, val change24h: Double?)

/** Kurse der Start-Merkliste (reines Kotlin, testbar); Abruf siehe StarterCoinsRepository. */
object StarterPrices {

    /** Kurse so lange im Speicher behalten. */
    const val TTL_MILLIS = 60_000L

    fun isFresh(savedAt: Long, now: Long): Boolean = savedAt in 1..now && now - savedAt < TTL_MILLIS

    /** Veränderung in % aus Eröffnungs- und letztem Kurs (Coinbase-Statistik); null ohne gültige Werte. */
    fun changePercent(open: Double?, last: Double?): Double? {
        if (open == null || last == null || !open.isFinite() || !last.isFinite() || open <= 0.0) return null
        return (last - open) / open * 100.0
    }

    /** Nur gültige, positive Kurse. */
    fun validPrice(value: Double?): Double? = value?.takeIf { it.isFinite() && it > 0.0 }

    /** Binance-Parameter «symbols»: ["BTCUSDT","ETHUSDT"] (noch nicht URL-kodiert). */
    fun binanceSymbols(bases: List<String>, quote: String): String =
        bases.joinToString(",", "[", "]") { "\"${it.uppercase()}${quote.uppercase()}\"" }

    /** Schlüssel des Speichers: Börse und Coins (Reihenfolge egal). */
    fun cacheKey(marketKey: String, bases: List<String>): String =
        marketKey + ":" + bases.map { it.uppercase() }.sorted().joinToString(",")
}
