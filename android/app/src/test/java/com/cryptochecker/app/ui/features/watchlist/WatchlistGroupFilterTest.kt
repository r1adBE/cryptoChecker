package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.AutoGroup
import com.cryptochecker.marketdata.model.FuturesContractType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gruppen-Filter der Merkliste (WatchlistGroupFilter): Gruppennamen ([groupsOf]), Startwerte von [GroupFilter] und
 * die Art je Paar ([assetKind]: TradFi aus Gruppe oder Liste, Alpha aus der Alpha-Liste, sonst Token).
 */
class WatchlistGroupFilterTest {

    private fun watch(
        base: String = "BTC",
        market: String = "Bybit",
        group: String? = null,
        pairId: String? = null,
    ) = WatchEntity(
        marketKey = market,
        marketName = market,
        baseAsset = base,
        quoteAsset = "USDT",
        groupName = group,
        pairId = pairId,
    )

    // ---- Gruppennamen ----

    @Test
    fun groupsOfDistinctAndSortedIgnoringCase() {
        val watches = listOf(
            watch(group = "majors"),
            watch(group = null),
            watch(group = "Alts"),
            watch(group = "majors"),
            watch(group = "Zulu"),
            watch(group = "beta"),
        )
        assertEquals(listOf("Alts", "beta", "majors", "Zulu"), groupsOf(watches))
    }

    @Test
    fun groupsOfWithoutGroupsIsEmpty() {
        assertTrue(groupsOf(emptyList()).isEmpty())
        assertTrue(groupsOf(listOf(watch(), watch(base = "ETH"))).isEmpty())
    }

    @Test
    fun groupFilterDefaults() {
        val filter = GroupFilter(groups = listOf("A"), selected = null)
        assertNull(filter.selected)
        assertFalse(filter.tokens)
        assertFalse(filter.alpha)
        assertTrue(filter.kinds.isEmpty())
    }

    // ---- Art je Paar ----

    @Test
    fun tradFiGroupMakesTradFi() {
        val kind = watch(base = "AAPL", group = AutoGroup.TRADFI).assetKind(emptySet(), emptySet())
        assertEquals(AssetKind.TRADFI, kind)
    }

    @Test
    fun tradFiListMakesTradFi() {
        val aapl = watch(base = "aapl")
        val key = CoinLogos.pairKey(aapl.marketKey, aapl.baseAsset, aapl.quoteAsset, FuturesContractType.NONE.name)
        assertEquals(AssetKind.TRADFI, aapl.assetKind(setOf(key), emptySet()))
        // Anderer Kontrakttyp im Schlüssel: kein Treffer
        val other = CoinLogos.pairKey(aapl.marketKey, aapl.baseAsset, aapl.quoteAsset, "PERPETUAL_X")
        assertEquals(AssetKind.TOKEN, aapl.assetKind(setOf(other), emptySet()))
    }

    @Test
    fun alphaSymbolMakesAlphaOutsideBinanceSpot() {
        assertEquals(AssetKind.ALPHA, watch(base = "AIA").assetKind(emptySet(), setOf("AIA")))
        // Binance Spot führt den Coin regulär: nie Alpha
        assertEquals(
            AssetKind.TOKEN,
            watch(base = "AIA", market = AssetKind.BINANCE_SPOT_MARKET).assetKind(emptySet(), setOf("AIA")),
        )
        // DEX-Pools nie
        assertEquals(AssetKind.TOKEN, watch(base = "AIA", market = CoinLogos.DEX_MARKET_KEY).assetKind(emptySet(), setOf("AIA")))
    }

    @Test
    fun withoutListsEverythingIsToken() {
        assertEquals(AssetKind.TOKEN, watch(base = "ETH").assetKind(emptySet(), emptySet()))
        assertEquals(AssetKind.TOKEN, watch(base = "ETH").assetKind(emptySet(), setOf("AIA")))
    }

    @Test
    fun tradFiWinsOverAlpha() {
        val kind = watch(base = "AIA", group = AutoGroup.TRADFI).assetKind(emptySet(), setOf("AIA"))
        assertEquals(AssetKind.TRADFI, kind)
    }
}
