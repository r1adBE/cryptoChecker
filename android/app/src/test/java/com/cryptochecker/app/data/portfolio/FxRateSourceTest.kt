package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.convert.FiatAlarmGuard
import com.cryptochecker.marketdata.util.EcbRates
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Devisenkurse ([FxRateSource]) mit einem Interceptor als Anbieter: Referenzkurse der EZB zuerst (eine Abfrage für alle
 * Währungen, Kreuzkurs USD → Ziel auf 5 Stellen), sonst open.er-api, Kurs ≤ 0 oder unlesbare Antwort zählen als
 * fehlend, Zwischenspeicher innerhalb der Frist, Zeitpunkt laut Quelle, BGN aus EUR.
 */
class FxRateSourceTest {

    private val hits = HashMap<String, Int>()

    private val ecb = EcbRates.DAILY_URL

    private val openEr = "https://open.er-api.com/v6/latest/USD"

    /** `eurofxref-daily.xml` mit einem Tag. */
    private fun daily(date: String, vararg rates: Pair<String, String>) =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><gesmes:Envelope xmlns:gesmes=\"http://www.gesmes.org/xml/2002-08-01\" " +
            "xmlns=\"http://www.ecb.int/vocabulary/2002-08-01/eurofxref\"><Cube><Cube time='$date'>" +
            rates.joinToString("") { (code, rate) -> "<Cube currency='$code' rate='$rate'/>" } +
            "</Cube></Cube></gesmes:Envelope>"

    private fun source(replies: Map<String, String>) = FxRateSource(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            hits[url] = (hits[url] ?: 0) + 1
            val body = replies[url] ?: throw IOException("offline: $url")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("text/xml".toMediaType())).build()
        }).build(),
    )

    @Test
    fun usdAndInvalidCodesNeedNoRequest() = runBlocking {
        val fx = source(emptyMap())
        assertEquals(1.0, fx.usdTo(" usd ")!!, 0.0)
        for (code in listOf("", "EU", "EURO", "E1R", "€UR")) assertNull(code, fx.usdTo(code))
        assertEquals(1.0, fx.cached("USD")!!, 0.0)
        assertNull(fx.fetchedAt("USD"))
        assertNull(fx.rateTime("USD"))
        assertTrue("keine Anfrage: $hits", hits.isEmpty())
    }

    @Test
    fun ecbRatesAreCachedForAllCurrenciesWithTheEcbDate() = runBlocking {
        val fx = source(mapOf(ecb to daily("2026-10-23", "USD" to "1.1267", "JPY" to "178.30", "CHF" to "0.9188")))
        val before = System.currentTimeMillis()
        // Kreuzkurs USD → CHF auf 5 signifikante Stellen (Genauigkeit der bisher bezogenen Kreuzkurse)
        assertEquals(0.81548, fx.usdTo("chf")!!, 0.0)
        assertEquals(0.81548, fx.usdTo("CHF")!!, 0.0)
        // Eine Abfrage bringt alle Währungen, EUR eingeschlossen
        assertEquals(158.25, fx.usdTo("JPY")!!, 0.0)
        assertEquals(0.88755, fx.usdTo("EUR")!!, 0.0)
        assertEquals(1, hits[ecb])
        assertNull(hits[openEr])
        assertEquals(0.81548, fx.cached("CHF")!!, 0.0)
        val fetched = fx.fetchedAt("CHF")
        assertNotNull(fetched)
        assertTrue(fetched!! >= before)
        assertEquals(FiatAlarmGuard.rateTimeOf("2026-10-23"), fx.rateTime("CHF"))
        assertEquals(FiatAlarmGuard.rateTimeOf("2026-10-23"), fx.rateTime("EUR"))
    }

    @Test
    fun openErApiWhenTheEcbFailsOrLacksTheCurrency() = runBlocking {
        val body = """{"result":"success","time_last_update_unix":1792800000,"rates":{"ISK":138.5}}"""
        val fx = source(mapOf(openEr to body))
        assertEquals(138.5, fx.usdTo("ISK")!!, 1e-12)
        assertEquals(1, hits[ecb])
        assertEquals(1792800000L * 1000L, fx.rateTime("ISK"))

        val partial = source(mapOf(ecb to daily("2026-10-23", "USD" to "1.12"), openEr to body))
        assertEquals(138.5, partial.usdTo("ISK")!!, 1e-12)
        assertEquals(2, hits[openEr])
    }

    @Test
    fun zeroOrUnreadableRatesCountAsMissing() = runBlocking {
        val fx = source(
            mapOf(
                ecb to "<html>maintenance</html>",
                openEr to """{"rates":{"SEK":10.5,"NOK":-1}}""",
            ),
        )
        assertEquals(10.5, fx.usdTo("SEK")!!, 1e-12)
        // Ohne Zeitangabe der Quelle gilt die Abrufzeit
        assertEquals(fx.fetchedAt("SEK"), fx.rateTime("SEK"))
        assertNull(fx.usdTo("NOK"))
        assertNull(fx.cached("NOK"))
        assertNull(fx.fetchedAt("NOK"))
        assertNull(fx.rateTime("NOK"))
        // Ein Kurs ≤ 0 bei der EZB zählt ebenfalls als fehlend
        val zero = source(mapOf(ecb to daily("2026-10-23", "USD" to "1.12", "SEK" to "0")))
        assertNull(zero.usdTo("SEK"))
    }

    @Test
    fun withoutAnySourceThereIsNoRate() = runBlocking {
        val fx = source(emptyMap())
        assertNull(fx.usdTo("JPY"))
        assertEquals(1, hits[ecb])
        assertEquals(1, hits[openEr])
        assertNull(fx.cached("JPY"))
    }

    @Test
    fun bgnWithoutOwnRateComesFromEur() = runBlocking {
        val fx = source(mapOf(ecb to daily("2026-10-23", "USD" to "1.1", "CHF" to "0.93")))
        val derive = CurrencyConversion.deriveBgnFromEur("BGN", LocalDate.now(ZoneOffset.UTC))
        val bgn = fx.usdTo("BGN")
        if (!derive) {
            assertNull(bgn)
            return@runBlocking
        }
        val eur = EcbRates.round5(1 / 1.1)
        assertEquals(eur * CurrencyConversion.BGN_PER_EUR, bgn!!, 1e-12)
        assertEquals(eur * CurrencyConversion.BGN_PER_EUR, fx.cached("BGN")!!, 1e-12)
        // Zeit des EUR-Kurses, kein eigener BGN-Eintrag
        assertEquals(fx.fetchedAt("EUR"), fx.fetchedAt("BGN"))
        assertEquals(FiatAlarmGuard.rateTimeOf("2026-10-23"), fx.rateTime("BGN"))
        // Ohne eigenen BGN-Kurs nicht bei jedem Aufruf erneut fragen; EUR kam aus derselben Abfrage der EZB
        fx.usdTo("BGN")
        assertEquals(1, hits[ecb])
        assertEquals(1, hits[openEr])
    }

    @Test
    fun currencyListStartsWithTheMostCommonAndHasNoDuplicates() {
        val list = FxRateSource.CURRENCIES
        assertEquals(listOf("CHF", "EUR", "USD"), list.take(3))
        assertEquals(list.size, list.toSet().size)
        assertTrue(list.all { it.length == 3 && it.all { c -> c in 'A'..'Z' } })
    }
}
