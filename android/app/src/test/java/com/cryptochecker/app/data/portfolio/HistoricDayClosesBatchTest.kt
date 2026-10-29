package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.fakePortfolioDao
import com.cryptochecker.app.data.remote.Reply
import com.cryptochecker.app.data.remote.RouteHttp
import com.cryptochecker.app.data.remote.candles.OwnCandleDataSource
import com.cryptochecker.app.data.testTokenIdentityStore
import com.cryptochecker.app.data.unusedExchangeRepository
import com.cryptochecker.app.domain.market.DataFreshness
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Steuer-Export: die Tagesschlüsse je Coin auf einmal ([dayClosesUsdt], Kerzen je Quelle in Stücken) sind für dieselben
 * Daten genau die der Einzelabfrage je (Coin, Tag) ([HistoricPriceSource.dailyCloseUsdt], bisheriger Weg): gleicher
 * Schluss, gleiche Quelle, gleiches «ungeprüft» — über einen gespielten Markt, der beide Abfragearten aus denselben
 * Kerzen beantwortet (Binance `startTime`/`endTime`/`limit`, Coinbase `start`/`end`). Spiegel: HistoricDayClosesBatchTests.swift.
 */
class HistoricDayClosesBatchTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun chainOpen() = RouteHttp.assumeChainOpen()

    private val first: LocalDate = LocalDate.of(2021, 1, 1)
    private val last: LocalDate = LocalDate.of(2024, 6, 30)

    private fun millis(day: LocalDate) = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Tage von [from] bis [to] ohne die, die [skip] ausnimmt; Schluss nach [close]. */
    private fun series(from: LocalDate, to: LocalDate, skip: (Int) -> Boolean = { false }, close: (Int) -> Double): Map<Long, Double> {
        val out = sortedMapOf<Long, Double>()
        var day = from
        var i = 0
        while (!day.isAfter(to)) {
            if (!skip(i)) out[millis(day)] = close(i)
            day = day.plusDays(1)
            i++
        }
        return out
    }

    /** Gespielter Markt: Binance-Reihen je «Host|Symbol», Coinbase je Produkt; was fehlt, ist unbekannt (404). */
    private class Market(val binance: Map<String, Map<Long, Double>>, val coinbase: Map<String, Map<Long, Double>>) {
        fun reply(url: String): Reply? {
            val u = url.toHttpUrl()
            return when {
                u.host == "api.exchange.coinbase.com" -> coinbase(u.pathSegments[1], u.queryParameter("start"), u.queryParameter("end"))
                "binance" in u.host && u.encodedPath.endsWith("/klines") -> binance(
                    "${u.host}|${u.queryParameter("symbol")}",
                    u.queryParameter("startTime")?.toLong(), u.queryParameter("endTime")?.toLong(),
                    u.queryParameter("limit")?.toInt() ?: 500,
                )
                else -> null
            }
        }

        private fun binance(key: String, start: Long?, end: Long?, limit: Int): Reply {
            val all = binance[key] ?: return Reply("""{"code":-1121,"msg":"Invalid symbol."}""", 404)
            val picked = if (start == null) {
                all.entries.toList().takeLast(limit)
            } else {
                all.entries.filter { it.key >= start && (end == null || it.key <= end) }.take(limit)
            }
            return Reply(picked.joinToString(",", "[", "]") { (t, c) -> """[$t,"$c","$c","$c","$c","10.0",${t + DAY - 1},"1"]""" })
        }

        private fun coinbase(product: String, start: String?, end: String?): Reply {
            val all = coinbase[product] ?: return Reply("""{"message":"NotFound"}""", 404)
            val picked = if (start == null || end == null) {
                all.entries.toList().takeLast(300)
            } else {
                val from = Instant.parse(start).toEpochMilli()
                val to = Instant.parse(end).toEpochMilli()
                all.entries.filter { it.key in from..to }
            }
            // NEUESTE zuerst wie Coinbase
            return Reply(picked.asReversed().joinToString(",", "[", "]") { (t, c) -> "[${t / 1000},$c,$c,$c,$c,2.0]" })
        }

        companion object {
            const val DAY = 86_400_000L
        }
    }

    private fun market(): Market {
        val eth = series(first, last) { 1000.0 + it * 1.5 }
        val newSpot = series(LocalDate.of(2023, 3, 1), last) { 8.0 + it * 0.01 }
        val newCoinbase = series(LocalDate.of(2021, 6, 1), last) { 7.5 + it * 0.003 }
        val gapSpot = series(first, last, skip = { it % 7 == 3 }) { 2.0 + (it % 5) * 0.01 }
        val gapFutures = series(first, last) { 2.05 + (it % 3) * 0.01 }
        return Market(
            binance = mapOf(
                "data-api.binance.vision|ETHUSDT" to eth, "api.binance.com|ETHUSDT" to eth,
                "data-api.binance.vision|NEWUSDT" to newSpot, "api.binance.com|NEWUSDT" to newSpot,
                // FOO: Binance-Spot führt unter dem Kürzel einen anderen Token (jüngster Schluss 0.5 gegen 50)
                "data-api.binance.vision|FOOUSDT" to series(first, last) { 0.5 },
                "api.binance.us|FOOUSD" to series(LocalDate.of(2022, 1, 1), last) { 40.0 + it * 0.01 },
                "data-api.binance.vision|GAPUSDT" to gapSpot, "api.binance.com|GAPUSDT" to gapSpot,
                "fapi.binance.com|GAPUSDT" to gapFutures,
                "data-api.binance.vision|BARUSDT" to series(first.minusDays(40), last) { 0.01 },
            ),
            coinbase = mapOf(
                "NEW-USD" to newCoinbase,
                "FOO-USDC" to series(first, last) { 45.0 + it * 0.005 },
                // BAR über alle verlangten Tage (auch die vor [first]): überall ein anderer Token
                "BAR-USD" to series(first.minusDays(40), last) { 0.02 },
            ),
        )
    }

    private fun source(http: RouteHttp) = HistoricPriceSource(
        http.client, testTokenIdentityStore(temp.newFolder()), OwnCandleDataSource(http.client), fakePortfolioDao(),
        unusedExchangeRepository(),
    )

    /** Verlangte Tage: jeder 9. über die ganze Spanne (mehrere Stücke je Quelle) und ein Block um den Jahreswechsel. */
    private fun days(): List<LocalDate> {
        val out = LinkedHashSet<LocalDate>()
        var day = first.minusDays(20)
        while (!day.isAfter(last)) {
            out += day
            day = day.plusDays(9)
        }
        var block = LocalDate.of(2022, 12, 25)
        while (!block.isAfter(LocalDate.of(2023, 1, 5))) {
            out += block
            block = block.plusDays(1)
        }
        return out.toList()
    }

    @Test
    fun batchedClosesEqualPerDayCloses() = runBlocking {
        val m = market()
        val wanted = listOf("ETH", "eth", "NEW", "FOO", "GAP", "BAR", "NOREF", "USDT", "BAD COIN").associateWith { days() }
        // Aktuelle Kurse wie CutoffExport.referencePrices: Schlüssel wie verlangt; NOREF ohne Bezug
        val current = mapOf(
            "ETH" to 3100.0, "eth" to 3100.0, "NEW" to 10.5, "FOO" to 50.0, "GAP" to 2.0, "BAR" to 5.0, "BAD COIN" to 1.0,
        )

        val perDayHttp = RouteHttp(m::reply)
        val perDaySource = source(perDayHttp)
        val perDay = wanted.mapValues { (coin, days) -> days.associateWith { perDaySource.dailyCloseUsdt(coin, it, current[coin]) } }

        val batchHttp = RouteHttp(m::reply)
        val batched = source(batchHttp).dayClosesUsdt(wanted, current)

        assertEquals(perDay, batched)
        // Alle Wege kommen vor: Binance-Spot, Futures, Binance.US, Coinbase, keiner, «ungeprüft», USDT = 1
        val sources = perDay.values.flatMap { it.values }.mapNotNull { it.source }.toSet()
        val expected = listOf(DataFreshness.BINANCE, DataFreshness.BINANCE_FUTURES, DataFreshness.BINANCE_US, DataFreshness.COINBASE)
        assertTrue(sources.toString(), sources.containsAll(expected))
        assertTrue(perDay.getValue("NEW").values.any { it.price == null })
        assertTrue(perDay.getValue("BAR").values.all { it.price == null && it.unchecked })
        assertTrue(perDay.getValue("NOREF").values.all { it.unchecked })
        assertTrue(perDay.getValue("USDT").values.all { it.price == 1.0 })
        // Je Coin und Quelle wenige Stücke statt je Tag zwei Abfragen
        val binanceSpot = { h: RouteHttp -> h.count("data-api.binance.vision/api/v3/klines?symbol=ETHUSDT") }
        // ETH und eth: je zwei Stücke (mehr als 1000 Tage) und ein jüngster Schluss
        assertEquals(6, binanceSpot(batchHttp))
        // Binance und Coinbase (die Kettenbörsen dahinter fragen beide Wege gleich über die eigenen Kerzen)
        val marketRequests = { h: RouteHttp -> h.requested.count { "binance" in it || "coinbase" in it } }
        assertTrue("${marketRequests(batchHttp)} / ${marketRequests(perDayHttp)}", marketRequests(batchHttp) * 10 < marketRequests(perDayHttp))
    }
}
