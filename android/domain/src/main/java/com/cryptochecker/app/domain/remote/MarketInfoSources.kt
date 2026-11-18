package com.cryptochecker.app.domain.remote

import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.spot.SpotData
import com.cryptochecker.app.domain.spot.SpotMarket

/** Funding Rate und Open Interest für die Karte «Marktdaten» (Umsetzung: `FuturesDataSource`). */
interface FuturesInfoSource {
    /** Perpetual des Paars selbst; null ohne Daten. */
    suspend fun fetch(watch: WatchEntity): FuturesInfo?

    /** USDT-Perpetual des Coins (Binance, sonst Bybit, OKX). */
    suspend fun fetchForBase(baseAsset: String): FuturesInfo
}

/** Spot-Kennzahlen (CoinGecko) für die Karte «Marktdaten» (Umsetzung: `SpotDataSource`). */
interface SpotInfoSource {
    suspend fun fetch(symbol: String, identity: SpotMarket.Identity = SpotMarket.Identity()): SpotData?
}
