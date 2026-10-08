package com.cryptochecker.marketdata.util

/**
 * Erkennt Futures auf etwas, das kein Krypto-Token ist — Aktien, ETFs, Rohstoffe (Gold, Öl …),
 * Devisen und Firmen vor dem Börsengang («TradFi»). Jede Börse kennzeichnet das anders; die
 * Regeln hier stammen aus ihren öffentlichen Paarlisten (Stand Oktober 2026). Wie `TradFi.swift`.
 *
 * Reine Funktionen auf Texten, damit sie ohne JSON-Bibliothek testbar sind.
 */
object TradFi {

    /**
     * Binance Futures (`/fapi/v1/exchangeInfo`): Vertragsart «TRADIFI_PERPETUAL» oder Kategorie
     * «TradFi» in `underlyingSubType` (z. B. ["TradFi"] oder ["Pre-IPO", "TradFi"]).
     */
    fun binance(contractType: String, subTypes: Collection<String>): Boolean =
        contractType == BINANCE_TRADFI_PERPETUAL || subTypes.any { it.equals("TradFi", ignoreCase = true) }

    /** Vertragsart der Binance-Kontrakte auf Aktien, Rohstoffe, Devisen und Pre-IPO. */
    const val BINANCE_TRADFI_PERPETUAL = "TRADIFI_PERPETUAL"

    /** Bybit (`/v5/market/instruments-info`, linear): `symbolType` stock, ETF, commodity oder forex. */
    fun bybit(symbolType: String): Boolean = symbolType.lowercase() in BYBIT_TYPES

    private val BYBIT_TYPES = setOf("stock", "etf", "commodity", "forex")

    /** OKX (`/api/v5/public/instruments`, SWAP): `instCategory` gesetzt und nicht 1 (Krypto); 3 = Aktien. */
    fun okx(instCategory: String): Boolean = instCategory.isNotEmpty() && instCategory != "1"

    /**
     * MEXC (`/api/v1/contract/detail`): Bereich «…-tradfi», «…-Stock», «…-metals», «…-Commodities»
     * oder «…-forex» in `conceptPlate`, oder `type` 2 (Aktien und ETFs; Gold hat Typ 1).
     */
    fun mexc(conceptPlates: Collection<String>, type: Int): Boolean =
        type == 2 || conceptPlates.any { plate -> MEXC_ZONES.any { plate.contains(it, ignoreCase = true) } }

    private val MEXC_ZONES = listOf("tradfi", "stock", "metals", "commodit", "forex")

    /** Bitget (`/api/v2/mix/market/contracts`): `isRwa` «YES»/«NO» je Kontrakt (Aktien-, Rohstoff- und Index-Futures, «RWA»; Live-Daten: rund 335 von 801). */
    fun bitget(isRwa: String): Boolean = isRwa.equals("YES", ignoreCase = true) || isRwa.equals("true", ignoreCase = true)
}
