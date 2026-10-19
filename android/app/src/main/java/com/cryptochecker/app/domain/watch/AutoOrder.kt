package com.cryptochecker.app.domain.watch

/**
 * «Nach Coin ordnen» bzw. «Nach Börse ordnen» im Sortiermodus (⋯ › Sortieren): ordnet die gezeigten
 * Paare einmal und wird danach wie nach dem Ziehen als eigene Reihenfolge gespeichert — von Hand
 * bleibt alles verschiebbar. Favoriten zuerst und unter sich gleich geordnet (wie [ColumnSort]);
 * Gross-/Kleinschreibung egal wie [SortKey.NAME]. Bei Gleichstand bleibt die bisherige Reihenfolge.
 * Wie `AutoOrder.swift` (iOS).
 */
enum class AutoOrder {
    /** Anzeigename A–Z, dann Börse A–Z: gleiche Coins verschiedener Börsen stehen untereinander. */
    COIN,

    /** Nach Börse A–Z gruppiert, innerhalb einer Börse Anzeigename A–Z. */
    EXCHANGE;

    /**
     * [items] neu geordnet: Favoriten zuerst, dann die übrigen.
     * @param name Anzeigename wie in der Zeile (z. B. «BTC/USDT Perp»)
     * @param exchange Börsenname (z. B. «Binance»)
     */
    fun <T> apply(
        items: List<T>,
        favorite: (T) -> Boolean,
        name: (T) -> String,
        exchange: (T) -> String,
    ): List<T> {
        val byName = compareBy<T, String>(String.CASE_INSENSITIVE_ORDER) { name(it) }
        val byExchange = compareBy<T, String>(String.CASE_INSENSITIVE_ORDER) { exchange(it) }
        val order = if (this == COIN) byName.then(byExchange) else byExchange.then(byName)
        val (favorites, others) = items.partition(favorite)
        // sortedWith ist stabil: Gleichstand behält die bisherige Reihenfolge
        return favorites.sortedWith(order) + others.sortedWith(order)
    }
}
