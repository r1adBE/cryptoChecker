package com.cryptochecker.app.testing

import com.cryptochecker.app.data.HttpLogger
import com.cryptochecker.app.di.RemoteDataModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import javax.inject.Singleton

/**
 * Feste Kurse für die instrumentierten Tests. `ExchangeRemoteDataSource` ist eine Klasse mit
 * @Inject-Konstruktor (kein Interface, kein Modul) — ersetzt wird deshalb eine Ebene tiefer
 * der OkHttpClient aus [RemoteDataModule]: Alle Abfragen über ihn beantwortet
 * [FakeExchangeInterceptor] ohne Netz. Die App selbst bleibt unverändert.
 */
object FakeExchange {
    const val START_PRICE = 95_000.0

    /** Aktueller BTC-Kurs (Binance bzw. Binance.US BTC/USDT, Coinbase BTC-USD); der Test setzt ihn neu. */
    @Volatile
    var btcPrice: Double = START_PRICE

    /** Die übrigen Start-Coins mit festen Kursen. */
    private val fixed = mapOf("ETH" to 3_500.0, "XRP" to 2.5, "BNB" to 900.0, "SOL" to 200.0)

    /** Coins der Börsen; mit [marketData] dazu die Coins der Markt-Tour ([FakeMarketSeries.tradable]). */
    val coins: List<String>
        get() {
            val start = listOf("BTC") + fixed.keys
            return if (marketData) (start + FakeMarketSeries.tradable).distinct() else start
        }

    /** Kurs; mit [marketData] auch für die übrigen Coins der Markt-Tour (und USDT/USD für Coinbase). */
    fun price(base: String): Double? = when {
        base == "BTC" -> btcPrice
        base in fixed -> fixed[base]
        marketData -> FakeMarketSeries.coin(base)?.price
        else -> null
    }

    /** 24-h-Veränderung in %: sonst überall 1.25, mit [marketData] die Werte der Markt-Tour (Merkliste = Markt-Tab). */
    fun change(base: String): Double = (if (marketData) FakeMarketSeries.coin(base)?.change24h else null) ?: DEFAULT_CHANGE

    private const val DEFAULT_CHANGE = 1.25

    /**
     * Auch den Binance-Spiegel `data-api.binance.vision` beantworten (Coin-Liste und Sammelkurse des Portfolios,
     * `PortfolioPriceSource`). Aus, damit die übrigen Tests unverändert ohne Portfolio-Kurse laufen; die
     * Bildschirmfoto-Tour schaltet ihn ein (Gesamtwert statt 0.00).
     */
    @Volatile
    var binanceMirror: Boolean = false

    /**
     * Auch die Quellen des Markt-Tabs und des USDT/USD-Kurses beantworten ([FakeMarketData], [FakeMarketSeries]):
     * CoinGecko, Fear & Greed, Coin Metrics, Funding, Gebühren, Kerzen und 24-h-Ticker des Binance-Spiegels, die
     * Binance-Symbolliste (Namen), USDT-USD bei Coinbase und dessen Tageskerzen bei Kraken. Aus, damit die übrigen
     * Tests unverändert laufen; nur die Bildschirmfoto-Tour schaltet es ein — vor dem Start der Activity, weil die App
     * einige dieser Abrufe gleich beim Start macht (z. B. die Symbolliste, danach erst wieder in einer Stunde).
     */
    @Volatile
    var marketData: Boolean = false

    fun reset() {
        btcPrice = START_PRICE
        binanceMirror = false
        marketData = false
    }
}

/**
 * Beantwortet Binance-, Binance.US- und Coinbase-Abfragen (mit [FakeExchange.binanceMirror] auch den
 * Binance-Spiegel, mit [FakeExchange.marketData] dazu die Markt-Quellen aus [FakeMarketData]) aus [FakeExchange];
 * alles andere: 404 (die App zeigt dann «—»). Start-Paare: Binance, in der Geräte-Region USA Binance.US
 * (StarterPairs) – der Emulator auf GitHub läuft in en-US, die App hält ihn darum für ein US-Gerät.
 */
class FakeExchangeInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = answer(request.url)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (body != null) 200 else 404)
            .message(if (body != null) "OK" else "Not Found")
            .body((body ?: """{"message":"not found"}""").toResponseBody(JSON))
            .build()
    }

    private fun answer(url: HttpUrl): String? {
        val path = url.encodedPath
        return when (url.host) {
            "api.binance.com", "api.binance.us" -> when (path) {
                // Erreichbarkeits-Prüfung des Startbildschirms (StarterPairs.BINANCE_PROBE_URL)
                "/api/v3/ping" -> "{}"
                "/api/v3/exchangeInfo" -> binanceExchangeInfo()
                "/api/v3/ticker/24hr" -> binanceTickers(url)
                else -> null
            }
            // Spiegel nur mit [FakeExchange.binanceMirror]: Paarliste wie oben, Kurse als `ticker/price`; mit
            // [FakeExchange.marketData] dazu 24-h-Ticker (Pulse, «Heute auffällig») und Kerzen (Markt-Tab)
            "data-api.binance.vision" -> when {
                FakeExchange.binanceMirror && path == "/api/v3/exchangeInfo" -> binanceExchangeInfo()
                FakeExchange.binanceMirror && path == "/api/v3/ticker/price" -> binancePrices(url)
                FakeExchange.marketData && path == "/api/v3/ticker/24hr" -> binanceTickers(url)
                FakeExchange.marketData && path == "/api/v3/klines" -> FakeMarketSeries.klines(url)
                else -> null
            }
            "api.exchange.coinbase.com" -> when {
                path == "/products" -> coinbaseProducts()
                path.startsWith("/products/") && path.endsWith("/stats") -> coinbaseStats(url.pathSegments[1])
                else -> null
            }
            else -> if (FakeExchange.marketData) FakeMarketData.answer(url) else null
        }
    }

    private fun binanceExchangeInfo(): String = FakeExchange.coins.joinToString(",", """{"symbols":[""", "]}") {
        """{"symbol":"${it}USDT","status":"TRADING","baseAsset":"$it","quoteAsset":"USDT"}"""
    }

    private fun binanceTicker(base: String): String? {
        val price = FakeExchange.price(base) ?: return null
        val now = System.currentTimeMillis()
        // Markt-Tour: Umsatz nach Marktkapitalisierung (für «Heute auffällig») und Zahl der Abschlüsse (lebendes Paar)
        val tour = if (FakeExchange.marketData) FakeMarketSeries.coin(base) else null
        val quoteVolume = tour?.let { it.marketCap * FakeMarketSeries.turnover(it) } ?: (price * 1000)
        val volume = if (tour != null) quoteVolume / price else 1000.0
        val extra = if (tour != null) ""","openTime":${now - FakeMarketSeries.DAY},"count":250000""" else ""
        return """{"symbol":"${base}USDT","bidPrice":"$price","askPrice":"$price","volume":"$volume",""" +
            """"quoteVolume":"$quoteVolume","highPrice":"${price * 1.02}","lowPrice":"${price * 0.98}",""" +
            """"lastPrice":"$price","closeTime":$now,"priceChangePercent":"${FakeExchange.change(base)}"$extra}"""
    }

    private fun binanceTickers(url: HttpUrl): String? {
        url.queryParameter("symbol")?.let { symbol -> return binanceTicker(symbol.removeSuffix("USDT")) }
        return wantedCoins(url).mapNotNull { binanceTicker(it) }.joinToString(",", "[", "]")
    }

    /** Coins aus `symbols=["BTCUSDT",…]`; ohne Auswahl alle. */
    private fun wantedCoins(url: HttpUrl): List<String> = url.queryParameter("symbols")
        ?.let { Regex("\"([A-Z0-9]+)USDT\"").findAll(it).map { m -> m.groupValues[1] }.toList() }
        ?: FakeExchange.coins

    /** Sammelkurse des Spiegels (`ticker/price`): nur Symbol und Kurs. */
    private fun binancePrices(url: HttpUrl): String = wantedCoins(url)
        .mapNotNull { coin -> FakeExchange.price(coin)?.let { """{"symbol":"${coin}USDT","price":"$it"}""" } }
        .joinToString(",", "[", "]")

    private fun coinbaseProducts(): String = FakeExchange.coins.joinToString(",", "[", "]") {
        """{"id":"$it-USD","base_currency":"$it","quote_currency":"USD","status":"online"}"""
    }

    /**
     * `open` = Kurs vor 24 h (+1.25 %); mit [FakeExchange.marketData] passend zu [FakeExchange.change], dazu USDT-USD
     * (Messung USDT/USD für das Portfolio, `DepegAlarmChecker`).
     */
    private fun coinbaseStats(productId: String): String? {
        val base = productId.substringBefore('-')
        val price = FakeExchange.price(base) ?: return null
        val open = if (FakeExchange.marketData) price / (1.0 + FakeExchange.change(base) / 100.0) else price / 1.0125
        return """{"open":"$open","high":"${price * 1.02}","low":"${price * 0.98}",""" +
            """"last":"$price","volume":"1000.0"}"""
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/** Ersetzt [RemoteDataModule] in allen instrumentierten Tests: gleiche Bindungen, kein Netz. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [RemoteDataModule::class])
object FakeRemoteDataModule {

    @Singleton
    @Provides
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(FakeExchangeInterceptor()).build()

    @Singleton
    @Provides
    fun provideHttpLoggerFlow(): HttpLogger = object : HttpLogger {
        private val flow = MutableSharedFlow<String>(1)
        override val messageFlow: SharedFlow<String> get() = flow
    }
}
