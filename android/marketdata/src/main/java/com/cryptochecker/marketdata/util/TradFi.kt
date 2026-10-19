package com.cryptochecker.marketdata.util

/**
 * Futures auf Dinge, die keine Krypto-Token sind — Aktien, ETFs, Rohstoffe (Gold, Öl …), Devisen, Firmen vor dem
 * Börsengang («TradFi»). Jede Börse kennzeichnet sie auf ihre Weise; die Regeln folgen den öffentlichen
 * Paarlisten (Stand Oktober 2026). iOS: `TradFi.swift`.
 *
 * Arbeitet nur auf Texten, damit es ohne JSON-Bibliothek testbar bleibt.
 */
object TradFi {

    /** Binance-Vertragsart für Aktien, Rohstoffe, Devisen und Pre-IPO. */
    const val BINANCE_TRADFI_PERPETUAL = "TRADIFI_PERPETUAL"

    private val BYBIT_TYPES = setOf("stock", "etf", "commodity", "forex")

    // Teilwörter der MEXC-Bereiche («…-tradfi», «…-Stock», «…-metals», «…-Commodities», «…-forex»)
    private val MEXC_ZONES = listOf("tradfi", "stock", "metals", "commodit", "forex")

    /**
     * Binance Futures (`/fapi/v1/exchangeInfo`): Vertragsart [BINANCE_TRADFI_PERPETUAL] oder «TradFi» unter den
     * `underlyingSubType` (etwa ["TradFi"] oder ["Pre-IPO", "TradFi"]).
     */
    fun binance(contractType: String, subTypes: Collection<String>): Boolean {
        if (contractType == BINANCE_TRADFI_PERPETUAL) return true
        return subTypes.any { "TradFi".equals(it, ignoreCase = true) }
    }

    /** Bybit (`/v5/market/instruments-info`, linear): `symbolType` ist stock, ETF, commodity oder forex. */
    fun bybit(symbolType: String): Boolean = BYBIT_TYPES.contains(symbolType.lowercase())

    /** OKX (`/api/v5/public/instruments`, SWAP): `instCategory` gesetzt und nicht «1» (Krypto); «3» = Aktien. */
    fun okx(instCategory: String): Boolean = when (instCategory) {
        "", "1" -> false
        else -> true
    }

    /**
     * MEXC (`/api/v1/contract/detail`): `type` 2 (Aktien und ETFs; Gold hat 1) oder ein TradFi-Bereich in
     * `conceptPlate`.
     */
    fun mexc(conceptPlates: Collection<String>, type: Int): Boolean {
        if (type == 2) return true
        return conceptPlates.any { plate -> MEXC_ZONES.any { zone -> plate.contains(zone, ignoreCase = true) } }
    }

    /**
     * Bitget (`/api/v2/mix/market/contracts`): `isRwa` je Kontrakt «YES» (oder «true») für Aktien-, Rohstoff- und
     * Index-Futures («RWA»; in Live-Daten etwa 335 von 801).
     */
    fun bitget(isRwa: String): Boolean =
        listOf("YES", "true").any { isRwa.equals(it, ignoreCase = true) }

    /** HTX Futures (`swap_contract_info`): mindestens ein nicht leeres `tradfi_labels` (Aktie, Rohstoff …). */
    fun htx(labels: Collection<String>): Boolean = labels.any(String::isNotBlank)

    /** Phemex Futures (`/public/products`, `perpProductsV2`): `perpProductSubType` ist «TradFi». */
    fun phemex(perpProductSubType: String): Boolean = "TradFi".equals(perpProductSubType, ignoreCase = true)

    /**
     * Lighter (`/api/v1/tokenlist`): `asset_type` «RWA» (Aktien, ETFs, Rohstoffe, Devisen, Pre-IPO, Rechenleistung)
     * statt «CRYPTO».
     */
    fun lighter(assetType: String): Boolean = assetType.equals("RWA", ignoreCase = true)
}
