package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.util.SourceGuard
import com.cryptochecker.app.util.StringResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Leeres Portfolio: nur ein Satz (das Beispiel) statt Text plus Beispiel, damit samt Fusstext alles ohne
 * Scrollen sichtbar ist; die Menge im Beispiel steht als Platzhalter und kommt aus dem Zahlenformat der App.
 * Beide Plattformen; Text in allen Sprachen. Wie `PortfolioEmptyStateTests.swift`.
 */
class PortfolioEmptyStateTest {

    private fun file(path: String): File? = listOf(File(path), File("app/$path")).firstOrNull { it.exists() }

    @Test
    fun mengeFolgtDemZahlenformatDerApp() {
        val before = Locale.getDefault(Locale.Category.FORMAT)
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("de-CH"))
            assertEquals("0.01", PortfolioEmptyExample.amountText())
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("de-DE"))
            assertEquals("0,01", PortfolioEmptyExample.amountText())
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, before)
        }
    }

    @Test
    fun beispielMitPlatzhalterInAllenSprachen() {
        val languages = StringResources.byLanguage()
        assertEquals(31, languages.size)
        for ((folder, strings) in languages) {
            val example = strings["portfolio_empty_example_plain"]
            assertTrue("$folder: fehlt", example != null)
            assertEquals("$folder: genau ein Platzhalter", 1, Regex("%1\\\$s").findAll(example!!).count())
            assertTrue("$folder: Menge vor BTC", example.contains("%1\$s BTC"))
            assertFalse("$folder: feste Zahl", Regex("0[.,]01").containsMatchIn(example))
            assertFalse("$folder: doppelter Absatz", strings.containsKey("portfolio_empty_text"))
        }
    }

    @Test
    fun androidLeerzustandOhneZweitenAbsatz() {
        val text = SourceGuard.android(
            file("src/main/java/com/cryptochecker/app/ui/features/portfolio/PortfolioRows.kt"), "PortfolioRows.kt"
        ).readText().substringAfter("internal fun EmptyPortfolio(").substringBefore("internal object PortfolioEmptyExample")
        assertFalse(text.contains("message ="))
        assertTrue(text.contains("R.string.portfolio_empty_example_plain, PortfolioEmptyExample.amountText()"))
        assertTrue(text.contains("PortfolioDisclaimer()"))
    }

    @Test
    fun iosLeerzustandOhneZweitenAbsatz() {
        val path = "../../ios/App/Features/Portfolio/PortfolioRows.swift"
        val text = SourceGuard.ios(
            listOf(File(path), File(path.removePrefix("../"))).firstOrNull { it.exists() }, "ios/…/PortfolioRows.swift"
        ).readText()
        val empty = text.substringAfter("var emptyState: some View").substringBefore("// MARK: Coin-Zeile")
        assertFalse(empty.contains("message:"))
        assertTrue(empty.contains("L(\"portfolio_empty_example_plain\", PortfolioEmptyExample.amountText())"))
        assertTrue(empty.contains("PortfolioDisclaimer()"))
        assertTrue(text.contains("static let amount = 0.01"))
    }
}
