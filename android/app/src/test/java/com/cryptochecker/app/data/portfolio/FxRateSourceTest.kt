package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.convert.FiatAlarmGuard
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
 * Devisenkurse ([FxRateSource]) mit einem Interceptor als Anbieter: Frankfurter zuerst, sonst open.er-api, Kurs ≤ 0
 * oder unlesbare Antwort zählen als fehlend, Zwischenspeicher innerhalb der Frist, Zeitpunkt laut Quelle, BGN aus EUR.
 */
class FxRateSourceTest {

    private val hits = HashMap<String, Int>()

    private fun frankfurter(code: String) = "https://api.frankfurter.app/latest?from=USD&to=$code"

    private val openEr = "https://open.er-api.com/v6/latest/USD"

    private fun source(replies: Map<String, String>) = FxRateSource(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            hits[url] = (hits[url] ?: 0) + 1
            val body = replies[url] ?: throw IOException("offline: $url")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
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
    fun frankfurterRateIsCachedWithTheEcbDate() = runBlocking {
        val body = """{"amount":1.0,"base":"USD","date":"2026-10-23","rates":{"CHF":0.79}}"""
        val fx = source(mapOf(frankfurter("CHF") to body))
        val before = System.currentTimeMillis()
        assertEquals(0.79, fx.usdTo("chf")!!, 1e-12)
        assertEquals(0.79, fx.usdTo("CHF")!!, 1e-12)
        // Zweiter Aufruf innerhalb der Frist: aus dem Zwischenspeicher
        assertEquals(1, hits[frankfurter("CHF")])
        assertNull(hits[openEr])
        assertEquals(0.79, fx.cached("CHF")!!, 1e-12)
        val fetched = fx.fetchedAt("CHF")
        assertNotNull(fetched)
        assertTrue(fetched!! >= before)
        assertEquals(FiatAlarmGuard.rateTimeOf("2026-10-23"), fx.rateTime("CHF"))
    }

    @Test
    fun openErApiWhenFrankfurterFails() = runBlocking {
        val body = """{"result":"success","time_last_update_unix":1792800000,"rates":{"ISK":138.5}}"""
        val fx = source(mapOf(openEr to body))
        assertEquals(138.5, fx.usdTo("ISK")!!, 1e-12)
        assertEquals(1, hits[frankfurter("ISK")])
        assertEquals(1792800000L * 1000L, fx.rateTime("ISK"))
    }

    @Test
    fun zeroOrUnreadableRatesCountAsMissing() = runBlocking {
        val fx = source(
            mapOf(
                frankfurter("SEK") to """{"rates":{"SEK":0}}""",
                frankfurter("NOK") to "<html>maintenance</html>",
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
    }

    @Test
    fun withoutAnySourceThereIsNoRate() = runBlocking {
        val fx = source(emptyMap())
        assertNull(fx.usdTo("JPY"))
        assertEquals(1, hits[frankfurter("JPY")])
        assertEquals(1, hits[openEr])
        assertNull(fx.cached("JPY"))
    }

    @Test
    fun bgnWithoutOwnRateComesFromEur() = runBlocking {
        val fx = source(mapOf(frankfurter("EUR") to """{"date":"2026-10-23","rates":{"EUR":0.9}}"""))
        val derive = CurrencyConversion.deriveBgnFromEur("BGN", LocalDate.now(ZoneOffset.UTC))
        val bgn = fx.usdTo("BGN")
        if (!derive) {
            assertNull(bgn)
            return@runBlocking
        }
        assertEquals(0.9 * CurrencyConversion.BGN_PER_EUR, bgn!!, 1e-12)
        assertEquals(0.9 * CurrencyConversion.BGN_PER_EUR, fx.cached("BGN")!!, 1e-12)
        // Zeit des EUR-Kurses, kein eigener BGN-Eintrag
        assertEquals(fx.fetchedAt("EUR"), fx.fetchedAt("BGN"))
        assertEquals(FiatAlarmGuard.rateTimeOf("2026-10-23"), fx.rateTime("BGN"))
        // Ohne eigenen BGN-Kurs nicht bei jedem Aufruf erneut beim Anbieter fragen
        fx.usdTo("BGN")
        assertEquals(1, hits[frankfurter("BGN")])
    }

    @Test
    fun currencyListStartsWithTheMostCommonAndHasNoDuplicates() {
        val list = FxRateSource.CURRENCIES
        assertEquals(listOf("CHF", "EUR", "USD"), list.take(3))
        assertEquals(list.size, list.toSet().size)
        assertTrue(list.all { it.length == 3 && it.all { c -> c in 'A'..'Z' } })
    }
}
