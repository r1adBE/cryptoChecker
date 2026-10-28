package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.PortfolioPrices
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger

/**
 * Die Coin-Ansicht rechnet bei Kurs-Ticks fremder Coins nicht neu, wohl aber bei Ticks des
 * eigenen Coins und der Coins, deren Tageskurs ihre Zeilen brauchen (Tausch ohne Wert → erhaltener Coin).
 */
class PortfolioCoinDetailFlowTest {

    private val txs = listOf(
        tx(1, "BTC", PortfolioTxType.BUY, price = "20000"),
        // Tausch BTC → SOL ohne Wert: braucht den Tageskurs von BTC bzw. SOL
        tx(2, "BTC", PortfolioTxType.SWAP, price = null).copy(toCoin = "SOL", toAmount = BigDecimal("10")),
        tx(3, "ETH", PortfolioTxType.BUY, price = "1500"),
    )

    @Test
    fun gebuehrCoinsNurAusEigenenZeilen() {
        assertEquals(setOf("BTC", "SOL"), coinDetailFeeCoins("BTC", txs))
        assertEquals(setOf("BTC", "SOL"), coinDetailFeeCoins("SOL", txs))
        assertEquals(emptySet<String>(), coinDetailFeeCoins("ETH", txs))
    }

    @Test
    fun schluesselBleibtBeiFremdemKurs() {
        val coins = coinDetailFeeCoins("BTC", txs)
        val before = CoinFeePrices.of(coins, prices("BTC" to 40_000.0, "SOL" to 100.0, "ETH" to 2_000.0), null)
        assertEquals(before, CoinFeePrices.of(coins, prices("BTC" to 40_000.0, "SOL" to 100.0, "ETH" to 2_100.0), null))
        assertNotEquals(before, CoinFeePrices.of(coins, prices("BTC" to 40_000.0, "SOL" to 110.0, "ETH" to 2_000.0), null))
    }

    @Test
    fun tickFremderCoinRechnetNichtNeu() = runBlocking {
        val calls = AtomicInteger()
        val list = MutableStateFlow<List<PortfolioTxEntity>?>(txs)
        val p = MutableStateFlow(prices("BTC" to 40_000.0, "SOL" to 100.0, "ETH" to 2_000.0))
        val flow = coinDetailFlowOf(
            "BTC", list, list, MutableStateFlow<List<WatchedAddress>>(emptyList()), p, MutableStateFlow<HistoryCloses?>(null),
            MutableStateFlow(CostMethod.FIFO),
        ) { s, all, calc, addresses, price, method, fee ->
            calls.incrementAndGet()
            coinDetail(s, all, calc, addresses, price, method, fee)
        }
        val job = launch(Dispatchers.Default) { flow.collect {} }
        awaitCalls(calls, 1)
        delay(300)
        val base = calls.get()

        p.value = prices("BTC" to 40_000.0, "SOL" to 100.0, "ETH" to 2_100.0)
        delay(300)
        assertEquals("Tick ETH", base, calls.get())

        p.value = prices("BTC" to 40_000.0, "SOL" to 110.0, "ETH" to 2_100.0)
        awaitCalls(calls, base + 1)
        p.value = prices("BTC" to 41_000.0, "SOL" to 110.0, "ETH" to 2_100.0)
        awaitCalls(calls, base + 2)
        job.cancel()
    }

    private suspend fun awaitCalls(calls: AtomicInteger, n: Int) = withTimeout(5_000) {
        while (calls.get() < n) delay(10)
    }

    private fun prices(vararg values: Pair<String, Double>) = PortfolioPrices(prices = mapOf(*values), updatedAt = 1)

    private fun tx(id: Long, coin: String, type: PortfolioTxType, price: String?) = PortfolioTxEntity(
        id = id, coin = coin, type = type, amount = BigDecimal.ONE, priceUsdt = price?.let(::BigDecimal), time = id * 86_400_000L,
    )
}
