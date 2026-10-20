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

    @Test
    fun searchShowsTokenAddressAndRanksAddressMatchesFirst() {
        // Zwei «PEPE»: der liquidere ist ein anderer Token; dazu ein Pool mit dem gesuchten Token nur als Gegenwährung
        val body = """{"pairs":[
            {"chainId":"ethereum","dexId":"uniswap","pairAddress":"0xp1","baseToken":{"symbol":"PEPE","address":"0x1111111111111111111111111111111111112222"},"quoteToken":{"symbol":"WETH"},"priceUsd":"0.1","liquidity":{"usd":900000}},
            {"chainId":"ethereum","dexId":"uniswap","pairAddress":"0xp2","baseToken":{"symbol":"WETH","address":"0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"},"quoteToken":{"symbol":"PEPE","address":"0x6982508145454Ce325dDbE47a25d4ec3d2311933"},"priceUsd":"2500","liquidity":{"usd":500000}},
            {"chainId":"ethereum","dexId":"uniswap","pairAddress":"0xp3","baseToken":{"symbol":"PEPE","address":"0x6982508145454Ce325dDbE47a25d4ec3d2311933"},"quoteToken":{"symbol":"WETH"},"priceUsd":"0.00001","liquidity":{"usd":40000}},
            {"chainId":"solana","dexId":"raydium","pairAddress":"p4","baseToken":{"symbol":"NOADDR"},"quoteToken":{"symbol":"SOL"},"priceUsd":"1"}
        ]}"""
        val byLiquidity = DexScreener.parseSearch(body)
        assertEquals(listOf("0xp1", "0xp2", "0xp3", "p4"), byLiquidity.map { it.pairAddress })
        assertEquals("0x1111…2222", byLiquidity[0].shortBaseAddress)
        assertEquals("0x6982…1933", byLiquidity[2].shortBaseAddress)
        assertEquals("", byLiquidity[3].shortBaseAddress)
        // Suche per Vertragsadresse (andere Schreibweise): Pools mit dem Token als Basis zuerst, Rest nach Liquidität
        val byAddress = DexScreener.parseSearch(body, " 0x6982508145454ce325ddbe47a25d4ec3d2311933 ")
        assertEquals(listOf("0xp3", "0xp1", "0xp2", "p4"), byAddress.map { it.pairAddress })
        // Kurze Adressen bleiben ganz
        assertEquals("So11111", DexScreener.shortAddress(" So11111 "))
        assertEquals("123456789012", DexScreener.shortAddress("123456789012"))
        assertEquals("123456…0123", DexScreener.shortAddress("1234567890123"))
    }
}
