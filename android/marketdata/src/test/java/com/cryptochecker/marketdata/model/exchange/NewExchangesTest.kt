package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.config.ExchangesConfig
import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.ExchangeCategory
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.util.EcbRates
import com.cryptochecker.marketdata.util.ReferenceRates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Bitunix Futures, Referenzkurse (EZB, NBU, NBP), Kategorien der Registry. Wie iOS `ExchangeListParityTests`. */
class NewExchangesTest {

    @Test
    fun bitunixKeepsTheMultiplierInTheBase() {
        assertEquals("1000PEPE", BitunixFutures.baseOf("1000PEPEUSDT", "PEPE", "USDT"))
        assertEquals("BTC", BitunixFutures.baseOf("BTCUSDT", "BTC", "USDT"))
        assertEquals("X", BitunixFutures.baseOf("USDT", "X", "USDT"))
        assertEquals("ODD", BitunixFutures.baseOf("ODDPERP", "ODD", "USDT"))
    }

    @Test
    fun ecbAddressesAndKind() {
        val ecb = EcbReference()
        val pair = PairQuery("EUR", "USD", "EURUSD", FuturesContractType.NONE)
        assertEquals("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist-90d.xml", ecb.tickerAddress(0, pair))
        assertEquals(EcbRates.HIST_90D_URL, ecb.getBulkTickersUrl(0, listOf("EURUSD", "USDJPY")))
        assertNull(ecb.getBulkTickersUrl(1, listOf("EURUSD")))
        assertEquals("eine Datei für alle Paare", 1, ecb.bulkTickersRequestCount(listOf("EURUSD", "USDJPY", "CHFEUR")))
        assertEquals("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml", ecb.pairListAddress(0))
        assertThrows(ExchangeParseException::class.java) { ecb.tickerAddress(0, PairQuery("EUR", "US", "EURUS", FuturesContractType.NONE)) }
        assertFalse(ecb.allowsAlarms)
        assertTrue(ecb.priceIsDailyReference)
        assertEquals(ExchangeCategory.FOREX, ecb.category)
        assertEquals("ECB", ecb.name)
    }

    @Test
    fun ecbSeriesTakesTheNewestDayAndThePreviousEcbDay() {
        val days = EcbRates.parseXml(
            "<Cube><Cube time='2026-10-26'><Cube currency='USD' rate='1.1188'/><Cube currency='JPY' rate='177.31'/></Cube>" +
                "<Cube time='2026-10-23'><Cube currency='USD' rate='1.1193'/><Cube currency='JPY' rate='176.95'/></Cube>" +
                "<Cube time='2026-10-12'><Cube currency='USD' rate='1.0'/></Cube></Cube>",
        )!!
        val window = EcbRates.recent(days, EcbReference.WINDOW_DAYS)
        assertEquals("12. Oktober liegt mehr als 10 Tage vor dem jüngsten Tag", 2, window.size)
        val ticker = SimpleTicker()
        assertTrue(EcbReference.copySeries(window, "USD", "JPY", ticker))
        assertEquals(EcbRates.round5(177.31 / 1.1188), ticker.last, 0.0)
        val previous = EcbRates.round5(176.95 / 1.1193)
        assertEquals((ticker.last - previous) / previous * 100.0, ticker.change24hPercent!!, 1e-12)
        assertFalse(EcbReference.copySeries(window, "USD", "CHF", SimpleTicker()))
    }

    @Test
    fun ecbRatesRoundCrossRatesToFiveSignificantDigits() {
        // Gleiche Werte wie bisher beim Spiegel (USD-Basis, 4.11.2026)
        val day = mapOf("USD" to 1.1267, "CHF" to 0.9188, "JPY" to 178.3, "IDR" to 20425.83)
        assertEquals(0.81548, EcbRates.cross(day, "USD", "CHF")!!, 0.0)
        assertEquals(158.25, EcbRates.cross(day, "USD", "JPY")!!, 0.0)
        assertEquals(18129.0, EcbRates.cross(day, "USD", "IDR")!!, 0.0)
        assertEquals(0.88755, EcbRates.cross(day, "USD", "EUR")!!, 0.0)
        assertEquals("Basis EUR: Wert der EZB", 20425.83, EcbRates.cross(day, "EUR", "IDR")!!, 0.0)
        assertNull(EcbRates.cross(day, "USD", "USD"))
        assertNull(EcbRates.cross(day, "USD", "GBP"))
        assertEquals(123460.0, EcbRates.round5(123456.7), 0.0)
        assertEquals(0.00012346, EcbRates.round5(0.000123456), 0.0)
        assertEquals(setOf("CHF", "JPY", "IDR", "EUR"), EcbRates.rebase(day, "USD").keys)
    }

    @Test
    fun ecbDataPortalAddressAndCoverage() {
        assertEquals(
            "https://data-api.ecb.europa.eu/service/data/EXR/D.CHF+USD.EUR.SP00.A" +
                "?startPeriod=2024-01-01&endPeriod=2024-01-10&format=jsondata&detail=dataonly",
            EcbRates.seriesUrl(listOf("USD", "EUR", "CHF", "USD"), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 10)),
        )
        val today = LocalDate.of(2026, 11, 5)
        assertTrue(EcbRates.coveredByHist90d(today.minusDays(80), today))
        assertFalse(EcbRates.coveredByHist90d(today.minusDays(81), today))
        assertNull("DOCTYPE wird nie gelesen", EcbRates.parseXml("<!DOCTYPE x><Cube time='2026-10-26'/>"))
        assertNull(EcbRates.parseXml("<html>maintenance</html>"))
    }

    @Test
    fun registryCategories() {
        val byCategory = ExchangesConfig.EXCHANGES.values.groupBy({ it.category }, { it.key })
        assertEquals(listOf("DexScreener"), byCategory[ExchangeCategory.DEX])
        assertEquals(listOf("EcbReference", "NbuReference", "NbpReference"), byCategory[ExchangeCategory.FOREX])
        val futures = byCategory.getValue(ExchangeCategory.FUTURES).toSet()
        assertTrue(ExchangesConfig.EXCHANGES.keys.filter { it.endsWith("Futures") }.all { it in futures })
        assertTrue(setOf("Hyperliquid", "Aster", "Lighter", "Deribit").all { it in futures })
        assertEquals(14, futures.size)
        // Nur Referenzkurse ohne Alarme
        assertEquals(
            listOf("EcbReference", "NbuReference", "NbpReference"),
            ExchangesConfig.EXCHANGES.values.filter { !it.allowsAlarms }.map { it.key },
        )
    }

    @Test
    fun bankReferencesReadSharedResponsesAndCombinedLists() {
        val nbu = NbuReference()
        val now = 1_793_872_800_000L // 2026-11-05 10:00 UTC
        assertEquals(1, nbu.responseAddresses(now).size)
        assertTrue(nbu.responseAddresses(now).single().contains("start=20261029&end=20261105"))
        assertTrue(nbu.pairListCombined)
        assertEquals(listOf(EcbRates.DAILY_URL, ReferenceRates.NBU_LIST_URL), (0 until nbu.pairListCallCount).map(nbu::pairListAddress))
        val nbp = NbpReference()
        assertEquals(listOf(ReferenceRates.NBP_TABLE_A_URL, ReferenceRates.NBP_TABLE_B_URL), nbp.responseAddresses(now))
        assertEquals(4, nbp.pairListCallCount)
        assertFalse(nbp.priceIsDailyReference)
        assertEquals(ExchangeCategory.FOREX, nbp.category)
        assertThrows(ExchangeParseException::class.java) { nbp.tickerAddress(0, PairQuery("NGN", "US", "NGNUS", FuturesContractType.NONE)) }
        // Paarliste: AED kommt von der NBU (die EZB führt es nicht), nie zusätzlich von der NBP
        val ecbDaily = "<Cube><Cube time='2026-11-04'><Cube currency='USD' rate='1.16'/><Cube currency='PLN' rate='4.24'/></Cube></Cube>"
        val nbuList = "[{\"cc\":\"AED\",\"rate\":12.4},{\"cc\":\"USD\",\"rate\":45.5},{\"cc\":\"XAU\",\"rate\":170000}]"
        val tableB = "[{\"effectiveDate\":\"2026-11-04\",\"rates\":[{\"code\":\"AED\",\"mid\":1.06},{\"code\":\"NGN\",\"mid\":0.0029}]}]"
        val nbuPairs = ArrayList<com.cryptochecker.marketdata.model.ExchangePair>()
        nbu.readPairListCombined(listOf(ecbDaily, nbuList), nbuPairs)
        assertEquals(listOf("AEDEUR", "AEDUSD", "UAHEUR", "UAHUSD"), nbuPairs.map { it.pairId }.sortedBy { it })
        val nbpPairs = ArrayList<com.cryptochecker.marketdata.model.ExchangePair>()
        nbp.readPairListCombined(listOf(ecbDaily, nbuList, tableB, "[]"), nbpPairs)
        assertEquals(listOf("NGNEUR", "NGNUSD"), nbpPairs.map { it.pairId }.sortedBy { it })
    }
}
