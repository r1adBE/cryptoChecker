package com.cryptochecker.wear

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Kurs der Komplikation (`testdata/parity/watch_price.json`): knapp für die kurze Form ([WatchFormat.compactPrice]),
 * Zeile der langen Form ([WatchFormat.priceLine]) und die Portfolio-Komplikation ohne Wert
 * ([WatchPortfolioText.offLine]). Gegenstück: iOS `WatchPriceFormatParityTests.swift`.
 */
class WatchPriceParityTest {

    private val data = JSONObject(fixtureDir().resolve("watch_price.json").readText(Charsets.UTF_8))

    /** Ordner `testdata/parity`: Gradle startet Unit-Tests im Modulordner (`wear/`), andere Läufer im Projektordner. */
    private fun fixtureDir(): File = above("testdata/parity")

    private fun above(vararg paths: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (path in paths) {
                val candidate = File(dir, path)
                if (candidate.exists()) return candidate
            }
            dir = dir.parentFile
        }
        error("${paths.joinToString()} not found above ${System.getProperty("user.dir")}")
    }

    private fun locale(id: String): Locale = Locale.forLanguageTag(id.replace('_', '-'))

    private fun cases(block: String) = data.getJSONObject(block).getJSONArray("cases").also { assertTrue(it.length() > 0) }

    @Test
    fun kursKnapp() {
        val cases = cases("compact")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val value = c.getDouble("value")
            assertEquals("${c.getString("locale")} $value", c.getString("expected"),
                WatchFormat.compactPrice(value, locale(c.getString("locale"))))
        }
    }

    @Test
    fun knappHoechstensSiebenZeichenBisEineMillionTausend() {
        for (value in listOf(84_450.12, 999_999.0, 2_234.56, 123.456, 0.02468, 0.0098765, 0.000012345, 999_000_000.0)) {
            val text = WatchFormat.compactPrice(value, Locale.US)
            assertTrue("$value → $text", text.length <= 7)
        }
    }

    @Test
    fun zeileDerLangenKomplikation() {
        val cases = cases("line")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val price = if (c.isNull("price")) null else c.getDouble("price")
            val change = if (c.isNull("change")) null else c.getDouble("change")
            val detail = if (c.isNull("detail")) null else c.getString("detail")
            assertEquals(c.toString(), c.getString("expected"),
                WatchFormat.priceLine(c.getString("coin"), price, c.getString("quote"), change, detail,
                    locale(c.getString("locale"))))
        }
    }

    @Test
    fun portfolioOhneWert() {
        val cases = cases("portfolio_off")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(c.getString("line"), WatchPortfolioText.offLine(c.getString("title"), c.getString("hint")))
            assertEquals(c.getString("spoken"), WatchPortfolioText.offSpoken(c.getString("title"), c.getString("hint")))
        }
    }

    /** Beide Komplikationen: kurz der Kurs statt der Änderung, Portfolio ohne Wert nie leer (keine «keine Daten»). */
    @Test
    fun komplikationenNutzenDieRegeln() {
        val dir = above("src/main/java/com/cryptochecker/wear", "wear/src/main/java/com/cryptochecker/wear")
        val price = File(dir, "PriceComplicationService.kt").readText(Charsets.UTF_8)
        assertTrue(price.contains("ShortTextComplicationData.Builder(text(WatchFormat.compactPrice(price)), description)"))
        assertTrue(price.contains("WatchFormat.priceLine(coin, price, quote, change, detail)"))
        assertTrue(price.contains("WatchPair.parse(coin)?.quote ?: WatchPair.USDT"))
        // Ohne gewählte Coins ein Hinweis mit Tipp-Ziel statt «keine Daten»
        assertTrue(price.contains("?: return empty(request.complicationType, settings.mode == WatchSettings.Mode.PHONE, tap)"))
        val portfolio = File(dir, "PortfolioComplicationService.kt").readText(Charsets.UTF_8)
        assertFalse(portfolio.contains("NoDataComplicationData"))
        assertTrue(portfolio.contains("portfolioFlow.value ?: return off(request.complicationType, tap)"))
        assertTrue(portfolio.contains("R.string.watch_portfolio_off"))
        assertTrue(portfolio.contains("WatchPortfolioText.offLine(title, hint)"))
    }
}
