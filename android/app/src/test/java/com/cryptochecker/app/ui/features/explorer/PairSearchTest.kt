package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Suche wie an der Börse: «BTCUSDT», «BTCUSDT Qtly 1225», «BTCUSD_PERP». */
class PairSearchTest {

    private val spot = MarketInfo("Binance", "Binance")
    private val futures = MarketInfo("BinanceFutures", "Binance Futures")
    private val cache = mapOf(
        "Binance" to MarketPairsInfo(1, listOf(
            MarketPair("BTC", "USDT", "BTCUSDT"),
            MarketPair("BTC", "EUR", "BTCEUR"),
            MarketPair("ETH", "USDT", "ETHUSDT"),
        )),
        "BinanceFutures" to MarketPairsInfo(1, listOf(
            MarketPair("BTC", "USDT", "BTCUSDT_261225", FuturesContractType.QUARTERLY),
            MarketPair("BTC", "USDT", "BTCUSDT_270326", FuturesContractType.BIQUARTERLY),
            MarketPair("BTC", "USDT", "BTCUSDT", FuturesContractType.PERPETUAL),
            MarketPair("BTC", "USD", "2:BTCUSD_PERP", FuturesContractType.INVERSE_PERPETUAL),
        )),
    )
    private val markets = listOf(spot, futures)

    private fun find(q: String) = PairSearch.find(q, markets, cache).map {
        "${it.market.key} ${it.pair.base}/${it.pair.quote} ${it.pair.contractType.name}"
    }

    @Test
    fun joinedSymbolFindsSpotPerpAndQuarters() {
        assertEquals(
            listOf(
                "Binance BTC/USDT NONE",
                "BinanceFutures BTC/USDT PERPETUAL",
                "BinanceFutures BTC/USDT QUARTERLY",
                "BinanceFutures BTC/USDT BIQUARTERLY",
            ),
            find("BTCUSDT"),
        )
    }

    @Test
    fun quarterWordAndDateNarrowDown() {
        assertEquals(listOf("BinanceFutures BTC/USDT QUARTERLY"), find("BTCUSDT Qtly 1225"))
        assertEquals(listOf("BinanceFutures BTC/USDT QUARTERLY"), find("BTCUSDT_261225"))
        assertEquals(listOf("BinanceFutures BTC/USDT BIQUARTERLY"), find("btc usdt 0326"))
        assertEquals(
            listOf("BinanceFutures BTC/USDT QUARTERLY", "BinanceFutures BTC/USDT BIQUARTERLY"),
            find("BTC USDT Quartal"),
        )
    }

    @Test
    fun perpWord() {
        assertEquals(listOf("BinanceFutures BTC/USD INVERSE_PERPETUAL"), find("BTCUSD_PERP"))
        assertEquals(
            listOf("BinanceFutures BTC/USDT PERPETUAL", "BinanceFutures BTC/USD INVERSE_PERPETUAL"),
            find("BTC PERP"),
        )
    }

    @Test
    fun classicQueriesUnchanged() {
        assertTrue(find("BTC").first().startsWith("Binance BTC/USDT NONE"))
        assertEquals(listOf("Binance ETH/USDT NONE"), find("eth/usdt"))
        assertEquals(emptyList<String>(), find("XYZ"))
    }

    @Test
    fun parseAndDeliveryCode() {
        val q = PairSearch.parse("BTCUSDT Qtly 1225")!!
        assertEquals("BTCUSDT", q.base)
        assertNull(q.quote)
        assertEquals("1225", q.date)
        // Zahl als erstes Wort bleibt ein Coin
        assertEquals("1000", PairSearch.parse("1000")!!.base)
        assertEquals("261225", PairSearch.deliveryCode(MarketPair("BTC", "USDT", "BTCUSDT_261225", FuturesContractType.QUARTERLY)))
        assertNull(PairSearch.deliveryCode(MarketPair("BTC", "USDT", "BTCUSDT", FuturesContractType.PERPETUAL)))
    }

    @Test
    fun arabicDigitsCountAsDate() {
        // «BTCUSDT ١٢٢٥» (arabisch-indisch) und «۲۶۱۲۲۵» (persisch) = Verfallsdatum
        assertEquals("1225", PairSearch.parse("BTCUSDT ١٢٢٥")!!.date)
        assertEquals("261225", PairSearch.parse("BTCUSDT ۲۶۱۲۲۵")!!.date)
        // Berechnetes Datum immer mit lateinischen Ziffern, auch in arabischer App-Sprache
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"))
            val code = PairSearch.deliveryCode(MarketPair("BTC", "USDT", "BTCUSDT", FuturesContractType.QUARTERLY))
            assertTrue(code == null || code.all { it in '0'..'9' })
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    // ---- Suche über Namen und Börsen (wie `ExplorerPairSearchTests.swift`)

    private val kraken = MarketInfo("Kraken", "Kraken")
    private val nameCache = mapOf(
        "Binance" to MarketPairsInfo(1, listOf(
            MarketPair("BTC", "USDT", "BTCUSDT"),
            MarketPair("ETH", "USDT", "ETHUSDT"),
            MarketPair("ETC", "USDT", "ETCUSDT"),
            MarketPair("SOL", "USDT", "SOLUSDT"),
            MarketPair("USDT", "EUR", "USDTEUR"),
            MarketPair("SHIB", "USDT", "SHIBUSDT"),
            MarketPair("BITCOIN", "USDT", "BITCOINUSDT"),
        )),
        "Kraken" to MarketPairsInfo(1, listOf(
            MarketPair("BTC", "EUR", "XBTEUR"),
            MarketPair("ETH", "EUR", "ETHEUR"),
        )),
        "BinanceFutures" to MarketPairsInfo(1, listOf(
            MarketPair("NVDA", "USDT", "NVDAUSDT", FuturesContractType.PERPETUAL, tradFi = true),
            MarketPair("PEPE", "USDT", "1000PEPEUSDT", FuturesContractType.PERPETUAL),
        )),
    )
    private val names = NameIndex(mapOf(
        "BTC" to "Bitcoin",
        "ETH" to "Ethereum",
        "ETC" to "Ethereum Classic",
        "SOL" to "Solana",
        "USDT" to "Tether",
        "SHIB" to "Shiba Inu",
        "PEPE" to "Pépé",
        "TRADFI:NVDA" to "NVIDIA",
        // Krypto-Token gleichen Kürzels: nie für das TradFi-Paar
        "NVDA" to "Nvidia Token",
    ))

    private fun findNamed(q: String, n: NameIndex = names) =
        PairSearch.find(q, listOf(spot, kraken, futures), nameCache, names = n).map {
            "${it.market.key} ${it.pair.base}/${it.pair.quote}"
        }

    @Test
    fun nameFindsCoin() {
        assertEquals(listOf("Binance BTC/USDT", "Kraken BTC/EUR"), findNamed("bitcoin").filter { "BTC/" in it })
        assertEquals("Binance SOL/USDT", findNamed("solana").single())
        assertEquals("Binance SOL/USDT", findNamed("SOL").first())
        // Gross/klein und Akzente egal
        assertEquals("BinanceFutures PEPE/USDT", findNamed("pepe").single())
        assertEquals("BinanceFutures PEPE/USDT", findNamed("PÉPÉ").single())
        // Ohne Namen bleibt alles wie bisher
        assertEquals(emptyList<String>(), findNamed("solana", NameIndex.EMPTY))
    }

    @Test
    fun rankingSymbolThenNamePrefixThenContains() {
        // «ether»: Name-Anfang (Ethereum, Ethereum Classic; gleicher Rang, nach Kürzel) vor «enthält» (Tether)
        assertEquals(
            listOf("Binance ETC/USDT", "Binance ETH/USDT", "Kraken ETH/EUR", "Binance USDT/EUR"),
            findNamed("ether"),
        )
        // Exaktes Kürzel vor Kürzel-Anfang vor Name: «BITCOIN» ist ein eigener Coin
        val bitcoin = findNamed("bitcoin")
        assertEquals("Binance BITCOIN/USDT", bitcoin.first())
        assertTrue(bitcoin.indexOf("Binance BTC/USDT") > 0)
        // «eth»: zuerst das Kürzel ETH, dann ETC (Name-Anfang), Tether (enthält) zuletzt
        assertEquals(
            listOf("Binance ETH/USDT", "Kraken ETH/EUR", "Binance ETC/USDT", "Binance USDT/EUR"),
            findNamed("eth"),
        )
    }

    @Test
    fun nameWithQuoteAndMultiWordName() {
        assertEquals(listOf("Binance BTC/USDT"), findNamed("bitcoin usdt").filter { "BTC/" in it })
        assertEquals(listOf("Kraken ETH/EUR"), findNamed("ethereum eur"))
        assertEquals(listOf("Binance SHIB/USDT"), findNamed("shiba inu"))
        assertEquals(listOf("Binance SHIB/USDT"), findNamed("inu"))
    }

    @Test
    fun tradFiUsesItsOwnName() {
        assertEquals(listOf("BinanceFutures NVDA/USDT"), findNamed("nvidia"))
        assertEquals(emptyList<String>(), findNamed("nvidia token"))
    }

    @Test
    fun exchangeWordsFilterAndMatchLast() {
        assertEquals(listOf("Kraken BTC/EUR"), findNamed("btc kraken"))
        assertEquals(listOf("Kraken BTC/EUR"), findNamed("bitcoin kraken"))
        assertEquals(listOf("Kraken ETH/EUR"), findNamed("kraken eth"))
        // Nur die Börse: alle ihre Paare
        assertEquals(listOf("Kraken BTC/EUR", "Kraken ETH/EUR"), findNamed("krak"))
        // Kürzel geht vor Börse; zu kurze Wörter nennen keine Börse
        assertEquals(emptyList<String>(), findNamed("kr"))
    }

    @Test
    fun nameMatchRules() {
        assertEquals(NameMatch.PREFIX, NameMatch.score("ethereum classic", "classic"))
        assertEquals(NameMatch.PREFIX, NameMatch.score("bitcoin", "bi"))
        assertEquals(NameMatch.CONTAINS, NameMatch.score("tether", "eth"))
        // Enthält erst ab 3 Zeichen, Anfang ab 2
        assertNull(NameMatch.score("tether", "et"))
        assertNull(NameMatch.score("bitcoin", "b"))
        assertEquals("pepe", NameMatch.fold(" Pépé "))
        assertTrue(NameMatch.matchesMarket(NameMatch.fold("Crypto.com"), "cryptocom"))
        assertTrue(NameMatch.matchesMarket(NameMatch.fold("Binance Futures"), "fut"))
        assertTrue(!NameMatch.matchesMarket(NameMatch.fold("Kraken"), "kr"))
    }

    @Test
    fun nameIndexKeepsDisplayNameForCoinSearch() {
        // Die Coin-Suche zeigt den Namen wie geliefert und geht alle Einträge durch
        assertEquals("Pépé", names.name("PEPE"))
        assertEquals("pepe", names.folded("PEPE"))
        assertEquals(null, names.name("XMR"))
        assertTrue(names.entries.any { it.key == "TRADFI:NVDA" && it.name == "NVIDIA" })
        // Leere Namen fallen weg
        assertTrue(NameIndex(mapOf("X" to "  ")).isEmpty)
    }
}
