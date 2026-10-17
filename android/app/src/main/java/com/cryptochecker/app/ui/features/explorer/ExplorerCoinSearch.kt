package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.TokenIdentity
import com.cryptochecker.app.settings.AssetScope
import com.cryptochecker.marketdata.model.FuturesContractType

/**
 * Ein Coin der Coin-Suche. [symbol] gross wie in den Paarlisten; [tradFi] trennt das Aktien-Kürzel vom
 * gleichnamigen Token. [exchanges]/[pairs] zählen nur Paarlisten, die schon auf dem Gerät liegen — 0 heisst:
 * nur aus dem Namensverzeichnis bekannt. [alpha]: alle seine Paare sind Alpha-Token («α»).
 */
data class CoinHit(
    val symbol: String,
    val tradFi: Boolean,
    val name: String?,
    val exchanges: Int,
    val pairs: Int,
    val alpha: Boolean = false,
) {
    /** Kennung für Listen und Auswahl (TradFi im eigenen Namensraum wie bei den Logos). */
    val id: String get() = CoinLogos.logoKey(symbol, tradFi)
}

/**
 * Was beim Vorschlag des Standard-Paars zählt: die Start-Börse der Region (wie die Start-Merkliste,
 * `StarterPairs.marketKey`), die Lieblingsbörsen und die Umrechnungswährung der Einstellungen.
 */
data class CoinPairPreference(
    val defaultExchange: String? = null,
    val favoriteExchanges: Set<String> = emptySet(),
    val displayCurrency: String? = null,
) {
    /**
     * Gegenwährungen in dieser Reihenfolge: zuerst die eigene Währung (Kurs ohne Umrechnung), dann die
     * gängigen Dollar-Stablecoins bzw. USD, die an fast jeder Börse die grössten Bücher haben.
     */
    val quotes: List<String> =
        (listOfNotNull(displayCurrency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }) + COMMON_QUOTES).distinct()

    private companion object {
        val COMMON_QUOTES = listOf("USDT", "USD", "USDC", "EUR", "BTC")
    }
}

/**
 * Coin zuerst: «Bitcoin», «btc» oder «Ethereum» finden den Coin — nicht ein einzelnes Paar —, danach zeigt
 * [pairsFor] seine Paare über alle Börsen mit einem sinnvollen Standard vorne. Alles läuft auf dem Gerät:
 * Namen aus dem gespeicherten bzw. mitgelieferten Verzeichnis ([NameIndex]), Paare aus den Paarlisten, die
 * schon geladen sind. Datenschutz: Die Suche fragt nie etwas zu einem bestimmten Coin ab; fehlt eine Liste,
 * bietet die Seite an, die ganze öffentliche Paarliste der Börse zu laden (für alle gleich).
 * Wie `ExplorerCoinSearch` in iOS (`ExplorerCoinSearch.swift`).
 */
object CoinSearch {

    /** Höchstens so viele Coins als Treffer. */
    const val LIMIT = 10

    // Ränge: Kürzel genau, Name genau, Kürzel-Anfang, Name-Anfang, Name enthält
    private const val TIER_SYMBOL = 0
    private const val TIER_NAME = 1
    private const val TIER_SYMBOL_PREFIX = 2
    private const val TIER_NAME_PREFIX = 3
    private const val TIER_NAME_CONTAINS = 4

    /** Suchbegriff als Coin: ein Wort ist Kürzel oder Name, mehrere Wörter nur ein Name («shiba inu»). */
    private class Terms(words: List<String>) {
        val symbol: String? = words.singleOrNull()
        val aliasSymbols: List<String> = symbol?.let(PairSearch::aliasesOf).orEmpty()
        val name: String = NameMatch.fold(words.joinToString(" "))

        /** Rang für das Kürzel [symbol] mit dem gefalteten Namen [foldedName]; null = kein Treffer. */
        fun tier(symbol: String, foldedName: String?): Int? {
            val term = this.symbol
            if (term != null && (symbol == term || symbol in aliasSymbols)) return TIER_SYMBOL
            if (foldedName != null && foldedName == name) return TIER_NAME
            if (term != null && symbol.startsWith(term)) return TIER_SYMBOL_PREFIX
            return when (foldedName?.let { NameMatch.score(it, name) }) {
                NameMatch.PREFIX -> TIER_NAME_PREFIX
                NameMatch.CONTAINS -> TIER_NAME_CONTAINS
                else -> null
            }
        }
    }

    /** Zwischenstand je Coin beim Durchgehen der Paarlisten. */
    private class Tally(val symbol: String, val tradFi: Boolean, val tier: Int) {
        val exchanges = HashSet<String>()
        var pairs = 0
        var alphaPairs = 0
    }

    /**
     * Nur ein Begriff für einen Coin sucht Coins; Laufzeit («perp», «qtly») oder Verfallsdatum meinen ein
     * bestimmtes Paar ([PairSearch]).
     */
    private fun coinTerms(query: String): Terms? {
        val q = PairSearch.parse(query) ?: return null
        if (q.contracts != null || q.date != null) return null
        return Terms(q.words)
    }

    /**
     * Paar-Treffer zusätzlich zeigen? Bei mehreren Wörtern («eth usdt», «btc kraken»), Laufzeit oder Datum
     * sucht jemand ein bestimmtes Paar; sonst nur, wenn kein Coin passt («BTCUSDT»).
     */
    fun showPairHits(query: String, coinsFound: Boolean): Boolean {
        val q = PairSearch.parse(query) ?: return false
        return q.words.size > 1 || q.contracts != null || q.date != null || !coinsFound
    }

    /**
     * Coins zu [query] aus den geladenen Listen ([cache]) und dem Namensverzeichnis. Rang: Kürzel genau,
     * Name genau, Kürzel-Anfang, Name-Anfang, Name enthält; darin mehr Börsen zuerst, dann der kürzere Name
     * bzw. das kürzere Kürzel. [includeUnlisted]: auch Coins ohne geladenes Paar (solange noch Listen fehlen —
     * sonst gibt es für sie nichts hinzuzufügen).
     */
    fun coins(
        query: String,
        markets: List<MarketInfo>,
        cache: Map<String, MarketPairsInfo>,
        names: NameIndex = NameIndex.EMPTY,
        scope: AssetScope = AssetScope.BOTH,
        hiddenAlpha: Set<String> = emptySet(),
        alphaSymbols: Set<String> = emptySet(),
        alphaNames: Map<String, String> = emptyMap(),
        pairNames: Map<String, Map<String, String>> = emptyMap(),
        includeUnlisted: Boolean = false,
        limit: Int = LIMIT,
    ): List<CoinHit> {
        val terms = coinTerms(query) ?: return emptyList()
        // Je Coin einmal bewertet; null = passt nicht (auch gemerkt, damit nicht jedes Paar neu rechnet)
        val tallies = HashMap<String, Tally?>()

        fun tally(symbol: String, tradFi: Boolean): Tally? {
            val key = CoinLogos.logoKey(symbol, tradFi)
            if (key in tallies) return tallies[key]
            val tier = terms.tier(symbol, names.folded(CoinLogos.nameKey(symbol, tradFi)))
            return tier?.let { Tally(symbol, tradFi, it) }.also { tallies[key] = it }
        }

        for (market in markets) {
            val info = cache[market.key] ?: continue
            // Keine Namen für DEX-Pools: dort darf ein fremder Token «BTC» heissen
            if (!CoinLogos.allowedFor(market.key)) continue
            val marketNames = pairNames[market.key].orEmpty()
            for (pair in info.pairs) {
                if (!scope.allows(pair, market.key, hiddenAlpha, marketNames, alphaNames)) continue
                val t = tally(pair.base.uppercase(), pair.tradFi) ?: continue
                t.exchanges += market.key
                t.pairs++
                val kind = AssetKind.of(
                    market.key, pair.base, pair.tradFi, alphaSymbols, pair.pairId,
                    marketNames[TokenIdentity.key(pair.base)], alphaNames,
                )
                if (kind == AssetKind.ALPHA) t.alphaPairs++
            }
        }

        if (includeUnlisted) {
            for (entry in names.entries) {
                val tradFi = entry.key.startsWith(CoinLogos.TRADFI_PREFIX)
                val symbol = entry.key.removePrefix(CoinLogos.TRADFI_PREFIX)
                val shown = if (tradFi) scope.allowsTradFi else scope.allowsTokens && symbol !in hiddenAlpha
                if (symbol.isEmpty() || !shown) continue
                tally(symbol, tradFi)
            }
        }

        return tallies.values.filterNotNull()
            .map { t -> t to names.name(CoinLogos.nameKey(t.symbol, t.tradFi)) }
            .sortedWith(
                compareBy<Pair<Tally, String?>> { it.first.tier }
                    .thenByDescending { it.first.exchanges.size }
                    .thenBy { (t, name) -> if (isNameTier(t.tier)) name?.length ?: Int.MAX_VALUE else t.symbol.length }
                    .thenBy { it.first.symbol }
                    .thenBy { it.first.tradFi }
            )
            .take(limit)
            .map { (t, name) ->
                CoinHit(
                    symbol = t.symbol,
                    tradFi = t.tradFi,
                    name = name,
                    exchanges = t.exchanges.size,
                    pairs = t.pairs,
                    alpha = t.pairs > 0 && t.alphaPairs == t.pairs,
                )
            }
    }

    private fun isNameTier(tier: Int) = tier == TIER_NAME || tier == TIER_NAME_PREFIX || tier == TIER_NAME_CONTAINS

    /**
     * Alle Paare von [coin] über die geladenen Listen, der Vorschlag zuerst: Start-Börse der Region, dann
     * Lieblingsbörsen, dann die übrigen; darin Spot vor Perpetual vor Laufzeit, die Gegenwährungen in der
     * Reihenfolge von [preference], dann die Börse (alphabetisch).
     */
    fun pairsFor(
        coin: CoinHit,
        markets: List<MarketInfo>,
        cache: Map<String, MarketPairsInfo>,
        preference: CoinPairPreference = CoinPairPreference(),
        scope: AssetScope = AssetScope.BOTH,
        hiddenAlpha: Set<String> = emptySet(),
        alphaSymbols: Set<String> = emptySet(),
        alphaNames: Map<String, String> = emptyMap(),
        pairNames: Map<String, Map<String, String>> = emptyMap(),
    ): List<SearchHit> {
        val found = ArrayList<SearchHit>()
        for (market in markets) {
            val info = cache[market.key] ?: continue
            if (!CoinLogos.allowedFor(market.key)) continue
            val marketNames = pairNames[market.key].orEmpty()
            for (pair in info.pairs) {
                if (pair.tradFi != coin.tradFi || pair.base.uppercase() != coin.symbol) continue
                if (!scope.allows(pair, market.key, hiddenAlpha, marketNames, alphaNames)) continue
                val kind = AssetKind.of(
                    market.key, pair.base, pair.tradFi, alphaSymbols, pair.pairId,
                    marketNames[TokenIdentity.key(pair.base)], alphaNames,
                )
                found += SearchHit(market, pair, kind == AssetKind.ALPHA)
            }
        }
        fun exchangeTier(key: String) = when (key) {
            preference.defaultExchange -> 0
            in preference.favoriteExchanges -> 1
            else -> 2
        }
        fun quoteRank(quote: String) = preference.quotes.indexOf(quote.uppercase()).let { if (it < 0) preference.quotes.size else it }
        return found
            .sortedWith(
                compareBy<SearchHit> { exchangeTier(it.market.key) }
                    .thenBy { contractGroup(it.pair.contractType) }
                    .thenBy { quoteRank(it.pair.quote) }
                    .thenBy { it.market.name.lowercase() }
                    .thenBy { it.pair.quote.uppercase() }
                    .thenBy { PairSearch.contractRank(it.pair.contractType) }
            )
            // Manche Börsen führen ein Paar doppelt — Listen-Schlüssel müssen eindeutig sein
            .distinctBy { listOf(it.market.key, it.pair.base, it.pair.quote, it.pair.contractType.name) }
    }

    /** Spot, Perpetual (auch invers), Laufzeit-Futures. */
    private fun contractGroup(type: FuturesContractType): Int = when (type) {
        FuturesContractType.NONE -> 0
        FuturesContractType.PERPETUAL, FuturesContractType.INVERSE_PERPETUAL -> 1
        else -> 2
    }
}
