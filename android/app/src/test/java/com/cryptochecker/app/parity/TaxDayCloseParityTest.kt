package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioLedger
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.TaxDayClose
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Gemeinsame Testfälle `tax_day_close.json` (iOS: `TaxDayCloseParityTests.swift`). */
class TaxDayCloseParityTest {

    private val data = ParityJson.load("tax_day_close.json")

    private val closes: Map<Pair<String, LocalDate>, BigDecimal> = data["closes"].list().map { it.obj() }.associate {
        (it["coin"] as String to LocalDate.ofEpochDay(it["day"].num()!!.toLong())) to BigDecimal(it["close"] as String)
    }

    private val trades: List<PortfolioTrade> = data["trades"].list().map { it.obj() }.map { t ->
        PortfolioTrade(
            id = t["id"].num()!!.toLong(),
            coin = t["coin"] as String,
            type = PortfolioTxType.valueOf(t["type"] as String),
            amount = BigDecimal(t["amount"] as String),
            priceUsdt = null,
            time = t["time"].num()!!.toLong(),
            fee = (t["fee"] as String?)?.let { BigDecimal(it) },
            feeUnit = (t["feeUnit"] as String?)?.let { PortfolioFeeUnit.valueOf(it) },
            feeRow = t["feeRow"] as Boolean? ?: false,
        )
    }

    /** Kraken-Gebühr-Zeile und Netzwerkgebühr derselben Stunde: derselbe Tag, derselbe Kurs, derselbe Wert. */
    @Test
    fun feeRowAndNetworkFeeShareOneClose() {
        val expectedDay = LocalDate.ofEpochDay(data["expectedDay"].num()!!.toLong())
        val expectedClose = BigDecimal(data["expectedClose"] as String)
        val expectedValue = BigDecimal(data["expectedFeeValue"] as String)
        val lookup = TaxDayClose.lookup(closes)

        // Gebühr-Zeile: Bedarf wie feeDisposalPrices (PortfolioLedger.dayCloseNeeds → UTC-Tag)
        val feeRow = trades.single { it.feeRow }
        val feeNeeds = PortfolioLedger.dayCloseNeeds(listOf(feeRow)).map { it.first to TaxDayClose.day(it.second) }
        assertEquals(listOf("BTC" to expectedDay), feeNeeds)
        // Netzwerkgebühr als Veräusserung: Bedarf wie networkFeePrices
        val transfer = trades.single { it.type == PortfolioTxType.TRANSFER }
        assertEquals(setOf("BTC" to expectedDay), TaxDayClose.networkFeeNeeds(trades, data["end"].num()!!.toLong()))

        val feeRowClose = lookup("BTC", feeRow.time)
        val networkClose = lookup("BTC", transfer.time)
        assertEquals(0, expectedClose.compareTo(feeRowClose))
        assertEquals(0, expectedClose.compareTo(networkClose))
        assertEquals(expectedValue, (feeRow.amount * feeRowClose!!).setScale(2, RoundingMode.HALF_UP))
        assertEquals(expectedValue, (transfer.coinFee * networkClose!!).setScale(2, RoundingMode.HALF_UP))
    }

    /** Kurz nach Mitternacht Ortszeit gilt der Schluss des UTC-Tags, nicht des lokalen Kalendertags. */
    @Test
    fun utcDayDecides() {
        val late = data["lateLocal"].obj()
        val time = late["time"].num()!!.toLong()
        assertEquals(LocalDate.ofEpochDay(late["expectedDay"].num()!!.toLong()), TaxDayClose.day(time))
        assertEquals(0, BigDecimal(late["expectedClose"] as String).compareTo(TaxDayClose.lookup(closes)("BTC", time)))
    }
}
