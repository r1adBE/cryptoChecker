package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioCoinDaily
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioImportBatch
import com.cryptochecker.app.domain.portfolio.PortfolioLedger
import com.cryptochecker.app.domain.portfolio.PortfolioPeriodChange
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.UsdtUsdRates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.max

/**
 * Gemeinsame Portfolio-Fälle (`testdata/parity/portfolio.json`): Buchhaltung, Gewinn im Zeitraum und
 * Skalieren auf die eingetragene Börse. Gegenstück: `Tests/PortfolioParityTests.swift`.
 */
class PortfolioParityTest {

    private val data = ParityJson.load("portfolio.json")

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
            feeCoin = o["feeCoin"] as String?,
            feeCoinAmount = (o["feeCoinAmount"] as String?)?.let(::BigDecimal),
            feeCoinPrice = (o["feeCoinPrice"] as String?)?.let(::BigDecimal),
            // Einzahlung: ursprüngliches Kaufdatum (optional)
            acquiredTime = o["acquiredTime"].num()?.toLong(),
            // Optional: Börse/Wallet (FIFO je Wallet), Umbuchung, Tausch
            exchange = o["exchange"] as String?,
            transferFrom = o["transferFrom"] as String?,
            transferTo = o["transferTo"] as String?,
            toCoin = o["toCoin"] as String?,
            toAmount = (o["toAmount"] as String?)?.let(::BigDecimal),
            swapValueUsdt = (o["swapValue"] as String?)?.let(::BigDecimal),
            imported = o["importKey"] != null,
            // Gebühr-Zeile: eigenes Feld; fehlt = nein
            feeRow = o["feeRow"] == true,
        )
    }

    private fun near(label: String, expected: Double?, actual: Double?) {
        if (expected == null) {
            assertNull("$label: expected null, got $actual", actual)
            return
        }
        assertNotNull("$label: expected $expected, got null", actual)
        assertTrue("$label: expected $expected, got $actual", abs(expected - actual!!) <= max(abs(expected) * 1e-9, 1e-9))
    }

    /** Exakter Vergleich, nur wenn der Fall den Text nennt. */
    private fun exact(label: String, expected: Any?, actual: BigDecimal?) {
        val text = expected as? String ?: return
        assertNotNull("$label: expected $text, got null", actual)
        assertEquals("$label: expected $text, got ${actual!!.toPlainString()}", 0, BigDecimal(text).compareTo(actual))
    }

    private fun prices(raw: Any?): Map<String, Double> = raw.obj().mapValues { it.value.num()!! }

    private fun series(raw: Any?): Map<String, Map<Long, Double>> =
        raw.obj().mapValues { (_, v) -> v.obj().entries.associate { it.key.toLong() to it.value.num()!! } }

    @Test
    fun positions() {
        val cases = data["positions"].list()
        assertTrue(cases.size >= 5)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            val e = c["expected"].obj()
            val method = CostMethod.valueOf(c["method"] as String)
            val ledger = PortfolioCalculator.ledger(c["coin"] as String, c["trades"].list().map(::trade), c["currentPrice"].num(), method)
            val p = ledger.position
            assertEquals("$name method", method, p.method)
            assertEquals("$name holdings", 0, BigDecimal(e["holdings"] as String).compareTo(p.holdingsExact))
            near("$name costBasis", e["costBasis"].num(), p.costBasis)
            near("$name avgCost", e["avgCost"].num(), p.avgCost)
            near("$name realized", e["realized"].num(), p.realizedExact.toDouble())
            assertEquals("$name oversold", e["oversold"], p.oversold)
            assertEquals("$name priceMissing", e["priceMissing"], p.priceMissing)
            // Exakte Texte (optional): Teilungen auf 34 Stellen wie iOS, unrealisiert exakt
            exact("$name costBasisExact", e["costBasisExact"], p.costBasisExact)
            exact("$name realizedExact", e["realizedExact"], p.realizedExact)
            exact("$name unrealizedExact", e["unrealizedExact"], p.unrealizedExact)
            exact("$name pricedHoldings", e["pricedHoldings"], p.pricedHoldingsExact)
            // Netzwerkgebühr-Einstand ohne bezahlten Posten (optional)
            exact("$name lostFeeCost", e["lostFeeCost"], p.lostFeeCostUsdt)
            // Optional: Umbuchungsgebühr auf Bestand ohne Kaufpreis — vermerkt statt still verworfen
            exact("$name unassignedFee", e["unassignedFee"], p.unassignedFeeUsdt)
            e["unassignedFees"]?.let { raw ->
                val expected = raw.list().map { it.obj() }
                assertEquals("$name unassignedFees", expected.size, ledger.unassignedFees.size)
                expected.zip(ledger.unassignedFees).forEachIndexed { i, (x, f) ->
                    assertEquals("$name unassignedFee $i txId", x["txId"].num()!!.toLong(), f.txId)
                    exact("$name unassignedFee $i usdt", x["usdt"], f.usdt)
                }
            }
            // Optional: «Seit Kauf» des Teils mit Kaufpreis, auch wenn ein Teil ohne Kaufpreis ist
            exact("$name pricedCost", e["pricedCost"], p.pricedCostExact)
            exact("$name unpricedHoldings", e["unpricedHoldings"], p.unpricedHoldingsExact)
            (e["unrealizedPartial"] as Boolean?)?.let { assertEquals("$name unrealizedPartial", it, p.unrealizedPartial) }
            e["unrealizedPercent"].num()?.let { near("$name unrealizedPercent", it, p.unrealizedPercent) }
            e["summary"]?.let { rawSummary ->
                val x = rawSummary.obj()
                val s = PortfolioCalculator.summarize(c["trades"].list().map(::trade), prices(c["summaryPrices"]), method)
                near("$name summary unrealized", (x["unrealized"] as String?)?.toDouble(), s.unrealized)
                near("$name summary unrealizedPercent", x["unrealizedPercent"].num(), s.unrealizedPercent)
                if (x.containsKey("invested")) optionalExact("$name summary invested", x["invested"], s.investedExact)
                assertEquals("$name summary missingCostCoins", x["missingCostCoins"].list(), s.missingCostCoins)
                assertEquals("$name summary costMissing", x["costMissing"], s.costMissing)
                exact("$name summary unassignedFee", x["unassignedFee"], s.unassignedFeeUsdt)
                val parts = x["partialCost"].list().map { it.obj() }
                assertEquals("$name summary partialCost", parts.size, s.partialCostParts.size)
                parts.zip(s.partialCostParts).forEach { (y, part) ->
                    assertEquals("$name summary partial coin", y["coin"], part.coin)
                    exact("$name summary partial amount", y["amount"], part.amount)
                }
            }
            // Optional: Auszahlung, Gebühr im dritten Coin
            e["withdrawn"].num()?.let { near("$name withdrawn", it, p.withdrawnAmount) }
            (e["feeValueMissing"] as Boolean?)?.let { assertEquals("$name feeValueMissing", it, p.feeValueMissing) }
            (e["realizedIncomplete"] as Boolean?)?.let { assertEquals("$name realizedIncomplete", it, p.realizedIncomplete) }
            // Optional: Einstandsmethode — offene Posten und Veräusserungen
            e["realizedUnknownAmount"].num()?.let { near("$name realizedUnknownAmount", it, p.realizedUnknownAmount) }
            e["lots"]?.let { raw ->
                val lots = raw.list().map { it.obj() }
                assertEquals("$name lots", lots.size, ledger.lots.size)
                lots.zip(ledger.lots).forEachIndexed { i, (x, lot) ->
                    assertEquals("$name lot $i txId", x["txId"].num()!!.toLong(), lot.txId)
                    exact("$name lot $i amount", x["amount"], lot.amount)
                    optionalExact("$name lot $i cost", x["costUsdt"], lot.costUsdt)
                    if (x.containsKey("wallet")) assertEquals("$name lot $i wallet", x["wallet"], lot.wallet)
                    (x["kind"] as String?)?.let { assertEquals("$name lot $i kind", it, lot.kind.name) }
                }
            }
            // Optional: offene Posten für die Anzeige, Fehlbestand je Wallet
            e["openLots"]?.let { raw ->
                val expected = raw.list().map { it.obj() }
                assertEquals("$name openLots", expected.size, ledger.openLots.size)
                expected.zip(ledger.openLots).forEachIndexed { i, (x, lot) ->
                    assertEquals("$name openLot $i txId", x["txId"].num()?.toLong(), lot.txId)
                    exact("$name openLot $i amount", x["amount"], lot.amount)
                    optionalExact("$name openLot $i cost", x["costUsdt"], lot.costUsdt)
                    assertEquals("$name openLot $i wallet", x["wallet"], lot.wallet)
                    assertEquals("$name openLot $i aggregated", x["aggregated"], lot.aggregated)
                }
            }
            e["shortfalls"]?.let { raw ->
                val expected = raw.list().map { it.obj() }
                assertEquals("$name shortfalls", expected.size, ledger.walletShortfalls.size)
                expected.zip(ledger.walletShortfalls).forEachIndexed { i, (x, sf) ->
                    assertEquals("$name shortfall $i wallet", x["wallet"], sf.wallet)
                    assertEquals("$name shortfall $i txId", x["txId"].num()!!.toLong(), sf.txId)
                    exact("$name shortfall $i missing", x["missing"], sf.missing)
                }
                // Gleiche Liste über alle Coins (nur dieser Coin in den Fällen mit Fehlbestand)
                val all = PortfolioCalculator.walletShortfalls(c["trades"].list().map(::trade), method)
                assertEquals("$name walletShortfalls", ledger.walletShortfalls, all.filter { it.coin == ledger.position.coin })
            }
            (e["withdrawnGain"] as String?)?.let { gain ->
                val w = PortfolioCalculator.withdrawnGain(ledger.withdrawals)
                exact("$name withdrawnGain", gain, w.amount)
                assertEquals("$name withdrawnGainIncomplete", e["withdrawnGainIncomplete"] ?: false, w.incomplete)
            }
            e["disposals"]?.let { raw ->
                val expected = raw.list().map { it.obj() }
                assertEquals("$name disposals", expected.size, ledger.disposals.size)
                expected.zip(ledger.disposals).forEachIndexed { i, (x, m) ->
                    val label = "$name disposal $i"
                    assertEquals("$label coin", x["coin"], m.coin)
                    assertEquals("$label sellTxId", x["sellTxId"].num()!!.toLong(), m.sellTxId)
                    assertEquals("$label lotTxId", x["lotTxId"].num()?.toLong(), m.lotTxId)
                    exact("$label amount", x["amount"], m.amount)
                    optionalExact("$label cost", x["costUsdt"], m.costUsdt)
                    optionalExact("$label proceeds", x["proceedsUsdt"], m.proceedsUsdt)
                    optionalExact("$label gain", x["gainUsdt"], m.gainUsdt)
                    assertEquals("$label holdingDays", x["holdingDays"].num()?.toLong(), m.holdingDays)
                    assertEquals("$label lotKind", x["lotKind"], m.lotKind?.name)
                    assertEquals("$label feeLeg", x["feeLeg"], m.feeLeg)
                    assertEquals("$label uncovered", x["uncovered"] ?: false, m.uncovered)
                    assertEquals("$label swap", x["swap"] ?: false, m.swap)
                    assertEquals("$label feeExpense", x["feeExpense"], m.feeExpense?.name)
                    if (x.containsKey("wallet")) assertEquals("$label wallet", x["wallet"], m.wallet)
                    x["costParts"]?.let { parts ->
                        val list = parts.list()
                        assertEquals("$label costParts", list.size, m.costParts.size)
                        list.zip(m.costParts).forEachIndexed { j, (y, part) ->
                            assertEquals("$label part $j time", y.obj()["time"].num()!!.toLong(), part.time)
                            exact("$label part $j usdt", y.obj()["usdt"], part.usdt)
                        }
                    }
                    assertEquals("$label method", method, m.method)
                }
            }
        }
    }

    /** Tausch: Bestand und Zuflüsse je Schritt ([PortfolioLedger.holdingsAfter], [PortfolioLedger.flow]) beider Seiten. */
    @Test
    fun ledgerFlows() {
        val cases = data["ledgerFlows"].list()
        assertTrue(cases.isNotEmpty())
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            val trades = c["trades"].list().map(::trade)
            val market = c["market"].obj().mapValues { (_, v) -> prices(v) }
            assertEquals("$name coins", c["coins"].list(), PortfolioLedger.coins(trades))
            c["expected"].obj().forEach { (coin, rawSteps) ->
                val steps = rawSteps.list().map { it.obj() }
                val events = PortfolioLedger.events(coin, trades)
                assertEquals("$name $coin events", steps.size, events.size)
                var holdings = BigDecimal.ZERO
                steps.zip(events).forEachIndexed { i, (x, e) ->
                    val label = "$name $coin step $i"
                    assertEquals("$label txId", x["txId"].num()!!.toLong(), e.trade.id)
                    assertEquals("$label swapIn", x["swapIn"] ?: false, e.swapIn)
                    assertEquals("$label needsMarket", x["needsMarket"] ?: false, PortfolioLedger.needsMarketPrice(e))
                    val price = market[e.trade.id.toString()]?.get(coin)
                    near("$label flow", x["flow"].num(), PortfolioLedger.flow(e, holdings, price))
                    holdings = PortfolioLedger.holdingsAfter(holdings, e)
                    exact("$label holdings", x["holdings"], holdings)
                }
            }
        }
    }

    /** CSV-Importlauf: Kennung (FNV-1a) und Liste der Läufe ([PortfolioImportBatch]). */
    @Test
    fun importBatch() {
        val d = data["importBatch"].obj()
        d["newId"].list().map { it.obj() }.forEach { x ->
            val id = PortfolioImportBatch.newId(x["format"] as String, x["time"].num()!!.toLong(), x["keys"].list().map { it as String })
            assertEquals("newId", x["expected"], id)
        }
        val s = d["summarize"].obj()
        val got = PortfolioImportBatch.summarize(s["input"].list().map { it as String? })
        val expected = s["expected"].list().map { it.obj() }
        assertEquals(expected.size, got.size)
        expected.zip(got).forEach { (x, b) ->
            assertEquals(x["id"], b.id)
            assertEquals(x["format"], b.format)
            assertEquals(x["time"].num()?.toLong(), b.time)
            assertEquals(x["count"].num()!!.toInt(), b.count)
        }
    }

    /** Exakt wie [exact], aber null erwartet = null. */
    private fun optionalExact(label: String, expected: Any?, actual: BigDecimal?) {
        if (expected == null) assertNull("$label: expected null, got $actual", actual) else exact(label, expected, actual)
    }

    @Test
    fun exchangeByLedger() {
        val cases = data["exchangeByLedger"].list()
        assertTrue(cases.size >= 5)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            val entries = c["entries"].list().map { r ->
                val o = r.obj()
                PortfolioExchangeRoute.RouteEntry(
                    coin = o["coin"] as String,
                    exchange = o["exchange"] as String?,
                    type = PortfolioTxType.valueOf(o["type"] as String),
                    transferTo = o["transferTo"] as String?,
                    time = o["time"].num()!!.toLong(),
                    id = o["id"].num()!!.toLong(),
                )
            }
            val (single, mixed) = PortfolioExchangeRoute.exchangeByLedger(entries)
            assertEquals("$name single", c["single"].obj().mapValues { it.value as String }, single)
            assertEquals("$name mixed", names(c["mixed"]), mixed)
        }
    }

    @Test
    fun periodGain() {
        val baseDay = data["baseDay"].num()!!.toLong()
        for (raw in data["periodGain"].list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val gain = PortfolioPeriodChange.gain(c["trades"].list().map(::trade), prices(c["current"]), series(c["closes"]), baseDay)
            val e = c["expected"]
            if (e == null) {
                assertNull("$name: expected no number", gain)
                continue
            }
            assertNotNull("$name: expected a number", gain)
            near("$name amount", e.obj()["amount"].num(), gain!!.amountUsd)
            near("$name percent", e.obj()["percent"].num(), gain.percent)
            assertEquals("$name skipped", e.obj()["skipped"].list(), gain.skipped)
        }
    }

    /** EINE Tagesgrenze (UTC) — «7 T» und Wertverlauf zählen einen Kauf um Mitternacht gleich. */
    @Test
    fun dayBoundary() {
        val cases = data["dayBoundary"].list()
        assertTrue(cases.size >= 2)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            val trades = c["trades"].list().map(::trade)
            val today = c["today"].num()!!.toLong()
            val e = c["expected"].obj()
            val gain = PortfolioPeriodChange.gain(trades, prices(c["current"]), series(c["closes"]), c["baseDay"].num()!!.toLong())
            near("$name gain", e["gain"].num(), gain?.amountUsd)
            near("$name percent", e["percent"].num(), gain?.percent)
            val history = PortfolioHistory.build(trades, series(c["closes"]), prices(c["current"]), PortfolioHistoryRange.WEEK, today)
            near("$name first point", e["firstPoint"].num(), history.points.firstOrNull()?.value)
            near("$name history pnl", e["historyPnl"].num(), history.pnlChange)
            // Dieselbe Zahl in Kopf und Kurve
            near("$name same number", gain?.amountUsd, history.pnlChange)
        }
    }

    @Test
    fun todayAdjusted() {
        val cases = data["todayAdjusted"].list()
        assertTrue(cases.size >= 3)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            val current = prices(c["current"])
            val coinChanges = prices(c["coinChanges"])
            val change = PortfolioPeriodChange.todayFromCoinChanges(prices(c["holdings"]), current, coinChanges)
            val today = PortfolioPeriodChange.todayAdjusted(
                change, c["trades"].list().map(::trade), current, coinChanges, c["windowStart"].num()!!.toLong(),
            )
            // Zeilen: Gewinn/Verlust je Coin, Summe = «Heute»
            val byCoin = PortfolioPeriodChange.todayByCoin(
                prices(c["holdings"]), c["trades"].list().map(::trade), current, coinChanges, c["windowStart"].num()!!.toLong(),
            )
            c["byCoin"]?.let { expectedRows ->
                val rows = expectedRows.obj()
                assertEquals("$name byCoin coins", rows.keys, byCoin.keys)
                for ((coin, row) in rows) {
                    near("$name $coin amount", row.obj()["amount"].num(), byCoin[coin]?.amountUsd)
                    near("$name $coin percent", row.obj()["percent"].num(), byCoin[coin]?.percent)
                }
                if (today != null) near("$name rows sum", today.amountUsd, byCoin.values.sumOf { it.amountUsd })
            }
            val e = c["expected"]
            if (e == null) {
                assertNull("$name: expected no number", today)
                continue
            }
            assertNotNull("$name: expected a number", today)
            near("$name amount", e.obj()["amount"].num(), today!!.amountUsd)
            near("$name percent", e.obj()["percent"].num(), today.percent)
            assertEquals("$name skipped", e.obj()["skipped"].list(), today.skipped)
        }
    }

    /**
     * Kopf «7 T/1 J», Gewinn/Verlust-Kurven und Karte zeigen dieselbe Zahl: junges Portfolio (Kurve ab 0 samt erstem
     * Tag), Tausch ohne Wert (EIN Wert zum Tagesschluss für beide Seiten), Umbuchung mit USDT-Gebühr ohne Bestand (0).
     */
    @Test
    fun rangeAgreement() {
        val cases = data["rangeAgreement"].list()
        assertTrue(cases.size >= 3)
        val today = data["today"].num()!!.toLong()
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            val trades = c["trades"].list().map(::trade)
            val current = prices(c["current"])
            val closes = series(c["closes"])
            val e = c["expected"].obj()
            for (method in CostMethod.entries) {
                val label = "$name [$method]"
                val week = PortfolioPeriodChange.gain(trades, current, closes, today - PortfolioPeriodChange.DAYS, method = method)
                near("$label 7 T", e["week"].num(), week?.amountUsd)
                near("$label 7 T percent", e["weekPercent"].num(), week?.percent)
                val year = PortfolioPeriodChange.gain(trades, current, closes, today - PortfolioPeriodChange.YEAR_DAYS, method = method)
                near("$label 1 J", e["year"].num(), year?.amountUsd)
                val weekCurve = PortfolioHistory.build(trades, closes, current, PortfolioHistoryRange.WEEK, today, method = method)
                near("$label curve 7 T", e["week"].num(), weekCurve.pnlChange)
                near("$label curve 7 T percent", e["weekPercent"].num(), weekCurve.pnlPercent)
                val yearCurve = PortfolioHistory.build(trades, closes, current, PortfolioHistoryRange.YEAR, today, method = method)
                near("$label curve 1 J", e["year"].num(), yearCurve.pnlChange)
                val sinceFirst = PortfolioHistory.build(trades, closes, current, PortfolioHistoryRange.SINCE_FIRST, today, method = method)
                near("$label curve since first", e["sinceFirst"].num(), sinceFirst.pnlChange)
                // Karte mit demselben Tageskurs wie die App (marketPriceAt in PortfolioDerivedFlows.kt)
                val market = { coin: String, time: Long ->
                    PortfolioPeriodChange.marketPriceAt(coin, current[PortfolioCalculator.normalizeCoin(coin)], closes, time)
                }
                val s = PortfolioCalculator.summarize(trades, current, method, market)
                near("$label card", e["card"].num(), (s.unrealized ?: 0.0) + s.realized + (s.withdrawnGainExact?.toDouble() ?: 0.0))
            }
        }
    }

    @Test
    fun scaleCloses() {
        for (raw in data["scaleCloses"].list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val result = PortfolioExchangeRoute.scaleCloses(series(c["closes"]), prices(c["prices"]), prices(c["binance"]))
            val expected = series(c["expected"])
            assertEquals("$name coins", expected.keys, result.keys)
            for ((coin, values) in expected) {
                for ((day, value) in values) near("$name $coin $day", value, result[coin]?.get(day))
            }
        }
    }

    private fun daily(raw: Any?): Map<Long, Double> = raw.obj().entries.associate { it.key.toLong() to it.value.num()!! }

    private fun names(raw: Any?): Set<String> = raw.list().map { it as String }.toSet()

    @Test
    fun historyCloses() {
        for (raw in data["historyCloses"].list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val result = PortfolioExchangeRoute.historyCloses(
                series(c["closes"]), emptyMap(), names(c["own"]), names(c["routed"]), names(c["failed"]),
                prices(c["prices"]), prices(c["binance"]),
            )
            val expected = series(c["expected"])
            assertEquals("$name coins", expected.keys, result.closes.keys)
            for ((coin, values) in expected) {
                assertEquals("$name $coin days", values.keys, result.closes[coin]?.keys)
                for ((day, value) in values) near("$name $coin $day", value, result.closes[coin]?.get(day))
            }
            assertEquals("$name scaled", names(c["expectedScaled"]), result.scaled)
        }
    }

    @Test
    fun ownDailyUsdt() {
        for (raw in data["ownDailyUsdt"].list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val rates = c["rates"]?.let { daily(it) }
            // USDT zum Marktkurs (optional): USDT/USD-Tagesschluss
            val usdtUsd = c["usdtUsd"]?.let { daily(it) }
            val result = PortfolioExchangeRoute.ownDailyUsdt(PortfolioCoinDaily(daily(c["closes"])), c["quote"] as String, rates, usdtUsd)
            val expected = c["expected"]?.let { daily(it) }
            if (expected == null) {
                assertNull("$name: expected null", result)
                continue
            }
            assertEquals("$name days", expected.keys, result?.closes?.keys)
            for ((day, value) in expected) near("$name $day", value, result?.closes?.get(day))
        }
    }

    /** USDT zum Marktkurs USDT/USD in Fiat; ohne Kurs 1, als Näherung markiert. */
    @Test
    fun usdtMarket() {
        val m = data["usdtMarket"].obj()
        for (raw in m["usdtUsd"].list()) {
            val c = raw.obj()
            val u = PortfolioStables.usdtUsd(c["market"].num())
            near("usdtUsd $c", c["rate"].num(), u.rate)
            assertEquals("usdtUsd approximate $c", c["approximate"], u.approximate)
        }
        for (raw in m["usdtTo"].list()) {
            val c = raw.obj()
            near("usdtTo $c", c["expected"].num(), PortfolioStables.usdtTo(c["usdToTarget"].num(), PortfolioStables.usdtUsd(c["market"].num())))
        }
        for (raw in m["ratesOn"].list()) {
            val c = raw.obj()
            val rates = UsdtUsdRates(c["live"].num(), daily(c["daily"]))
            val u = rates.on(c["day"].num()!!.toLong(), c["today"].num()!!.toLong())
            near("ratesOn $c", c["rate"].num(), u.rate)
            assertEquals("ratesOn approximate $c", c["approximate"], u.approximate)
        }
        for (raw in m["toUsdt"].list()) {
            val c = raw.obj()
            val usdTo = c["usdTo"].obj()
            val result = PortfolioExchangeRoute.toUsdt(c["price"].num()!!, c["quote"] as String, c["usdtUsd"].num()!!) { usdTo[it].num() }
            near("toUsdt $c", c["expected"].num(), result)
        }
        for (raw in m["holdings"].list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val total = PortfolioCalculator.summarize(c["trades"].list().map(::trade), prices(c["prices"])).totalValue
            val usdt = PortfolioStables.usdtUsd(c["usdtMarket"].num())
            val rate = PortfolioStables.usdtTo(c["usdToTarget"].num(), usdt)!!
            val par = PortfolioStables.usdtTo(c["usdToTarget"].num(), PortfolioStables.usdtUsd(1.0))!!
            near("$name valueUsdt", c["valueUsdt"].num(), total)
            near("$name valueTarget", c["valueTarget"].num(), total * rate)
            assertEquals("$name approximate", c["approximate"], usdt.approximate)
            near("$name change vs USDT = 1", c["changeVsParPercent"].num(), (rate / par - 1.0) * 100.0)
            // Anzeigewährung (auch USD): Faktor von Widget und Karte = derselbe Wert, Verlauf wird umgerechnet
            (c["currency"] as? String)?.let { currency ->
                val factor = com.cryptochecker.app.widget.PortfolioWidgetChart.usdtFactor(currency, c["usdToTarget"].num()!!, c["usdtMarket"].num())
                near("$name valueTarget ($currency, widget/card)", c["valueTarget"].num(), total * factor)
                assertTrue("$name history converted", com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx.convertsUsdt(currency, usdtMarket = true))
            }
        }
        assertTrue("USD display case", m["holdings"].list().any { (it.obj()["currency"] as? String) == "USD" })
    }

    @Test
    fun stitch() {
        for (raw in data["stitch"].list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val result = PortfolioExchangeRoute.stitch(PortfolioCoinDaily(daily(c["own"])), PortfolioCoinDaily(daily(c["binance"])))
            val expected = daily(c["expected"])
            assertEquals("$name days", expected.keys, result.daily.closes.keys)
            for ((day, value) in expected) near("$name $day", value, result.daily.closes[day])
            assertEquals("$name binanceBefore", c["binanceBefore"].num()?.toLong(), result.binanceBefore)
        }
    }

    @Test
    fun scaledHint() {
        for (raw in data["scaledHint"].list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val result = PortfolioExchangeRoute.scaledHint(
                names(c["scaled"]), c["binanceBefore"].obj().mapValues { it.value.num()!!.toLong() },
                c["fromDay"].num()!!.toLong(), c["coins"].list().map { it as String },
            )
            assertEquals(name, c["expected"].list(), result)
        }
    }
}
