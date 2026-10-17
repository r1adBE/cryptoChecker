package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class EstvKurslisteTest {

    private val texts = CutoffCsvTexts(
        coin = "Coin", amount = "Menge", priceUsdt = "Kurs USDT", valueUsdt = "Wert USDT",
        rate = "Kurs", valueTarget = "Wert", note = "Hinweis", total = "Total",
        noPrice = "kein Kurs", noFx = "kein Devisenkurs", unchecked = "ungeprüft", incomplete = "unvollständig",
        commentFormat = "# %1\$s %2\$s %3\$s",
        estvNoteFormat = "ESTV-Kursliste %1\$s: %2\$s",
        estvLanguage = "de",
    )

    @Test
    fun parityFixture() {
        val data = ParityJson.load("estv_kursliste.json")
        val urls = data["urls"].list()
        urls.forEach { raw ->
            val c = raw.obj()
            assertEquals("url ${c["language"]}", c["expected"], EstvKursliste.listUrl(c["year"].num()!!.toInt(), c["language"] as String))
        }
        val applies = data["applies"].list()
        applies.forEach { raw ->
            val c = raw.obj()
            assertEquals("applies ${c["date"]} ${c["currency"]}", c["expected"],
                EstvKursliste.applies(LocalDate.parse(c["date"] as String), c["currency"] as String))
        }
        assertTrue(urls.size >= 5 && applies.size >= 4)
    }

    @Test
    fun noteLineOnlyWithFormatInChfAtYearEnd() {
        val d = LocalDate.of(2025, 12, 31)
        assertEquals(
            "ESTV-Kursliste 2025: https://www.ictax.admin.ch/extern/de.html#/ratelist/2025",
            EstvKursliste.noteLine("ESTV-Kursliste %1\$s: %2\$s", d, "CHF", "de"),
        )
        assertNull(EstvKursliste.noteLine(null, d, "CHF", "de"))
        assertNull(EstvKursliste.noteLine("", d, "CHF", "de"))
        assertNull(EstvKursliste.noteLine("x %1\$s %2\$s", d, "EUR", "de"))
        assertNull(EstvKursliste.noteLine("x %1\$s %2\$s", LocalDate.of(2025, 6, 30), "CHF", "de"))
    }

    @Test
    fun csvHasNoteLineAfterCommentInChf() {
        val rows = CutoffExport.rows(listOf(CutoffHolding("BTC", BigDecimal("0.5"))), mapOf("BTC" to BigDecimal("100000")), BigDecimal("0.8"))
        val lines = CutoffExport.csv(LocalDate.of(2025, 12, 31), "CHF", "2025-12-31", rows, texts)
            .removePrefix("\uFEFF").split("\r\n")
        assertEquals("ESTV-Kursliste 2025: https://www.ictax.admin.ch/extern/de.html#/ratelist/2025", lines[1])
        assertEquals("Coin;Menge;Kurs USDT;Wert USDT;Kurs;Wert;Hinweis", lines[2])
        // Mit «,» als Trennzeichen bleibt die Zeile ein Feld
        val comma = CutoffExport.csv(LocalDate.of(2025, 12, 31), "CHF", null, rows,
            texts.copy(estvNoteFormat = "ESTV, %1\$s: %2\$s"), CutoffCsvFormat.COMMA_POINT)
            .removePrefix("\uFEFF").split("\r\n")
        assertEquals("\"ESTV, 2025: https://www.ictax.admin.ch/extern/de.html#/ratelist/2025\"", comma[1])
    }

    @Test
    fun csvWithoutNoteOutsideChfOrYearEnd() {
        val rows = CutoffExport.rows(listOf(CutoffHolding("BTC", BigDecimal("1"))), mapOf("BTC" to BigDecimal("1")), BigDecimal("1"))
        val eur = CutoffExport.csv(LocalDate.of(2025, 12, 31), "EUR", null, rows, texts).removePrefix("\uFEFF").split("\r\n")
        assertEquals("Coin;Menge;Kurs USDT;Wert USDT;Kurs;Wert;Hinweis", eur[1])
        val mid = CutoffExport.csv(LocalDate.of(2025, 6, 30), "CHF", null, rows, texts).removePrefix("\uFEFF").split("\r\n")
        assertEquals("Coin;Menge;Kurs USDT;Wert USDT;Kurs;Wert;Hinweis", mid[1])
    }
}
