package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** FIFO-Posten, Veräusserungen und Adress-Teil (Gegenstück: `Tests/PortfolioFifoTests.swift`). */
class PortfolioFifoTest {

    private val day = 86_400_000L

    private fun dec(s: String) = BigDecimal(s)

    private fun same(expected: String, actual: BigDecimal?) =
        assertEquals("expected $expected, got ${actual?.toPlainString()}", 0, dec(expected).compareTo(actual))

    private fun tx(id: Long, coin: String, type: PortfolioTxType, amount: String, price: String?, time: Long) =
        PortfolioTrade(id, coin, type, dec(amount), price?.let(::dec), time)

    @Test
    fun `FIFO is the default and differs from the average`() {
        val trades = listOf(
            tx(1, "BTC", PortfolioTxType.BUY, "2", "100", day),
            tx(2, "BTC", PortfolioTxType.BUY, "2", "200", 2 * day),
            tx(3, "BTC", PortfolioTxType.SELL, "1", "300", 3 * day),
        )
        assertEquals(CostMethod.FIFO, CostMethod.DEFAULT)
        assertEquals(CostMethod.FIFO, CostMethod.fromName(null))
        assertEquals(CostMethod.AVERAGE, CostMethod.fromName("AVERAGE"))
        val fifo = PortfolioCalculator.position("BTC", trades, 150.0)
        same("200", fifo.realizedExact)
        same("500", fifo.costBasisExact)
        assertEquals(500.0 / 3.0, fifo.avgCost!!, 1e-9)
        same("-50", fifo.unrealizedExact)
        val avg = PortfolioCalculator.position("BTC", trades, 150.0, CostMethod.AVERAGE)
        same("150", avg.realizedExact)
        same("450", avg.costBasisExact)
        assertEquals(CostMethod.AVERAGE, avg.method)
    }

    @Test
    fun `ledger lists open lots and one match per consumed lot`() {
        val trades = listOf(
            tx(1, "ETH", PortfolioTxType.BUY, "1", "100", day),
            tx(2, "ETH", PortfolioTxType.REWARD, "1", "150", 2 * day),
            tx(3, "ETH", PortfolioTxType.BUY, "1", "200", 3 * day),
            tx(4, "ETH", PortfolioTxType.SELL, "1.5", "300", 10 * day),
        )
        val l = PortfolioCalculator.ledger("ETH", trades, null)
        assertEquals(2, l.lots.size)
        assertEquals(2L, l.lots[0].txId)
        same("0.5", l.lots[0].amount)
        same("75", l.lots[0].costUsdt)
        assertEquals(PortfolioTxType.REWARD, l.lots[0].kind)
        assertEquals(2, l.disposals.size)
        val (a, b) = l.disposals
        assertEquals(1L, a.lotTxId)
        same("1", a.amount)
        same("100", a.costUsdt)
        same("300", a.proceedsUsdt)
        same("200", a.gainUsdt)
        assertEquals(9L, a.holdingDays)
        assertEquals(PortfolioTxType.BUY, a.lotKind)
        assertEquals(2L, b.lotTxId)
        same("0.5", b.amount)
        same("75", b.costUsdt)
        same("150", b.proceedsUsdt)
        same("75", b.gainUsdt)
        assertEquals(PortfolioTxType.REWARD, b.lotKind)
        assertTrue(l.disposals.all { it.sellTxId == 4L && it.method == CostMethod.FIFO && !it.feeLeg })
    }

    @Test
    fun `withdrawal consumes the oldest lot without a disposal`() {
        val trades = listOf(
            tx(1, "SOL", PortfolioTxType.BUY, "1", "10", day),
            tx(2, "SOL", PortfolioTxType.BUY, "1", "30", 2 * day),
            tx(3, "SOL", PortfolioTxType.WITHDRAWAL, "1", null, 3 * day),
        )
        val l = PortfolioCalculator.ledger("SOL", trades, 30.0)
        assertTrue(l.disposals.isEmpty())
        same("30", l.position.costBasisExact)
        assertEquals(1.0, l.position.withdrawnAmount, 0.0)
        same("0", l.position.realizedExact)
    }

    @Test
    fun `average method records one disposal per sell without lots`() {
        val trades = listOf(
            tx(1, "BTC", PortfolioTxType.BUY, "1", "100", day),
            tx(2, "BTC", PortfolioTxType.BUY, "1", null, 2 * day),
            tx(3, "BTC", PortfolioTxType.SELL, "1", "300", 3 * day),
        )
        val l = PortfolioCalculator.ledger("BTC", trades, null, CostMethod.AVERAGE)
        assertTrue(l.lots.isEmpty())
        assertEquals(2, l.disposals.size)
        same("0.5", l.disposals[0].amount)
        same("50", l.disposals[0].costUsdt)
        same("150", l.disposals[0].proceedsUsdt)
        same("100", l.disposals[0].gainUsdt)
        same("0.5", l.disposals[1].amount)
        assertNull(l.disposals[1].costUsdt)
        same("150", l.disposals[1].proceedsUsdt)
        assertNull(l.disposals[1].gainUsdt)
        assertTrue(l.disposals.all { it.lotTxId == null && it.holdingDays == null && it.method == CostMethod.AVERAGE })
    }

    @Test
    fun `disposals over all coins are ordered by sell time`() {
        val trades = listOf(
            tx(1, "BNB", PortfolioTxType.BUY, "1", "300", day),
            tx(2, "ETH", PortfolioTxType.BUY, "1", "100", day),
            tx(4, "ETH", PortfolioTxType.SELL, "1", "120", 5 * day),
            PortfolioTrade(3, "BTC", PortfolioTxType.BUY, dec("1"), dec("1000"), 3 * day,
                feeCoin = "BNB", feeCoinAmount = dec("0.1"), feeCoinPrice = dec("400")),
        )
        val all = PortfolioCalculator.disposals(trades)
        assertEquals(listOf("BNB", "ETH"), all.map { it.coin })
        assertTrue(all[0].feeLeg)
        assertEquals(3L, all[0].sellTxId)
        same("10", all[0].gainUsdt)
    }

    @Test
    fun `address balance is its own part and leaves the cost basis intact`() {
        val real = listOf(tx(1, "BTC", PortfolioTxType.BUY, "1", "20000", day))
        val merged = real + tx(AddressBalance.tradeId(AddressChain.BTC), "BTC", PortfolioTxType.DEPOSIT, "0.5", null, 0)
        val p = PortfolioCalculator.position("BTC", merged, 30000.0)
        same("1.5", p.holdingsExact)
        same("0.5", p.addressHoldingsExact)
        same("1", p.ledgerHoldingsExact)
        same("20000", p.costBasisExact)
        same("10000", p.unrealizedExact)
        same("45000", p.valueExact)
        same("15000", p.addressValueExact)
        assertFalse(p.priceMissing)
        val s = PortfolioCalculator.summarize(merged, mapOf("BTC" to 30000.0))
        assertFalse(s.costMissing)
        assertEquals(15000.0, s.addressValue, 1e-9)
        assertEquals(10000.0, s.unrealized!!, 1e-9)
        // Nur Adresse: offen, aber nicht «ohne Kaufpreis»
        val only = PortfolioCalculator.summarize(merged.drop(1), mapOf("BTC" to 30000.0))
        assertEquals(listOf("BTC"), only.open.map { it.coin })
        assertTrue(only.missingCostCoins.isEmpty())
        assertNull(only.unrealized)
        assertTrue(PortfolioCalculator.disposals(merged).isEmpty())
    }

    @Test
    fun `holding days count UTC calendar days`() {
        assertEquals(365L, PortfolioFifo.holdingDays(10 * day + 1, 375 * day))
        assertEquals(366L, PortfolioFifo.holdingDays(10 * day + day - 1, 376 * day))
        assertEquals(0L, PortfolioFifo.holdingDays(day, 2 * day - 1))
    }

    @Test
    fun `sell beyond the holdings keeps the uncovered rest as a disposal without buy price`() {
        val trades = listOf(
            tx(1, "BTC", PortfolioTxType.BUY, "1", "100", day),
            tx(2, "BTC", PortfolioTxType.SELL, "3", "50", 2 * day),
            tx(3, "ETH", PortfolioTxType.SELL, "2", "10", 3 * day), // nichts gehalten
        )
        for (method in CostMethod.entries) {
            val all = PortfolioCalculator.disposals(trades, method)
            // Gesamterlös fehlt nirgends: 150 BTC + 20 ETH
            same("170", all.fold(BigDecimal.ZERO) { a, d -> a + d.proceedsUsdt!! })
            val uncovered = all.filter { it.uncovered }
            assertEquals(2, uncovered.size)
            assertTrue(uncovered.all { it.costUsdt == null && it.gainUsdt == null && it.lotTxId == null })
            same("2", uncovered.first { it.coin == "BTC" }.amount)
            same("100", uncovered.first { it.coin == "BTC" }.proceedsUsdt)
            val p = PortfolioCalculator.position("BTC", trades, 50.0, method)
            assertTrue(p.oversold)
            same("0", p.holdingsExact)
            same("-50", p.realizedExact)
        }
    }

    @Test
    fun `deposit with original purchase date sorts before younger lots`() {
        val deposit = tx(2, "BTC", PortfolioTxType.DEPOSIT, "1", null, 20 * day).copy(acquiredTime = 5 * day)
        val trades = listOf(tx(1, "BTC", PortfolioTxType.BUY, "1", "100", 10 * day), deposit, tx(3, "BTC", PortfolioTxType.SELL, "1", "200", 30 * day))
        val d = PortfolioCalculator.disposals(trades).single()
        assertEquals(2L, d.lotTxId)
        assertEquals(25L, d.holdingDays)
        assertEquals(5 * day, d.acquiredTime)
        assertNull(d.gainUsdt) // Einzahlung ohne Preis: Einstand unbekannt
        // Späteres «Kaufdatum» als die Einzahlung zählt nicht (höchstens die Einzahlung)
        assertEquals(20 * day, deposit.copy(acquiredTime = 40 * day).acquisitionTime)
        // Nur bei Einzahlungen
        assertEquals(10 * day, tx(1, "BTC", PortfolioTxType.BUY, "1", "100", 10 * day).copy(acquiredTime = day).acquisitionTime)
    }

    @Test
    fun `summary groups trades per coin once and reports the withdrawn gain`() {
        val trades = listOf(
            tx(1, "SOL", PortfolioTxType.BUY, "2", "10", day),
            tx(2, "SOL", PortfolioTxType.WITHDRAWAL, "1", null, 2 * day),
            tx(3, "ETH", PortfolioTxType.BUY, "1", "1000", day),
        )
        val prices = mapOf("SOL" to 20.0, "ETH" to 1000.0)
        // Ohne Kurs zur Auszahlung: unbekannt
        val plain = PortfolioCalculator.summarize(trades, prices)
        assertNull(plain.withdrawnGainExact)
        assertTrue(plain.withdrawnGainIncomplete)
        // Mit Kurs zum Zeitpunkt (z. B. Tagesschluss): 1 × 25 − 10 = +15
        val withMarket = PortfolioCalculator.summarize(trades, prices, withdrawalPrice = { coin, time -> if (coin == "SOL" && time == 2 * day) 25.0 else null })
        same("15", withMarket.withdrawnGainExact)
        assertFalse(withMarket.withdrawnGainIncomplete)
        // Gleiche Positionen wie einzeln gerechnet
        assertEquals(PortfolioCalculator.position("SOL", trades, 20.0), withMarket.open.first { it.coin == "SOL" })
    }

    private fun utc(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    /** M-6a: Netzwerkgebühr verbraucht den letzten bezahlten Posten, übrig nur eine Einzahlung ohne Preis. */
    @Test
    fun `network fee eating the last paid lot reports its cost instead of losing it`() {
        val trades = listOf(
            tx(1, "BTC", PortfolioTxType.BUY, "0.001", "100000", day),
            tx(2, "BTC", PortfolioTxType.DEPOSIT, "1", null, 2 * day),
            PortfolioTrade(3, "BTC", PortfolioTxType.TRANSFER, dec("1"), null, 3 * day, fee = dec("0.001"), feeUnit = PortfolioFeeUnit.COIN),
        )
        for (method in CostMethod.entries) {
            val l = PortfolioCalculator.ledger("BTC", trades, 50000.0, method)
            same("1", l.position.holdingsExact)
            assertNull(method.name, l.position.costBasis) // nur Bestand ohne Kaufpreis übrig
            same("0", l.position.realizedExact) // kein realisierter Verlust
            if (method == CostMethod.AVERAGE) {
                // Ø: Gebühr anteilig aus beiden Teilen — der bezahlte Teil bleibt (Einstand im Rest), nichts verloren
                same("0", l.position.lostFeeCostUsdt)
            } else {
                same("100", l.position.lostFeeCostUsdt)
                val lost = l.lostFeeCosts.single()
                assertEquals(3L, lost.txId)
                assertEquals(3 * day, lost.time)
                same("100", lost.costUsdt)
            }
        }
        // Ø: Gebühr nimmt den GANZEN Bestand mit Kaufpreis (nur bezahlter Posten da) → vermerkt
        val avgAll = PortfolioCalculator.position(
            "ETH",
            listOf(
                tx(1, "ETH", PortfolioTxType.BUY, "0.01", "2000", day),
                PortfolioTrade(2, "ETH", PortfolioTxType.TRANSFER, dec("0.01"), null, 2 * day, fee = dec("0.01"), feeUnit = PortfolioFeeUnit.COIN),
            ),
            null, CostMethod.AVERAGE,
        )
        same("20", avgAll.lostFeeCostUsdt)
        // Steuer-Export: Vermerk je Gebühr im Jahr
        val zone = java.time.ZoneOffset.UTC
        val lost = TaxExport.lostFeeCostsIn(trades, CostMethod.FIFO, 1970, zone)
        assertEquals(1, lost.size)
        assertTrue(TaxExport.lostFeeCostsIn(trades, CostMethod.FIFO, 1971, zone).isEmpty())
        val x = "x"
        val texts = TaxCsvTexts(
            coin = x, acquired = x, sold = x, holdingDays = x, longTerm = x, amount = x, costUsdt = x, proceedsUsdt = x,
            gainUsdt = x, costTarget = x, proceedsTarget = x, gainTarget = x, date = x, valueUsdt = x, valueTarget = x,
            note = x, total = "Total", yes = x, no = x, costMissing = x, proceedsMissing = x, feeLeg = x, feeValueMissing = x,
            avgCostRates = x, valueMissing = x, noFx = x, incomplete = x,
            lostFeeCost = "Netzwerkgebühr-Einstand ohne bezahlten Posten",
        )
        val csv = TaxExport.gainsCsv(emptyList(), texts, emptyList(), lostFeeCosts = lost, zone = zone)
        assertTrue(csv, csv.contains("BTC;;1970-01-04;;;;100.00;;;;;;Netzwerkgebühr-Einstand ohne bezahlten Posten"))
        // Ohne Text keine Zeile (ältere Aufrufer)
        assertFalse(TaxExport.gainsCsv(emptyList(), texts.copy(lostFeeCost = ""), emptyList(), lostFeeCosts = lost).contains("BTC"))
    }

    /**
     * Build 58: Umbuchungsgebühr auf Bestand ohne Kaufpreis im Pool der Steuer-Vorlagen ([TaxPool.addFee], UK/CA ohne
     * Vorlage-Regeln) — derselbe Anteil wie im Portfolio vermerkt, nicht verworfen. Mit den UK-Regeln zählt die Gebühr USDT
     * gar nicht zum Einstand (nur Information) — dann bleibt nichts offen.
     */
    @Test
    fun `transfer fee share on coins without purchase price is noted in the tax pools`() {
        val zone = java.time.ZoneOffset.UTC
        val trades = listOf(
            PortfolioTrade(1, "ETH", PortfolioTxType.DEPOSIT, dec("1"), null, day),
            tx(2, "ETH", PortfolioTxType.BUY, "1", "2000", 2 * day),
            PortfolioTrade(3, "ETH", PortfolioTxType.TRANSFER, dec("2"), null, 3 * day, fee = dec("10"), feeUnit = PortfolioFeeUnit.QUOTE),
        )
        for (m in listOf(TaxMethod.UK_POOL, TaxMethod.CA_ACB)) {
            val fees = TaxEngines.unassignedFees(trades, m, zone, TaxEventRules.NONE)
            assertEquals("$m", 1, fees.size)
            assertEquals(3L, fees.single().txId)
            same("5", fees.single().usdt)
            same("5", TaxExport.unassignedFeesIn(trades, m, TaxTemplate.NEUTRAL.period(1970), zone, TaxEventRules.NONE).single().usdt)
        }
        assertTrue(TaxEngines.unassignedFees(trades, TaxMethod.UK_POOL, zone).isEmpty())
        // Portfolio-Methoden über das Buch (gleicher Anteil), andere Jahre leer
        same("5", TaxExport.unassignedFeesIn(trades, CostMethod.FIFO, 1970, zone).single().usdt)
        assertTrue(TaxExport.unassignedFeesIn(trades, CostMethod.AVERAGE, 1971, zone).isEmpty())
        // Pool direkt: ohne Bestand und ohne Teil mit Kaufpreis die ganze Gebühr (wie FIFO je Wallet vermerkt)
        val pool = TaxPool()
        same("4", pool.addFee(day, dec("4"), dec("1"), BigDecimal.ZERO))
        pool.add(dec("1"), null, day)
        same("4", pool.addFee(day, dec("4"), dec("1"), BigDecimal.ZERO))
        pool.add(dec("1"), dec("100"), day)
        same("2", pool.addFee(day, dec("4"), dec("2"), BigDecimal.ZERO))
        same("102", pool.cost)
        // Menge 2 + Netzwerkgebühr 0,5 bei Bestand 2: gedeckt 1,5 → 4 × 0,5 / 2 = 1 vermerkt, vom Rest 3 die Hälfte
        // zum Einstand (1,5), die andere vermerkt: 2,5
        same("2.5", pool.addFee(day, dec("4"), dec("2"), dec("0.5")))
        same("103.5", pool.cost)
    }

    /** M-6b: Gebühr im Coin über zwei Posten mit verschiedener Haltedauer — kurz/lang getrennt richtig. */
    @Test
    fun `coin fee of a sell is consumed after the sold amount and keeps the short and long split`() {
        val trades = listOf(
            tx(1, "BTC", PortfolioTxType.BUY, "1", "100", utc(2020, 1, 1)),
            tx(2, "BTC", PortfolioTxType.BUY, "1", "200", utc(2021, 6, 1)),
            PortfolioTrade(3, "BTC", PortfolioTxType.SELL, dec("1"), dec("300"), utc(2021, 12, 1), fee = dec("0.5"), feeUnit = PortfolioFeeUnit.COIN),
        )
        val d = PortfolioCalculator.disposals(trades)
        assertEquals(2, d.size)
        // Verkaufte Menge aus dem ältesten Posten: voller Erlös, langfristig
        assertEquals(1L, d[0].lotTxId)
        same("1", d[0].amount); same("300", d[0].proceedsUsdt); same("200", d[0].gainUsdt)
        // Gebühr-Coins aus dem nächsten Posten: Erlös 0, Einstand = Kosten der Veräusserung, kurzfristig
        assertEquals(2L, d[1].lotTxId)
        same("0.5", d[1].amount); same("0", d[1].proceedsUsdt); same("-100", d[1].gainUsdt)
        val zone = java.time.ZoneOffset.UTC
        fun long(x: PortfolioDisposal) = TaxExport.isLongTerm(TaxExport.localDate(x.acquiredTime!!, zone), TaxExport.localDate(x.sellTime, zone))
        same("200", d.filter { long(it) }.fold(BigDecimal.ZERO) { a, x -> a + x.gainUsdt!! })
        same("-100", d.filter { !long(it) }.fold(BigDecimal.ZERO) { a, x -> a + x.gainUsdt!! })
        same("100", PortfolioCalculator.position("BTC", trades, null).realizedExact) // Summe unverändert
        // Mehr abgegeben als erfasst: zuerst die verkaufte Menge, der ungedeckte Rest sind Gebühr-Coins (Erlös 0)
        val short = listOf(
            tx(1, "BTC", PortfolioTxType.BUY, "1.2", "100", utc(2020, 1, 1)),
            PortfolioTrade(2, "BTC", PortfolioTxType.SELL, dec("1"), dec("300"), utc(2021, 12, 1), fee = dec("0.5"), feeUnit = PortfolioFeeUnit.COIN),
        )
        val s = PortfolioCalculator.disposals(short)
        same("300", s.fold(BigDecimal.ZERO) { a, x -> a + x.proceedsUsdt!! })
        val uncovered = s.single { it.uncovered }
        same("0.3", uncovered.amount); same("0", uncovered.proceedsUsdt)
        same("1", PortfolioCalculator.position("BTC", short, null).soldAmount.toBigDecimal())
    }

    /** M-6c: rückdatierter oder geänderter Kauf rechnet spätere Verkäufe neu (nichts zwischengespeichert). */
    @Test
    fun `backdated or edited buy recomputes later sells from the full ledger`() {
        val buy = tx(1, "BTC", PortfolioTxType.BUY, "1", "100", 2 * day)
        val sell = tx(2, "BTC", PortfolioTxType.SELL, "1", "300", 5 * day)
        same("200", PortfolioCalculator.position("BTC", listOf(buy, sell), null).realizedExact)
        // Kaufpreis nachträglich geändert
        val edited = buy.copy(priceUsdt = dec("150"))
        same("150", PortfolioCalculator.position("BTC", listOf(edited, sell), null).realizedExact)
        same("150", PortfolioCalculator.disposals(listOf(sell, edited)).single().gainUsdt)
        // Früherer Kauf nachgetragen: FIFO verkauft jetzt ihn, der spätere Kauf bleibt offen
        val backdated = tx(3, "BTC", PortfolioTxType.BUY, "1", "50", day)
        val l = PortfolioCalculator.ledger("BTC", listOf(sell, edited, backdated), null)
        same("250", l.position.realizedExact)
        assertEquals(3L, l.disposals.single().lotTxId)
        assertEquals(1L, l.lots.single().txId)
        same("150", l.position.costBasisExact)
    }

    /**
     * Gebühr als Aufwand — dieselbe Regel auf der Karte (realisiert), im Zeitraum (Wert Ende − Wert Anfang − Zuflüsse)
     * und im Steuer-Export (Gewinne + Vermerk): Tausch mit Gebühr im erhaltenen Coin ≥ erhaltener Menge, Kauf mit Gebühr
     * im Coin ≥ Menge, Auszahlung mit USDT-Gebühr. Gegenstück in `PortfolioFifoTests.swift`.
     */
    @Test
    fun `fee expenses count the same on the card, in the period and in the tax export`() {
        val trades = listOf(
            tx(1, "ETH", PortfolioTxType.BUY, "1", "1000", day),
            PortfolioTrade(
                2, "ETH", PortfolioTxType.SWAP, dec("0.5"), null, 2 * day,
                toCoin = "BTC", toAmount = dec("0.01"), swapValueUsdt = dec("600"), feeCoin = "BTC", feeCoinAmount = dec("0.01"),
            ),
            PortfolioTrade(3, "SOL", PortfolioTxType.BUY, dec("1"), dec("100"), 3 * day, fee = dec("1"), feeUnit = PortfolioFeeUnit.COIN),
            tx(4, "ADA", PortfolioTxType.BUY, "100", "1", 4 * day),
            PortfolioTrade(5, "ADA", PortfolioTxType.WITHDRAWAL, dec("50"), null, 5 * day, fee = dec("2"), feeUnit = PortfolioFeeUnit.QUOTE),
        )
        val prices = mapOf("ETH" to 1000.0, "BTC" to 60000.0, "SOL" to 100.0, "ADA" to 1.0)
        for (method in CostMethod.entries) {
            // Karte: Σ realisiert = +100 (ETH) − 600 (BTC-Zugang verbraucht) − 100 (SOL) − 2 (Gebühr Auszahlung)
            val card = PortfolioLedger.coins(trades).fold(BigDecimal.ZERO) { a, c ->
                a + PortfolioCalculator.position(c, trades, prices[c], method).realizedExact
            }
            same("-602", card)
            // Zeitraum von vor dem ersten Kauf bis heute (Kurse = Einstand, keine Kursbewegung): Gewinn = −602
            var flows = 0.0
            var endValue = 0.0
            for ((coin, events) in PortfolioLedger.eventsByCoin(trades)) {
                val book = PortfolioHoldingsBook(method)
                for (e in events) {
                    flows += PortfolioLedger.flow(e, book.capFor(e), marketPrice = prices[coin])!!
                    book.apply(e)
                }
                endValue += book.total.toDouble() * prices.getValue(coin)
            }
            assertEquals(method.name, -602.0, endValue - flows, 1e-9)
            // Steuer-Export: Gewinne summieren gleich, je Aufwand eine Zeile mit Vermerk
            val rows = TaxExport.gainRows(trades, method, 1970, java.time.ZoneOffset.UTC, "USD", emptyMap())
            same("-602", rows.fold(BigDecimal.ZERO) { a, r -> a + r.gainUsdt!! })
            assertEquals(
                method.name,
                listOf("BTC" to PortfolioFeeExpense.CONSUMED, "SOL" to PortfolioFeeExpense.CONSUMED, "ADA" to PortfolioFeeExpense.WITHDRAWAL),
                rows.filter { it.feeExpense != null }.map { it.coin to it.feeExpense },
            )
            val x = "x"
            val texts = TaxCsvTexts(
                coin = x, acquired = x, sold = x, holdingDays = x, longTerm = x, amount = x, costUsdt = x, proceedsUsdt = x,
                gainUsdt = x, costTarget = x, proceedsTarget = x, gainTarget = x, date = x, valueUsdt = x, valueTarget = x,
                note = x, total = "Total", yes = x, no = x, costMissing = x, proceedsMissing = x, feeLeg = x, feeValueMissing = x,
                avgCostRates = "AVG", valueMissing = x, noFx = x, incomplete = x,
                feeConsumed = "Gebühr verbraucht den ganzen Zugang", withdrawalFee = "Gebühr Auszahlung",
            )
            val csv = TaxExport.gainsCsv(rows, texts, emptyList())
            assertTrue(csv, csv.contains("SOL;1970-01-04;1970-01-04;0;"))
            assertTrue(csv, csv.lines().single { it.startsWith("SOL;") }.endsWith(";Gebühr verbraucht den ganzen Zugang"))
            assertTrue(csv, csv.lines().single { it.startsWith("ADA;") }.endsWith(";Gebühr Auszahlung"))
        }
    }

    /**
     * FIFO je Wallet: Netzwerkgebühr frisst den einzigen Posten mit Kaufpreis der Quelle, verschoben werden nur Coins
     * ohne Kaufpreis — der Einstand geht auf den nächsten Posten mit Kaufpreis (im Ziel), nicht in «verloren»; gibt es
     * keinen, wartet er bei den verschobenen Coins im Ziel. Gegenstück in `PortfolioFifoTests.swift`.
     */
    @Test
    fun `wallet transfer fee cost goes to the next priced lot before it counts as lost`() {
        fun inWallet(t: PortfolioTrade, w: String) = t.copy(exchange = w)
        val move = PortfolioTrade(
            4, "BTC", PortfolioTxType.TRANSFER, dec("0.5"), null, 4 * day,
            fee = dec("0.1"), feeUnit = PortfolioFeeUnit.COIN, transferFrom = "Binance", transferTo = "Ledger",
        )
        val base = listOf(
            inWallet(tx(2, "BTC", PortfolioTxType.BUY, "0.1", "2000", 2 * day), "Binance"),
            inWallet(tx(3, "BTC", PortfolioTxType.DEPOSIT, "1", null, 3 * day), "Binance"),
            move,
        )
        // Posten mit Kaufpreis im Ziel: übernimmt den Einstand (200)
        val withTarget = PortfolioCalculator.ledger("BTC", listOf(inWallet(tx(1, "BTC", PortfolioTxType.BUY, "1", "1000", day), "Ledger")) + base, null, CostMethod.FIFO_WALLET)
        same("0", withTarget.position.lostFeeCostUsdt)
        same("1200", withTarget.lots.single { it.txId == 1L }.costUsdt)
        // Keiner da: wartet im Ziel — ein späterer Kauf in der Quelle übernimmt ihn nicht, einer im Ziel schon
        val laterSrc = PortfolioCalculator.ledger("BTC", base + inWallet(tx(5, "BTC", PortfolioTxType.BUY, "1", "3000", 5 * day), "Binance"), null, CostMethod.FIFO_WALLET)
        same("200", laterSrc.position.lostFeeCostUsdt)
        same("3000", laterSrc.lots.single { it.txId == 5L }.costUsdt)
        val laterDst = PortfolioCalculator.ledger("BTC", base + inWallet(tx(5, "BTC", PortfolioTxType.BUY, "1", "3000", 5 * day), "Ledger"), null, CostMethod.FIFO_WALLET)
        same("0", laterDst.position.lostFeeCostUsdt)
        same("3200", laterDst.lots.single { it.txId == 5L }.costUsdt)
        // Universelles FIFO: der verbleibende Posten mit Kaufpreis übernimmt ihn (unverändert)
        val universal = PortfolioCalculator.ledger("BTC", listOf(inWallet(tx(1, "BTC", PortfolioTxType.BUY, "1", "1000", day), "Ledger")) + base, null, CostMethod.FIFO)
        same("0", universal.position.lostFeeCostUsdt)
    }
}
