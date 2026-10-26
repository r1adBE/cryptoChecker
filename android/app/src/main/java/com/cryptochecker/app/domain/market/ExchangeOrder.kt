package com.cryptochecker.app.domain.market

import com.cryptochecker.marketdata.model.MarketCategory
import org.json.JSONArray

/**
 * Reihenfolge der Börsenauswahl (Build 133): Abschnitte Spot · Futures · DEX-Pools · Forex, darin nach dem
 * Handelsvolumen am Tag der Installation (einmal von CoinGecko geholt, `ExchangeRankingStore`), ohne Ranking in der
 * Reihenfolge der Registry (`MarketsConfig`). Reine Logik — wie iOS `ExchangeOrder`
 * (`testdata/parity/picker_order.json`).
 */
object ExchangeOrder {

    /** Öffentliche Liste der Spot-Börsen (ohne Schlüssel), Feld `trade_volume_24h_btc`. */
    const val SPOT_URL = "https://api.coingecko.com/api/v3/exchanges?per_page=250&page=1"

    /** Öffentliche Liste der Derivate-Börsen, nach Volumen. */
    const val DERIVATIVES_URL =
        "https://api.coingecko.com/api/v3/derivatives/exchanges?order=trade_volume_24h_btc_desc&per_page=100&page=1"

    /** Börsen-Schlüssel → Kennung bei CoinGecko: Spot aus [SPOT_URL], Futures aus [DERIVATIVES_URL]. */
    val SPOT_IDS: Map<String, String> = mapOf(
        "Coinbase" to "gdax", "Kraken" to "kraken", "GateIo" to "gate", "Binance" to "binance",
        "BinanceUs" to "binance_us", "Kucoin" to "kucoin", "Okex" to "okex", "Bybit" to "bybit_spot",
        "Bitget" to "bitget", "Mexc" to "mxc", "Htx" to "huobi", "Bitfinex" to "bitfinex", "Bitstamp" to "bitstamp",
        "Bitvavo" to "bitvavo", "CryptoCom" to "crypto_com", "Gemini" to "gemini", "BingX" to "bingx",
        "Woo" to "wootrade", "Phemex" to "phemex", "Poloniex" to "poloniex", "OneTrading" to "bitpanda",
        "Upbit" to "upbit", "Bithumb" to "bithumb", "BitFlyer" to "bitflyer", "Bitbank" to "bitbank",
        "Coincheck" to "coincheck", "Btcturk" to "btcturk", "Paribu" to "paribu", "Icrypex" to "icrypex",
        "Bitso" to "bitso", "MercadoBitcoin" to "mercado_bitcoin", "Indodax" to "indodax", "ZebPay" to "zebpay",
        "CoinDcx" to "coindcx", "HashKey" to "hashkey_exchange", "IndependentReserve" to "independent_reserve",
        "BtcMarkets" to "btcmarkets", "Luno" to "luno", "Latoken" to "latoken", "NonKyc" to "nonkyc_io",
    )

    /** Wie [SPOT_IDS] für die Futures-Abschnitte. */
    val DERIVATIVES_IDS: Map<String, String> = mapOf(
        "OkexFutures" to "okex_swap", "BinanceFutures" to "binance_futures", "BybitFutures" to "bybit",
        "BitgetFutures" to "bitget_futures", "MexcFutures" to "mxc_futures", "HtxFutures" to "huobi_dm",
        "BingXFutures" to "bingx_futures", "WooFutures" to "woo_network_futures", "PhemexFutures" to "phemex_futures",
        "Hyperliquid" to "hyperliquid", "Aster" to "aster", "Lighter" to "lighter", "Deribit" to "deribit",
        "BitunixFutures" to "bitunix_futures",
    )

    /** Eine Börse der Auswahl: Schlüssel und Abschnitt. */
    data class Entry(val key: String, val category: MarketCategory)

    /**
     * Volumen je Börsen-Schlüssel aus beiden Listen ([{id, trade_volume_24h_btc}, …]); nur endliche Werte ≥ 0.
     * Unlesbare Antwort → Ausnahme (dann wird nichts gespeichert).
     */
    fun parseVolumes(spotBody: String, derivativesBody: String): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        collect(JSONArray(spotBody), SPOT_IDS, out)
        collect(JSONArray(derivativesBody), DERIVATIVES_IDS, out)
        return out
    }

    private fun collect(rows: JSONArray, ids: Map<String, String>, out: MutableMap<String, Double>) {
        val byId = HashMap<String, Double>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val volume = row.optDouble("trade_volume_24h_btc")
            if (volume.isFinite() && volume >= 0.0) byId.putIfAbsent(row.optString("id"), volume)
        }
        for ((key, id) in ids) byId[id]?.let { out[key] = it }
    }

    /**
     * Abschnitte in der Reihenfolge von [MarketCategory] (leere fallen weg). Mit [volumes] darin absteigend nach
     * Volumen (gleiches Volumen: Registry-Reihenfolge), Börsen ohne Wert danach in Registry-Reihenfolge.
     */
    fun sections(markets: List<Entry>, volumes: Map<String, Double>?): List<Pair<MarketCategory, List<String>>> =
        MarketCategory.entries.mapNotNull { category ->
            val inSection = markets.filter { it.category == category }.map { it.key }
            if (inSection.isEmpty()) return@mapNotNull null
            if (volumes == null) return@mapNotNull category to inSection
            val (ranked, rest) = inSection.partition { volumes[it] != null }
            // sortedByDescending ist stabil: gleiches Volumen behält die Registry-Reihenfolge
            category to ranked.sortedByDescending { volumes.getValue(it) } + rest
        }

    /**
     * Rang je Börse über alle Abschnitte (0 = grösstes Volumen; gleiches Volumen: Schlüssel alphabetisch) für den
     * Paar-Vorschlag der Coin-Suche; ohne Ranking leer. Wie iOS `ExchangeOrder.ranks`.
     */
    fun ranks(volumes: Map<String, Double>?): Map<String, Int> =
        volumes.orEmpty().entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .withIndex().associate { (index, entry) -> entry.key to index }
}
