package com.cryptochecker.app.domain.portfolio.csvimport

import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import kotlin.random.Random

/**
 * Umbuchungs-Abgleich ([CsvTransferMatch.candidates]) mit Zeitfenster per Binärsuche ([CsvTransferWindow]) gegen die
 * naive Rechnung «jede Auszahlung mit jeder Einzahlung» auf Zufallsdaten mit Seed: dieselben Paare in derselben
 * Reihenfolge, dieselben Kandidaten samt «eindeutig». iOS: CsvTransferWindowTests.swift.
 */
class CsvTransferWindowTest {

    private data class Item(val coin: String, val time: Long, val value: Int)

    @Test
    fun pairsMatchTheDoubleLoop() {
        val early = 2L * HOUR
        val window = 72L * HOUR
        for (seed in 1..50) {
            val rnd = Random(seed)
            fun items(n: Int) = List(n) {
                Item(COINS[rnd.nextInt(COINS.size)], rnd.nextLong(0, 30 * DAY) / HOUR * HOUR, rnd.nextInt(10))
            }
            val outs = items(rnd.nextInt(0, 150))
            val ins = items(rnd.nextInt(0, 150))
            // Wie [CsvTransferMatch]: nur derselbe Coin im Fenster, dazu eine weitere Bedingung
            val fits = { w: Item, d: Item ->
                w.coin == d.coin && d.time >= w.time - early && d.time <= w.time + window && (w.value + d.value) % 3 != 0
            }
            val naive = ArrayList<Pair<Int, Int>>()
            for (wi in outs.indices) for (di in ins.indices) if (fits(outs[wi], ins[di])) naive += wi to di
            val fast = CsvTransferWindow.pairs(outs, ins, Item::coin, Item::time, early, window, fits)
            assertEquals("Seed $seed", naive, fast)
        }
    }

    @Test
    fun candidatesMatchTheNaiveReference() {
        var links = 0
        for (seed in 1..30) {
            val rnd = Random(seed)
            val txs = List(rnd.nextInt(10, 70)) { i -> tx(rnd, i) }
            val stored = List(rnd.nextInt(0, 25)) { i -> stored(rnd, i) }
            val place = if (seed % 2 == 0) "Binance" else null
            val actual = CsvTransferMatch.candidates(txs, place, stored)
            assertEquals("Seed $seed", naive(txs, place, stored), actual)
            links += actual.size
        }
        assertTrue("Kandidaten geprüft: $links", links > 100)
    }

    /**
     * Naive Referenz: jedes Paar (Auszahlung, Einzahlung) in der Reihenfolge der Rechnung einzeln abgleichen (ein Paar
     * ergibt höchstens einen Kandidaten), «eindeutig» über alle Paare, sortiert wie [CsvTransferMatch.candidates].
     */
    private fun naive(txs: List<CsvImportTx>, place: String?, stored: List<CsvStoredMove>): List<CsvTransferLink> {
        val free = stored.filter { it.linkedImportKey == null }
        val outs = txs.filter { it.type == PortfolioTxType.WITHDRAWAL }.map { listOf(it) to emptyList<CsvStoredMove>() } +
            free.filter { it.type == PortfolioTxType.WITHDRAWAL || it.type == PortfolioTxType.TRANSFER && it.transferTo == null }
                .map { emptyList<CsvImportTx>() to listOf(it) }
        val ins = txs.filter { it.type == PortfolioTxType.DEPOSIT }.map { listOf(it) to emptyList<CsvStoredMove>() } +
            free.filter { it.type == PortfolioTxType.DEPOSIT }.map { emptyList<CsvImportTx>() to listOf(it) }
        val found = ArrayList<CsvTransferLink>()
        for (w in outs) for (d in ins) found += CsvTransferMatch.candidates(w.first + d.first, place, w.second + d.second)
        val perOut = found.groupingBy { it.withdrawalKey }.eachCount()
        val perIn = found.groupingBy { it.depositKey }.eachCount()
        return found.map { it.copy(unambiguous = perOut[it.withdrawalKey] == 1 && perIn[it.depositKey] == 1) }
            .sortedWith(compareBy({ it.withdrawalTime }, { it.depositTime }, { it.key }))
    }

    private fun tx(rnd: Random, i: Int): CsvImportTx {
        val type = if (rnd.nextBoolean()) PortfolioTxType.WITHDRAWAL else PortfolioTxType.DEPOSIT
        val amount = BigDecimal(100 + rnd.nextInt(5)).movePointLeft(2)
        val (fee, unit) = when (rnd.nextInt(4)) {
            0 -> BigDecimal("0.01") to PortfolioFeeUnit.COIN
            1 -> BigDecimal("1") to PortfolioFeeUnit.QUOTE
            else -> null to null
        }
        return CsvImportTx(
            line = i + 1, coin = COINS[rnd.nextInt(COINS.size)], type = type, amount = amount, priceUsdt = null,
            time = rnd.nextLong(0, 10 * DAY) / HOUR * HOUR, fee = fee, feeUnit = unit, feeCoin = null, feeCoinAmount = null,
            note = null, importKey = "BINANCE|$i",
        )
    }

    private fun stored(rnd: Random, i: Int): CsvStoredMove {
        val type = listOf(PortfolioTxType.WITHDRAWAL, PortfolioTxType.DEPOSIT, PortfolioTxType.TRANSFER)[rnd.nextInt(3)]
        return CsvStoredMove(
            id = 1_000L + i, coin = COINS[rnd.nextInt(COINS.size)], type = type,
            amount = BigDecimal(100 + rnd.nextInt(5)).movePointLeft(2),
            fee = BigDecimal("0.01").takeIf { rnd.nextBoolean() }, feeUnit = PortfolioFeeUnit.COIN, feeCoin = null,
            feeCoinAmount = null, time = rnd.nextLong(0, 10 * DAY) / HOUR * HOUR, importKey = "KRAKEN|$i",
            transferFrom = "Kraken".takeIf { type == PortfolioTxType.TRANSFER }, transferTo = null,
            linkedImportKey = "COINBASE|$i".takeIf { rnd.nextInt(8) == 0 },
        )
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L
        val COINS = listOf("BTC", "ETH", "SOL")
    }
}
