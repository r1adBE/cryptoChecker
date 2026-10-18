package com.cryptochecker.app.domain.watch

import com.cryptochecker.marketdata.model.market.DexScreener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alarm-Sperre für dünne DEX-Pools (unter 10’000 USD Liquidität oder unbekannt). Wie `DexLiquidityTests.swift`. */
class DexLiquidityTest {

    @Test
    fun thresholdIsTenThousandUsd() {
        assertEquals(10_000.0, DexScreener.DEX_MIN_ALARM_LIQUIDITY_USD, 0.0)
        assertTrue(DexScreener.alarmCapable(10_000.0))
        assertTrue(DexScreener.alarmCapable(45_678_901.23))
        assertFalse(DexScreener.alarmCapable(9_999.99))
        assertFalse(DexScreener.alarmCapable(0.0))
        // Unbekannt zählt wie zu wenig
        assertFalse(DexScreener.alarmCapable(null))
        assertFalse(DexScreener.alarmCapable(Double.NaN))
        assertFalse(DexScreener.alarmCapable(Double.POSITIVE_INFINITY))
    }

    @Test
    fun onlyDexPoolsAreBlocked() {
        assertTrue(DexLiquidity.blocksAlarms("DexScreener", 5_000.0))
        assertTrue(DexLiquidity.blocksAlarms("DexScreener", null))
        assertFalse(DexLiquidity.blocksAlarms("DexScreener", 25_000.0))
        // Börsen-Paare: nie gesperrt, auch ohne Liquidität
        assertFalse(DexLiquidity.blocksAlarms("Binance", null))
        assertFalse(DexLiquidity.blocksAlarms("Kraken", 1.0))
    }

    @Test
    fun keyIgnoresCaseAndSpaces() {
        assertEquals("ethereum/0xabc", DexLiquidity.key(" ethereum/0xABC "))
        assertNull(DexLiquidity.key("  "))
        assertNull(DexLiquidity.key(null))
    }

    @Test
    fun searchKeepsSortingAndMarksThinPools() {
        val body = """{"pairs":[
            {"chainId":"bsc","dexId":"pancake","pairAddress":"0x1","baseToken":{"symbol":"THIN"},"quoteToken":{"symbol":"WBNB"},"priceUsd":"0.1","liquidity":{"usd":2500.5}},
            {"chainId":"ethereum","dexId":"uniswap","pairAddress":"0x2","baseToken":{"symbol":"BIG"},"quoteToken":{"symbol":"WETH"},"priceUsd":"1.5","liquidity":{"usd":250000}},
            {"chainId":"solana","dexId":"raydium","pairAddress":"0x3","baseToken":{"symbol":"NOLIQ"},"quoteToken":{"symbol":"SOL"},"priceUsd":"2"}
        ]}"""
        val pools = DexScreener.parseSearch(body)
        assertEquals(listOf("BIG", "THIN", "NOLIQ"), pools.map { it.baseSymbol })
        assertEquals(listOf(true, false, false), pools.map { it.alarmCapable })
        assertEquals(2500.5, pools[1].liquidityUsd!!, 0.0)
        assertNull(pools[2].liquidityUsd)
    }
}
