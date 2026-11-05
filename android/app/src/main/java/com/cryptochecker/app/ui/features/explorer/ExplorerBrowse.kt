// Übersicht von «Paar hinzufügen» ohne Suchbegriff: Markt-Chips (Alle · Spot · Futures · DEX · Devisen),
// «Zuletzt genutzte Börsen» und das Zeichen der Börsen. Reine Logik (testbar); gleich in iOS `ExplorerBrowse.swift`,
// gemeinsame Fälle in `testdata/parity/explorer_browse.json`.
package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.domain.convert.FxCurrencies
import com.cryptochecker.marketdata.config.ExchangesConfig
import com.cryptochecker.marketdata.model.ExchangeCategory

/** Markt-Chip über den Börsen und Treffern; [ALL] = alles (Standard), [FOREX] = Devisen (Referenzkurse, live: [ForexLive]). */
enum class MarketFilter {
    ALL, SPOT, FUTURES, DEX, FOREX;

    /** Zeigt dieser Chip Börsen der Art [category]? */
    fun allows(category: ExchangeCategory): Boolean = when (this) {
        ALL -> true
        SPOT -> category == ExchangeCategory.SPOT
        FUTURES -> category == ExchangeCategory.FUTURES
        DEX -> category == ExchangeCategory.DEX
        FOREX -> category == ExchangeCategory.FOREX
    }

    /** Wie [allows] für den Börsen-Schlüssel [exchangeKey] (Art aus der festen Börsenliste, sonst Spot). */
    fun allowsExchange(exchangeKey: String): Boolean = allows(categoryOf(exchangeKey))

    companion object {
        /** Art der Börse [exchangeKey] laut [ExchangesConfig]; unbekannt = Spot (wie die Abschnitte der Auswahl). */
        fun categoryOf(exchangeKey: String): ExchangeCategory =
            ExchangesConfig.EXCHANGES[exchangeKey]?.category ?: ExchangeCategory.SPOT
    }
}

/**
 * Chip «Devisen», Gruppe «Live (Börse)»: echte Fiat-Fiat-Paare von Kraken und Bitstamp — gewöhnliche Paare mit Alarmen
 * und Live-Strom (EUR/USD, USD/JPY …). Aus dieser Gruppe geöffnet zeigt die Börse nur ihre Fiat-Paare ([isFiatPair]);
 * die Gruppe «Referenzkurs» sind die Börsen der Art [ExchangeCategory.FOREX] (EZB, NBU, NBP). Wie iOS
 * `ExplorerForexLive`, gemeinsame Fälle in `explorer_browse.json` («forexLive»).
 */
object ForexLive {
    /** Börsen mit echten Devisenpaaren, in dieser Reihenfolge. */
    val EXCHANGES = listOf("Kraken", "Bitstamp")

    private val FIAT: Set<String> = FxCurrencies.ALL.toSet()

    /** Gehört [exchangeKey] zur Gruppe «Live (Börse)»? */
    fun isLive(exchangeKey: String): Boolean = exchangeKey in EXCHANGES

    /** Beide Seiten Fiat-Währungen (EZB-Liste, [FxCurrencies]) und verschieden: ein Devisenpaar. */
    fun isFiatPair(base: String, quote: String): Boolean =
        base != quote && base.uppercase() in FIAT && quote.uppercase() in FIAT
}

/**
 * «Zuletzt genutzte Börsen»: Schlüssel der zuletzt geöffneten Börsen, neueste zuerst, jede nur einmal, höchstens
 * [MAX]. Nur auf dem Gerät ([RecentSearchStore]). Wie iOS `ExplorerRecentExchanges`.
 */
object RecentExchanges {
    const val MAX = 4

    /** [key] nach vorne; leer bleibt die Liste, wie sie ist (höchstens [MAX]). */
    fun add(list: List<String>, key: String): List<String> {
        val k = key.trim()
        if (k.isEmpty()) return list.take(MAX)
        return (listOf(k) + list.filter { it != k }).take(MAX)
    }

    /** Gespeicherter Text (ein Schlüssel pro Zeile) → Liste; leer/doppelt weg. */
    fun decode(text: String?): List<String> =
        text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX)

    fun encode(list: List<String>): String = list.joinToString("\n")
}

/**
 * Ersatz-Zeichen einer Börse in den Listen (solange ihr Logo lädt oder fehlt, [ExchangeMarkBadge]): farbiger Kreis
 * mit dem Anfangsbuchstaben, bei zwei Wörtern beide («Bybit Futures» → «BF»). Die Farbe folgt aus dem ersten Wort
 * ([colorKey]), so haben «Bybit» und «Bybit Futures» dieselbe. Wie iOS `ExplorerExchangeMark`.
 */
object ExchangeMark {
    private fun words(name: String): List<String> =
        name.trim().split(Regex("\\s+")).filter { w -> w.firstOrNull()?.isLetterOrDigit() == true }

    fun initials(name: String): String =
        words(name).take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

    /** Kürzel für die Farbe ([com.cryptochecker.app.domain.logos.CoinLogoPlaceholder]). */
    fun colorKey(name: String): String = words(name).firstOrNull()?.uppercase() ?: "?"
}

/**
 * Börsen zum Suchtext: ein Wort (ab 3 Zeichen) nennt den Namen ([NameMatch.matchesExchange]: «kraken», «btc kraken»,
 * «cryptocom»). Reihenfolge wie [items], höchstens [LIMIT]. Wie iOS `ExplorerExchangeSearch`.
 */
object ExchangeSearch {
    const val LIMIT = 5

    fun <T> find(query: String, items: List<T>, name: (T) -> String): List<T> {
        val words = query.split(' ', '/', '-', ':', '_').map { NameMatch.fold(it) }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return items.filter { item ->
            val folded = NameMatch.fold(name(item))
            words.any { NameMatch.matchesExchange(folded, it) }
        }.take(LIMIT)
    }
}
