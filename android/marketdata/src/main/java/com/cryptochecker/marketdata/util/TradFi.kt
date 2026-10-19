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

    /**
     * Lighter-Spot ohne eigenen `tokenlist`-Eintrag (etwa «rhSPY/USDC»): Basis «rh» + Grossbuchstabe ist ein
     * Robinhood-Aktientoken (Aktie oder ETF). Kennt `tokenlist` den Rest («SPY»), entscheidet dessen `asset_type`
     * ([restAssetType]) — so bleibt ein gleich gebauter Krypto-Name unter Krypto; sonst gilt das Paar als TradFi.
     */
    fun lighterRobinhood(base: String, restAssetType: String?): Boolean {
        if (base.length < 3 || !base.startsWith("rh") || base[2] !in 'A'..'Z') return false
        return restAssetType?.takeIf(String::isNotBlank)?.let { lighter(it) } ?: true
    }

    // Einträge in Aster `underlyingSubType` für Aktien, ETFs und Rohstoffe (Krypto: «Top», «Meme», «AI», «AOS2» …)
    private val ASTER_SUB_TYPES = setOf("stock", "etf", "commodities", "usd1-rwa")

    /**
     * Aster (`/fapi/v1/exchangeInfo`): `underlyingType` ist immer «COIN»; Aktien, ETFs und Rohstoffe tragen in
     * `underlyingSubType` «STOCK», «ETF», «Commodities» oder «USD1-RWA» (etwa ["STOCK", "Semiconductor"]).
     */
    fun aster(subTypes: Collection<String>): Boolean = subTypes.any { it.trim().lowercase() in ASTER_SUB_TYPES }

    // Vorsilben von `asset` der BingX-TradFi-Kontrakte: Aktie, Index/ETF, Rohstoff, Devisen
    private val BINGX_PREFIXES = listOf("NCSK", "NCSI", "NCCO", "NCFX")

    /**
     * BingX Futures (`/openApi/swap/v2/quote/contracts`): `asset` beginnt mit «NCSK» (Aktie), «NCSI» (Index/ETF),
     * «NCCO» (Rohstoff) oder «NCFX» (Devisen), etwa «NCSKAAPL2USD». Nur die bekannten Vorsilben, damit ein
     * Krypto-Token, dessen Kürzel zufällig mit «NC» beginnt, nicht unter TradFi verschwindet.
     */
    fun bingx(asset: String): Boolean = BINGX_PREFIXES.any { asset.length > it.length && asset.startsWith(it) }

    /**
     * Basis eines BingX-TradFi-Kontrakts aus `displayName` (Teil vor dem letzten «-»): ein Kürzel in Klammern
     * («GOLD(XAU)» → XAU), sonst der Text ohne Leerzeichen in Grossbuchstaben («Oil Brent» → OILBRENT). `asset`
     * («NCSKAAPL2USD») taugt nicht zum Anzeigen; ohne brauchbaren Namen bleibt es trotzdem die Basis.
     */
    fun bingxBase(displayName: String, asset: String): String {
        val name = displayName.substringBeforeLast('-').trim()
        val short = name.substringAfter('(', "").substringBefore(')').trim()
        val base = short.ifEmpty { name }.filterNot(Char::isWhitespace).uppercase()
        return base.ifEmpty { asset }
    }
}
