package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetEntries
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetEntry
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetEntryFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * Buchungen unter den Coins im Portfolio-Widget (`widget_portfolio_entries.json`) — wie
 * `WidgetPortfolioEntriesParityTests.swift`: gekürzte Notiz, Bestandsänderung je Buchung nach der Regel des Rechners
 * und welche Buchungen unter den Coins Platz haben.
 */
class WidgetPortfolioEntriesParityTest {

    private val data = ParityJson.load("widget_portfolio_entries.json")

    private fun trade(raw: Any?): PortfolioTrade {
        val o = raw.obj()
        return PortfolioTrade(
            id = o["id"].num()!!.toLong(),
            coin = o["coin"] as String,
            type = PortfolioTxType.valueOf(o["type"] as String),
            amount = BigDecimal(o["amount"] as String),
            priceUsdt = (o["price"] as String?)?.let(::BigDecimal),
            time = o["time"].num()!!.toLong(),
            fee = (o["fee"] as String?)?.let(::BigDecimal),
            feeUnit = (o["feeUnit"] as String?)?.let { PortfolioFeeUnit.valueOf(it) },
            exchange = o["exchange"] as String?,
            transferFrom = o["transferFrom"] as String?,
            transferTo = o["transferTo"] as String?,
            toCoin = o["toCoin"] as String?,
            toAmount = (o["toAmount"] as String?)?.let(::BigDecimal),
            swapValueUsdt = (o["swapValue"] as String?)?.let(::BigDecimal),
        )
    }

    @Test
    fun shortNote() {
        for (raw in data.getValue("shortNote").list()) {
            val c = raw.obj()
            assertEquals("«${c["note"]}»", c["expected"] as String?, PortfolioWidgetEntries.shortNote(c["note"] as String?))
        }
    }

    @Test
    fun entriesFollowTheHoldingsRule() {
        for (raw in data.getValue("entries").list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val trades = c["trades"].list().map(::trade)
            val notes = c["notes"].obj().entries.associate { (id, note) -> id.toLong() to note as String? }
            val entries = PortfolioWidgetEntries.withValues(
                PortfolioWidgetEntries.of(c["coin"] as String, trades, notes, CostMethod.valueOf(c["method"] as String)),
                c["price"].num(),
                c["fx"].num()!!,
            )
            val expected = c["expected"].list().map { it.obj() }
            assertEquals("$name: Anzahl", expected.size, entries.size)
            expected.zip(entries).forEachIndexed { i, (e, actual) ->
                assertEquals("$name [$i] Menge", 0, BigDecimal(e["quantity"] as String).compareTo(BigDecimal(actual.quantity.toString())))
                assertEquals("$name [$i] Notiz", e["note"] as String?, actual.note)
                val value = e["value"].num()
                if (value == null) assertNull("$name [$i] Wert", actual.value) else assertEquals("$name [$i] Wert", value, actual.value!!, 1e-9)
            }
            assertEquals("$name: Summe", 0, BigDecimal(c["total"] as String).compareTo(PortfolioWidgetEntries.total(entries)))
        }
    }

    @Test
    fun assignKeepsWholeCoins() {
        for (raw in data.getValue("assign").list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val perCoin = c["perCoin"].list().map { coin ->
                (coin as List<*>?)?.mapIndexed { i, q -> PortfolioWidgetEntry(i.toLong(), (q as String).toDouble(), null) }
            }
            val shown = PortfolioWidgetEntries.assign(
                perCoin,
                PortfolioWidgetEntryFilter.fromKey(c["filter"] as String),
                c["available"].num()!!,
                c["rowHeight"].num()!!,
            )
            val expected = c["expected"].list().map { coin -> coin.list().map { it.num()!!.toInt() } }
            assertEquals(name, expected, shown.map { coin -> coin.map { it.time.toInt() } })
        }
    }
}
