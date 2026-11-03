package com.cryptochecker.app.domain.market

/**
 * Logos der Börsen in den Listen («Paar hinzufügen»): mit der App ausgeliefert ([PACK_FILE], Format wie das
 * Coin-Logo-Paket, `CoinLogos.parseIconPack`; erzeugt mit `android/tools/gen_logo_packs.py`), angezeigt sofort vom
 * Gerät — kein Abruf. Futures zeigen das Logo der Mutterbörse (Binance Futures → Binance). DexScreener und die
 * EZB-Referenzkurse bekommen ein festes Symbol ([Symbol]). Jede registrierte Börse hat genau eins von beiden
 * (Paritätstest). Reine Logik — wie iOS `ExchangeLogos` (`testdata/parity/exchange_logos.json`).
 */
object ExchangeLogos {

    /** Festes Symbol statt Logo. */
    enum class Symbol { DEX, FOREX }

    /** Mitgeliefertes Paket der Börsen-Logos (Android `assets/`, iOS `App/Resources`). */
    const val PACK_FILE = "exchange_logos.bin"

    /** Börsen-Schlüssel → Name des Logos im Paket ([PACK_FILE]); Futures: die Mutterbörse. Feste Reihenfolge. */
    val LOGOS: Map<String, String> = linkedMapOf(
        "Coinbase" to "gdax", "Kraken" to "kraken", "GateIo" to "gate", "Binance" to "binance",
        "BinanceUs" to "binanceus", "Kucoin" to "kucoin", "Okex" to "okex", "OkexFutures" to "okex",
        "BinanceFutures" to "binance", "Bybit" to "bybit", "BybitFutures" to "bybit", "Bitget" to "bitget",
        "BitgetFutures" to "bitget", "Mexc" to "mexc", "MexcFutures" to "mexc", "Htx" to "htx",
        "HtxFutures" to "htx", "Bitfinex" to "bitfinex", "Bitstamp" to "bitstamp", "Bitvavo" to "bitvavo",
        "CryptoCom" to "cryptocom", "Gemini" to "gemini", "Hyperliquid" to "hyperliquid", "Aster" to "aster",
        "BingX" to "bingx", "BingXFutures" to "bingx", "Lighter" to "lighter", "Woo" to "woo",
        "WooFutures" to "woo", "Deribit" to "deribit", "Phemex" to "phemex", "PhemexFutures" to "phemex",
        "BitunixFutures" to "bitunix", "Poloniex" to "poloniex", "OneTrading" to "onetrading", "Upbit" to "upbit",
        "Bithumb" to "bithumb", "BitFlyer" to "bitflyer", "Bitbank" to "bitbank", "Coincheck" to "coincheck",
        "Btcturk" to "btcturk", "Paribu" to "paribu", "Icrypex" to "icrypex", "Bitso" to "bitso",
        "MercadoBitcoin" to "mercadobitcoin", "Indodax" to "indodax", "ZebPay" to "zebpay", "CoinDcx" to "coindcx",
        "HashKey" to "hashkey", "IndependentReserve" to "independentreserve", "BtcMarkets" to "btcmarkets",
        "Luno" to "luno", "Bitkub" to "bitkub", "BinanceTh" to "binance", "CoinsPh" to "coinsph",
        "Coinone" to "coinone", "WhiteBit" to "whitebit", "MaxMaicoin" to "maxmaicoin", "BitoPro" to "bitopro",
        "BinanceTr" to "binance", "Foxbit" to "foxbit", "Bit2Me" to "bit2me", "Valr" to "valr",
        "WazirX" to "wazirx", "Buda" to "buda", "Tokocrypto" to "tokocrypto", "UpbitTh" to "upbit",
        "UpbitId" to "upbitid", "CoinJar" to "coinjar", "GmoCoin" to "gmocoin", "Latoken" to "latoken",
        "NonKyc" to "nonkyc", "Bitpanda" to "bitpanda", "CoinSpot" to "coinspot", "Quidax" to "quidax",
        "Swyftx" to "swyftx", "Ndax" to "ndax", "BitOasis" to "bitoasis",
    )

    /** Festes Symbol statt Logo. */
    val SYMBOLS: Map<String, Symbol> = mapOf("DexScreener" to Symbol.DEX, "EcbReference" to Symbol.FOREX)

    /** Name des Logos im Paket; mehrere Börsen teilen eines. null = festes Symbol bzw. unbekannte Börse. */
    fun logoName(exchangeKey: String): String? = LOGOS[exchangeKey]

    /** Alle Logo-Namen in fester Reihenfolge, ohne Doppelte (so viele Bilder enthält das Paket). */
    val NAMES: List<String> = LOGOS.values.distinct()
}
