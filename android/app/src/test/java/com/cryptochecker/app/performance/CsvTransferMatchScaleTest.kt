package com.cryptochecker.app.performance

import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportTx
import com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferMatch
import com.cryptochecker.app.performance.PerformanceTiming.medianMillis
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

/**
 * Umbuchungs-Abgleich beim CSV-Import ([CsvTransferMatch.candidates]) für eine grosse Datei: 20 000 Ein- und
 * Auszahlungen (12 Coins über ein Jahr, je Auszahlung eine passende Einzahlung eine Stunde später mit Netzwerkgebühr).
 * Einzahlungen je Coin nach Zeit, Zeitfenster per Binärsuche — feste Grenze (die Rechnung «jede mit jeder» bräuchte
 * 10⁸ Vergleiche). iOS: CsvTransferMatchScaleTests.
 */
class CsvTransferMatchScaleTest {

    private fun file(count: Int): List<CsvImportTx> {
        val rng = SplitMix64(PerformanceData.SEED + 20)
        val out = ArrayList<CsvImportTx>(count)
        for (i in 0 until count / 2) {
            val coin = PortfolioPerformanceData.COINS[rng.nextInt(PortfolioPerformanceData.COINS.size)]
            val time = START_MS + YEAR_MS * i / (count / 2) + rng.nextInt(HOUR_MS)
            val amount = BigDecimal.valueOf(1_000_000L + rng.nextInt(1_000_000), 8)
            out += CsvImportTx(
                line = 2 * i + 1, coin = coin, type = PortfolioTxType.WITHDRAWAL, amount = amount, priceUsdt = null,
                time = time, fee = BigDecimal("0.0001"), feeUnit = PortfolioFeeUnit.COIN, feeCoin = null,
                feeCoinAmount = null, note = null, importKey = "BINANCE|w$i",
            )
            out += CsvImportTx(
                line = 2 * i + 2, coin = coin, type = PortfolioTxType.DEPOSIT, amount = amount, priceUsdt = null,
                time = time + HOUR_MS, fee = null, feeUnit = null, feeCoin = null, feeCoinAmount = null, note = null,
                importKey = "KRAKEN|d$i",
            )
        }
        return out
    }

    @Test
    fun twentyThousandMovesStayFast() {
        val txs = file(MOVES)
        var links = 0
        val millis = medianMillis { CsvTransferMatch.candidates(txs, null, emptyList()).also { links = it.size } }
        val text = "%d Ein-/Auszahlungen %.2f ms · Grenze %.0f ms"
        println("[Performance] Umbuchungs-Abgleich: " + String.format(Locale.ROOT, text, MOVES, millis, LIMIT_MILLIS))
        assertTrue("Kandidaten: $links", links >= MOVES / 2)
        assertTrue("Umbuchungs-Abgleich: $millis ms für $MOVES Ein-/Auszahlungen", millis <= LIMIT_MILLIS)
    }

    private companion object {
        const val MOVES = 20_000

        /** Erwartet rund 50–250 ms (Sortieren, Fenster, Kandidaten samt Sortierung). */
        const val LIMIT_MILLIS = 3_000.0
        const val START_MS = 1_704_067_200_000L
        const val YEAR_MS = 31_536_000_000L
        const val HOUR_MS = 3_600_000
    }
}
