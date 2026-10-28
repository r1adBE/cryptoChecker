package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Teilung und Multiplikation einmal auf 34 Stellen gerundet (`testdata/parity/portfolio_decimal.json`): `divide`,
 * `multiply` und `divideProduct` (Produkt exakt, dann geteilt) — wie die Rechenwege der Buchhaltung
 * (`divide(…, PortfolioDecimal.MC)`, `multiply(…, PortfolioDecimal.MC)`, `multiply(…).divide(…, PortfolioDecimal.MC)`).
 * Gegenstück: `Tests/PortfolioDecimalParityTests.swift` (iOS rechnet dafür exakt über die Mantissen).
 */
class PortfolioDecimalParityTest {

    private val data = ParityJson.load("portfolio_decimal.json")

    private fun cases(section: String) = data[section].list().map { it.obj() }.also { assertTrue(section, it.isNotEmpty()) }

    private fun dec(value: Any?) = BigDecimal(value as String)

    /** Ohne Exponent und Nullen am Ende. */
    private fun plain(v: BigDecimal): String = if (v.signum() == 0) "0" else v.stripTrailingZeros().toPlainString()

    @Test
    fun divide() {
        cases("divide").forEach { c ->
            val a = dec(c["a"])
            val b = dec(c["b"])
            assertEquals("divide: $c", c["expected"], plain(a.divide(b, PortfolioDecimal.MC)))
            // Abgeleiteter Preis des CSV-Imports: dieselbe Teilung
            assertEquals("derive: $c", c["expected"], plain(CsvImport.derive(a, b)))
        }
    }

    @Test
    fun multiply() {
        cases("multiply").forEach { c ->
            assertEquals("multiply: $c", c["expected"], plain(dec(c["a"]).multiply(dec(c["b"]), PortfolioDecimal.MC)))
        }
    }

    @Test
    fun divideProduct() {
        cases("divideProduct").forEach { c ->
            val q = dec(c["a"]).multiply(dec(c["b"])).divide(dec(c["c"]), PortfolioDecimal.MC)
            assertEquals("divideProduct: $c", c["expected"], plain(q))
        }
    }

    /** Jede Gruppe enthält Fälle, in denen doppeltes Runden abwiche. */
    @Test
    fun doubleRoundingCovered() {
        listOf("divide", "multiply", "divideProduct").forEach { section ->
            assertTrue(section, cases(section).any { it["doubleRounding"] == true })
        }
        // Produkte, die ein einfaches `a * b` mit iOS `Decimal` anders ergäbe (Geldpfade nutzen `multiply(…, MC)`)
        assertTrue(cases("multiply").any { it["plainDecimalDiffers"] == true })
    }
}
