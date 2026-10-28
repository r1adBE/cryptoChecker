package com.cryptochecker.marketdata.model

/**
 * Abschnitt einer Börse in der Börsenauswahl: Spot, Futures (auch Perp-DEXs wie Hyperliquid), DEX-Pools
 * und Forex-Referenzkurse. Reihenfolge = Reihenfolge der Abschnitte. iOS: `ExchangeCategory.swift`.
 */
enum class ExchangeCategory { SPOT, FUTURES, DEX, FOREX }
