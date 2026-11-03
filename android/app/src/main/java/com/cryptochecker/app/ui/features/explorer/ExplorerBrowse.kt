// Übersicht von «Paar hinzufügen» ohne Suchbegriff: Markt-Chips (Alle · Spot · Futures · DEX · Devisen), «Beliebt»,
// «Zuletzt genutzte Börsen» und das Zeichen der Börsen. Reine Logik (testbar); gleich in iOS `ExplorerBrowse.swift`,
// gemeinsame Fälle in `testdata/parity/explorer_browse.json`.
package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.marketdata.config.ExchangesConfig
import com.cryptochecker.marketdata.model.ExchangeCategory

/** Markt-Chip über den Börsen und Treffern; [ALL] = alles (Standard), [FOREX] = EZB-Referenzkurse. */
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
 * «Beliebt»: rund [COUNT] Coins der Start-Börse (Binance, in den USA Binance.US) als …/USDT-Paare — zuerst die
 * gespeicherte Start-Auswahl (grösste Coins), dann die Ausweich-Liste, dann [EXTRA]. Liegt die Paarliste der
 * Start-Börse schon auf dem Gerät ([available] = Basis-Kürzel mit Paar in USDT), nur Coins mit Paar; null = keine
 * Liste, dann ungeprüft. Kein Abruf. Wie iOS `ExplorerPopularPairs`.
 */
object PopularPairs {
    const val COUNT = 6

    /** Weitere grosse Coins, falls die Start-Auswahl weniger als [COUNT] liefert. */
    val EXTRA = listOf("DOGE", "ADA", "TRX", "LTC")

    fun pick(starter: List<String>, fallback: List<String>, available: Set<String>?): List<String> =
        (starter + fallback + EXTRA)
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() && (available == null || it in available) }
            .distinct()
            .take(COUNT)
}

/**
 * Zeichen einer Börse in den Listen (es gibt keine Börsen-Logos auf dem Gerät und keinen Abruf dafür): farbiger Kreis
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
