package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gebrückte Stablecoins (USDC.e, USDbC, USDT.e) zählen zum Basis-Stablecoin, die Position trägt «≈». Wie iOS. */
class BridgedStablesTest {

    private val evm = WatchedAddress(
        chain = AddressChain.ETH,
        address = "0x1111111111111111111111111111111111111111",
        tokenBalances = mapOf(
            "USDC" to "100",
            "ARBITRUM:USDC.e" to "50",
            "BASE:USDbC" to "25",
            "AVALANCHE:USDT.e" to "10",
            "AVALANCHE:USDT" to "5",
            // Gleiches Symbol (Binance-Peg «USDC» auf BSC) und Guthaben 0 zählen nicht als gebrückt
            "BSC:USDC" to "7",
            "OPTIMISM:USDC.e" to "0",
        ),
    )

    @Test
    fun bridgedVariantsAreListedPerBaseStable() {
        val bridged = AddressBalance.bridgedStables(listOf(evm))
        assertEquals(mapOf("USDC" to listOf("USDC.e", "USDbC"), "USDT" to listOf("USDT.e")), bridged)
        // Summe bleibt zusammengezählt, nur markiert
        val holdings = AddressBalance.holdings(listOf(evm)).toMap()
        assertEquals("182", holdings["USDC"])
        assertEquals("15", holdings["USDT"])
    }

    @Test
    fun notCountedOrWithoutBridgedNothingMarked() {
        assertTrue(AddressBalance.bridgedStables(listOf(evm.copy(countInPortfolio = false))).isEmpty())
        assertTrue(AddressBalance.bridgedStables(listOf(evm.copy(tokenBalances = mapOf("USDC" to "1")))).isEmpty())
    }

    @Test
    fun isBridgedStable() {
        assertTrue(ChainAssets.isBridgedStable("USDC.e", "USDC"))
        assertTrue(ChainAssets.isBridgedStable("USDbC", "USDC"))
        assertFalse(ChainAssets.isBridgedStable("USDC", "USDC"))
        // Gekapselte Nicht-Stablecoins laufen über «≈ BTC-Kurs» (priceViaBase), nicht hierüber
        assertFalse(ChainAssets.isBridgedStable("WBTC", "BTC"))
    }
}
