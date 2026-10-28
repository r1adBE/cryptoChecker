package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.fakePortfolioDao
import com.cryptochecker.app.data.remote.Reply
import com.cryptochecker.app.data.remote.RouteHttp
import com.cryptochecker.app.data.remote.candles.OwnCandleDataSource
import com.cryptochecker.app.data.testTokenIdentityStore
import com.cryptochecker.app.data.unusedExchangeRepository
import com.cryptochecker.app.domain.convert.CurrencyConversion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

/**
 * Devisenkurse des Stichtags und des Wertverlaufs ([HistoricPriceSource.usdTo], [HistoricPriceSource.usdToSeries]):
 * Frankfurter (.app, sonst .dev), Wochenende → letzter Geschäftstag, Lew nach der Euro-Einführung aus EUR, Zeitreihe
 * in Stücken mit 6 h Speicher; ein Stück ohne Werktag darf leer bleiben, eines mit Werktag nicht.
 */
class HistoricFxTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun source(http: RouteHttp) = HistoricPriceSource(
        http.client, testTokenIdentityStore(temp.newFolder()), OwnCandleDataSource(http.client), fakePortfolioDao(),
        unusedExchangeRepository(),
    )

    private val saturday = LocalDate.of(2024, 1, 6)

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
        val http = RouteHttp { url ->
            if (url == "https://api.frankfurter.app/2024-01-06?from=USD&to=CHF") {
                Reply("""{"amount":1.0,"base":"USD","date":"2024-01-05","rates":{"CHF":0.85}}""")
            } else {
                null
            }
        }
        assertEquals(HistoricFx(0.85, "2024-01-05"), source(http).usdTo("chf", saturday))
    }

    @Test
    fun secondHostStepsIn() = runBlocking {
        val http = RouteHttp { url ->
            when (url) {
                // Erste Adresse ohne gültigen Kurs (0), zweite ohne Datum
                "https://api.frankfurter.app/2024-01-06?from=USD&to=EUR" -> Reply("""{"rates":{"EUR":0}}""")
                "https://api.frankfurter.dev/v1/2024-01-06?from=USD&to=EUR" -> Reply("""{"rates":{"EUR":0.91}}""")
                else -> null
            }
        }
        assertEquals(HistoricFx(0.91, null), source(http).usdTo("EUR", saturday))
        assertNull("beide offline", source(RouteHttp { null }).usdTo("EUR", saturday))
    }

    @Test
    fun levAfterTheEuroComesFromEur() = runBlocking {
        val day = LocalDate.of(2026, 3, 2)
        val http = RouteHttp { url ->
            if (url.endsWith("2026-03-02?from=USD&to=EUR") && "frankfurter.app" in url) {
                Reply("""{"date":"2026-03-02","rates":{"EUR":0.9}}""")
            } else {
                null
            }
        }
        val bgn = source(http).usdTo("BGN", day)!!
        assertEquals(0.9 * CurrencyConversion.BGN_PER_EUR, bgn.rate, 1e-12)
        assertEquals("2026-03-02", bgn.date)
        // Vor der Euro-Einführung: kein Ersatz
        assertNull(source(RouteHttp { null }).usdTo("BGN", LocalDate.of(2025, 3, 3)))
    }

    @Test
    fun seriesIsReadAndKept() = runBlocking {
        val from = LocalDate.of(2024, 1, 1)
        val to = LocalDate.of(2024, 1, 10)
        val http = RouteHttp { url ->
            if (url == "https://api.frankfurter.app/2024-01-01..2024-01-10?from=USD&to=CHF") {
                Reply("""{"rates":{"2024-01-02":{"CHF":0.85},"2024-01-03":{"CHF":0.86},"kein-datum":{"CHF":1.0}}}""")
            } else {
                null
            }
        }
        val historic = source(http)
        val series = historic.usdToSeries("CHF", from, to)!!
        assertEquals(
            mapOf(LocalDate.of(2024, 1, 2).toEpochDay() to 0.85, LocalDate.of(2024, 1, 3).toEpochDay() to 0.86),
            series,
        )
        assertEquals(series, historic.usdToSeries("chf", from, to))
        assertEquals("6 h im Speicher", 1, http.requested.size)

        // USD: leer ohne Abfrage; ungültig oder verkehrter Zeitraum: keine Reihe
        assertEquals(emptyMap<Long, Double>(), historic.usdToSeries("USD", from, to))
        assertNull(historic.usdToSeries("SWISS", from, to))
        assertNull(historic.usdToSeries("CHF", to, from))
        assertEquals(1, http.requested.size)
    }

    @Test
    fun emptyFirstAnswerFallsBackToTheSecondHost() = runBlocking {
        val from = LocalDate.of(2024, 2, 5)
        val to = LocalDate.of(2024, 2, 9)
        val http = RouteHttp { url ->
            when {
                url.startsWith("https://api.frankfurter.app/") -> Reply("""{"rates":{}}""")
                url.startsWith("https://api.frankfurter.dev/v1/2024-02-05..2024-02-09?from=USD&to=BGN,EUR") ->
                    Reply("""{"rates":{"2024-02-05":{"BGN":1.81,"EUR":0.93}}}""")
                else -> null
            }
        }
        // Lew: eine Abfrage für BGN und EUR
        assertEquals(mapOf(from.toEpochDay() to 1.81), source(http).usdToSeries("BGN", from, to))

        // Beide gültig, aber leer: keine Reihe
        val empty = RouteHttp { Reply("""{"rates":{}}""") }
        assertNull(source(empty).usdToSeries("CHF", from, to))
    }

    @Test
    fun missingAnswersGiveNoSeries() = runBlocking {
        val offline = RouteHttp { null }
        // Werktage ohne Antwort: lieber keine Reihe als eine mit Lücke
        assertNull(source(offline).usdToSeries("CHF", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)))
        // Nur ein Wochenende: ohne Antwort normal (leeres Stück), aber dann ebenfalls keine Reihe
        assertNull(source(offline).usdToSeries("CHF", saturday, saturday.plusDays(1)))
        assertEquals(4, offline.requested.size)
    }
}
