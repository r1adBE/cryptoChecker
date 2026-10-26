package com.cryptochecker.marketdata.model

/**
 * Abschnitt einer Börse in der Börsenauswahl (Build 133): Spot, Futures (auch Perp-DEXs wie Hyperliquid), DEX-Pools
 * und Forex-Referenzkurse. Reihenfolge = Reihenfolge der Abschnitte. iOS: `MarketCategory.swift`.
 */
enum class MarketCategory { SPOT, FUTURES, DEX, FOREX }
