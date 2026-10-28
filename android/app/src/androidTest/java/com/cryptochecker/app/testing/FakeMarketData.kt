package com.cryptochecker.app.testing

import com.cryptochecker.app.testing.FakeMarketSeries.num
import okhttp3.HttpUrl

/**
 * Antworten der Quellen hinter Markt-Tab und Portfolio für die Bildschirmfoto-Tour ([FakeExchange.marketData]) — im
 * Format der echten Dienste, Werte aus [FakeMarketSeries]; gleich in `UITestExchange+Market.swift` (iOS):
 *  - CoinGecko: `/global` (Gesamtmarkt, Dominanz) und `/coins/markets` (Rangliste: Marktbreite, Gewinner/Verlierer,
 *    «Heute auffällig», Logos)
 *  - alternative.me: Fear & Greed (31 Tage)
 *  - Coin Metrics: BTC-Verlauf seit 2016 und On-Chain-Werte (Zyklus-Vergleich, Kennzahlen)
 *  - Binance USDⓈ-M: Funding und Open Interest (Pulse, «Heute auffällig»)
 *  - mempool.space und die Ethereum-/EVM-Knoten: Gebühren und Blockhöhe (Gas-Karte, Halving)
 *  - Binance-Website: Symbolliste mit vollen Namen — damit gilt der Binance-Kurs im Portfolio als derselbe Token
 *    (sonst «Kurs ungeprüft»)
 *  - Kraken: Tageskerzen USDT/USD (Portfolio-Verlauf und Einstand zum USDT-Kurs des Tages statt «USDT = 1»)
 * Binance-Spiegel (24-h-Ticker, Kerzen) und Coinbase beantwortet [FakeExchangeInterceptor]. Der Daten-Spiegel auf
 * GitHub bleibt 404: Seine Dateien gelten nur mit gültig signiertem Manifest — die App holt dann direkt beim Anbieter.
 */
internal object FakeMarketData {

    /** Antwort auf [url]; null = 404. */
    fun answer(url: HttpUrl): String? {
        val path = url.encodedPath
        return when (url.host) {
            "api.coingecko.com" -> when (path) {
                "/api/v3/global" -> global()
                "/api/v3/coins/markets" -> markets(url)
                else -> null
            }
            "api.alternative.me" -> if (path.startsWith("/fng")) fearGreed(url) else null
            "community-api.coinmetrics.io" ->
                if (path == "/v4/timeseries/asset-metrics") FakeMarketSeries.coinMetrics(url) else null
            "fapi.binance.com" -> when (path) {
                "/fapi/v1/premiumIndex" -> premiumIndex(url)
                "/fapi/v1/openInterest" -> openInterest(url)
                // Kein Kontrakt mit abweichendem Intervall: alle 8 Std.
                "/fapi/v1/fundingInfo" -> "[]"
                else -> null
            }
            "mempool.space" -> when (path) {
                "/api/v1/fees/recommended" ->
                    """{"fastestFee":6,"halfHourFee":4,"hourFee":3,"economyFee":2,"minimumFee":1}"""
                "/api/blocks/tip/height" -> BLOCK_HEIGHT.toString()
                "/api/v1/difficulty-adjustment" ->
                    """{"progressPercent":41.2,"difficultyChange":1.8,"remainingBlocks":1185,"timeAvg":589000}"""
                else -> null
            }
            "www.binance.com" ->
                if (path == "/bapi/composite/v1/public/marketing/symbol/list") binanceSymbolList() else null
            "api.kraken.com" -> if (path == "/0/public/OHLC") krakenUsdtUsd(url) else null
            else -> GAS[url.host]?.let(::feeHistory)
        }
    }

    // ── CoinGecko ──

    private fun global(): String {
        val coins = FakeMarketSeries.coins
        fun share(symbol: String) = coins.first { it.symbol == symbol }.marketCap / FakeMarketSeries.TOTAL_MARKET_CAP * 100
        // Beträge auch in den Anzeigewährungen (Kurse wie die Umrechnung in der App ungefähr)
        val fx = mapOf("usd" to 1.0, "eur" to 0.92, "chf" to 0.88, "gbp" to 0.78, "jpy" to 150.0)
        fun amounts(usd: Double) = fx.entries.joinToString(",", "{", "}") { (k, f) -> "\"$k\":${usd * f}" }
        return """{"data":{"active_cryptocurrencies":17500,"markets":1250,""" +
            """"total_market_cap":${amounts(FakeMarketSeries.TOTAL_MARKET_CAP)},""" +
            """"total_volume":${amounts(FakeMarketSeries.TOTAL_VOLUME)},""" +
            """"market_cap_percentage":{"btc":${share("BTC")},"eth":${share("ETH")},"usdt":${share("USDT")}},""" +
            """"market_cap_change_percentage_24h_usd":${FakeMarketSeries.MARKET_CAP_CHANGE_24H},""" +
            """"updated_at":${System.currentTimeMillis() / 1000}}}"""
    }

    /** Rangliste nach Marktkapitalisierung; `per_page`, `page` (ab Seite 2 leer) und `symbols=` wie bei CoinGecko. */
    private fun markets(url: HttpUrl): String {
        val page = url.queryParameter("page")?.toIntOrNull() ?: 1
        val perPage = url.queryParameter("per_page")?.toIntOrNull() ?: 100
        val symbols = url.queryParameter("symbols")?.lowercase()?.split(',')?.map { it.trim() }?.toSet()
        val ranked = FakeMarketSeries.coins.withIndex()
            .filter { symbols == null || it.value.symbol.lowercase() in symbols }
        val shown = if (page == 1) ranked.take(perPage) else emptyList()
        return shown.joinToString(",", "[", "]") { (index, coin) ->
            val price = FakeMarketSeries.price(coin)
            """{"id":"${coin.name.lowercase().replace(' ', '-')}","symbol":"${coin.symbol.lowercase()}",""" +
                """"name":"${coin.name}","current_price":$price,"market_cap":${coin.marketCap},""" +
                """"market_cap_rank":${index + 1},"total_volume":${coin.marketCap * FakeMarketSeries.turnover(coin)},""" +
                """"price_change_percentage_24h":${coin.change24h}}"""
        }
    }

    // ── alternative.me ──

    /** Fear & Greed der letzten 31 Tage, neuester zuerst (heute 55, gestern 52, vor einer Woche 48, vor 30 Tagen 38). */
    private fun fearGreed(url: HttpUrl): String {
        val now = System.currentTimeMillis()
        val count = (url.queryParameter("limit")?.toIntOrNull() ?: 1).coerceIn(1, FEAR_GREED.size)
        return FEAR_GREED.take(count).mapIndexed { i, value ->
            """{"value":"$value","value_classification":"${fearGreedClass(value)}",""" +
                """"timestamp":"${FakeMarketSeries.dayStartSeconds(now, i)}"}"""
        }.joinToString(",", """{"name":"Fear and Greed Index","data":[""", """],"metadata":{"error":null}}""")
    }

    private fun fearGreedClass(value: Int): String = when {
        value < 25 -> "Extreme Fear"
        value < 47 -> "Fear"
        value < 55 -> "Neutral"
        value < 76 -> "Greed"
        else -> "Extreme Greed"
    }

    // ── Binance USDⓈ-M ──

    /** Funding je Coin: bei steigendem Kurs etwas höher, bei fallendem tiefer (0.01 % Grundwert je 8 Std.). */
    private fun fundingRate(coin: TourCoin): Double = 0.0001 * (1.0 + coin.change24h / 4.0)

    private fun premiumIndex(url: HttpUrl): String? {
        val now = System.currentTimeMillis()
        val nextFunding = (now / (8 * FakeMarketSeries.HOUR) + 1) * 8 * FakeMarketSeries.HOUR
        fun entry(coin: TourCoin): String {
            val mark = FakeMarketSeries.price(coin)
            return """{"symbol":"${coin.symbol}USDT","markPrice":"${num(mark)}","indexPrice":"${num(mark)}",""" +
                """"lastFundingRate":"${num(fundingRate(coin))}","interestRate":"0.00010000",""" +
                """"nextFundingTime":$nextFunding,"time":$now}"""
        }
        val perpetuals = FakeMarketSeries.coins.filter { it.beta > 0.0 }
        val symbol = url.queryParameter("symbol") ?: return perpetuals.joinToString(",", "[", "]") { entry(it) }
        return perpetuals.firstOrNull { "${it.symbol}USDT" == symbol }?.let(::entry)
    }

    /** Offene Kontrakte (in Coins): rund 4 % der Marktkapitalisierung. */
    private fun openInterest(url: HttpUrl): String? {
        val symbol = url.queryParameter("symbol") ?: return null
        val coin = FakeMarketSeries.coins.firstOrNull { it.beta > 0.0 && "${it.symbol}USDT" == symbol } ?: return null
        val contracts = coin.marketCap * 0.004 / FakeMarketSeries.price(coin)
        return """{"symbol":"$symbol","openInterest":"${num(contracts)}","time":${System.currentTimeMillis()}}"""
    }

    // ── Gebühren ──

    /** `eth_feeHistory`: Grundgebühr des nächsten Blocks und Trinkgelder (10./50./90. Perzentil), alles in gwei. */
    private fun feeHistory(gwei: List<Double>): String {
        fun hex(value: Double) = "\"0x" + (value * 1e9).toLong().toString(16) + "\""
        val base = (0..2).joinToString(",") { hex(gwei[0]) }
        val reward = (0..1).joinToString(",") { "[" + gwei.drop(1).joinToString(",") { hex(it) } + "]" }
        return """{"jsonrpc":"2.0","id":1,"result":{"oldestBlock":"0x1","baseFeePerGas":[$base],""" +
            """"gasUsedRatio":[0.5,0.5],"reward":[$reward]}}"""
    }

    // ── Kraken ──

    /**
     * `GET /0/public/OHLC?pair=USDTZUSD&interval=…`: die jüngsten 720 Kerzen USDT/USD (Zeit in Sekunden, Texte), knapp
     * über 1 USD; andere Paare 404.
     */
    private fun krakenUsdtUsd(url: HttpUrl): String? {
        if (url.queryParameter("pair") != USDT_USD_PAIR) return null
        val step = (url.queryParameter("interval")?.toLongOrNull() ?: 1L).coerceAtLeast(1L) * 60_000L
        val current = System.currentTimeMillis() / step * step
        val rows = (KRAKEN_CANDLES - 1 downTo 0).map { back ->
            val close = 1.0002 + 0.0002 * kotlin.math.sin(back * 0.7)
            val open = 1.0002 + 0.0002 * kotlin.math.sin((back + 1) * 0.7)
            "[${(current - back * step) / 1000},\"${num(open)}\",\"${num(maxOf(open, close) + 0.0001)}\"," +
                "\"${num(minOf(open, close) - 0.0001)}\",\"${num(close)}\",\"${num(close)}\",\"25000000.0\",12000]"
        }
        return """{"error":[],"result":{"$USDT_USD_PAIR":${rows.joinToString(",", "[", "]")},""" +
            """"last":${(current - step) / 1000}}}"""
    }

    // ── Binance-Website ──

    /** Symbolliste mit vollen Namen (`fullName`), wie die Website sie für Logos und Namen liefert. */
    private fun binanceSymbolList(): String = FakeMarketSeries.coins.joinToString(",", """{"code":"000000","data":[""",
        """],"success":true}""") { coin ->
        """{"name":"${coin.symbol}","fullName":"${coin.name}","logo":null,"tags":[],"onlyFutures":false}"""
    }

    /** Fear & Greed, heute zuerst. */
    private val FEAR_GREED = listOf(
        55, 52, 50, 51, 49, 47, 46, 48, 45, 44, 46, 43, 41, 42, 40, 39,
        41, 38, 37, 39, 40, 38, 36, 37, 39, 41, 40, 38, 37, 39, 38,
    )

    /** Paar USDT/USD bei Kraken (wie `DepegAlarm.KRAKEN_PAIR_ID`) und wie viele Kerzen Kraken höchstens liefert. */
    private const val USDT_USD_PAIR = "USDTZUSD"
    private const val KRAKEN_CANDLES = 720

    /** Blockhöhe Ende Oktober 2026 (nächstes Halving bei 1’050’000). */
    private const val BLOCK_HEIGHT = 972_104

    /** Gebühren je Knoten in gwei: Grundgebühr, dann Trinkgeld langsam/normal/schnell. */
    private val GAS = mapOf(
        "ethereum-rpc.publicnode.com" to listOf(1.8, 0.02, 0.1, 0.8),
        "base-rpc.publicnode.com" to listOf(0.006, 0.001, 0.003, 0.01),
        "arbitrum-one-rpc.publicnode.com" to listOf(0.01, 0.0, 0.0, 0.001),
        "polygon-bor-rpc.publicnode.com" to listOf(28.0, 25.0, 30.0, 45.0),
        "bsc-rpc.publicnode.com" to listOf(0.1, 0.0, 0.0, 0.05),
    )
}
