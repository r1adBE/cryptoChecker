package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.data.portfolio.PortfolioJson
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportKind
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportMapping
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportParser
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportPriced
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportRow
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportTx
import com.cryptochecker.app.domain.portfolio.csvimport.CsvWithdrawalChoice
import com.cryptochecker.app.domain.portfolio.csvimport.tokenize
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/**
 * Build 76 (Portfolio-Speicher und Steuer-Export): Devisenkurs je Tag ohne Durchsuchen der ganzen Reihe, Zeitzone je
 * Vorlage, AT-Freigrenze Altbestand, Gebühr-Zeile und Zins als eigene Merker, exakter Zufluss, Vorschlag «Umbuchung».
 * Wie `PortfolioStorageTaxB76Tests.swift`.
 */
class PortfolioStorageTaxB76Test {

    private val day = 86_400_000L

    // ── Devisenkurs (rateOn) ──

    @Test
    fun fxOnOrBeforeFindsLastPositiveRateWithinGap() {
        val d = LocalDate.of(2025, 3, 10).toEpochDay()
        val rates = mapOf(d - 3 to BigDecimal("0.9"), d - 1 to BigDecimal.ZERO, d - 10 to BigDecimal("0.8"))
        for (map in listOf(rates, TreeMap(rates))) {
            // Kurs 0 übersprungen, der letzte positive davor zählt
            assertEquals(0, BigDecimal("0.9").compareTo(TaxExport.fxOnOrBefore(map, d)))
            // Mehr als 7 Tage zurück: keiner
            assertNull(TaxExport.fxOnOrBefore(map, d + 6))
            // Genau 7 Tage zurück: noch ja
            assertEquals(0, BigDecimal("0.9").compareTo(TaxExport.fxOnOrBefore(map, d + 4)))
        }
        assertNull(TaxExport.fxOnOrBefore(null, d))
        assertEquals(0, BigDecimal.ONE.compareTo(TaxExport.rateOn(null, LocalDate.of(2025, 3, 10), "usd")))
    }

    // ── Zeitzone je Vorlage ──

    @Test
    fun templateZonesAreTheCountryNeutralIsDevice() {
        val expected = mapOf(
            TaxTemplate.DE to "Europe/Berlin", TaxTemplate.AT to "Europe/Vienna", TaxTemplate.CH to "Europe/Zurich",
            TaxTemplate.UK to "Europe/London", TaxTemplate.US to "America/New_York", TaxTemplate.CA to "America/Toronto",
            TaxTemplate.AU to "Australia/Sydney", TaxTemplate.FR to "Europe/Paris", TaxTemplate.JP to "Asia/Tokyo",
        )
        val device = ZoneId.of("Pacific/Auckland")
        for (t in TaxTemplate.entries) {
            assertEquals(t.name, expected[t] ?: device.id, t.zone(device).id)
        }
        assertNull(TaxTemplate.NEUTRAL.zoneId)
    }

    /** Ein Verkauf 23:30 in London am 5.4. gehört mit der UK-Vorlage ins Steuerjahr bis 5.4., nicht in das der Gerätezone. */
    @Test
    fun ukTaxYearFollowsLondonNotDeviceZone() {
        val sale = java.time.ZonedDateTime.of(2025, 4, 5, 23, 30, 0, 0, ZoneId.of("Europe/London")).toInstant().toEpochMilli()
        val trades = listOf(
            PortfolioTrade(1, "BTC", PortfolioTxType.BUY, BigDecimal.ONE, BigDecimal("100"), sale - 30 * day),
            PortfolioTrade(2, "BTC", PortfolioTxType.SELL, BigDecimal.ONE, BigDecimal("200"), sale),
        )
        assertEquals(listOf(2024), TaxExport.years(trades, TaxTemplate.UK.zone(ZoneId.of("Asia/Tokyo")), TaxTemplate.UK).take(1))
        // In Tokio wäre es schon der 6.4. — neues Steuerjahr
        assertEquals(listOf(2025), TaxExport.years(trades, ZoneId.of("Asia/Tokyo"), TaxTemplate.UK).take(1))
    }

    // ── AT Freigrenze Altbestand ≤ 1 Jahr ──

    private fun atSummary(sellPrice: String): TaxYearSummary {
        val buy = 1604318400000L // 2.11.2020
        val sell = 1622548800000L // 1.6.2021
        val trades = listOf(
            PortfolioTrade(1, "BTC", PortfolioTxType.BUY, BigDecimal.ONE, BigDecimal("10000"), buy),
            PortfolioTrade(2, "BTC", PortfolioTxType.SELL, BigDecimal("0.01"), BigDecimal(sellPrice), sell),
        )
        return TaxExport.yearSummary(trades, TaxTemplate.AT, TaxMethod.AT_MOVING_AVERAGE, 2021, ZoneId.of("UTC"), "USD", null)
    }

    @Test
    fun atOldSpeculationLimitStatus() {
        fun status(s: TaxYearSummary) = s.lines.single { it.line == TaxLine.AT_OLD_SPECULATION_LIMIT }
        // 0.01 × (54000 − 10000) = 440: höchstens 440 → unter der Freigrenze
        assertEquals(TaxLimitStatus.BELOW, status(atSummary("54000")).status)
        // 440.01 → erreicht
        assertEquals(TaxLimitStatus.REACHED, status(atSummary("54001")).status)
        // Verlust
        assertEquals(TaxLimitStatus.LOSS, status(atSummary("5000")).status)
        assertEquals(0, BigDecimal(440).compareTo(status(atSummary("5000")).target))
        assertTrue(TaxLine.AT_OLD_SPECULATION_LIMIT.orientation)
        // Ohne Kurse (EUR): nicht prüfbar
        val noFx = TaxExport.yearSummary(
            atSummaryTrades(), TaxTemplate.AT, TaxMethod.AT_MOVING_AVERAGE, 2021, ZoneId.of("UTC"), "EUR", null,
        )
        assertEquals(TaxLimitStatus.UNKNOWN, noFx.lines.single { it.line == TaxLine.AT_OLD_SPECULATION_LIMIT }.status)
    }

    private fun atSummaryTrades() = listOf(
        PortfolioTrade(1, "BTC", PortfolioTxType.BUY, BigDecimal.ONE, BigDecimal("10000"), 1604318400000L),
        PortfolioTrade(2, "BTC", PortfolioTxType.SELL, BigDecimal("0.01"), BigDecimal("40000"), 1622548800000L),
    )

    @Test
    fun noOldHoldingsNoSpeculationLine() {
        val trades = listOf(
            PortfolioTrade(1, "BTC", PortfolioTxType.BUY, BigDecimal.ONE, BigDecimal("1000"), 1673352000000L),
            PortfolioTrade(2, "BTC", PortfolioTxType.SELL, BigDecimal.ONE, BigDecimal("1500"), 1685620800000L),
        )
        val s = TaxExport.yearSummary(trades, TaxTemplate.AT, TaxMethod.AT_MOVING_AVERAGE, 2023, ZoneId.of("UTC"), "USD", null)
        assertTrue(s.lines.none { it.line == TaxLine.AT_OLD_SPECULATION_LIMIT })
    }

    // ── Gebühr-Zeile und Zins als eigene Merker ──

    @Test
    fun legacyRulesDeriveFlagsOnce() {
        assertTrue(LegacyStorageMigration.legacyFeeRow(PortfolioTxType.SELL, "KRAKEN|F1", BigDecimal("0.00")))
        assertFalse(LegacyStorageMigration.legacyFeeRow(PortfolioTxType.SELL, null, BigDecimal.ZERO))
        assertFalse(LegacyStorageMigration.legacyFeeRow(PortfolioTxType.SELL, " ", BigDecimal.ZERO))
        assertFalse(LegacyStorageMigration.legacyFeeRow(PortfolioTxType.SELL, "K", null))
        assertFalse(LegacyStorageMigration.legacyFeeRow(PortfolioTxType.BUY, "K", BigDecimal.ZERO))
        assertTrue(LegacyStorageMigration.legacyInterest(PortfolioTxType.REWARD, "Lending interest"))
        assertFalse(LegacyStorageMigration.legacyInterest(PortfolioTxType.REWARD, "staking"))
        assertFalse(LegacyStorageMigration.legacyInterest(PortfolioTxType.BUY, "interest"))
    }

    @Test
    fun backupJsonCarriesFlagsAndDerivesThemWhenMissing() {
        val fee = PortfolioTxEntity(
            id = 3, coin = "BTC", type = PortfolioTxType.SELL, amount = BigDecimal("0.0001"), priceUsdt = BigDecimal.ZERO,
            time = 10, note = "Fee", importKey = "KRAKEN|F1", feeRow = true,
        )
        val json = PortfolioJson.txToJson(fee)
        assertTrue(json.getBoolean("feeRow"))
        assertFalse(json.getBoolean("interest"))
        assertEquals(fee, PortfolioJson.jsonToTx(JSONObject(json.toString())))
        // Ausdrücklich false bleibt false, auch wenn die frühere Regel true ergäbe
        val explicit = JSONObject(json.toString()).put("feeRow", false)
        assertFalse(PortfolioJson.jsonToTx(explicit)!!.feeRow)
        // Sicherung bis Build 75 (ohne Feld): einmal nach der früheren Regel
        val old = JSONObject(json.toString()).apply { remove("feeRow"); remove("interest") }
        assertTrue(PortfolioJson.jsonToTx(old)!!.feeRow)
        val reward = JSONObject()
            .put("coin", "ETH").put("type", "REWARD").put("amountText", "0.1").put("time", 5).put("note", "Lending interest")
        assertTrue(PortfolioJson.jsonToTx(reward)!!.interest)
        // Merker gelten nur für ihre Art
        val buy = PortfolioJson.jsonToTx(JSONObject(json.toString()).put("type", "BUY"))!!
        assertFalse(buy.feeRow)
        assertFalse(buy.toTrade().feeRow)
    }

    @Test
    fun entityFlagsReachTheTrade() {
        val sell = PortfolioTxEntity(coin = "BTC", type = PortfolioTxType.SELL, amount = BigDecimal.ONE, priceUsdt = null, time = 1, feeRow = true)
        assertTrue(PortfolioLedger.isFeeDisposal(sell.toTrade()))
        val reward = PortfolioTxEntity(coin = "ETH", type = PortfolioTxType.REWARD, amount = BigDecimal.ONE, time = 1, note = "interest")
        // Notiz allein zählt nicht mehr — nur das Feld
        assertFalse(reward.toTrade().interestIncome)
        assertTrue(reward.copy(interest = true).toTrade().interestIncome)
    }

    @Test
    fun csvImportSetsFeeRowAndInterest() {
        fun priced(kind: CsvImportKind, raw: String) = CsvImportPriced(
            row = CsvImportRow(1, kind, raw, "BTC", BigDecimal("0.01"), null, "USD", null, null, 1_700_000_000_000L, null, "X|$raw"),
            priceUsdt = BigDecimal("30000"), feeUsdt = null, needsFx = false, missingPrice = false,
        )
        val fee = CsvImportMapping.toTx(priced(CsvImportKind.FEE, "Fee"))
        assertEquals(PortfolioTxType.SELL, fee.type)
        assertTrue(fee.feeRow)
        assertFalse(fee.interest)
        val interest = CsvImportMapping.toTx(priced(CsvImportKind.REWARD, "Lending interest"))
        assertTrue(interest.interest)
        assertFalse(interest.feeRow)
        assertFalse(CsvImportMapping.toTx(priced(CsvImportKind.REWARD, "Staking")).interest)
    }

    // ── Exakter Zufluss ──

    @Test
    fun flowIsExactUntilTheResult() {
        // 3 × 0.1 + 0.2 als Double: 0.5000000000000001; exakt 0.5
        val buy = PortfolioTrade(
            1, "X", PortfolioTxType.BUY, BigDecimal("3"), BigDecimal("0.1"), 1,
            fee = BigDecimal("0.2"), feeUnit = PortfolioFeeUnit.QUOTE,
        )
        assertEquals(0.5, PortfolioLedger.flow(PortfolioLedgerEvent(buy, false), BigDecimal.ZERO)!!, 0.0)
        assertEquals(0, BigDecimal("0.5").compareTo(PortfolioLedger.flowExact(PortfolioLedgerEvent(buy, false), BigDecimal.ZERO)))
        // Ohne Preis mit Marktkurs (Double) über dessen kürzeste Darstellung
        val reward = PortfolioTrade(2, "X", PortfolioTxType.REWARD, BigDecimal("3"), null, 1)
        assertEquals(0.3, PortfolioLedger.flow(PortfolioLedgerEvent(reward, false), BigDecimal.ZERO, marketPrice = 0.1)!!, 0.0)
    }

    // ── Vorschlag «Umbuchung» ──

    private fun withdrawal(key: String, amount: String, time: Long) = CsvImportTx(
        line = 1, coin = "BTC", type = PortfolioTxType.WITHDRAWAL, amount = BigDecimal(amount), priceUsdt = null, time = time,
        fee = null, feeUnit = null, feeCoin = null, feeCoinAmount = null, note = null, importKey = key,
    )

    private fun address(vararg observations: Pair<Long, String>) = WatchedAddress(
        id = 1, chain = AddressChain.BTC, address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", label = "Ledger",
        observations = observations.map { AddressObservation(it.first, BigDecimal(it.second)) }, countInPortfolio = true,
    )

    @Test
    fun proposalNeedsObservationAfterWithdrawalWithinSevenDays() {
        val t = 1_718_870_400_000L
        val w = listOf(withdrawal("A", "0.005", t))
        // Beobachtet vor der Auszahlung: passt nicht
        assertNull(CsvImportMapping.proposeWithdrawals(w, listOf(address(t - 1 to "0.005"))).single().choice)
        // 7 Tage danach: passt noch; später nicht
        assertEquals(
            CsvWithdrawalChoice.TRANSFER,
            CsvImportMapping.proposeWithdrawals(w, listOf(address(t + CsvImportMapping.MATCH_WINDOW_MILLIS to "0.005"))).single().choice,
        )
        assertNull(CsvImportMapping.proposeWithdrawals(w, listOf(address(t + CsvImportMapping.MATCH_WINDOW_MILLIS + 1 to "0.005"))).single().choice)
    }

    @Test
    fun eachObservedChangeMatchesOnlyOneWithdrawal() {
        val t = 1_718_870_400_000L
        val two = listOf(withdrawal("A", "0.005", t), withdrawal("B", "0.005", t + 1000))
        val one = CsvImportMapping.proposeWithdrawals(two, listOf(address(t + day to "0.005")))
        assertEquals(listOf(CsvWithdrawalChoice.TRANSFER, null), one.map { it.choice })
        // Zwei Zunahmen: beide zugeordnet
        val both = CsvImportMapping.proposeWithdrawals(two, listOf(address(t + day to "0.005", t + 2 * day to "0.010")))
        assertEquals(listOf(CsvWithdrawalChoice.TRANSFER, CsvWithdrawalChoice.TRANSFER), both.map { it.choice })
    }

    @Test
    fun dustToleranceForTinyAmounts() {
        val t = 1_718_870_400_000L
        val w = listOf(withdrawal("A", "0.00000100", t))
        // 1 % von 0.000001 wäre 0.00000001 — der Staubrest erlaubt genau das, mehr nicht
        assertEquals(CsvWithdrawalChoice.TRANSFER, CsvImportMapping.proposeWithdrawals(w, listOf(address(t + 1 to "0.00000101"))).single().choice)
        assertNull(CsvImportMapping.proposeWithdrawals(w, listOf(address(t + 1 to "0.00000103"))).single().choice)
    }

    // ── Erkennung der Kopfzeile ──

    @Test
    fun tokenizeStopsAfterMaxRecords() {
        val text = (1..50).joinToString("\n") { "a$it;b$it" }
        assertEquals(10, CsvImportParser.tokenize(text, ';', maxRecords = 10).size)
        assertEquals(50, CsvImportParser.tokenize(text, ';').size)
        assertEquals("a10", CsvImportParser.tokenize(text, ';', maxRecords = 10).last().cells.first())
    }
}
