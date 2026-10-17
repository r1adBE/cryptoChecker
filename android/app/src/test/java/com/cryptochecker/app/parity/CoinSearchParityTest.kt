package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.app.settings.AssetScope
import com.cryptochecker.app.ui.features.explorer.CoinHit
import com.cryptochecker.app.ui.features.explorer.CoinPairPreference
import com.cryptochecker.app.ui.features.explorer.CoinSearch
import com.cryptochecker.app.ui.features.explorer.NameIndex
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.MarketPair
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Gemeinsame Testfälle `coin_search.json`: Coin-Suche und Paar-Vorschlag in «Paar hinzufügen»
 * (iOS: `CoinSearchParityTests.swift`) — nur aus geladenen Listen und dem Namensverzeichnis.
 */
class CoinSearchParityTest {

    private val data = ParityJson.load("coin_search.json")

    private val markets: List<MarketInfo> = data["markets"].list().map { raw ->
        val m = raw.obj()
        MarketInfo(m["key"] as String, m["name"] as String)
    }

    private val cache: Map<String, MarketPairsInfo> = data["cache"].obj().mapValues { (_, rows) ->
        MarketPairsInfo(1, rows.list().map { raw ->
            val p = raw.obj()
            MarketPair(
                p["base"] as String,
                p["quote"] as String,
                p["pairId"] as String?,
                FuturesContractType.valueOf(p["contract"] as String),
                p["tradFi"] as Boolean,
            )
        })
    }

    @Suppress("UNCHECKED_CAST")
    private val names = NameIndex(data["names"] as Map<String, String>)

    private fun scope(c: Map<String, Any?>) = (c["scope"] as String?)?.let(AssetScope::valueOf) ?: AssetScope.BOTH

    private fun coins(c: Map<String, Any?>): List<CoinHit> = CoinSearch.coins(
        c["query"] as String, markets, cache,
        names = names,
        scope = scope(c),
        includeUnlisted = c["includeUnlisted"] as Boolean? ?: false,
    )

    @Test
    fun coins() {
        data["coins"].list().forEach { raw ->
            val c = raw.obj()
            val actual = coins(c).map { "${it.symbol}|${it.tradFi}|${it.exchanges}|${it.pairs}" }
            assertEquals("coins: ${c["query"]} ${c["scope"]} ${c["includeUnlisted"]}", c["expected"], actual)
        }
    }

    @Test
    fun namesOfHits() {
        data["names_of_hits"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals("names: ${c["query"]}", c["expected"], coins(c).map { it.name })
        }
    }

    @Test
    fun showPairHits() {
        data["showPairHits"].list().forEach { raw ->
            val c = raw.obj()
            val query = c["query"] as String
            assertEquals("showPairHits: $query", c["expected"], CoinSearch.showPairHits(query, c["coinsFound"] as Boolean))
        }
    }

    @Test
    fun pairsFor() {
        data["pairsFor"].list().forEach { raw ->
            val c = raw.obj()
            val coin = CoinHit(c["coin"] as String, c["tradFi"] as Boolean, name = null, exchanges = 0, pairs = 0)
            @Suppress("UNCHECKED_CAST")
            val preference = CoinPairPreference(
                defaultExchange = c["defaultExchange"] as String?,
                favoriteExchanges = (c["favorites"] as List<String>).toSet(),
                displayCurrency = c["displayCurrency"] as String?,
            )
            val actual = CoinSearch.pairsFor(coin, markets, cache, preference, scope = scope(c)).map {
                "${it.market.key} ${it.pair.base}/${it.pair.quote} ${it.pair.contractType.name}"
            }
            assertEquals("pairsFor: ${c["coin"]} ${c["defaultExchange"]}", c["expected"], actual)
        }
    }

    @Test
    fun quotes() {
        data["quotes"].list().forEach { raw ->
            val c = raw.obj()
            val currency = c["displayCurrency"] as String?
            assertEquals("quotes: $currency", c["expected"], CoinPairPreference(displayCurrency = currency).quotes)
        }
    }
}
