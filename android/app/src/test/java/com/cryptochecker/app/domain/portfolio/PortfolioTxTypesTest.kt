package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Umbuchung, Belohnung, Ein-/Auszahlung und Gebühr in einem dritten Coin ([PortfolioLedger]).
 * Wie `PortfolioTxTypesTests` (iOS); gemeinsame Fälle in `testdata/parity/portfolio.json`.
 */
class PortfolioTxTypesTest {

    private fun dec(s: String) = BigDecimal(s)
    private fun same(expected: String, actual: BigDecimal?) =
        assertEquals("expected $expected, got ${actual?.toPlainString()}", 0, dec(expected).compareTo(actual))

    private fun tx(
        id: Long, coin: String, type: PortfolioTxType, amount: String, price: String?, time: Long,
        fee: String? = null, unit: PortfolioFeeUnit? = null,
        feeCoin: String? = null, feeAmount: String? = null, feePrice: String? = null,
    ) = PortfolioTrade(
        id, coin, type, dec(amount), price?.let(::dec), time, fee?.let(::dec), unit,
        feeCoin, feeAmount?.let(::dec), feePrice?.let(::dec),
    )

    private val day = 86_400_000L

    @Test
    fun `type names stay stable and unknown names are null`() {
        assertEquals(
            listOf("BUY", "SELL", "TRANSFER", "REWARD", "DEPOSIT", "WITHDRAWAL", "SWAP", "GIFT", "PAYMENT"),
            PortfolioTxType.entries.map { it.name },
        )
        // «Verschenkt» und «Damit bezahlt» als Text gespeichert (Name je Fall)
        assertEquals(PortfolioTxType.GIFT, PortfolioTxType.fromName("GIFT"))
        assertEquals(PortfolioTxType.PAYMENT, PortfolioTxType.fromName("PAYMENT"))
        assertTrue(PortfolioTxType.GIFT.isOutflow && PortfolioTxType.GIFT.isWithdrawalLike && !PortfolioTxType.GIFT.isSaleLike)
        assertTrue(PortfolioTxType.PAYMENT.isOutflow && PortfolioTxType.PAYMENT.isSaleLike && !PortfolioTxType.PAYMENT.isInflow)
        assertEquals(PortfolioTxType.REWARD, PortfolioTxType.fromName("REWARD"))
        assertNull(PortfolioTxType.fromName("STAKE"))
        assertNull(PortfolioTxType.fromName(null))
        assertTrue(PortfolioTxType.DEPOSIT.isInflow)
        assertTrue(PortfolioTxType.WITHDRAWAL.isOutflow)
        assertFalse(PortfolioTxType.TRANSFER.isInflow || PortfolioTxType.TRANSFER.isOutflow)
    }

    @Test
    fun `third coin fee is only valid for another coin with an amount`() {
        val same = tx(1, "BNB", PortfolioTxType.BUY, "1", "300", 1, feeCoin = "bnb", feeAmount = "0.1")
        assertNull(same.thirdFeeCoin)
        assertEquals(0, BigDecimal.ZERO.compareTo(same.thirdFeeValue))
        val noAmount = tx(1, "BTC", PortfolioTxType.BUY, "1", "300", 1, feeCoin = "BNB")
        assertNull(noAmount.thirdFeeCoin)
        val usdt = tx(1, "BTC", PortfolioTxType.BUY, "1", "300", 1, feeCoin = " usdt ", feeAmount = "2")
        assertEquals("USDT", usdt.thirdFeeCoin)
        // USDT gilt immer 1 — auch ohne erfassten Preis
        same("2", usdt.thirdFeeValue)
        val unknown = tx(1, "BTC", PortfolioTxType.BUY, "1", "300", 1, feeCoin = "BNB", feeAmount = "0.1")
        assertNull(unknown.thirdFeeValue)
    }

    @Test
    fun `transfer keeps the cost basis and spreads network fee into the average`() {
        val trades = listOf(
            tx(1, "BTC", PortfolioTxType.BUY, "1", "100", day),
            tx(2, "BTC", PortfolioTxType.TRANSFER, "1", null, 2 * day, "0.25", PortfolioFeeUnit.COIN),
        )
        val p = PortfolioCalculator.position("BTC", trades, 100.0)
        same("0.75", p.holdingsExact)
        same("100", p.costBasisExact)
        assertEquals(133.33333333333334, p.avgCost!!, 1e-9)
        same("0", p.realizedExact)
        assertEquals(0.0, p.soldAmount, 0.0)
        // Menge + Netzwerkgebühr (1,25) über dem Bestand 1: markiert wie FIFO je Wallet (Rechnung unverändert)
        assertTrue(p.oversold)
        assertEquals(2, p.tradeCount)
    }

    @Test
    fun `withdrawn coin stays visible as closed position`() {
        val trades = listOf(
            tx(1, "ADA", PortfolioTxType.DEPOSIT, "100", "0.5", day),
            tx(2, "ADA", PortfolioTxType.WITHDRAWAL, "100", null, 2 * day),
        )
        val s = PortfolioCalculator.summarize(trades, mapOf("ADA" to 0.6))
        assertTrue(s.open.isEmpty())
        assertEquals(listOf("ADA"), s.closed.map { it.coin })
        assertEquals(100.0, s.closed.single().withdrawnAmount, 0.0)
        same("0", s.realizedExact)
        assertFalse(s.realizedIncomplete)
    }

    @Test
    fun `fee only coin becomes a position and is listed when used`() {
        val trades = listOf(
            tx(1, "BNB", PortfolioTxType.DEPOSIT, "1", null, day),
            tx(2, "BTC", PortfolioTxType.BUY, "1", "100", 2 * day, feeCoin = "BNB", feeAmount = "0.1", feePrice = "300"),
        )
        val s = PortfolioCalculator.summarize(trades, mapOf("BTC" to 100.0, "BNB" to 300.0))
        val bnb = s.open.single { it.coin == "BNB" }
        same("0.9", bnb.holdingsExact)
        // Gebühr aus dem Teil ohne Kaufpreis: Gewinn unbekannt
        assertTrue(bnb.realizedIncomplete)
        same("130", s.open.single { it.coin == "BTC" }.costBasisExact)
        assertTrue(s.feeValueMissingCoins.isEmpty())

        val unknown = trades.toMutableList().also {
            it[1] = tx(2, "BTC", PortfolioTxType.BUY, "1", "100", 2 * day, feeCoin = "BNB", feeAmount = "0.1")
        }
        assertEquals(listOf("BTC"), PortfolioCalculator.summarize(unknown, emptyMap()).feeValueMissingCoins)
    }

    @Test
    fun `holdings timeline and cutoff include all types and fee coins`() {
        val trades = listOf(
            tx(1, "BNB", PortfolioTxType.BUY, "2", "300", day),
            tx(2, "ETH", PortfolioTxType.REWARD, "1", null, 2 * day),
            tx(3, "ETH", PortfolioTxType.TRANSFER, "1", null, 3 * day, "0.01", PortfolioFeeUnit.COIN, "BNB", "0.5", null),
            tx(4, "ETH", PortfolioTxType.WITHDRAWAL, "0.49", null, 4 * day),
        )
        assertEquals(
            listOf(2 * day to 1.0, 3 * day to 0.99, 4 * day to 0.5),
            PortfolioHistory.holdingsTimeline("ETH", trades),
        )
        assertEquals(listOf(day to 2.0, 3 * day to 1.5), PortfolioHistory.holdingsTimeline("BNB", trades))
        val cut = CutoffExport.holdingsAt(trades, 3 * day)
        assertEquals(listOf("BNB", "ETH"), cut.map { it.coin })
        same("1.5", cut[0].amount)
        same("0.99", cut[1].amount)
    }

    @Test
    fun `ledger flows follow the rules per type`() {
        val buy = tx(1, "SOL", PortfolioTxType.BUY, "2", "10", day, "1", PortfolioFeeUnit.QUOTE, "BNB", "0.01", "300")
        val e = PortfolioLedgerEvent(buy, false)
        assertEquals(24.0, PortfolioLedger.flow(e, BigDecimal.ZERO)!!, 1e-12)
        // Gebühr-Abgang im BNB: −0.01 × 300, ohne Bestand 0
        val leg = PortfolioLedgerEvent(buy, true)
        assertEquals(-3.0, PortfolioLedger.flow(leg, dec("1"))!!, 1e-12)
        assertEquals(0.0, PortfolioLedger.flow(leg, BigDecimal.ZERO)!!, 0.0)
        // Umbuchung: nur Gebühren in USDT
        val transfer = tx(2, "SOL", PortfolioTxType.TRANSFER, "2", null, 2 * day, "0.5", PortfolioFeeUnit.QUOTE)
        assertEquals(0.5, PortfolioLedger.flow(PortfolioLedgerEvent(transfer, false), dec("2"))!!, 1e-12)
        // Auszahlung ohne Preis zum Marktkurs (nicht zum Einstand): kein Gewinn/Verlust aus dem Abgang
        val out = PortfolioLedgerEvent(tx(3, "SOL", PortfolioTxType.WITHDRAWAL, "1", null, 3 * day), false)
        assertTrue(PortfolioLedger.needsMarketPrice(out))
        assertEquals(-20.0, PortfolioLedger.flow(out, dec("2"), 20.0)!!, 1e-12)
        assertNull(PortfolioLedger.flow(out, dec("2")))
        // Belohnung ohne Preis: zum Marktkurs (neutral wie eine Einzahlung ohne Preis), ohne Marktkurs unbekannt
        val reward = PortfolioLedgerEvent(tx(4, "SOL", PortfolioTxType.REWARD, "1", null, 4 * day), false)
        assertTrue(PortfolioLedger.needsMarketPrice(reward))
        assertEquals(20.0, PortfolioLedger.flow(reward, dec("1"), 20.0)!!, 1e-12)
        assertNull(PortfolioLedger.flow(reward, dec("1")))
        // Kauf ohne Preis bleibt unbekannt (kein Marktkurs)
        val unpricedBuy = PortfolioLedgerEvent(tx(6, "SOL", PortfolioTxType.BUY, "1", null, 4 * day), false)
        assertFalse(PortfolioLedger.needsMarketPrice(unpricedBuy))
        assertNull(PortfolioLedger.flow(unpricedBuy, dec("1"), 20.0))
        // Gebühr im dritten Coin ohne Preis: Gebühr-Abgang und Haupttransaktion zum Marktkurs des Gebühr-Coins
        val noFeePrice = tx(7, "SOL", PortfolioTxType.BUY, "2", "10", day, null, null, "BNB", "0.01", null)
        val noFeeLeg = PortfolioLedgerEvent(noFeePrice, true)
        assertTrue(PortfolioLedger.needsMarketPrice(noFeeLeg))
        assertEquals(-3.0, PortfolioLedger.flow(noFeeLeg, dec("1"), 300.0)!!, 1e-12)
        assertNull(PortfolioLedger.flow(noFeeLeg, dec("1")))
        val noFeeMain = PortfolioLedgerEvent(noFeePrice, false)
        assertTrue(PortfolioLedger.needsFeeMarketPrice(noFeeMain))
        assertFalse(PortfolioLedger.needsFeeMarketPrice(e)) // Gebühr-Preis erfasst
        assertEquals(23.0, PortfolioLedger.flow(noFeeMain, BigDecimal.ZERO, null, 300.0)!!, 1e-12)
        assertNull(PortfolioLedger.flow(noFeeMain, BigDecimal.ZERO))
        // Adress-Änderung (negative Id) zum Marktkurs
        val address = PortfolioLedgerEvent(tx(-5, "SOL", PortfolioTxType.DEPOSIT, "0.5", null, 4 * day), false)
        assertTrue(PortfolioLedger.needsMarketPrice(address))
        assertEquals(10.0, PortfolioLedger.flow(address, dec("1"), 20.0)!!, 1e-12)
    }

    @Test
    fun `history cash flows cover rewards, transfer fees and fee coins`() {
        val today = 20_000L
        val closes = mapOf(
            "DOT" to (today - 30..today).associateWith { 10.0 },
            "BNB" to (today - 30..today).associateWith { 300.0 },
        )
        val live = mapOf("DOT" to 10.0, "BNB" to 300.0)
        val base = listOf(
            tx(1, "BNB", PortfolioTxType.BUY, "1", "300", (today - 20) * day),
            tx(2, "DOT", PortfolioTxType.BUY, "1", "10", (today - 20) * day),
        )
        val withFlows = base + listOf(
            tx(3, "DOT", PortfolioTxType.REWARD, "1", "10", (today - 2) * day + 1),
            tx(4, "DOT", PortfolioTxType.TRANSFER, "2", null, (today - 1) * day + 1, feeCoin = "BNB", feeAmount = "0.01", feePrice = "300"),
        )
        val s = PortfolioHistory.build(withFlows, closes, live, PortfolioHistoryRange.WEEK, today)
        assertEquals(
            listOf(PortfolioCashFlow(today - 2, 10.0), PortfolioCashFlow(today - 1, -3.0), PortfolioCashFlow(today - 1, 3.0)),
            s.cashFlows!!.sortedWith(compareBy({ it.epochDay }, { it.amount })),
        )
        // Belohnung ist Zufluss, die Gebühr der Umbuchung (0.01 BNB × 300) bleibt als Kosten: netto −3
        assertEquals(-3.0, s.netChange!!, 1e-9)

        // Belohnung ohne Wert: zum Tagesschluss (10) — der Coin bleibt drin
        val unpricedReward = base + tx(3, "DOT", PortfolioTxType.REWARD, "1", null, (today - 2) * day + 1)
        assertEquals(
            listOf(PortfolioCashFlow(today - 2, 10.0)),
            PortfolioHistory.build(unpricedReward, closes, live, PortfolioHistoryRange.WEEK, today).cashFlows,
        )
        // Kauf ohne Preis bleibt unbekannt
        val unknown = base + tx(3, "DOT", PortfolioTxType.BUY, "1", null, (today - 2) * day + 1)
        assertNull(PortfolioHistory.build(unknown, closes, live, PortfolioHistoryRange.WEEK, today).cashFlows)
    }

    @Test
    fun `route follows the latest transfer destination`() {
        val r = PortfolioExchangeRoute.exchangeByLedger(
            listOf(
                PortfolioExchangeRoute.RouteEntry("BTC", "Binance", PortfolioTxType.BUY, null, 1, 1),
                PortfolioExchangeRoute.RouteEntry("BTC", null, PortfolioTxType.TRANSFER, "Kraken", 2, 2),
                PortfolioExchangeRoute.RouteEntry("ETH", "Kraken", PortfolioTxType.BUY, null, 1, 3),
                PortfolioExchangeRoute.RouteEntry("ETH", null, PortfolioTxType.TRANSFER, "Ledger Nano", 2, 4),
            )
        )
        assertEquals(mapOf("BTC" to "Kraken", "ETH" to "Ledger Nano"), r.first)
        assertTrue(r.second.isEmpty())
        // Wallet ist keine Spot-Börse der App → kein eigener Weg (Binance)
        assertNull(PortfolioExchangeRoute.exchange("Ledger Nano", mapOf("Kraken" to "Kraken", "Binance" to "Binance")))
    }

    /** Damit bezahlt = Veräusserung zum Marktwert in allen Methoden; Abfluss wie ein Verkauf. Wie iOS. */
    @Test
    fun `payment is a disposal at market value in every method`() {
        val trades = listOf(
            tx(1, "BTC", PortfolioTxType.BUY, "1", "100", day),
            tx(2, "BTC", PortfolioTxType.PAYMENT, "0.4", "300", 2 * day),
        )
        for (m in CostMethod.entries) {
            val l = PortfolioCalculator.ledger("BTC", trades, 300.0, m)
            same("0.6", l.position.holdingsExact)
            // Erlös 0.4 × 300 = 120 − Einstand 40 = 80 (kein Geld erhalten, trotzdem realisiert)
            same("80", l.position.realizedExact)
            assertTrue("$m payment", l.disposals.isNotEmpty() && l.disposals.all { it.payment && !it.gift && !it.valueEstimated })
        }
        val e = PortfolioLedger.events("BTC", trades)
        assertFalse(PortfolioLedger.needsMarketPrice(e[1]))
        assertEquals(-120.0, PortfolioLedger.flow(e[1], dec("1"))!!, 1e-9)
        same("0.6", PortfolioLedger.holdingsAfter(dec("1"), e[1]))
    }

    /** Damit bezahlt ohne Marktwert: Verlauf zum Marktkurs, Karte/Steuer zum Tagesschluss geschätzt. Wie iOS. */
    @Test
    fun `payment without value is estimated at the day close`() {
        val trades = listOf(
            tx(1, "BTC", PortfolioTxType.BUY, "1", "100", day),
            tx(2, "BTC", PortfolioTxType.PAYMENT, "0.4", null, 2 * day),
        )
        val e = PortfolioLedger.events("BTC", trades)
        assertTrue(PortfolioLedger.needsMarketPrice(e[1]))
        assertEquals(-100.0, PortfolioLedger.flow(e[1], dec("1"), marketPrice = 250.0)!!, 1e-9)
        assertNull(PortfolioLedger.flow(e[1], dec("1")))
        // Ohne Tageskurs: Erlös unbekannt
        val unknown = PortfolioCalculator.ledger("BTC", trades, null)
        assertTrue(unknown.position.realizedIncomplete)
        // Mit Tageskurs 250: 0.4 × 250 − 40 = 60, geschätzt
        val l = PortfolioCalculator.ledger("BTC", trades, null, feePrice = { _, _ -> 250.0 })
        same("60", l.position.realizedExact)
        assertFalse(l.position.realizedIncomplete)
        assertTrue(l.disposals.all { it.payment && it.valueEstimated })
        assertEquals(setOf("BTC" to 2 * day), PortfolioLedger.dayCloseNeeds(trades))
    }

    /**
     * Verschenkt = beim Schenkenden keine Veräusserung — Karte und Einstandsmethoden wie eine Auszahlung (kein
     * realisierter Gewinn, Wert verlässt das Portfolio zum Marktwert); nur für UK/CA/AU als Veräusserung. Wie iOS.
     */
    @Test
    fun `gift leaves like a withdrawal and is a disposal only where the template says so`() {
        val trades = listOf(
            tx(1, "BTC", PortfolioTxType.BUY, "1", "100", day),
            tx(2, "BTC", PortfolioTxType.GIFT, "0.4", null, 2 * day),
        )
        for (m in CostMethod.entries) {
            val l = PortfolioCalculator.ledger("BTC", trades, 300.0, m, feePrice = { _, _ -> 250.0 })
            same("0.6", l.position.holdingsExact)
            same("0", l.position.realizedExact)
            assertTrue("$m keine Veräusserung", l.disposals.isEmpty())
            assertEquals(1, l.withdrawals.size)
        }
        val e = PortfolioLedger.events("BTC", trades)
        assertTrue(PortfolioLedger.needsMarketPrice(e[1]))
        assertEquals(-100.0, PortfolioLedger.flow(e[1], dec("1"), marketPrice = 250.0)!!, 1e-9)
        // Tagesschluss trotzdem geholt (für Vorlagen mit Schenkung = Veräusserung)
        assertEquals(setOf("BTC" to 2 * day), PortfolioLedger.dayCloseNeeds(trades))
        val asDisposal = TaxEngines.applyRules(trades, TaxTemplate.AU.eventRules, null)
        assertEquals(PortfolioTxType.PAYMENT, asDisposal[1].type)
        assertTrue(asDisposal[1].fromGift)
        val l = PortfolioCalculator.ledger("BTC", asDisposal, null, feePrice = { _, _ -> 250.0 })
        same("60", l.position.realizedExact)
        assertTrue(l.disposals.all { it.gift && !it.payment && it.valueEstimated })
    }

    /** Tausch ohne Wert (z. B. Kraken): zum Tagesschluss des abgegebenen, sonst des erhaltenen Coins geschätzt. Wie iOS. */
    @Test
    fun `swap without value is estimated at the day close`() {
        val swap = PortfolioTrade(
            id = 2, coin = "ETH", type = PortfolioTxType.SWAP, amount = dec("1"), priceUsdt = null, time = 2 * day,
            toCoin = "BTC", toAmount = dec("0.05"),
        )
        val trades = listOf(tx(1, "ETH", PortfolioTxType.BUY, "1", "1000", day), swap)
        assertEquals(setOf("ETH" to 2 * day, "BTC" to 2 * day), PortfolioLedger.dayCloseNeeds(trades))
        val byGiven = PortfolioLedger.valueAtDayClose(trades) { c, _ -> if (c == "ETH") dec("2000") else dec("50000") }
        same("2000", byGiven[1].swapValueUsdt)
        assertTrue(byGiven[1].valueEstimated)
        val byReceived = PortfolioLedger.valueAtDayClose(trades) { c, _ -> if (c == "BTC") dec("50000") else null }
        same("2500", byReceived[1].swapValueUsdt)
        // Ohne Kurs unverändert (unbekannt), mit erfasstem Wert unverändert
        assertNull(PortfolioLedger.valueAtDayClose(trades) { _, _ -> null }[1].swapValueUsdt)
        val exact = listOf(trades[0], swap.copy(swapValueUsdt = dec("1800")))
        assertTrue(PortfolioLedger.valueAtDayClose(exact) { _, _ -> dec("2000") } === exact)
        // Karte: realisiert 2000 − 1000, der erhaltene Coin mit Einstand 2000
        val eth = PortfolioCalculator.ledger("ETH", trades, null, feePrice = { c, _ -> if (c == "ETH") 2000.0 else 50000.0 })
        same("1000", eth.position.realizedExact)
        assertTrue(eth.disposals.all { it.valueEstimated })
        val btc = PortfolioCalculator.ledger("BTC", trades, null, feePrice = { c, _ -> if (c == "ETH") 2000.0 else 50000.0 })
        same("2000", btc.position.costBasisExact)
    }

    /**
     * Gebühr-Zeile nur mit eigenem Merker (feeRow) — ein Verkauf zum Preis 0 mit Import-Herkunft allein ist keine
     * mehr; von Hand ein Verkauf eines wertlosen Coins.
     */
    @Test
    fun `sale is a fee row only with the explicit flag`() {
        val buy = tx(1, "BTC", PortfolioTxType.BUY, "1", "100", day)
        val manual = tx(2, "BTC", PortfolioTxType.SELL, "0.4", "0", 2 * day)
        assertFalse(PortfolioLedger.isFeeDisposal(manual))
        val l = PortfolioCalculator.ledger("BTC", listOf(buy, manual), null)
        // Erlös 0, Einstand 40: Verlust −40, vollständig
        same("-40", l.position.realizedExact)
        assertFalse(l.position.realizedIncomplete)
        val imported = manual.copy(imported = true)
        assertFalse(PortfolioLedger.isFeeDisposal(imported))
        val feeRow = imported.copy(feeRow = true)
        assertTrue(PortfolioLedger.isFeeDisposal(feeRow))
        // Nur bei einem Verkauf
        assertFalse(PortfolioLedger.isFeeDisposal(feeRow.copy(type = PortfolioTxType.BUY)))
        // Gebühr-Zeile ohne Tageskurs: Erlös unbekannt (nie 0)
        assertTrue(PortfolioCalculator.ledger("BTC", listOf(buy, feeRow), null).position.realizedIncomplete)
    }
}
