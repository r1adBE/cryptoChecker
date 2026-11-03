package com.cryptochecker.app.domain.market

import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.util.json.JsonArray
import com.cryptochecker.app.util.json.JsonObject

/**
 * Logos der Börsen in den Listen («Paar hinzufügen»): Bild-Adressen (Feld `image`) aus der öffentlichen Börsenliste von
 * CoinGecko — Seite 1 ist dieselbe Abfrage wie für die Reihenfolge ([ExchangeOrder.SPOT_URL]); fehlen zugeordnete
 * Börsen, folgen weitere Seiten ([needsNextPage], höchstens [MAX_PAGES]), für bei CoinGecko inaktive Börsen ohne
 * Eintrag in der Liste die Einzelseite ([detailUrl]). Für alle dieselben Abfragen in fester Reihenfolge. Futures zeigen
 * das Logo der Mutterbörse (Binance Futures → Binance). DexScreener und die EZB-Referenzkurse bekommen ein festes Symbol
 * ([Symbol]); Börsen ohne Eintrag bei CoinGecko behalten den Kreis mit den Buchstaben ([NO_LOGO]). Geladen wird über
 * die Pipeline der Coin-Logos unter dem Schlüssel [logoKey] ([CoinLogos.EXCHANGE_PREFIX]). Reine Logik — wie iOS
 * `ExchangeLogos` (`testdata/parity/exchange_logos.json`).
 */
object ExchangeLogos {

    /** Festes Symbol statt Logo. */
    enum class Symbol { DEX, FOREX }

    /** Börsen-Schlüssel → Kennung in der Börsenliste ([pageUrl]); Futures: die Mutterbörse. Feste Reihenfolge. */
    val SPOT_IDS: Map<String, String> = linkedMapOf(
        "Coinbase" to "gdax", "Kraken" to "kraken", "GateIo" to "gate", "Binance" to "binance",
        "BinanceUs" to "binance_us", "Kucoin" to "kucoin", "Okex" to "okex", "OkexFutures" to "okex",
        "BinanceFutures" to "binance", "Bybit" to "bybit_spot", "BybitFutures" to "bybit_spot",
        "Bitget" to "bitget", "BitgetFutures" to "bitget", "Mexc" to "mxc", "MexcFutures" to "mxc",
        "Htx" to "huobi", "HtxFutures" to "huobi", "Bitfinex" to "bitfinex", "Bitstamp" to "bitstamp",
        "Bitvavo" to "bitvavo", "CryptoCom" to "crypto_com", "Gemini" to "gemini", "Hyperliquid" to "hyperliquid-spot",
        "Aster" to "aster-spot",
        "BingX" to "bingx", "BingXFutures" to "bingx", "Lighter" to "lighter-spot", "Woo" to "wootrade",
        "WooFutures" to "wootrade", "Deribit" to "deribit_spot", "Phemex" to "phemex", "PhemexFutures" to "phemex",
        "BitunixFutures" to "bitunix", "Poloniex" to "poloniex", "OneTrading" to "bitpanda", "Upbit" to "upbit",
        "Bithumb" to "bithumb", "BitFlyer" to "bitflyer", "Bitbank" to "bitbank", "Coincheck" to "coincheck",
        "Btcturk" to "btcturk", "Paribu" to "paribu", "Icrypex" to "icrypex", "Bitso" to "bitso",
        "MercadoBitcoin" to "mercado_bitcoin", "Indodax" to "indodax", "ZebPay" to "zebpay", "CoinDcx" to "coindcx",
        "HashKey" to "hashkey", "IndependentReserve" to "independent_reserve", "BtcMarkets" to "btcmarkets",
        "Luno" to "luno", "Bitkub" to "bitkub", "BinanceTh" to "binance", "CoinsPh" to "coinspro",
        "Coinone" to "coinone", "WhiteBit" to "whitebit", "MaxMaicoin" to "max_maicoin",
        "BitoPro" to "bitopro", "BinanceTr" to "binance", "Foxbit" to "foxbit", "Bit2Me" to "bit2me",
        "Valr" to "valr", "WazirX" to "wazirx", "Buda" to "buda",
        "Tokocrypto" to "toko_crypto", "UpbitTh" to "upbit", "UpbitId" to "upbit_indonesia",
        "CoinJar" to "coinjar", "GmoCoin" to "gmo_japan", "Latoken" to "latoken", "NonKyc" to "nonkyc_io",
    )

    /** Festes Symbol statt Logo. */
    val SYMBOLS: Map<String, Symbol> = mapOf("DexScreener" to Symbol.DEX, "EcbReference" to Symbol.FOREX)

    /**
     * Bewusst ohne Logo (der Kreis mit den Buchstaben bleibt), weil CoinGecko sie nicht führt; «bitpanda» bei CoinGecko
     * ist One Trading.
     */
    val NO_LOGO: Set<String> = setOf("Bitpanda", "CoinSpot", "Quidax", "Swyftx", "Ndax", "BitOasis")

    /** Einträge je Seite der Börsenliste (wie [ExchangeOrder.SPOT_URL]). */
    const val PER_PAGE = 250

    /** Höchstens so viele Seiten (CoinGecko führt rund 950 aktive Börsen). */
    const val MAX_PAGES = 5

    /** Unvollständig (Seite fehlte, Server bremste): frühestens nach so langer Zeit erneut versuchen. */
    const val RETRY_MILLIS = 24L * 60 * 60 * 1000

    /** Seite [page] der Börsenliste; Seite 1 = [ExchangeOrder.SPOT_URL]. */
    fun pageUrl(page: Int): String = "https://api.coingecko.com/api/v3/exchanges?per_page=$PER_PAGE&page=$page"

    /** Einzelseite einer Börse (für Börsen, die in der Liste fehlen). */
    fun detailUrl(id: String): String = "https://api.coingecko.com/api/v3/exchanges/$id"

    /** Schlüssel des Logos (Datei [CoinLogos.fileName]); mehrere Börsen teilen eines. null = keins. */
    fun logoKey(exchangeKey: String): String? = SPOT_IDS[exchangeKey]?.let { CoinLogos.EXCHANGE_PREFIX + it }

    /** Zugeordnete Kennungen in fester Reihenfolge (Reihenfolge der Abrufe und Einzelseiten, für alle gleich). */
    val IDS: List<String> = SPOT_IDS.values.distinct()

    /** Alle Logo-Schlüssel in derselben Reihenfolge. */
    val ORDER: List<String> = IDS.map { CoinLogos.EXCHANGE_PREFIX + it }

    /** Kennung → Bild-Adresse einer Seite (nur freigegebene, [CoinLogos.isAllowedUrl]) und Zahl der Einträge. */
    data class Page(val images: Map<String, String>, val rows: Int)

    /** Eine Seite der Börsenliste lesen; unlesbar → Ausnahme. */
    fun parsePage(body: String): Page {
        val rows = JsonArray(body)
        val out = LinkedHashMap<String, String>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            imageOf(row)?.let { out.putIfAbsent(row.optString("id"), it) }
        }
        return Page(out, rows.length())
    }

    /** Bild-Adresse der Einzelseite ([detailUrl]); keine oder nicht freigegeben → null, unlesbar → Ausnahme. */
    fun parseDetail(body: String): String? = imageOf(JsonObject(body))

    private fun imageOf(row: JsonObject): String? = row.optString("image").trim().takeIf { CoinLogos.isAllowedUrl(it) }

    /** Noch fehlende Kennungen in fester Reihenfolge. */
    fun missing(found: Map<String, String>): List<String> = IDS.filter { it !in found }

    /** Nach Seite [page] mit [rows] Einträgen weiterlesen? Nur solange Kennungen fehlen und die Liste weitergeht. */
    fun needsNextPage(page: Int, rows: Int, missing: Int): Boolean = missing > 0 && rows >= PER_PAGE && page < MAX_PAGES

    /** Gefundene Bilder ([found]: Kennung → Adresse) als Logo-Schlüssel → Adresse, in [ORDER]. */
    fun images(found: Map<String, String>): Map<String, String> =
        IDS.mapNotNull { id -> found[id]?.let { CoinLogos.EXCHANGE_PREFIX + id to it } }.toMap()

    /** Gespeichert sind nicht alle: nach [RETRY_MILLIS] seit dem letzten Versuch erneut (nie öfter). */
    fun retryDue(complete: Boolean, attemptAt: Long, now: Long): Boolean =
        !complete && (attemptAt <= 0 || attemptAt > now || now - attemptAt >= RETRY_MILLIS)
}
