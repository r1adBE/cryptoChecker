package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioTxFirstInvalid.Field
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm.Entry
import com.cryptochecker.app.performance.PerformanceTiming.TRANSACTIONS
import com.cryptochecker.app.performance.PortfolioPerformanceData
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Kleine Testklassen dieses Themas in einer Datei (R, Build 147); jede Klasse unverändert.

// ---- aus PortfolioFifoPartsTest

/**
 * Herkunftsteile der FIFO-Posten erst beim Auslesen ([PortfolioFifoParts]): Die Gebühr USDT einer Umbuchung steht
 * einmal im Gebühren-Strom der Warteschlange; Veräusserungen und offener Einstand lesen ihre Teile daraus. Gleich wie
 * iOS (`PortfolioFifoPartsTests`):
 *  - jede Veräusserung: Summe der Teile = Einstand (exakt), kein Teil nach dem Abgang;
 *  - offene Posten: Summe der Teile = Einstand mit Kaufpreis (exakt);
 *  - Umbuchung auf zwei Posten mit Kaufpreis und einen ohne: Anteil nach Menge, der Anteil ohne Kaufpreis vermerkt.
 */
class PortfolioFifoPartsTest {

    private val trades = PortfolioPerformanceData.trades(TRANSACTIONS.small)

    private fun sum(parts: List<PortfolioCostPart>): BigDecimal = parts.fold(BigDecimal.ZERO) { acc, p -> acc + p.usdt }

    @Test
    fun partsAddUpToCostOnLargePortfolio() {
        for (method in listOf(CostMethod.FIFO, CostMethod.FIFO_WALLET)) {
            val books = PortfolioCalculator.ledgers(trades, method)
            var checked = 0
            for (book in books) {
                for (d in book.disposals) {
                    val cost = d.costUsdt ?: continue
                    if (d.costParts.isEmpty()) continue
                    assertEquals("${method.name} ${d.coin} ${d.sellTxId}", 0, cost.compareTo(sum(d.costParts)))
                    assertTrue(d.costParts.all { it.time <= d.sellTime })
                    checked++
                }
                val open = book.position.pricedCostParts
                val cost = book.position.pricedCostExact
                if (open.isNotEmpty() && cost != null) assertEquals("${method.name} ${book.position.coin} open", 0, cost.compareTo(sum(open)))
            }
            assertTrue("${method.name}: Veräusserungen mit Teilen geprüft", checked > 100)
        }
    }

    @Test
    fun transferFeeSharedByAmountAcrossQueue() {
        val t0 = 1_700_000_000_000L
        val day = 86_400_000L
        val list = listOf(
            trade(1, "BUY", "1", "100", t0),
            trade(2, "DEPOSIT", "2", null, t0 + day),
            trade(3, "BUY", "1", "200", t0 + 2 * day),
            // Gebühr 8 USDT auf 4 Coins: je Coin 2 — Posten 1 und 3 je 2, Posten 2 (ohne Kaufpreis) 4 vermerkt
            trade(4, "TRANSFER", "1", null, t0 + 3 * day, fee = "8"),
            trade(5, "SELL", "1", "500", t0 + 4 * day),
        )
        val book = PortfolioCalculator.ledgers(list, CostMethod.FIFO).single()
        val sale = book.disposals.single()
        assertEquals(0, BigDecimal("102").compareTo(sale.costUsdt))
        assertEquals(listOf(t0, t0 + 3 * day), sale.costParts.map { it.time })
        assertEquals(0, BigDecimal("100").compareTo(sale.costParts[0].usdt))
        assertEquals(0, BigDecimal("2").compareTo(sale.costParts[1].usdt))
        assertEquals(0, BigDecimal("4").compareTo(book.unassignedFees.single().usdt))
        // Offen: Posten 3 mit 200 + 2 (und Posten 2 ohne Kaufpreis)
        assertEquals(0, BigDecimal("202").compareTo(book.position.pricedCostExact))
        assertEquals(listOf(t0 + 2 * day, t0 + 3 * day), book.position.pricedCostParts.map { it.time })
    }

    private fun trade(id: Long, type: String, amount: String, price: String?, time: Long, fee: String? = null) = PortfolioTrade(
        id = id, coin = "BTC", type = PortfolioTxType.valueOf(type), amount = BigDecimal(amount),
        priceUsdt = price?.let(::BigDecimal), time = time,
        fee = fee?.let(::BigDecimal), feeUnit = if (fee != null) PortfolioFeeUnit.QUOTE else null,
    )
}

// ---- aus PortfolioHistoryAsOfTest

/** Älterer Stand im Wertverlauf (eigene Börse nicht erreichbar); Gegenstück `PortfolioHistoryAsOfTests`. */
class PortfolioHistoryAsOfTest {

    private val now = 1_700_000_000_000L
    private val ttl = 12 * 60 * 60_000L

    @Test
    fun oldestStaleTimeAmongDrawnCoins() {
        val stale = mapOf("BTC" to now - 3 * ttl, "ETH" to now - 2 * ttl, "SOL" to now - 5 * ttl)
        // SOL ist nicht gezeichnet (ohne Verlauf) und zählt nicht
        assertEquals(now - 3 * ttl, PortfolioHistoryAsOf.of(stale, listOf("BTC", "ETH", "ADA")))
        assertNull(PortfolioHistoryAsOf.of(stale, listOf("ADA")))
        assertNull(PortfolioHistoryAsOf.of(emptyMap(), listOf("BTC")))
        assertNull(PortfolioHistoryAsOf.of(mapOf("BTC" to 0L), listOf("BTC")))
    }

    @Test
    fun fallbackIsStaleOnlyWhenOlderThanTheCacheTime() {
        // Jünger als 12 h: heutiger Verlauf (nur evtl. kürzer), kein «Stand …»
        assertNull(PortfolioHistoryAsOf.staleSince(now - 60_000L, now, ttl))
        // Älter: mit seiner Zeit markiert
        assertEquals(now - ttl, PortfolioHistoryAsOf.staleSince(now - ttl, now, ttl))
        assertEquals(now - 3 * ttl, PortfolioHistoryAsOf.staleSince(now - 3 * ttl, now, ttl))
        // Unbekannte Zeit gilt nicht als frisch; künftige (Uhr zurückgestellt) wie frisch, nie eine künftige Anzeige
        assertEquals(0L, PortfolioHistoryAsOf.staleSince(0L, now, ttl))
        assertNull(PortfolioHistoryAsOf.staleSince(now + 60_000L, now, ttl))
    }
}

// ---- aus PortfolioHoldingDaysTest

/** Haltedauer in Kalendertagen der gewählten Zeitzone ([PortfolioFifo.holdingDays], wie die Steuer-Liste). */
class PortfolioHoldingDaysTest {

    private fun at(text: String, zone: ZoneId) = ZonedDateTime.of(java.time.LocalDateTime.parse(text), zone).toInstant().toEpochMilli()

    @Test
    fun utcDefaultMatchesCalendarDaysInUtc() {
        val from = at("2026-01-01T23:30:00", ZoneOffset.UTC)
        val to = at("2026-01-02T00:30:00", ZoneOffset.UTC)
        assertEquals(1L, PortfolioFifo.holdingDays(from, to))
    }

    @Test
    fun zoneDecidesTheCalendarDay() {
        val zurich = ZoneId.of("Europe/Zurich")
        // 00:30 und 23:30 Ortszeit am selben Tag — in UTC zwei Tage (23:30 UTC am Vortag, 22:30 UTC)
        val from = at("2026-01-02T00:30:00", zurich)
        val to = at("2026-01-02T23:30:00", zurich)
        assertEquals(1L, PortfolioFifo.holdingDays(from, to))
        assertEquals(0L, PortfolioFifo.holdingDays(from, to, zurich))
    }
}

// ---- aus PortfolioInsightsTest

/** «Aufteilung», «Grösste Bewegungen» und «Beträge verbergen» (Spiegel: PortfolioInsightsTests.swift). */
class PortfolioInsightsTest {

    private fun position(coin: String, value: Double?) = CoinPosition(
        coin = coin, holdings = 1.0, avgCost = null, costBasis = null, currentPrice = value, value = value,
        unrealized = null, unrealizedPercent = null, realized = 0.0, priceMissing = false, oversold = false, tradeCount = 1,
    )

    @Test
    fun allocation_topFourAndOthers() {
        val open = listOf(
            position("BTC", 500.0), position("ETH", 200.0), position("SOL", 100.0),
            position("ADA", 100.0), position("DOT", 60.0), position("XRP", 40.0),
        )
        val slices = PortfolioInsights.allocation(open)
        assertEquals(listOf("BTC", "ETH", "ADA", "SOL", null), slices.map { it.coin })
        assertEquals(50.0, slices[0].sharePercent, 1e-9)
        assertEquals(100.0, slices.last().valueUsd, 1e-9)
        assertEquals(10.0, slices.last().sharePercent, 1e-9)
        assertTrue(slices.last().isOther)
        assertEquals(100.0, slices.sumOf { it.sharePercent }, 1e-9)
    }

    @Test
    fun allocation_noOthersWithFourOrFewer_andSkipsUnpriced() {
        val slices = PortfolioInsights.allocation(listOf(position("BTC", 300.0), position("ETH", null), position("SOL", 100.0)))
        assertEquals(listOf("BTC", "SOL"), slices.map { it.coin })
        assertEquals(75.0, slices[0].sharePercent, 1e-9)
        assertTrue(PortfolioInsights.allocation(listOf(position("ETH", null), position("X", 0.0))).isEmpty())
    }

    @Test
    fun valueChange_fromPercent() {
        // 110 jetzt nach +10 % → damals 100, also +10
        assertEquals(10.0, PortfolioInsights.valueChange(110.0, 10.0)!!, 1e-9)
        // 90 jetzt nach −10 % → damals 100, also −10
        assertEquals(-10.0, PortfolioInsights.valueChange(90.0, -10.0)!!, 1e-9)
        assertNull(PortfolioInsights.valueChange(90.0, -100.0))
        assertNull(PortfolioInsights.valueChange(Double.NaN, 1.0))
    }

    @Test
    fun movers_byAbsoluteValueChange() {
        val open = listOf(position("BTC", 1000.0), position("ETH", 500.0), position("SOL", 50.0), position("ADA", 200.0), position("DOT", 80.0))
        val changes = mapOf("BTC" to 1.0, "ETH" to -10.0, "SOL" to 50.0, "ADA" to 0.0, "XRP" to 99.0)
        val movers = PortfolioInsights.movers(open, changes)
        // ETH −55.56, SOL +16.67, BTC +9.90; ADA ohne Bewegung, DOT ohne Veränderung
        assertEquals(listOf("ETH", "SOL", "BTC"), movers.map { it.coin })
        assertEquals(-500.0 + 500.0 / 0.9, -movers[0].changeUsd, 1e-9)
        assertTrue(movers[0].changeUsd < 0)
        assertEquals(-10.0, movers[0].changePercent, 1e-9)
    }

    @Test
    fun movers_limitAndEmpty() {
        assertTrue(PortfolioInsights.movers(listOf(position("BTC", 100.0)), emptyMap()).isEmpty())
        val open = (1..5).map { position("C$it", it * 100.0) }
        val changes = open.associate { it.coin to 5.0 }
        assertEquals(listOf("C5", "C4", "C3"), PortfolioInsights.movers(open, changes).map { it.coin })
    }

    @Test
    fun mask() {
        assertEquals("•••", PortfolioInsights.mask("1’234.00 USDT", hidden = true))
        assertEquals("1’234.00 USDT", PortfolioInsights.mask("1’234.00 USDT", hidden = false))
    }
}

// ---- aus PortfolioOffTest

/** Gleiche Fälle wie `PortfolioOffTests`. */
class PortfolioOffTest {

    @Test
    fun `nur eine neue Transaktion schaltet das ausgeschaltete Portfolio ein`() {
        assertTrue(PortfolioOff.enableOnSave(isNew = true, portfolioEnabled = false))
        assertFalse(PortfolioOff.enableOnSave(isNew = true, portfolioEnabled = true))
        assertFalse(PortfolioOff.enableOnSave(isNew = false, portfolioEnabled = false))
        assertFalse(PortfolioOff.enableOnSave(isNew = false, portfolioEnabled = true))
    }

    @Test
    fun `nur die erste neue Transaktion bei Portfolio aus fragt nach dem Tab`() {
        assertTrue(PortfolioOff.askEnableOnSave(isNew = true, portfolioEnabled = false, hadTransactions = false))
        assertFalse(PortfolioOff.askEnableOnSave(isNew = true, portfolioEnabled = false, hadTransactions = true))
        assertFalse(PortfolioOff.askEnableOnSave(isNew = true, portfolioEnabled = true, hadTransactions = false))
        assertFalse(PortfolioOff.askEnableOnSave(isNew = false, portfolioEnabled = false, hadTransactions = false))
    }

    @Test
    fun `hinzufuegen wird immer angeboten ausser in der Vorschau`() {
        assertTrue(PortfolioOff.offerAddToPortfolio(preview = false))
        assertFalse(PortfolioOff.offerAddToPortfolio(preview = true))
    }

    @Test
    fun `widget aus geht vor Sperre und leer`() {
        assertEquals(PortfolioOff.WidgetState.OFF, PortfolioOff.widgetState(portfolioEnabled = false, locked = true, empty = true))
        assertEquals(PortfolioOff.WidgetState.OFF, PortfolioOff.widgetState(portfolioEnabled = false, locked = false, empty = false))
        assertEquals(PortfolioOff.WidgetState.LOCKED, PortfolioOff.widgetState(portfolioEnabled = true, locked = true, empty = true))
        assertEquals(PortfolioOff.WidgetState.EMPTY, PortfolioOff.widgetState(portfolioEnabled = true, locked = false, empty = true))
        assertEquals(PortfolioOff.WidgetState.VALUES, PortfolioOff.widgetState(portfolioEnabled = true, locked = false, empty = false))
    }

    @Test
    fun `widget oeffnet Tab oder Einstellungen`() {
        assertEquals(PortfolioOff.WidgetTarget.PORTFOLIO_TAB, PortfolioOff.widgetTarget(portfolioEnabled = true))
        assertEquals(PortfolioOff.WidgetTarget.PORTFOLIO_SETTINGS, PortfolioOff.widgetTarget(portfolioEnabled = false))
    }
}

// ---- aus PortfolioTxFirstInvalidTest

/** Erstes ungültiges Feld beim Speichern ([PortfolioTxFirstInvalid]). Wie `PortfolioTxFirstInvalidTests` (iOS). */
class PortfolioTxFirstInvalidTest {

    private fun dec(s: String) = BigDecimal(s)

    private fun buy() = Entry(type = PortfolioTxType.BUY, coinQuery = "btc", amount = dec("1"))

    private val swap = Entry(
        type = PortfolioTxType.SWAP, coinQuery = "BTC", amount = dec("1"), toCoinQuery = "ETH", toAmount = dec("2"),
    )

    private fun first(e: Entry): Field? =
        PortfolioTxFirstInvalid.of(PortfolioTxForm.check(e), amountValid = e.amount != null, isSwap = e.type == PortfolioTxType.SWAP)

    @Test
    fun gueltigeEingabeHatKeinFehlerfeld() {
        assertNull(first(buy()))
        assertNull(first(swap))
    }

    @Test
    fun reihenfolgeWieImBlatt() {
        // Coin und Menge falsch: zuerst der Coin (steht oben)
        assertEquals(Field.COIN, first(buy().copy(coinQuery = "", amount = null)))
        assertEquals(Field.AMOUNT, first(buy().copy(amount = null, priceBlank = false)))
        // Preis unlesbar, Gebühr unlesbar: zuerst der Preis
        assertEquals(Field.PRICE, first(buy().copy(priceBlank = false, feeBlank = false)))
        assertEquals(Field.FEE, first(buy().copy(feeBlank = false)))
    }

    @Test
    fun tauschFelder() {
        assertEquals(Field.TO_COIN, first(swap.copy(toCoinQuery = "btc", toAmount = null)))
        assertEquals(Field.TO_AMOUNT, first(swap.copy(toAmount = null)))
        assertEquals(Field.SWAP_VALUE, first(swap.copy(swapValueBlank = false, swapValue = dec("-1"))))
    }

    @Test
    fun gebuehrImDrittenCoinUndVerbrauchteMenge() {
        // Dritter Coin gleich dem Coin der Transaktion: ungültig
        assertEquals(Field.FEE_COIN, first(buy().copy(feeCoin = "BTC", feeCoinAmountBlank = false, feeCoinAmount = dec("1"))))
        // Gebühr im Coin ≥ Menge: am Gebührenfeld
        assertEquals(Field.FEE, first(buy().copy(feeBlank = false, fee = dec("1"), feeUnit = PortfolioFeeUnit.COIN)))
        // Tausch: Gebühr im erhaltenen Coin ≥ erhaltene Menge — an der Gebühr im erhaltenen Coin
        assertEquals(Field.FEE_COIN, first(swap.copy(feeCoin = "ETH", feeCoinAmountBlank = false, feeCoinAmount = dec("2"))))
    }

    @Test
    fun nurGebuehrenfelderLiegenImAbschnitt() {
        assertTrue(Field.FEE.inLedger)
        assertTrue(Field.FEE_COIN.inLedger)
        assertFalse(Field.PRICE.inLedger)
        assertFalse(Field.COIN.inLedger)
    }

    @Test
    fun keinFehlerfeldGenauDannWennSpeicherbar() {
        val entries = listOf(
            buy(), buy().copy(coinQuery = ""), buy().copy(amount = null), buy().copy(priceBlank = false),
            buy().copy(feeBlank = false), swap, swap.copy(toAmount = null),
            swap.copy(feeCoin = "ETH", feeCoinAmountBlank = false, feeCoinAmount = dec("2")),
        )
        for (e in entries) {
            assertEquals(e.toString(), PortfolioTxForm.check(e).valid, first(e) == null)
        }
    }
}
