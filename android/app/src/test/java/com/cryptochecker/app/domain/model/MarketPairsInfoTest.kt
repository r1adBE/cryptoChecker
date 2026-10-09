package com.cryptochecker.app.domain.model

import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.random.Random

/** Die Verzeichnisse liefern dasselbe wie die frühere Suche über die ganze Liste. */
class MarketPairsInfoTest {

    // ---- Frühere Umsetzung als Referenz (Suche über die ganze Liste)

    private fun oldBases(pairs: List<CurrencyPairInfo>): List<String> =
        pairs.map { it.currencyBase }.distinct()

    private fun oldQuotes(pairs: List<CurrencyPairInfo>, base: String): List<String> =
        pairs.filter { it.currencyBase == base }.map { it.currencyCounter }.distinct()

    private fun oldContracts(pairs: List<CurrencyPairInfo>, base: String?, quote: String?): List<FuturesContractType> {
        if (base == null || quote == null) return emptyList()
        return pairs.filter { it.currencyBase == base && it.currencyCounter == quote }.map { it.contractType }.distinct()
    }

    private fun oldPair(pairs: List<CurrencyPairInfo>, base: String, quote: String, type: FuturesContractType) =
        pairs.firstOrNull { it.currencyBase == base && it.currencyCounter == quote && it.contractType == type }

    private fun oldBulkQuotes(pairs: List<CurrencyPairInfo>): List<String> =
        pairs
            .mapNotNull { it.currencyCounter.takeIf { q -> q.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    /** Grosse Liste mit Doppelten, allen Kontrakttypen, Schreibweisen (btc/BTC) und leeren Quotes. */
    private fun syntheticPairs(seed: Int, count: Int): List<CurrencyPairInfo> {
        val random = Random(seed)
        val bases = (0 until 400).map { "C$it" } + listOf("BTC", "btc", "Btc", "ETH", "eth", "")
        val quotes = listOf("USDT", "usdt", "USDT.P", "USD", "EUR", "BTC", "eth", " ", "")
        val types = FuturesContractType.entries
        return List(count) { i ->
            CurrencyPairInfo(
                bases[random.nextInt(bases.size)],
                quotes[random.nextInt(quotes.size)],
                "id$i",
                types[random.nextInt(types.size)],
            )
        }
    }

    @Test
    fun indexMatchesFullScanOnLargeList() {
        for (seed in 1..2) {
            val raw = syntheticPairs(seed, 8_000)
            // Wie nach dem Abruf (sortiert) und unsortiert (wie gespeichert gelesen)
            for (pairs in listOf(raw, raw.sorted())) {
                val info = MarketPairsInfo(1L, pairs)
                assertEquals(oldBases(pairs), info.baseCurrencies.toList())
                assertEquals(oldBulkQuotes(pairs), info.bulkQuoteCurrencies)

                val probeBases = oldBases(pairs).take(30) + listOf("BTC", "btc", "Btc", "ETH", "eth", "", "MISSING", "bTc")
                val probeQuotes = listOf("USDT", "usdt", "USDT.P", "USD", "EUR", "BTC", "eth", " ", "", "NONE")
                for (base in probeBases) {
                    assertEquals(oldQuotes(pairs, base), info.getQuoteCurrencies(base).toList())
                    for (quote in probeQuotes) {
                        assertEquals(oldContracts(pairs, base, quote), info.getAvailableFuturesContractsTypes(base, quote))
                        for (type in FuturesContractType.entries) {
                            assertSame(oldPair(pairs, base, quote, type), info.getCurrencyPairInfo(base, quote, type))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun duplicatesKeepFirstOccurrence() {
        val first = CurrencyPairInfo("BTC", "USDT", "first", FuturesContractType.PERPETUAL)
        val second = CurrencyPairInfo("BTC", "USDT", "second", FuturesContractType.PERPETUAL)
        val info = MarketPairsInfo(0L, listOf(first, CurrencyPairInfo("btc", "USDT", "lower"), second))
        assertSame(first, info.getCurrencyPairInfo("BTC", "USDT", FuturesContractType.PERPETUAL))
        assertEquals(listOf("BTC", "btc"), info.baseCurrencies.toList())
        assertEquals(listOf(FuturesContractType.PERPETUAL), info.getAvailableFuturesContractsTypes("BTC", "USDT"))
        assertNull(info.getCurrencyPairInfo("BTC", "USDT", FuturesContractType.NONE))
    }

    @Test
    fun nullAndMissingInputsGiveEmptyResults() {
        val info = MarketPairsInfo()
        assertEquals(emptyList<String>(), info.baseCurrencies.toList())
        assertEquals(emptyList<String>(), info.getQuoteCurrencies("BTC").toList())
        assertEquals(emptyList<FuturesContractType>(), info.getAvailableFuturesContractsTypes(null, "USDT"))
        assertEquals(emptyList<FuturesContractType>(), info.getAvailableFuturesContractsTypes("BTC", null))
        assertNull(info.defaultBulkQuote)
    }

    @Test
    fun defaultBulkQuotePrefersUsdtIgnoringCase() {
        val pairs = listOf(
            CurrencyPairInfo("A", "EUR", null),
            CurrencyPairInfo("B", "EUR", null),
            CurrencyPairInfo("C", "usdt", null),
        )
        assertEquals("usdt", MarketPairsInfo(0L, pairs).defaultBulkQuote)
        assertEquals("EUR", MarketPairsInfo(0L, pairs.take(2)).defaultBulkQuote)
    }

    @Test
    fun copiesAndEqualityIgnoreTheIndex() {
        val pairs = listOf(CurrencyPairInfo("BTC", "USDT", null))
        val a = MarketPairsInfo(5L, pairs)
        a.baseCurrencies.toList()
        val b = MarketPairsInfo(5L, pairs)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val c = a.copy(pairs = listOf(CurrencyPairInfo("ETH", "EUR", null)))
        assertEquals(listOf("ETH"), c.baseCurrencies.toList())
        assertEquals(listOf("EUR"), c.getQuoteCurrencies("ETH").toList())
    }
}
