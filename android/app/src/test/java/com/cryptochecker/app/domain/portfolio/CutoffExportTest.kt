package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

class CutoffExportTest {

    private var nextId = 1L
    private val zurich = ZoneId.of("Europe/Zurich")
    private val d = 1e-9

    private fun bd(text: String) = BigDecimal(text)

    /** Gleicher Zahlenwert (Skala egal). */
    private fun same(expected: String, actual: BigDecimal?) =
        assertEquals("$expected ≠ $actual", 0, bd(expected).compareTo(actual!!))

    private fun buy(coin: String, amount: Double, price: Double?, time: Long) =
        PortfolioTrade(nextId++, coin, PortfolioTxType.BUY, amount, price, time)

    private fun sell(coin: String, amount: Double, price: Double?, time: Long) =
        PortfolioTrade(nextId++, coin, PortfolioTxType.SELL, amount, price, time)

    private fun at(date: String, hour: Int, minute: Int = 0) =
        LocalDate.parse(date).atTime(hour, minute).atZone(zurich).toInstant().toEpochMilli()

    private val texts = CutoffCsvTexts(
        coin = "Coin", amount = "Menge", priceUsdt = "Kurs USDT", valueUsdt = "Wert USDT",
        rate = "Kurs USD→CHF", valueTarget = "Wert CHF", note = "Hinweis", total = "Total",
        noPrice = "kein Kurs", noFx = "kein Devisenkurs", unchecked = "ungeprüft", incomplete = "unvollständig",
        commentFormat = "# Stichtag %1\$s, Währung %2\$s, Devisenkurs vom %3\$s",
    )

    @Test
    fun endOfDayIsLastMillisecondInZone() {
        val end = CutoffExport.endOfDayMillis(LocalDate.of(2025, 12, 31), zurich)
        assertEquals(at("2026-01-01", 0) - 1, end)
    }

    @Test
    fun defaultDateIsLastNewYearsEve() {
        assertEquals(LocalDate.of(2025, 12, 31), CutoffExport.defaultDate(LocalDate.of(2026, 10, 4)))
        assertEquals(LocalDate.of(2025, 12, 31), CutoffExport.defaultDate(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun holdingsOnlyCountTradesUntilEndOfDay() {
        val cutoff = CutoffExport.endOfDayMillis(LocalDate.of(2025, 12, 31), zurich)
        val trades = listOf(
            buy("btc", 1.0, 100.0, at("2025-03-01", 12)),
            sell("BTC", 0.25, 200.0, at("2025-12-31", 23, 59)),   // zählt noch
            buy("BTC", 5.0, 300.0, at("2026-01-01", 0, 1)),       // nach dem Stichtag
            buy("ETH", 2.0, null, at("2025-06-01", 12)),
            sell("ETH", 2.0, 10.0, at("2025-07-01", 12)),         // ganz verkauft → fällt weg
            buy("ADA", 10.0, 0.5, at("2026-02-01", 12)),          // erst später gekauft
            buy("USDT", 50.0, 1.0, at("2025-01-01", 12)),
        )
        val h = CutoffExport.holdingsAt(trades, cutoff)
        assertEquals(listOf("BTC", "USDT"), h.map { it.coin })
        same("0.75", h[0].amount)
        same("50", h[1].amount)
    }

    @Test
    fun rowsUseStableOneAndMarkMissingPrice() {
        val holdings = listOf(
            CutoffHolding("BTC", 0.5),
            CutoffHolding("FOO", 3.0),
            CutoffHolding("USDC", 10.0),
        )
        val rows = CutoffExport.rows(holdings, mapOf("BTC" to bd("90000")), bd("0.8"))
        same("45000", rows[0].valueUsdt)
        same("36000", rows[0].valueTarget)
        assertTrue(rows[1].noPrice)
        assertNull(rows[1].valueUsdt)
        assertNull(rows[1].valueTarget)
        // USDC ohne Tagesschluss: «ohne Kurs» wie jeder Coin (kein stilles 1)
        assertTrue(rows[2].noPrice)
        assertNull(rows[2].valueTarget)
    }

    @Test
    fun csvLayout() {
        val rows = CutoffExport.rows(
            listOf(CutoffHolding("BTC", 0.5), CutoffHolding("FOO", 3.0)),
            mapOf("BTC" to bd("87654.321")),
            bd("0.79123"),
        )
        val csv = CutoffExport.csv(LocalDate.of(2025, 12, 31), "CHF", "2025-12-31", rows, texts)
        assertTrue(csv.startsWith("\uFEFF"))
        val lines = csv.removePrefix("\uFEFF").split("\r\n")
        assertEquals("# Stichtag 2025-12-31, Währung CHF, Devisenkurs vom 2025-12-31", lines[0])
        assertEquals("Coin;Menge;Kurs USDT;Wert USDT;Kurs USD→CHF;Wert CHF;Hinweis", lines[1])
        assertEquals("BTC;0.5;87654.321;43827.16;0.79123;34677.36;", lines[2])
        assertEquals("FOO;3;;;0.79123;;kein Kurs", lines[3])
        assertEquals("Total;;;43827.16;0.79123;34677.36;unvollständig", lines[4])
        assertEquals("", lines[5])
        assertEquals(6, lines.size)
    }

    @Test
    fun csvFormatPerLocale() {
        // de-CH: Excel trennt mit «;», Dezimalpunkt
        assertEquals(CutoffCsvFormat(';', '.'), CutoffCsvFormat.forLocale("CH", '.'))
        assertEquals(CutoffCsvFormat(';', '.'), CutoffCsvFormat.forLocale("li", '.'))
        // fr-CH mit Komma
        assertEquals(CutoffCsvFormat(';', ','), CutoffCsvFormat.forLocale("CH", ','))
        // de-DE, fr-FR: Dezimalkomma → «;» + «,»
        assertEquals(CutoffCsvFormat(';', ','), CutoffCsvFormat.forLocale("DE", ','))
        // en-US, Arabisch u. a.: «,» + «.»
        assertEquals(CutoffCsvFormat(',', '.'), CutoffCsvFormat.forLocale("US", '.'))
        assertEquals(CutoffCsvFormat(',', '.'), CutoffCsvFormat.forLocale("", '٫'))
    }

    @Test
    fun csvWithDecimalComma() {
        val rows = CutoffExport.rows(
            listOf(CutoffHolding("BTC", 0.5), CutoffHolding("FOO", 3.0)),
            mapOf("BTC" to bd("87654.321")),
            bd("0.79123"),
        )
        val lines = CutoffExport.csv(
            LocalDate.of(2025, 12, 31), "EUR", "2025-12-31", rows, texts, CutoffCsvFormat(';', ','),
        ).removePrefix("\uFEFF").split("\r\n")
        assertEquals("BTC;0,5;87654,321;43827,16;0,79123;34677,36;", lines[2])
        assertEquals("FOO;3;;;0,79123;;kein Kurs", lines[3])
        assertEquals("Total;;;43827,16;0,79123;34677,36;unvollständig", lines[4])
    }

    @Test
    fun csvWithCommaSeparatorQuotesTexts() {
        val rows = CutoffExport.rows(
            listOf(CutoffHolding("BTC", 0.5), CutoffHolding("FOO", 3.0)),
            mapOf("BTC" to bd("87654.321")),
            null,
        )
        val lines = CutoffExport.csv(
            LocalDate.of(2025, 12, 31), "USD", null, rows, texts, CutoffCsvFormat(',', '.'),
        ).removePrefix("\uFEFF").split("\r\n")
        assertEquals("\"# Stichtag 2025-12-31, Währung USD, Devisenkurs vom —\"", lines[0])
        assertEquals("Coin,Menge,Kurs USDT,Wert USDT,Kurs USD→CHF,Wert CHF,Hinweis", lines[1])
        assertEquals("BTC,0.5,87654.321,43827.16,,,kein Devisenkurs", lines[2])
        assertEquals("FOO,3,,,,,\"kein Kurs, kein Devisenkurs\"", lines[3])
        assertEquals("\"a,b\"", CutoffExport.escape("a,b", ','))
        assertEquals("a;b", CutoffExport.escape("a;b", ','))
    }

    @Test
    fun uncheckedCoinsGetEmptyPriceAndNote() {
        // ETH ungeprüft (kein passender aktueller Kurs) — auch ein vorhandener Kurs wird nicht verwendet;
        // USDT bleibt 1 (Regel unverändert)
        val rows = CutoffExport.rows(
            listOf(CutoffHolding("BTC", 0.5), CutoffHolding("ETH", 2.0), CutoffHolding("USDT", 10.0)),
            mapOf("BTC" to bd("100"), "ETH" to bd("3000")),
            BigDecimal.ONE,
            unchecked = setOf("ETH", "USDT"),
        )
        assertTrue(rows[1].unchecked)
        assertFalse(rows[1].noPrice)
        assertNull(rows[1].priceUsdt)
        assertFalse(rows[2].unchecked)
        same("1", rows[2].priceUsdt)
        val lines = CutoffExport.csv(LocalDate.of(2025, 12, 31), "CHF", "2025-12-31", rows, texts)
            .removePrefix("\uFEFF").split("\r\n")
        assertEquals("ETH;2;;;1;;ungeprüft", lines[3])
        assertEquals("Total;;;60.00;1;60.00;unvollständig", lines[5])
    }

    @Test
    fun referencePricesSkipUncheckedAndInvalid() {
        val ref = CutoffExport.referencePrices(
            mapOf("BTC" to 100.0, "FOO" to 2.0, "BAR" to 0.0, "BAZ" to Double.NaN),
            uncheckedCurrent = setOf("FOO"),
        )
        assertEquals(mapOf("BTC" to 100.0), ref)
        // Laden gescheitert → leer → alle ungeprüft
        assertTrue(CutoffExport.referencePrices(emptyMap(), emptySet()).isEmpty())
    }

    @Test
    fun csvWithoutFxRate() {
        val rows = CutoffExport.rows(listOf(CutoffHolding("ETH", 2.0)), mapOf("ETH" to bd("3000")), null)
        val csv = CutoffExport.csv(LocalDate.of(2025, 12, 31), "CHF", null, rows, texts)
        val lines = csv.removePrefix("\uFEFF").split("\r\n")
        assertEquals("# Stichtag 2025-12-31, Währung CHF, Devisenkurs vom —", lines[0])
        assertEquals("ETH;2;3000;6000.00;;;kein Devisenkurs", lines[2])
        assertEquals("Total;;;6000.00;;;kein Devisenkurs", lines[3])
    }

    @Test
    fun emptyHoldingsGiveZeroTotal() {
        val csv = CutoffExport.csv(LocalDate.of(2025, 12, 31), "USD", null, emptyList(), texts)
        val lines = csv.removePrefix("\uFEFF").split("\r\n")
        assertEquals("Total;;;0.00;;;", lines[2])
    }

    @Test
    fun exactWhereDoubleWouldBeWrong() {
        // 0.1 + 0.2 aus der Buchhaltung: genau 0.3 (Double: 0.30000000000000004)
        val trades = listOf(
            PortfolioTrade(nextId++, "ETH", PortfolioTxType.BUY, bd("0.1"), BigDecimal.ONE, 1L),
            PortfolioTrade(nextId++, "ETH", PortfolioTxType.BUY, bd("0.2"), BigDecimal.ONE, 2L),
            PortfolioTrade(nextId++, "SOL", PortfolioTxType.BUY, bd("0.000000000000000001"), BigDecimal.ONE, 3L),
        )
        val h = CutoffExport.holdingsAt(trades, 10L)
        assertEquals("0.3", CutoffExport.amount(h[0].amount))
        assertEquals("0.000000000000000001", CutoffExport.amount(h[1].amount))
        // 0.075 × 3 = 0.225 → «0.23» (Double: 0.22499999999999998 → «0.22»)
        val rows = CutoffExport.rows(
            listOf(CutoffHolding("ETH", bd("0.075")), CutoffHolding("BTC", bd("123456789.123456789"))),
            mapOf("ETH" to bd("3"), "BTC" to bd("12345.6789")),
            bd("0.79123"),
        )
        same("0.225", rows[0].valueUsdt)
        assertEquals("0.23", CutoffExport.money(rows[0].valueUsdt!!))
        same("1524157876543.2099750190521", rows[1].valueUsdt)
        same("1205959436657.284028534324593083", rows[1].valueTarget)
        // Kurs aus einem Double: über die kürzeste Darstellung, nicht über den Binärwert
        same("0.1", PortfolioDecimal.of(0.1))
        assertEquals("1000000000.000000001", CutoffExport.amount(bd("1000000000.000000001")))
        assertEquals("0.00000000003", CutoffExport.price(bd("3E-11")))
    }

    @Test
    fun numberFormats() {
        assertEquals("0.0000123", CutoffExport.price(0.0000123))
        assertEquals("0.00", CutoffExport.money(-0.001))
        assertEquals("1234567.89", CutoffExport.money(1234567.891))
        assertEquals("12", CutoffExport.amount(12.0))
        // Kleinstkurse mit gültigen Stellen statt «0», kaufmännisch gerundet wie iOS
        assertEquals("0.00000000003", CutoffExport.price(3e-11))
        assertEquals("1.01", CutoffExport.money(1.005))
        assertEquals("", CutoffExport.money(Double.NaN))
        assertEquals("\"a;b\"", CutoffExport.escape("a;b"))
        assertEquals("\"x\"\"y\"", CutoffExport.escape("x\"y"))
    }

    /** Formel-Injektion: Textfelder mit = + - @ Tab CR am Anfang bekommen ein Apostroph, Zahlen nie. */
    @Test
    fun formulaInjection() {
        assertEquals("'=HYPERLINK(\"x\")", CutoffExport.escape("=HYPERLINK(\"x\")", ',').removeSurrounding("\"").replace("\"\"", "\""))
        assertEquals("'=1+2", CutoffExport.escape("=1+2"))
        assertEquals("'+41", CutoffExport.escape("+41"))
        assertEquals("'-x", CutoffExport.escape("-x"))
        assertEquals("'@SUM(A1)", CutoffExport.escape("@SUM(A1)"))
        assertEquals("'\tx", CutoffExport.escape("\tx"))
        assertEquals("\"'\rx\"", CutoffExport.escape("\rx"))
        assertEquals("'=a;b".let { "\"$it\"" }, CutoffExport.escape("=a;b"))
        assertEquals("a=b", CutoffExport.escape("a=b"))
        assertEquals("", CutoffExport.escape(""))
        // Zahlen vom Zahlenformat bleiben Zahlen
        assertEquals("-12.50", CutoffExport.escapeNumber("-12.50"))
        assertEquals("\"-12,50\"", CutoffExport.escapeNumber("-12,50", ','))
        assertEquals("-12.50;'-1;x", CutoffExport.joinCells(listOf(CsvCell("-12.50", true), CsvCell("-1"), CsvCell("x")), ';'))
    }

    /** Steuer-CSV: Coin und Wallet aus Eingaben neutralisiert, negativer Gewinn bleibt Zahl. */
    @Test
    fun taxCsvInjection() {
        val zone = ZoneId.of("UTC")
        val t0 = LocalDate.of(2025, 1, 2).atStartOfDay(zone).toInstant().toEpochMilli()
        val trades = listOf(
            PortfolioTrade(1, "=EVIL", PortfolioTxType.BUY, bd("1"), bd("100"), t0, exchange = "@wallet"),
            PortfolioTrade(2, "=EVIL", PortfolioTxType.SELL, bd("1"), bd("40"), t0 + 86_400_000L, exchange = "@wallet"),
        )
        val texts = TaxCsvTexts(
            coin = "Coin", acquired = "Acq", sold = "Sold", holdingDays = "Days", longTerm = "> 1y", amount = "Amount",
            costUsdt = "Cost", proceedsUsdt = "Proceeds", gainUsdt = "Gain", costTarget = "CostT", proceedsTarget = "ProcT",
            gainTarget = "GainT", date = "Date", valueUsdt = "V", valueTarget = "VT", note = "Note", total = "Total",
            yes = "yes", no = "no", costMissing = "cm", proceedsMissing = "pm", feeLeg = "fl", feeValueMissing = "fv",
            avgCostRates = "avg", valueMissing = "vm", noFx = "nofx", incomplete = "inc", wallet = "%s",
        )
        val rows = TaxExport.gainRows(trades, CostMethod.FIFO_WALLET, 2025, zone, "USD", null)
        val csv = TaxExport.gainsCsv(rows, texts, listOf("-header"), CutoffCsvFormat.SEMICOLON_POINT)
        val lines = csv.removePrefix("\uFEFF").split("\r\n")
        assertEquals("'-header", lines[0])
        assertEquals("'=EVIL;2025-01-02;2025-01-03;1;no;1;100.00;40.00;-60.00;100.00;40.00;-60.00;'@wallet", lines[2])
        assertEquals("Total;;;;;;100.00;40.00;-60.00;100.00;40.00;-60.00;", lines[3])
    }
}
