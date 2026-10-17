package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertEquals
import org.junit.Test

/** Art eines Paars (Token, TradFi, Alpha) — wie `AssetKindTests` (iOS, WatchFilterTests.swift). */
class AssetKindTest {
    private val alpha = setOf("AIA", "AGT")

    @Test
    fun tradFi_winsOverEverything() {
        assertEquals(AssetKind.TRADFI, AssetKind.of("BinanceFutures", "NVDA", tradFi = true, alphaSymbols = alpha))
        // Auch ein Kürzel der Alpha-Liste ist als TradFi-Kontrakt TradFi
        assertEquals(AssetKind.TRADFI, AssetKind.of("BinanceFutures", "AIA", tradFi = true, alphaSymbols = alpha))
    }

    @Test
    fun alpha_onlyForListedSymbols() {
        assertEquals(AssetKind.ALPHA, AssetKind.of("BinanceFutures", "AIA", tradFi = false, alphaSymbols = alpha))
        assertEquals(AssetKind.ALPHA, AssetKind.of("Bybit", "agt", tradFi = false, alphaSymbols = alpha))
        // Hebel-Kürzel wie das Kürzel
        assertEquals(AssetKind.ALPHA, AssetKind.of("BinanceFutures", "1000AIA", tradFi = false, alphaSymbols = alpha))
        assertEquals(AssetKind.TOKEN, AssetKind.of("BinanceFutures", "BTC", tradFi = false, alphaSymbols = alpha))
    }

    @Test
    fun binanceSpotAndDex_neverAlpha() {
        // An Binance Spot gehandelt = regulär gelistet
        assertEquals(AssetKind.TOKEN, AssetKind.of("Binance", "AIA", tradFi = false, alphaSymbols = alpha))
        // DEX: fremder Token darf gleich heissen
        assertEquals(AssetKind.TOKEN, AssetKind.of("DexScreener", "AIA", tradFi = false, alphaSymbols = alpha))
    }

    @Test
    fun noAlphaList_meansToken() {
        assertEquals(AssetKind.TOKEN, AssetKind.of("BinanceFutures", "AIA", tradFi = false, alphaSymbols = emptySet()))
    }
}
