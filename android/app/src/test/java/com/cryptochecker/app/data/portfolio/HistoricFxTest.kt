package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.fakePortfolioDao
import com.cryptochecker.app.data.remote.Reply
import com.cryptochecker.app.data.remote.RouteHttp
import com.cryptochecker.app.data.remote.candles.OwnCandleDataSource
import com.cryptochecker.app.data.testTokenIdentityStore
import com.cryptochecker.app.data.unusedExchangeRepository
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.marketdata.util.EcbRates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Devisenkurse des Stichtags und des Wertverlaufs ([HistoricPriceSource.usdTo], [HistoricPriceSource.usdToSeries]):
 * Euro-Referenzkurse der EZB über das Data Portal (eine Abfrage für Ziel und USD, Kreuzkurs auf 5 Stellen),
 * Wochenende → letzter Geschäftstag, Lew nach der Euro-Einführung aus EUR derselben Abfrage, Zeitreihe in einer
 * Abfrage mit 6 h Speicher; antwortet das Portal nicht, die Datei der letzten 90 Tage bzw. die ganze Geschichte.
 */
class HistoricFxTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun source(http: RouteHttp) = HistoricPriceSource(
        http.client, testTokenIdentityStore(temp.newFolder()), OwnCandleDataSource(http.client, ioDispatcher = Dispatchers.IO), fakePortfolioDao(),
        unusedExchangeRepository(),
        ioDispatcher = Dispatchers.IO,
    )

    private val saturday = LocalDate.of(2024, 1, 6)

    /** Antwort des Data Portals (SDMX-JSON, nur Daten): Code → (Tag → Kurs je 1 EUR). */
    private fun sdmx(series: Map<String, Map<String, Double>>): String {
        val codes = series.keys.toList()
        val dates = series.values.flatMap { it.keys }.distinct().sorted()
        val rows = codes.mapIndexed { i, code ->
            val observations = series.getValue(code).entries
                .joinToString(",") { (day, rate) -> "\"${dates.indexOf(day)}\":[$rate]" }
            "\"0:$i:0:0:0\":{\"observations\":{$observations}}"
        }.joinToString(",")
        fun ids(values: List<String>) = values.joinToString(",") { "{\"id\":\"$it\"}" }
        return """{"dataSets":[{"series":{$rows}}],"structure":{"dimensions":{"series":[""" +
            """{"id":"FREQ","values":[{"id":"D"}]},{"id":"CURRENCY","values":[${ids(codes)}]},""" +
            """{"id":"CURRENCY_DENOM","values":[{"id":"EUR"}]}],""" +
            """"observation":[{"id":"TIME_PERIOD","values":[${ids(dates)}]}]}}}"""
    }

    /** Datei der EZB (`eurofxref-hist*.xml`): Tag → Code → Kurs je 1 EUR. */
    private fun xml(days: Map<String, Map<String, Double>>): String =
        "<Cube>" + days.entries.joinToString("") { (day, rates) ->
            "<Cube time='$day'>" + rates.entries.joinToString("") { (c, r) -> "<Cube currency='$c' rate='$r'/>" } + "</Cube>"
        } + "</Cube>"

    @Test
    fun usdAndInvalidCodesNeedNoRequest() = runBlocking {
        val http = RouteHttp { null }
        val historic = source(http)
        assertEquals(HistoricFx(1.0, null), historic.usdTo(" usd ", saturday))
        assertNull(historic.usdTo("EURO", saturday))
        assertNull(historic.usdTo("E1R", saturday))
        assertTrue(http.requested.isEmpty())
    }

    @Test
    fun weekendRateCarriesTheBusinessDay() = runBlocking {
        val url = EcbRates.seriesUrl(listOf("CHF", "USD"), LocalDate.of(2023, 12, 27), saturday)
        val body = sdmx(
            mapOf(
                "CHF" to mapOf("2024-01-04" to 0.9313, "2024-01-05" to 0.932),
                "USD" to mapOf("2024-01-04" to 1.0953, "2024-01-05" to 1.0921),
            ),
        )
        val http = RouteHttp { if (it == url) Reply(body) else null }
        assertEquals(HistoricFx(EcbRates.round5(0.932 / 1.0921), "2024-01-05"), source(http).usdTo("chf", saturday))
        // EUR: Kehrwert des USD-Kurses, nur USD wird abgefragt
        val eurUrl = EcbRates.seriesUrl(listOf("USD"), LocalDate.of(2023, 12, 27), saturday)
        val eur = RouteHttp { if (it == eurUrl) Reply(body) else null }
        assertEquals(HistoricFx(EcbRates.round5(1 / 1.0921), "2024-01-05"), source(eur).usdTo("EUR", saturday))
        assertEquals(1, eur.requested.size)
    }

    @Test
    fun fullHistoryStepsInWhenTheDataPortalFails() = runBlocking {
        val history = xml(
            mapOf(
                "2024-01-05" to mapOf("USD" to 1.0921, "CHF" to 0.932, "JPY" to 157.0),
                "2024-01-04" to mapOf("USD" to 1.0953, "CHF" to 0.9313),
            ),
        )
        val http = RouteHttp { url ->
            when {
                url.startsWith("https://data-api.ecb.europa.eu/") -> Reply("<html>504</html>", 504)
                url == EcbRates.HIST_URL -> Reply(history)
                else -> null
            }
        }
        val historic = source(http)
        assertEquals(HistoricFx(EcbRates.round5(0.932 / 1.0921), "2024-01-05"), historic.usdTo("CHF", saturday))
        // Zweiter Tag derselben Währungen: Geschichte aus dem Speicher
        assertEquals(
            HistoricFx(EcbRates.round5(0.9313 / 1.0953), "2024-01-04"),
            historic.usdTo("CHF", LocalDate.of(2024, 1, 4)),
        )
        assertEquals(1, http.count(EcbRates.HIST_URL))
        assertEquals(0, http.count(EcbRates.HIST_90D_URL))
        assertNull("alles offline", source(RouteHttp { null }).usdTo("CHF", saturday))
    }

    @Test
    fun ninetyDayFileForRecentDays() = runBlocking {
        val day = LocalDate.now(ZoneOffset.UTC).minusDays(5)
        val recent = xml(mapOf(day.minusDays(1).toString() to mapOf("USD" to 1.1, "CHF" to 0.9)))
        val http = RouteHttp { url -> if (url == EcbRates.HIST_90D_URL) Reply(recent) else null }
        assertEquals(HistoricFx(EcbRates.round5(0.9 / 1.1), day.minusDays(1).toString()), source(http).usdTo("CHF", day))
        assertEquals(0, http.count(EcbRates.HIST_URL))
    }

    @Test
    fun levAfterTheEuroComesFromEur() = runBlocking {
        val day = LocalDate.of(2026, 3, 2)
        val url = EcbRates.seriesUrl(listOf("BGN", "USD"), day.minusDays(10), day)
        val http = RouteHttp { if (it == url) Reply(sdmx(mapOf("USD" to mapOf("2026-03-02" to 1.1)))) else null }
        val bgn = source(http).usdTo("BGN", day)!!
        assertEquals(EcbRates.round5(1 / 1.1) * CurrencyConversion.BGN_PER_EUR, bgn.rate, 1e-12)
        assertEquals("2026-03-02", bgn.date)
        assertEquals("eine Abfrage für BGN und EUR", 1, http.requested.size)
        // Vor der Euro-Einführung: kein Ersatz
        assertNull(source(RouteHttp { null }).usdTo("BGN", LocalDate.of(2025, 3, 3)))
    }

    @Test
    fun seriesIsReadAndKept() = runBlocking {
        val from = LocalDate.of(2024, 1, 1)
        val to = LocalDate.of(2024, 1, 10)
        val url = EcbRates.seriesUrl(listOf("CHF", "USD"), from, to)
        val body = sdmx(
            mapOf(
                "CHF" to mapOf("2024-01-02" to 0.9305, "2024-01-03" to 0.9322),
                "USD" to mapOf("2024-01-02" to 1.0956, "2024-01-03" to 1.0919),
            ),
        )
        val http = RouteHttp { if (it == url) Reply(body) else null }
        val historic = source(http)
        val series = historic.usdToSeries("CHF", from, to)!!
        assertEquals(
            mapOf(
                LocalDate.of(2024, 1, 2).toEpochDay() to EcbRates.round5(0.9305 / 1.0956),
                LocalDate.of(2024, 1, 3).toEpochDay() to EcbRates.round5(0.9322 / 1.0919),
            ),
            series,
        )
        assertEquals(series, historic.usdToSeries("chf", from, to))
        assertEquals("eine Abfrage, 6 h im Speicher", 1, http.requested.size)

        // USD: leer ohne Abfrage; ungültig oder verkehrter Zeitraum: keine Reihe
        assertEquals(emptyMap<Long, Double>(), historic.usdToSeries("USD", from, to))
        assertNull(historic.usdToSeries("SWISS", from, to))
        assertNull(historic.usdToSeries("CHF", to, from))
        assertEquals(1, http.requested.size)
    }

    @Test
    fun levSeriesAndEmptyAnswers() = runBlocking {
        val from = LocalDate.of(2024, 2, 5)
        val to = LocalDate.of(2024, 2, 9)
        val url = EcbRates.seriesUrl(listOf("BGN", "USD"), from, to)
        val body = sdmx(mapOf("BGN" to mapOf("2024-02-05" to 1.9558), "USD" to mapOf("2024-02-05" to 1.0789)))
        val http = RouteHttp { if (it == url) Reply(body) else null }
        assertEquals(mapOf(from.toEpochDay() to EcbRates.round5(1.9558 / 1.0789)), source(http).usdToSeries("BGN", from, to))

        // Gültige, aber leere Antwort (Zeitraum ohne Kurse): keine Reihe, kein Ersatz nötig
        val empty = RouteHttp { if (it.startsWith("https://data-api.ecb.europa.eu/")) Reply("") else null }
        assertNull(source(empty).usdToSeries("CHF", from, to))
        assertEquals(1, empty.requested.size)
    }

    @Test
    fun missingAnswersGiveNoSeries() = runBlocking {
        val offline = RouteHttp { null }
        assertNull(source(offline).usdToSeries("CHF", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)))
        // Data Portal, dann die ganze Geschichte
        assertEquals(2, offline.requested.size)
    }
}
